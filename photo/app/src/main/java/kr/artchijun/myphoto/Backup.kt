package kr.artchijun.myphoto

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.MediaStore
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 자동 백업 — 안정성이 먼저:
 *   1) 휴대폰 사진·동영상마다 상태를 이 휴대폰의 작은 DB 에 적음 (기다림·완료·실패). 앱이 꺼져도 어디까지 했는지 안 잊음
 *   2) 새것부터 올림. 올리기 전 그 폴더에 같은 이름·크기가 이미 있으면 올리지 않고 '완료'로 (예전 시놀로지 포토 앱이 올린 것)
 *   3) 끊기면(망·서버) 그 자리에서 멈추고 나중에 이어서. 한 파일이 계속 실패해도 다른 파일은 계속
 *   4) 원본 그대로: 위치(GPS) 정보를 지우지 않음 (ACCESS_MEDIA_LOCATION + setRequireOriginal)
 *   올릴 곳: 공유 공간의 "/<기기 폴더>/<휴대폰 안 경로>"  예) /JUN PHONE/DCIM/Camera  (선택: 연/월 폴더 더하기)
 */
object Backup {
    const val WAIT = 0; const val DONE = 1; const val FAIL = 2
    private const val MAX_TRIES = 6

    class Settings(ctx: Context) {
        private val pf = Prefs(ctx)
        var on: Boolean get() = pf.bool("bk_on", false); set(v) = pf.putBool("bk_on", v)
        /** 공유 공간 안 기기 폴더 이름 (예: JUN PHONE) */
        var root: String get() = pf.str("bk_root", ""); set(v) = pf.putStr("bk_root", v.trim().trim('/'))
        /** 올릴 휴대폰 폴더들: "DCIM/Camera/" = 그 폴더와 아래 하위 폴더 전부, "=DCIM/" = 그 폴더에 바로 있는 파일만, "=" = 맨 위 파일 */
        var buckets: Set<String> get() = pf.str("bk_buckets", "DCIM/Camera/").split('\n').filter { it.isNotEmpty() }.toSet()
            set(v) = pf.putStr("bk_buckets", v.joinToString("\n"))
        var wifiOnly: Boolean get() = pf.bool("bk_wifi", true); set(v) = pf.putBool("bk_wifi", v)
        var chargingOnly: Boolean get() = pf.bool("bk_charge", false); set(v) = pf.putBool("bk_charge", v)
        var videos: Boolean get() = pf.bool("bk_video", true); set(v) = pf.putBool("bk_video", v)
        var byMonth: Boolean get() = pf.bool("bk_month", false); set(v) = pf.putBool("bk_month", v)
        var lastRun: Long get() = pf.long("bk_last", 0); set(v) = pf.putLong("bk_last", v)
        var lastOk: Long get() = pf.long("bk_lastok", 0); set(v) = pf.putLong("bk_lastok", v)
        var lastMsg: String get() = pf.str("bk_msg", ""); set(v) = pf.putStr("bk_msg", v)
    }

