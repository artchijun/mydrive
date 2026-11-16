package kr.artchijun.myphoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore
import java.util.TimeZone

/**
 * 휴대폰 ↔ 서버 대조.
 *   서버 목록 (가벼운 정보만, library.db): 백업 폴더(올릴 곳 기기 폴더 아래)만 읽은 것 — 빠름, 기본
 *                                         서버 전체(공유 공간 모든 사진)를 읽은 것 — 넓혀 볼 때 (12만 장에 30초쯤)
 *   휴대폰 목록: MediaStore 의 모든 사진·동영상 (어느 폴더든)
 *   같은 것 찾기: ① 이름(대소문자 무시)·크기가 같음  ② 이름은 달라도 크기가 같고 찍은 때가 하루 안쪽
 *   상태: 백업됨(둘 다 있음) · 서버에만 · 휴대폰에만(백업 안 됨)
 */
object Library {
    const val BOTH = 1; const val SERVER_ONLY = 2; const val PHONE_ONLY = 3

    /** 휴대폰의 사진·동영상 하나 */
    class Phone(val key: Long, val video: Boolean, val rel: String, val name: String, val size: Long, val taken: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, key / 2)
        /** 시놀로지의 time 과 같은 꼴 (그 곳 시각을 UTC 로 적은 초) */
        val srvTime: Long get() = (taken + TimeZone.getDefault().getOffset(taken)) / 1000
        /** 격자·뷰어에서 쓰는 모양 (id 는 서버 번호와 겹치지 않게 음수) */
        fun toItem() = Item(-key, name, size, srvTime, if (video) "video" else "photo", 0, "", 0, 0, 0, 1, false, local = uri.toString())
    }
    class Match(val server: Item, val how: String)   // how: "이름·크기" · "크기·찍은 때"

    /** 대조 범위: 백업 폴더(올릴 곳 = 기기 폴더 아래)만 · 서버 전체 */
    const val BACKUP = 1; const val ALL = 0
    private fun table(scope: Int) = if (scope == BACKUP) "srvb" else "srv"
    private fun key(scope: Int, k: String) = (if (scope == BACKUP) "libb_" else "lib_") + k

    private class Db(ctx: Context) : SQLiteOpenHelper(ctx, "library.db", null, 2) {
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE srv(id INTEGER PRIMARY KEY, name TEXT, size INTEGER, time INTEGER, folder INTEGER, type TEXT, ck TEXT, unit INTEGER, w INTEGER, h INTEGER)")
            d.execSQL("CREATE TABLE fold(id INTEGER PRIMARY KEY, path TEXT)")
            onUpgrade(d, 1, 2)
        }
        // 2: 백업 폴더만 읽은 목록 (srvb)
        override fun onUpgrade(d: SQLiteDatabase, a: Int, b: Int) {
            if (a < 2) d.execSQL("CREATE TABLE srvb(id INTEGER PRIMARY KEY, name TEXT, size INTEGER, time INTEGER, folder INTEGER, type TEXT, ck TEXT, unit INTEGER, w INTEGER, h INTEGER)")
        }
    }
    @Volatile private var dbh: Db? = null
    private fun db(ctx: Context) = (dbh ?: synchronized(this) { dbh ?: Db(ctx.applicationContext).also { dbh = it } }).writableDatabase

    // ---------- 서버 목록 ----------
    @Volatile var refreshing = false; private set
    /** 마지막으로 서버 목록을 읽은 때 (ms) · 그때 읽은 장수 · (백업 폴더) 그때의 폴더 경로 */
    fun serverAt(ctx: Context, scope: Int = ALL) = Prefs(ctx).long(key(scope, "at"), 0)
    fun serverCount(ctx: Context, scope: Int = ALL) = Prefs(ctx).int(key(scope, "n"), 0)
    fun backupRoot(ctx: Context) = Backup.Settings(ctx).root.let { if (it.isEmpty()) "" else "/$it" }
    fun hasServer(ctx: Context, scope: Int = ALL) = serverAt(ctx, scope) > 0 && (scope == ALL || Prefs(ctx).str("libb_root", "") == backupRoot(ctx))
    /** 다시 읽어야 하나: 없거나, maxAge 보다 오래됐거나, 그 뒤에 백업으로 올린 것이 있거나, (백업 폴더) 올릴 곳이 바뀌었으면 */
    fun stale(ctx: Context, maxAge: Long, scope: Int = ALL): Boolean {
        val at = serverAt(ctx, scope)
        return !hasServer(ctx, scope) || System.currentTimeMillis() - at > maxAge || Backup.Settings(ctx).lastOk > at
    }

    /**
     * 서버 목록을 다시 읽어 적음.
     *   ALL: 공유 공간 전체 (5000장씩) — progress(읽은 장수, 전체 장수)
     *   BACKUP: 올릴 곳(기기 폴더)과 그 아래 폴더만 — progress(읽은 장수, -읽은 폴더 수)
     */
    fun refreshServer(ctx: Context, scope: Int = ALL, stop: () -> Boolean = { false }, progress: (Int, Int) -> Unit = { _, _ -> }): Boolean {
        if (!synchronized(this) { if (refreshing) false else { refreshing = true; true } }) return false
        try {
            val ph = Photos(ctx); val ns = Photos.TEAM
            val all = ArrayList<Item>()
            val paths = HashMap<Long, String>()
            if (scope == ALL) {
                val total = try { ph.syno.call("$ns.Browse.Item", "count", 7).optInt("count") } catch (e: Exception) { 0 }
                var off = 0
                while (true) {
                    if (stop()) return false
                    val page = ph.list(ns, off, 5000)
                    all += page; off += page.size
                    progress(all.size, maxOf(total, all.size))
                    if (page.size < 5000) break
                }
            } else {
                val root = backupRoot(ctx)
                if (root.isEmpty()) throw IllegalStateException("백업 화면에서 '올릴 곳'(기기 폴더)을 먼저 정해 주세요")
                val rid = ph.folderByPath(ns, root, false) ?: throw IllegalStateException("서버에 $root 폴더가 아직 없습니다")
                val queue = ArrayDeque<Pair<Long, String>>(); queue += rid to root
                var nf = 0
                while (queue.isNotEmpty()) {
                    if (stop()) return false
                    val (fid, fpath) = queue.removeFirst()
                    paths[fid] = fpath
                    var off = 0
                    while (true) {
                        val page = ph.list(ns, off, 5000, mapOf("folder_id" to fid))
                        all += page; off += page.size
                        if (page.size < 5000) break
                    }
                    for (f in ph.folders(ns, fid)) queue += f.id to f.path
                    nf++; progress(all.size, -nf)
                }
            }
            val d = db(ctx)
            d.beginTransaction()
            try {
                val t = table(scope)
                d.execSQL("DELETE FROM $t")
                val st = d.compileStatement("INSERT OR REPLACE INTO $t(id,name,size,time,folder,type,ck,unit,w,h) VALUES(?,?,?,?,?,?,?,?,?,?)")
                for (it in all) {
                    st.clearBindings()
                    st.bindLong(1, it.id); st.bindString(2, it.name); st.bindLong(3, it.size); st.bindLong(4, it.time); st.bindLong(5, it.folderId)
                    st.bindString(6, it.type); st.bindString(7, it.cacheKey); st.bindLong(8, it.unitId); st.bindLong(9, it.w.toLong()); st.bindLong(10, it.h.toLong())
                    st.executeInsert()
                }
                for ((id, path) in paths) d.insertWithOnConflict("fold", null, ContentValues().apply { put("id", id); put("path", path) }, SQLiteDatabase.CONFLICT_REPLACE)
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
            val pf = Prefs(ctx)
            pf.putLong(key(scope, "at"), System.currentTimeMillis()); pf.putInt(key(scope, "n"), all.size)
            if (scope == BACKUP) pf.putStr("libb_root", backupRoot(ctx))
            synchronized(this) { index.remove(scope) }
            return true
        } finally { refreshing = false }
    }

    // 서버 찾기표 (범위마다, 처음 쓸 때 DB 에서 만듦)
    private class Index(val byNameSize: HashMap<String, Long>, val bySize: HashMap<Long, LongArray>, val time: HashMap<Long, Long>)
    private val index = HashMap<Int, Index>()
    private fun idx(ctx: Context, scope: Int): Index = synchronized(this) {
        index[scope]?.let { return@synchronized it }
        val ns = HashMap<String, Long>(); val bs = HashMap<Long, ArrayList<Long>>(); val tm = HashMap<Long, Long>()
        db(ctx).rawQuery("SELECT id, name, size, time FROM ${table(scope)}", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val size = c.getLong(2)
                ns.putIfAbsent(c.getString(1).lowercase() + "|" + size, id)
                bs.getOrPut(size) { ArrayList(1) } += id
                tm[id] = c.getLong(3)
            }
        }
        Index(ns, HashMap(bs.mapValues { it.value.toLongArray() }), tm).also { index[scope] = it }
    }

    fun serverItem(ctx: Context, id: Long, scope: Int = ALL): Item? = db(ctx).rawQuery("SELECT id,name,size,time,folder,type,ck,unit,w,h FROM ${table(scope)} WHERE id=?", arrayOf(id.toString())).use { c ->
        if (!c.moveToFirst()) null else Item(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(5), c.getLong(4), c.getString(6), c.getLong(7), c.getInt(8), c.getInt(9), 1, false)
    }
    /** 서버 폴더 경로 (한 번 물어본 것은 적어 둠) */
    fun folderPath(ctx: Context, id: Long): String {
        db(ctx).rawQuery("SELECT path FROM fold WHERE id=?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) return c.getString(0) }
        val p = try { Photos(ctx).folder(Photos.TEAM, id).path } catch (e: Exception) { return "?" }
        db(ctx).insertWithOnConflict("fold", null, ContentValues().apply { put("id", id); put("path", p) }, SQLiteDatabase.CONFLICT_REPLACE)
        return p
    }

    /** 이 휴대폰 파일이 서버(그 범위)에 있나 */
    fun matchOnServer(ctx: Context, p: Phone, scope: Int = ALL): Match? {
        if (!hasServer(ctx, scope)) return null
        val ix = idx(ctx, scope)
        ix.byNameSize[p.name.lowercase() + "|" + p.size]?.let { id -> serverItem(ctx, id, scope)?.let { return Match(it, "이름·크기") } }
        val cands = ix.bySize[p.size] ?: return null
        if (p.size < 50_000) return null   // 아주 작은 파일은 크기만으로는 믿지 않음
        val t = p.srvTime
        val best = cands.minByOrNull { Math.abs((ix.time[it] ?: 0) - t) } ?: return null
        if (Math.abs((ix.time[best] ?: 0) - t) > 26 * 3600) return null
        return serverItem(ctx, best, scope)?.let { Match(it, "크기·찍은 때") }
    }

    // ---------- 휴대폰 목록 ----------
    @Volatile private var phone: List<Phone>? = null
    private var phByNameSize = HashMap<String, Phone>()
    private var phBySize = HashMap<Long, ArrayList<Phone>>()
    private var phoneAt = 0L

    /** 휴대폰의 모든 사진·동영상 (30초 동안은 다시 읽지 않음). 권한이 없으면 빈 목록 */
    fun phone(ctx: Context, force: Boolean = false): List<Phone> {
        val now = System.currentTimeMillis()
        phone?.let { if (!force && now - phoneAt < 30_000) return it }
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED) return phone ?: emptyList()   // 권한 전: 모름
        val out = ArrayList<Phone>()
        try {
            for ((uri, video) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to 0, MediaStore.Video.Media.EXTERNAL_CONTENT_URI to 1))
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(6) == 1) continue
                        val size = c.getLong(3); if (size <= 0) continue
                        val taken = if (c.isNull(4) || c.getLong(4) <= 0) c.getLong(5) * 1000 else c.getLong(4)
                        out += Phone(c.getLong(0) * 2 + video, video == 1, c.getString(1) ?: "", c.getString(2) ?: "", size, taken)
                    }
                }
        } catch (e: SecurityException) { }
        out.sortByDescending { it.taken }
        val bn = HashMap<String, Phone>(); val bs = HashMap<Long, ArrayList<Phone>>()
        for (p in out) { bn.putIfAbsent(p.name.lowercase() + "|" + p.size, p); bs.getOrPut(p.size) { ArrayList(1) } += p }
        synchronized(this) { phone = out; phByNameSize = bn; phBySize = bs; phoneAt = now }
        return out
    }

    /** 서버의 이 사진이 휴대폰에도 있나 (격자의 표시용 — 빠르게) */
    fun phoneHas(it: Item): Boolean {
        if (phone == null) return false
        if (phByNameSize.containsKey(it.name.lowercase() + "|" + it.size)) return true
        if (it.size < 50_000) return false
        return phBySize[it.size]?.any { p -> Math.abs(p.srvTime - it.time) <= 26 * 3600 } == true
    }
    val phoneLoaded get() = phone != null

    /** 백업 기록에 '올림·이미 있음' 으로 적힌 휴대폰 파일 번호 */
    private fun doneKeys(ctx: Context): Set<Long> = try {
        Backup.db(ctx).rawQuery("SELECT id FROM media WHERE state=1", null).use { c -> HashSet<Long>(c.count).apply { while (c.moveToNext()) add(c.getLong(0)) } }
    } catch (e: Exception) { emptySet() }

    /**
     * 서버에 없는 휴대폰 파일 (백업 안 됨): 백업 폴더 목록에도, (읽어 둔 적이 있으면) 서버 전체 목록에도 없고, 백업 기록에도 없는 것.
     * 어느 목록도 아직 읽지 않았으면 null
     */
    fun phoneOnly(ctx: Context): List<Phone>? {
        val b = hasServer(ctx, BACKUP); val a = hasServer(ctx, ALL)
        if (!b && !a) return null
        val done = doneKeys(ctx)
        return phone(ctx).filter { it.key !in done && (!b || matchOnServer(ctx, it, BACKUP) == null) && (!a || matchOnServer(ctx, it, ALL) == null) }
    }
}
