package com.privacyguard.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One access to the microphone/camera/location/... */
data class AccessEvent(
    val pkg: String,
    val uid: Int,
    val op: String,
    val ts: Long,            // ms
    val durationMs: Long,
    val state: String,       // top, fgsvc, bg, cch, ... or "unknown"
    val kind: String,        // access | reject
    val source: String,      // discrete | last | live
)

data class NetRow(val uid: Int, val net: String, val set: String, val bucket: Long, val rx: Long, val tx: Long)
data class ConnRow(val ts: Long, val uid: Int, val host: String, val bytes: Long, val blocked: Boolean = false, val why: String? = null)
data class ScreenRow(val ts: Long, val on: Boolean, val topPkg: String?)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "privacy.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE access (pkg TEXT, uid INTEGER, op TEXT, ts INTEGER, duration_ms INTEGER,
            state TEXT, kind TEXT, source TEXT, UNIQUE(pkg, op, ts, kind, source))""")
        db.execSQL("""CREATE TABLE net (uid INTEGER, net TEXT, nset TEXT, bucket INTEGER, rx INTEGER, tx INTEGER,
            PRIMARY KEY(uid, net, nset, bucket))""")
        db.execSQL("CREATE TABLE screen (ts INTEGER PRIMARY KEY, screen_on INTEGER, top_pkg TEXT)")
        db.execSQL("CREATE TABLE conn (ts INTEGER, uid INTEGER, host TEXT, bytes INTEGER, blocked INTEGER DEFAULT 0, why TEXT)")
        db.execSQL("CREATE INDEX conn_ts ON conn(ts)")
        db.execSQL("CREATE INDEX access_ts ON access(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE conn ADD COLUMN blocked INTEGER DEFAULT 0")
        // v3: the block reason ("tracker", "list:pro", "background"...)
        if (oldVersion < 3) db.execSQL("ALTER TABLE conn ADD COLUMN why TEXT")
    }

    fun addAccess(events: List<AccessEvent>): Int {
        var n = 0
        writableDatabase.transaction {
            // one prepared statement for all rows (11k per collection); -1 = already existed
            compileStatement("INSERT OR IGNORE INTO access(pkg,uid,op,ts,duration_ms,state,kind,source) VALUES (?,?,?,?,?,?,?,?)").use { st ->
                for (e in events) {
                    st.clearBindings()
                    st.bindString(1, e.pkg); st.bindLong(2, e.uid.toLong()); st.bindString(3, e.op); st.bindLong(4, e.ts)
                    st.bindLong(5, e.durationMs); st.bindString(6, e.state); st.bindString(7, e.kind); st.bindString(8, e.source)
                    if (st.executeInsert() != -1L) n++
                }
            }
        }
        return n
    }

    fun addNet(rows: List<NetRow>) = writableDatabase.transaction {
        for (r in rows) {
            execSQL(
                "INSERT INTO net VALUES (?,?,?,?,?,?) ON CONFLICT(uid,net,nset,bucket) DO UPDATE SET " +
                    "rx=max(rx,excluded.rx), tx=max(tx,excluded.tx)",
                arrayOf<Any>(r.uid, r.net, r.set, r.bucket, r.rx, r.tx)
            )
        }
    }

    fun addScreen(r: ScreenRow) {
        val cv = ContentValues().apply { put("ts", r.ts); put("screen_on", if (r.on) 1 else 0); put("top_pkg", r.topPkg) }
        writableDatabase.insertWithOnConflict("screen", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun addConn(r: ConnRow) {
        val cv = ContentValues().apply { put("ts", r.ts); put("uid", r.uid); put("host", r.host); put("bytes", r.bytes); put("blocked", if (r.blocked) 1 else 0); put("why", r.why) }
        writableDatabase.insert("conn", null, cv)
    }

    fun access(from: Long, to: Long): List<AccessEvent> {
        val out = ArrayList<AccessEvent>()
        readableDatabase.rawQuery(
            "SELECT pkg,uid,op,ts,duration_ms,state,kind,source FROM access WHERE ts>=? AND ts<? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += AccessEvent(
                c.getString(0), c.getInt(1), c.getString(2), c.getLong(3), c.getLong(4),
                c.getString(5), c.getString(6), c.getString(7)
            )
        }
        return out
    }

    fun net(from: Long, to: Long): List<NetRow> {
        val out = ArrayList<NetRow>()
        readableDatabase.rawQuery(
            "SELECT uid,net,nset,bucket,rx,tx FROM net WHERE bucket>=? AND bucket<?",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += NetRow(c.getInt(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getLong(5))
        }
        return out
    }

    fun screen(from: Long, to: Long): List<ScreenRow> {
        val out = ArrayList<ScreenRow>()
        readableDatabase.rawQuery(
            "SELECT ts,screen_on,top_pkg FROM screen WHERE ts>=? AND ts<? ORDER BY ts",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += ScreenRow(c.getLong(0), c.getInt(1) == 1, c.getString(2))
        }
        return out
    }

    fun conns(from: Long, to: Long): List<ConnRow> {
        val out = ArrayList<ConnRow>()
        readableDatabase.rawQuery(
            "SELECT ts,uid,host,bytes,blocked,why FROM conn WHERE ts>=? AND ts<?",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += ConnRow(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3), c.getInt(4) == 1, c.getString(5))
        }
        return out
    }

    /** Deletes everything collected. */
    fun clearAll() = writableDatabase.transaction {
        for (t in listOf("access", "net", "screen", "conn")) delete(t, null, null)
    }

    /** (ts, op) of one app's accesses since `from` (for the app's chart). */
    fun accessOf(pkg: String, from: Long): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT DISTINCT ts, op FROM access WHERE pkg=? AND ts>=? AND kind='access'", arrayOf(pkg, from.toString())).use { c ->
            List(c.count) { c.moveToNext(); c.getLong(0) to c.getString(1) }
        }

    /** (ts, host) of one uid's DNS requests since `from`. */
    fun connOf(uid: Int, from: Long): List<Pair<Long, String>> =
        readableDatabase.rawQuery("SELECT ts, host FROM conn WHERE uid=? AND ts>=?", arrayOf(uid.toString(), from.toString())).use { c ->
            List(c.count) { c.moveToNext(); c.getLong(0) to c.getString(1) }
        }

    @Volatile private var lastPrune = 0L

    /**
     * Deletes old data: history after 60 days, the DNS log (the biggest table) after 30 days.
     * At most once every 6 hours; a weekly VACUUM shrinks the file.
     */
    fun prune(days: Int = 60, connDays: Int = 30, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPrune < 6 * 3_600_000L) return
        lastPrune = now
        val cut = (now - days * 86_400_000L).toString()
        val connCut = (now - connDays * 86_400_000L).toString()
        writableDatabase.transaction {
            delete("access", "ts<?", arrayOf(cut))
            delete("net", "bucket<?", arrayOf(cut))
            delete("screen", "ts<?", arrayOf(cut))
            delete("conn", "ts<?", arrayOf(connCut))
        }
    }

    fun vacuum() = runCatching { writableDatabase.execSQL("VACUUM") }

    fun sizeBytes(ctx: Context) = ctx.getDatabasePath("privacy.db").length()

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            val r = block()
            setTransactionSuccessful()
            return r
        } finally {
            endTransaction()
        }
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }
}