    // ---------- DB ----------
    class Db(ctx: Context) : SQLiteOpenHelper(ctx, "backup.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE media(id INTEGER PRIMARY KEY, video INTEGER, rel TEXT, name TEXT, size INTEGER, mtime INTEGER, taken INTEGER,
                state INTEGER DEFAULT 0, tries INTEGER DEFAULT 0, err TEXT, at INTEGER DEFAULT 0)""")
            db.execSQL("CREATE INDEX media_state ON media(state, taken)")
            db.execSQL("CREATE TABLE log(t INTEGER, msg TEXT)")
            onUpgrade(db, 1, 3)
        }
        // 2: 서버에서 확인한 자리 (공간 확보가 씀) — srv_folder: 공유 공간 폴더 번호, srv_at: 서버에 있음을 확인한 때
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) {
                db.execSQL("ALTER TABLE media ADD COLUMN srv_folder INTEGER DEFAULT 0"); db.execSQL("ALTER TABLE media ADD COLUMN srv_at INTEGER DEFAULT 0")
                db.execSQL("UPDATE media SET state=0 WHERE state=1")   // 예전 완료는 다음 백업 때 서버 자리를 다시 확인 (이미 있으면 올리지 않고 바로 완료)
            }
            // 3: 어떻게 끝났는지 (how: 1 올림 · 2 이미 있어 건너뜀) 와 서버 경로 — 백업 기록 화면이 보여 줌
            if (o < 3) { db.execSQL("ALTER TABLE media ADD COLUMN how INTEGER DEFAULT 0"); db.execSQL("ALTER TABLE media ADD COLUMN srv_path TEXT") }
        }
    }
    @Volatile private var db: Db? = null
    fun db(ctx: Context): SQLiteDatabase = (db ?: synchronized(this) { db ?: Db(ctx.applicationContext).also { db = it } }).writableDatabase

    fun log(ctx: Context, msg: String) {
        val d = db(ctx)
        d.insert("log", null, ContentValues().apply { put("t", System.currentTimeMillis()); put("msg", msg) })
        d.execSQL("DELETE FROM log WHERE rowid NOT IN (SELECT rowid FROM log ORDER BY t DESC LIMIT 300)")
    }
    fun recentLog(ctx: Context, n: Int = 60): List<Pair<Long, String>> =
        db(ctx).rawQuery("SELECT t, msg FROM log ORDER BY t DESC LIMIT $n", null).use { c -> List(c.count) { c.moveToNext(); c.getLong(0) to c.getString(1) } }

    class Counts(val waiting: Int, val done: Int, val failed: Int, val waitBytes: Long, val uploaded: Int = 0, val skipped: Int = 0)
    fun counts(ctx: Context): Counts = db(ctx).rawQuery(
        "SELECT SUM(state=0), SUM(state=1), SUM(state=2), SUM(CASE WHEN state=0 THEN size ELSE 0 END), SUM(state=1 AND how=1), SUM(state=1 AND how=2) FROM media", null).use { c ->
        c.moveToFirst(); Counts(c.getInt(0), c.getInt(1), c.getInt(2), c.getLong(3), c.getInt(4), c.getInt(5)) }

    /** 백업 기록 화면의 한 줄 */
    class Entry(val key: Long, val video: Boolean, val rel: String, val name: String, val size: Long, val taken: Long, val state: Int, val how: Int, val at: Long, val srvPath: String?, val err: String?) {
        val uri: Uri get() = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, key / 2)
    }
    /** state: 0 기다림(올릴 차례대로) · 1 완료(최근 것부터) · 2 실패 */
    fun entries(ctx: Context, state: Int, limit: Int = 3000): List<Entry> =
        db(ctx).rawQuery("SELECT id, video, rel, name, size, taken, state, how, at, srv_path, err FROM media WHERE state=$state ORDER BY " +
            (if (state == 0) "taken DESC" else "at DESC") + " LIMIT $limit", null).use { c ->
            List(c.count) { c.moveToNext(); Entry(c.getLong(0), c.getInt(1) == 1, c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4), c.getLong(5), c.getInt(6), c.getInt(7), c.getLong(8), c.getString(9), c.getString(10)) } }

    fun failures(ctx: Context): List<Pair<String, String>> =
        db(ctx).rawQuery("SELECT rel||name, err FROM media WHERE state=2 ORDER BY at DESC LIMIT 100", null).use { c -> List(c.count) { c.moveToNext(); c.getString(0) to (c.getString(1) ?: "") } }

    fun retryFailed(ctx: Context) { db(ctx).execSQL("UPDATE media SET state=0, tries=0 WHERE state=2") }

    // ---------- 휴대폰 사진 훑기 ----------
    /** 휴대폰 안 사진 폴더 목록 (RELATIVE_PATH → 장수) — 백업할 폴더 고르기용 */
    fun deviceBuckets(ctx: Context): List<Pair<String, Int>> {
        val m = HashMap<String, Int>()
        for (uri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
                while (c.moveToNext()) { val r = c.getString(0) ?: continue; m[r] = (m[r] ?: 0) + 1 } }
        return m.entries.sortedWith(compareBy({ !it.key.startsWith("DCIM/") }, { it.key })).map { it.key to it.value }
    }

    /** 고른 폴더 → MediaStore 조건 (하위 폴더 포함은 LIKE '경로%') */
    fun bucketWhere(buckets: Set<String>): Pair<String, Array<String>> {
        val rp = MediaStore.MediaColumns.RELATIVE_PATH
        val parts = ArrayList<String>(); val args = ArrayList<String>()
        for (b in buckets) when {
            b == "=" -> parts += "($rp IS NULL OR $rp='' OR $rp='/')"
            b.startsWith("=") -> { parts += "$rp=?"; args += b.removePrefix("=") }
            else -> { parts += "$rp LIKE ? ESCAPE '\\'"; args += b.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        }
        return parts.joinToString(" OR ") to args.toTypedArray()
    }

    /** 고른 폴더의 사진을 DB 와 맞춤: 새것은 '기다림'으로, 바뀐 것은 다시 '기다림'으로, 사라진 것은 지움 */
    fun scan(ctx: Context): Int {
        val st = Settings(ctx)
        val d = db(ctx)
        val seen = HashSet<Long>()
        var added = 0
        val buckets = st.buckets
        if (buckets.isEmpty()) return 0
        val (where, args) = bucketWhere(buckets)
        val sources = arrayListOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to 0)
        if (st.videos) sources += MediaStore.Video.Media.EXTERNAL_CONTENT_URI to 1
        d.beginTransaction()
        try {
            for ((uri, video) in sources) {
                ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.IS_PENDING),
                    "($where)", args, null)?.use { c ->
                    while (c.moveToNext()) {
                        if (c.getInt(6) == 1) continue   // 아직 쓰는 중인 파일
                        val id = c.getLong(0); val size = c.getLong(3); val mtime = c.getLong(4)
                        if (size <= 0) continue
                        val key = id * 2 + video   // 사진·동영상 번호가 겹치지 않게
                        seen += key
                        val cur = d.rawQuery("SELECT size, mtime FROM media WHERE id=?", arrayOf(key.toString())).use { r -> if (r.moveToFirst()) r.getLong(0) to r.getLong(1) else null }
                        if (cur == null) {
                            d.insert("media", null, ContentValues().apply {
                                put("id", key); put("video", video); put("rel", c.getString(1) ?: ""); put("name", c.getString(2) ?: "$id")
                                put("size", size); put("mtime", mtime); put("taken", if (c.isNull(5)) mtime * 1000 else c.getLong(5))
                            }); added++
                        } else if (cur.first != size || cur.second != mtime) {
                            d.update("media", ContentValues().apply { put("size", size); put("mtime", mtime); put("state", WAIT); put("tries", 0); put("srv_folder", 0); put("srv_at", 0) }, "id=?", arrayOf(key.toString()))
                            added++
                        }
                    }
                }
            }
            // 고른 폴더에서 빠졌거나 휴대폰에서 지운 것은 목록에서 뺌 (NAS 의 것은 그대로)
            val all = d.rawQuery("SELECT id FROM media", null).use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } }
            for (k in all) if (k !in seen) d.delete("media", "id=?", arrayOf(k.toString()))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        return added
    }

    // ---------- 조건 ----------
    fun netOk(ctx: Context, wifiOnly: Boolean): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    fun charging(ctx: Context): Boolean = ctx.getSystemService(BatteryManager::class.java).isCharging
    /** 지금 백업할 수 있나 (아니면 이유) */
    fun blocker(ctx: Context): String? {
        val st = Settings(ctx)
        val pf = Prefs(ctx)
        return when {
            !pf.loggedIn -> "로그인이 필요합니다"
            st.root.isEmpty() -> "올릴 기기 폴더 이름을 정해 주세요"
            st.buckets.isEmpty() -> "백업할 휴대폰 폴더를 골라 주세요"
            !netOk(ctx, st.wifiOnly) -> if (st.wifiOnly) "와이파이를 기다리는 중" else "인터넷 연결을 기다리는 중"
            st.chargingOnly && !charging(ctx) -> "충전을 기다리는 중"
            else -> null
        }
    }

    // ---------- 올리기 ----------
    /** 지금 올리는 파일: done/total 번째, 이름, 보낸 바이트/크기, 휴대폰 번호(key), 휴대폰 폴더, 이 파일을 시작한 때(ms), 서버 폴더 */
    class Progress(val done: Int, val total: Int, val name: String, val bytes: Long, val size: Long,
                   val key: Long = 0, val rel: String = "", val started: Long = System.currentTimeMillis(), val target: String = "")
    private val runLock = Any()
    @Volatile var running = false; private set
    @Volatile var current: Progress? = null; private set

    private val ym = SimpleDateFormat("yyyy/MM", Locale.KOREA).apply { timeZone = TimeZone.getDefault() }
    private fun target(st: Settings, rel: String, taken: Long): String {
        var p = "/" + st.root + "/" + rel.trim('/')
        if (st.byMonth) p += "/" + ym.format(Date(taken))
        return p.trimEnd('/')
    }

    private class Row(val key: Long, val video: Boolean, val rel: String, val name: String, val size: Long, val mtime: Long, val taken: Long, val tries: Int)

    /**
     * 한 번 돌기. stop() 이 true 가 되면 지금 파일을 끝내지 않고 멈춤 (다음에 처음부터 다시 올림).
     * 돌려주는 값: 다 끝났으면 true, 조건·망 때문에 멈췄으면 false (나중에 다시)
     */
    fun run(ctx: Context, stop: () -> Boolean, onProgress: (Progress?) -> Unit): Boolean {
        if (!synchronized(runLock) { if (running) false else { running = true; true } }) return false
        val st = Settings(ctx)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "myphoto:backup").apply { setReferenceCounted(false); acquire(60 * 60_000L) }
        @Suppress("DEPRECATION")
        val wifi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
            .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "myphoto:backup").apply { setReferenceCounted(false); acquire() }
        st.lastRun = System.currentTimeMillis()
        try {
            blocker(ctx)?.let { st.lastMsg = it; return false }
            scan(ctx)
            val ph = Photos(ctx)
            val ns = Photos.TEAM
            val d = db(ctx)
            val rows = d.rawQuery("SELECT id, video, rel, name, size, mtime, taken, tries FROM media WHERE state=0 OR (state=2 AND tries<$MAX_TRIES) ORDER BY taken DESC", null).use { c ->
                List(c.count) { c.moveToNext(); Row(c.getLong(0), c.getInt(1) == 1, c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getInt(7)) } }
            if (rows.isEmpty()) { st.lastMsg = "모두 백업됨"; st.lastOk = System.currentTimeMillis(); return true }
            val folderIds = HashMap<String, Long>()
            val existing = HashMap<Long, HashMap<String, Long>>()   // 폴더 → (이름 → 크기)
            var n = 0; var uploaded = 0; var skipped = 0; var failed = 0
            log(ctx, "백업 시작 — ${rows.size}개 남음")
            for (r in rows) {
                if (stop()) { st.lastMsg = "멈춤 — ${rows.size - n}개 남음"; return false }
                blocker(ctx)?.let { st.lastMsg = it; log(ctx, "멈춤: $it"); return false }
                n++
                val t0 = System.currentTimeMillis()
                val path = target(st, r.rel, r.taken)
                current = Progress(n, rows.size, r.name, 0, r.size, r.key, r.rel, t0, path); onProgress(current)
                try {
                    val fid = folderIds[path] ?: ph.folderByPath(ns, path, true)!!.also { folderIds[path] = it }
                    val names = existing.getOrPut(fid) { listNames(ph, ns, fid) }
                    val have = names[r.name.lowercase()]
                    if (have == r.size) { done(d, r.key, fid, 2, path); skipped++; continue }   // 이미 있음 (같은 이름·크기) — 서버 자리도 적어 둠
                    // 밖(터널)에서 큰 파일은 Syno.upload 가 알아서 나눠 올림
                    val uri = ContentUris.withAppendedId(if (r.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, r.key / 2)
                    ph.syno.upload(ns, fid, r.name, r.mtime, r.size, { openOriginal(ctx, uri) },
                        duplicate = if (have != null) "rename" else "ignore",   // 같은 이름인데 다른 파일이면 이름을 바꿔서
                        progress = { b -> current = Progress(n, rows.size, r.name, b, r.size, r.key, r.rel, t0, path); onProgress(current) }, canceled = stop)
                    names[r.name.lowercase()] = r.size
                    done(d, r.key, fid, 1, path); uploaded++
                    st.lastOk = System.currentTimeMillis()
                } catch (e: InterruptedException) {
                    st.lastMsg = "멈춤"; return false
                } catch (e: SynoError) {
                    // 서버가 거절: 이 파일만 실패로 두고 다음 파일 (세션 문제면 전체를 멈춤)
                    if (e.code in setOf(105, 106, 107, 119)) { st.lastMsg = "로그인이 끊겼습니다 — 앱에서 다시 로그인해 주세요"; log(ctx, st.lastMsg); return false }
                    fail(d, r, e.message ?: "오류 ${e.code}"); failed++
                    log(ctx, "실패: ${r.name} — ${e.message}")
                } catch (e: IOException) {
                    // 망이 끊김: 전체를 멈추고 나중에 이어서 (이 파일은 시도 횟수만 셈)
                    if (!netOk(ctx, st.wifiOnly) || e is java.net.SocketTimeoutException || e is java.net.ConnectException || e is java.net.UnknownHostException) {
                        d.execSQL("UPDATE media SET tries=tries+1 WHERE id=${r.key}")
                        st.lastMsg = "연결이 끊겨 멈춤 — 나중에 이어서"; log(ctx, "${r.name}: ${e.message} → 나중에 이어서"); return false
                    }
                    fail(d, r, e.message ?: "읽거나 보내지 못함"); failed++
                    log(ctx, "실패: ${r.name} — ${e.message}")
                } catch (e: SecurityException) {
                    fail(d, r, "읽기 권한 없음"); failed++
                }
            }
            st.lastMsg = "끝 — 올림 $uploaded · 이미 있음 $skipped" + (if (failed > 0) " · 실패 $failed" else "")
            log(ctx, st.lastMsg)
            return failed == 0
        } catch (e: Exception) {
            st.lastMsg = "멈춤: ${e.message}"; log(ctx, st.lastMsg); return false
        } finally {
            current = null; onProgress(null)
            if (wifi.isHeld) wifi.release()
            if (wake.isHeld) wake.release()
            running = false
        }
    }

    private fun listNames(ph: Photos, ns: String, folderId: Long): HashMap<String, Long> {
        val m = HashMap<String, Long>()
        var off = 0
        while (true) {
            val d = ph.syno.call("$ns.Browse.Item", "list", 4, mapOf("offset" to off, "limit" to 1000, "folder_id" to folderId))
            val a = d.optJSONArray("list") ?: break
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); m[o.optString("filename").lowercase()] = o.optLong("filesize") }
            if (a.length() < 1000) break
            off += 1000
        }
        return m
    }

    // ---------- 공간 확보 ----------
    class Local(val key: Long, val video: Boolean, val rel: String, val name: String, val size: Long, val taken: Long, val folder: Long) {
        val uri: Uri get() = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, key / 2)
    }
    /** 백업이 끝난(서버에 있다고 적힌) 휴대폰 파일들. 공간 확보 화면이 거름 */
    fun backedUp(ctx: Context): List<Local> {
        scan(ctx)
        return db(ctx).rawQuery("SELECT id, video, rel, name, size, taken, srv_folder FROM media WHERE state=1 AND srv_folder>0 ORDER BY taken DESC", null).use { c ->
            List(c.count) { c.moveToNext(); Local(c.getLong(0), c.getInt(1) == 1, c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getLong(6)) } }
    }
    /** 지우기 직전 확인: 서버 폴더에 같은 이름·크기가 지금도 있는 것만 돌려줌 */
    fun verifyOnServer(ctx: Context, list: List<Local>, progress: (Int) -> Unit = {}): List<Local> {
        val ph = Photos(ctx)
        val byFolder = list.groupBy { it.folder }
        val ok = ArrayList<Local>()
        var n = 0
        for ((fid, items) in byFolder) {
            val names = listNames(ph, Photos.TEAM, fid)
            for (it in items) if (names[it.name.lowercase()] == it.size) ok += it
            n += items.size; progress(n)
        }
        return ok
    }
    fun forget(ctx: Context, keys: Collection<Long>) { val d = db(ctx); keys.forEach { d.delete("media", "id=?", arrayOf(it.toString())) } }

    /** 원본 그대로 열기 (위치 정보 포함). 권한이 없으면 그냥 열기 */
    private fun openOriginal(ctx: Context, uri: Uri): InputStream = try {
        ctx.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))!!
    } catch (e: SecurityException) { ctx.contentResolver.openInputStream(uri)!! } catch (e: UnsupportedOperationException) { ctx.contentResolver.openInputStream(uri)!! }

    private fun done(d: SQLiteDatabase, key: Long, folder: Long, how: Int, path: String) {
        val now = System.currentTimeMillis()
        d.update("media", ContentValues().apply { put("state", DONE); put("err", null as String?); put("at", now); put("srv_folder", folder); put("srv_at", now); put("how", how); put("srv_path", path) }, "id=?", arrayOf(key.toString()))
    }
    private fun fail(d: SQLiteDatabase, r: Row, err: String) {
        d.update("media", ContentValues().apply { put("state", FAIL); put("err", err); put("tries", r.tries + 1); put("at", System.currentTimeMillis()) }, "id=?", arrayOf(r.key.toString()))
    }
}
