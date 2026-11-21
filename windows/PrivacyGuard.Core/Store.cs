using Microsoft.Data.Sqlite;

namespace PrivacyGuard.Core;

/// <summary>
/// History on this PC (SQLite). Windows keeps only the LAST use per program and capability, so the monitor
/// writes every use here as it happens. Access is kept 60 days, DNS 30 days.
/// </summary>
public sealed class Store : IDisposable
{
    readonly SqliteConnection db;
    readonly object gate = new();

    public Store(string path)
    {
        db = new SqliteConnection($"Data Source={path}");
        db.Open();
        Exec("PRAGMA journal_mode=WAL");
        Exec(@"CREATE TABLE IF NOT EXISTS access (app TEXT, packaged INTEGER, cap TEXT, start INTEGER, stop INTEGER,
                 infront INTEGER, away INTEGER, PRIMARY KEY (app, cap, start))");
        Exec("CREATE TABLE IF NOT EXISTS dns (ts INTEGER, pid INTEGER, exe TEXT, name TEXT, blocked INTEGER)");
        Exec("CREATE INDEX IF NOT EXISTS dns_ts ON dns(ts)");
        Exec("CREATE INDEX IF NOT EXISTS access_start ON access(start)");
    }

    void Exec(string sql, params (string, object?)[] p)
    {
        lock (gate)
        {
            using var c = db.CreateCommand();
            c.CommandText = sql;
            foreach (var (k, v) in p) c.Parameters.AddWithValue(k, v ?? DBNull.Value);
            c.ExecuteNonQuery();
        }
    }

    static long T(DateTime d) => d.ToUniversalTime().Ticks;
    static DateTime D(long t) => new DateTime(t, DateTimeKind.Utc).ToLocalTime();

    /// <summary>Inserts a use, or fills in its end time. The first InFront/UserAway values are kept (they describe the start).</summary>
    public void Upsert(Access a) => Exec(@"INSERT INTO access VALUES ($a,$p,$c,$s,$e,$f,$w)
        ON CONFLICT(app,cap,start) DO UPDATE SET stop=excluded.stop",
        ("$a", a.App.Key), ("$p", a.App.Packaged ? 1 : 0), ("$c", a.Capability), ("$s", T(a.Start)),
        ("$e", a.Stop is { } s ? T(s) : null), ("$f", a.InFront ? 1 : 0), ("$w", a.UserAway ? 1 : 0));

    public bool Has(AppId app, string cap, DateTime start)
    {
        lock (gate)
        {
            using var c = db.CreateCommand();
            c.CommandText = "SELECT 1 FROM access WHERE app=$a AND cap=$c AND start=$s";
            c.Parameters.AddWithValue("$a", app.Key); c.Parameters.AddWithValue("$c", cap); c.Parameters.AddWithValue("$s", T(start));
            return c.ExecuteScalar() != null;
        }
    }

    public List<Access> AccessSince(DateTime from)
    {
        var r = new List<Access>();
        lock (gate)
        {
            using var c = db.CreateCommand();
            c.CommandText = "SELECT app,packaged,cap,start,stop,infront,away FROM access WHERE start>=$f ORDER BY start";
            c.Parameters.AddWithValue("$f", T(from));
            using var x = c.ExecuteReader();
            while (x.Read())
                r.Add(new Access(new AppId(x.GetString(0), x.GetInt64(1) == 1), x.GetString(2), D(x.GetInt64(3)),
                    x.IsDBNull(4) ? null : D(x.GetInt64(4)), x.GetInt64(5) == 1, x.GetInt64(6) == 1));
        }
        return r;
    }

    public void Add(DnsQuery q) => Exec("INSERT INTO dns VALUES ($t,$p,$e,$n,$b)",
        ("$t", T(q.Time)), ("$p", q.Pid), ("$e", q.Exe), ("$n", q.Name.ToLowerInvariant().TrimEnd('.')), ("$b", q.Blocked ? 1 : 0));

    public List<DnsQuery> DnsSince(DateTime from, int limit = 50_000)
    {
        var r = new List<DnsQuery>();
        lock (gate)
        {
            using var c = db.CreateCommand();
            c.CommandText = "SELECT ts,pid,exe,name,blocked FROM dns WHERE ts>=$f ORDER BY ts DESC LIMIT $l";
            c.Parameters.AddWithValue("$f", T(from)); c.Parameters.AddWithValue("$l", limit);
            using var x = c.ExecuteReader();
            while (x.Read()) r.Add(new DnsQuery(D(x.GetInt64(0)), (int)x.GetInt64(1), x.IsDBNull(2) ? null : x.GetString(2), x.GetString(3), x.GetInt64(4) == 1));
        }
        return r;
    }

    public void Prune(int accessDays = 60, int dnsDays = 30)
    {
        Exec("DELETE FROM access WHERE start<$t", ("$t", T(DateTime.Now.AddDays(-accessDays))));
        Exec("DELETE FROM dns WHERE ts<$t", ("$t", T(DateTime.Now.AddDays(-dnsDays))));
    }

    public void Clear() { Exec("DELETE FROM access"); Exec("DELETE FROM dns"); Exec("VACUUM"); }

    public void Dispose() => db.Dispose();
}
