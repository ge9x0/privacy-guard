using PrivacyGuard.Core;

namespace PrivacyGuard.Platform;

/// <summary>The HaGeZi lists on disk, used to flag lookups in the DNS log. Downloaded on request and weekly.</summary>
public sealed class Lists
{
    readonly Dictionary<string, BlockList> loaded = new();
    readonly Prefs prefs;
    public Lists(Prefs p) { prefs = p; Reload(); }

    public void Reload()
    {
        lock (loaded)
        {
            loaded.Clear();
            foreach (var d in BlockList.All)
                if (prefs.Lists.Contains(d.Id) && File.Exists(Prefs.ListPath(d.Id)))
                    loaded[d.Id] = BlockList.Parse(d.Id, File.ReadAllText(Prefs.ListPath(d.Id)));
        }
    }

    public int Count(string id) { lock (loaded) return loaded.TryGetValue(id, out var l) ? l.Count : 0; }

    /// <summary>Name of the first enabled list that contains the host, or null.</summary>
    public string? Match(string host)
    {
        lock (loaded)
            foreach (var l in loaded.Values)
                if (l.Matches(host)) return BlockList.All.First(d => d.Id == l.Id).Name;
        return null;
    }

    public async Task<string> Update()
    {
        Directory.CreateDirectory(Prefs.Dir);
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("PrivacyGuard-Windows");
        var done = new List<string>(); var failed = new List<string>();
        foreach (var d in BlockList.All.Where(d => prefs.Lists.Contains(d.Id)))
        {
            string? text = null;
            foreach (var b in new[] { BlockList.Cdn, BlockList.GitHub })
            {
                try { text = await http.GetStringAsync(b + d.File); break; } catch { }
            }
            if (text == null || BlockList.Parse(d.Id, text).Count < 100) { failed.Add(d.Name); continue; }
            await File.WriteAllTextAsync(Prefs.ListPath(d.Id), text);
            done.Add(d.Name);
        }
        Reload();
        if (done.Count > 0) { prefs.ListsUpdated = DateTime.Now; prefs.Save(); }
        return failed.Count == 0 ? $"Lists updated: {string.Join(", ", done)}." : $"Could not download: {string.Join(", ", failed)}. Check the internet connection.";
    }
}
