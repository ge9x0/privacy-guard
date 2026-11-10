package com.privacyguard.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Nje qasje ne mikrofon/kamere/vendndodhje/... */
data class AccessEvent(
    val pkg: String,
    val uid: Int,
    val op: String,
    val ts: Long,            // ms
    val durationMs: Long,
    val state: String,       // top, fgsvc, bg, cch, ... ose "unknown"
    val kind: String,        // access | reject
    val source: String,      // discrete | last | live
)

data class NetRow(val uid: Int, val net: String, val set: String, val bucket: Long, val rx: Long, val tx: Long)
data class ConnRow(val ts: Long, val uid: Int, val host: String, val bytes: Long)
data class ScreenRow(val ts: Long, val on: Boolean, val topPkg: String?)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "privacy.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE access (pkg TEXT, uid INTEGER, op TEXT, ts INTEGER, duration_ms INTEGER,
            state TEXT, kind TEXT, source TEXT, UNIQUE(pkg, op, ts, kind, source))""")
        db.execSQL("""CREATE TABLE net (uid INTEGER, net TEXT, nset TEXT, bucket INTEGER, rx INTEGER, tx INTEGER,
            PRIMARY KEY(uid, net, nset, bucket))""")
        db.execSQL("CREATE TABLE screen (ts INTEGER PRIMARY KEY, screen_on INTEGER, top_pkg TEXT)")
        db.execSQL("CREATE TABLE conn (ts INTEGER, uid INTEGER, host TEXT, bytes INTEGER)")
        db.execSQL("CREATE INDEX conn_ts ON conn(ts)")
        db.execSQL("CREATE INDEX access_ts ON access(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun addAccess(events: List<AccessEvent>): Int {
        var n = 0
        writableDatabase.transaction {
            for (e in events) {
                val cv = ContentValues().apply {
                    put("pkg", e.pkg); put("uid", e.uid); put("op", e.op); put("ts", e.ts)
                    put("duration_ms", e.durationMs); put("state", e.state); put("kind", e.kind); put("source", e.source)
                }
                if (insertWithOnConflict("access", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) n++
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
        val cv = ContentValues().apply { put("ts", r.ts); put("uid", r.uid); put("host", r.host); put("bytes", r.bytes) }
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
            "SELECT ts,uid,host,bytes FROM conn WHERE ts>=? AND ts<?",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) out += ConnRow(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3))
        }
        return out
    }

    /** Fshi te dhenat me te vjetra se N dite (qe baza te mos rritet pa fund). */
    fun prune(days: Int = 60) {
        val cut = System.currentTimeMillis() - days * 86_400_000L
        writableDatabase.transaction {
            delete("access", "ts<?", arrayOf(cut.toString()))
            delete("net", "bucket<?", arrayOf(cut.toString()))
            delete("screen", "ts<?", arrayOf(cut.toString()))
            delete("conn", "ts<?", arrayOf(cut.toString()))
        }
    }

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
