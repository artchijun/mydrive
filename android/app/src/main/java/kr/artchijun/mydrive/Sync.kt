package kr.artchijun.mydrive

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 폴더 동기화: 휴대폰 폴더 하나 ↔ NAS 폴더 하나 (짝).
 *   방향  양방향 · 휴대폰→NAS(올리기만) · NAS→휴대폰(받기만)
 *   언제  실시간(바뀌면 바로) · N시간마다 · 원할 때만
 *
 * 지난번에 맞춰 둔 상태(기준)를 짝마다 기억해 두고, 양쪽 지금 상태와 비교해서 어느 쪽이 바뀌었는지 가림.
 *   한쪽만 바뀜 → 그쪽 것을 다른 쪽으로 (지운 것도 — 양방향만)
 *   양쪽 다 바뀜 → 충돌: 둘 다 남김 (휴대폰 것은 '이름 (휴대폰 날짜 시각)' 으로 바꿔 함께 올림)
 *   지울 때는 휴대폰 → 앱 휴지통, NAS → NAS 휴지통 (#recycle) — 되살릴 수 있게
 *   한 번에 많이 지우게 되면 (폴더를 잘못 비웠을 때 등) 지우지 않고 멈춰서 물음
 */
object Sync {
    const val BOTH = 0; const val UP = 1; const val DOWN = 2
    const val REALTIME = 0; const val TIMED = 1; const val MANUAL = 2

    class Link(
        var id: String = Api.newId().take(12), var local: String = "", var root: String = "", var rpath: String = "",
        var dir: Int = BOTH, var mode: Int = REALTIME, var hours: Int = 6, var wifiOnly: Boolean = false, var on: Boolean = true,
    ) {
        val web get() = root.startsWith("~")
        val name get() = local.substringAfterLast('/').ifEmpty { "휴대폰" }
        fun nasText() = (if (web) root.drop(1) else root) + (if (rpath.isEmpty()) "" else "/$rpath")
        fun json(): JSONObject = JSONObject().put("id", id).put("local", local).put("root", root).put("rpath", rpath)
            .put("dir", dir).put("mode", mode).put("hours", hours).put("wifi", wifiOnly).put("on", on)
        companion object {
            fun of(o: JSONObject) = Link(o.getString("id"), o.optString("local"), o.optString("root"), o.optString("rpath"),
                o.optInt("dir"), o.optInt("mode"), o.optInt("hours", 6), o.optBoolean("wifi"), o.optBoolean("on", true))
        }
    }

    class Status(var time: Long = 0, var msg: String = "", var err: Boolean = false, var held: Int = 0, var epoch: String = "", var seq: Long = -1) {
        fun json(): JSONObject = JSONObject().put("t", time).put("msg", msg).put("err", err).put("held", held).put("epoch", epoch).put("seq", seq)
        companion object { fun of(o: JSONObject?) = if (o == null) Status() else Status(o.optLong("t"), o.optString("msg"), o.optBoolean("err"), o.optInt("held"), o.optString("epoch"), o.optLong("seq", -1)) }
    }

    /** 지금 도는 짝의 진행 */
    class Progress(val id: String) {
        @Volatile var phase = "준비 중"; @Volatile var done = 0; @Volatile var total = 0
        @Volatile var file = ""; @Volatile var bytes = 0L; @Volatile var size = 0L
        /** 지금 함께 옮기는 파일들: 경로 → [옮긴 바이트, 전체 바이트, 1=올림 0=받음] */
        val active = ConcurrentHashMap<String, LongArray>()
    }
    /** 한 번에 함께 옮길 파일 수 (설정, 1~8) */
    fun parallel(ctx: Context) = Prefs(ctx).int("sync_par", 4).coerceIn(1, 8)
    /** 왜 돌았는지 (화면에 보임) */
    const val WHY_MANUAL = "지금 동기화"; const val WHY_LOCAL = "휴대폰에서 바뀜"; const val WHY_NAS = "NAS에서 바뀜"; const val WHY_TIME = "정한 시간"
    @Volatile var cur: Progress? = null

    // ---------- 짝·상태 저장 ----------
    private val pf = Any()
    fun pairs(ctx: Context): List<Link> = try { JSONArray(Prefs(ctx).str("sync_pairs", "[]")).let { a -> (0 until a.length()).map { Link.of(a.getJSONObject(it)) } } } catch (e: Exception) { emptyList() }
    fun savePairs(ctx: Context, l: List<Link>) { Prefs(ctx).putStr("sync_pairs", JSONArray(l.map { it.json() }).toString()) }
    fun status(ctx: Context, id: String): Status = synchronized(pf) { Status.of(try { JSONObject(Prefs(ctx).str("sync_st", "{}")).optJSONObject(id) } catch (e: Exception) { null }) }
    fun setStatus(ctx: Context, id: String, f: (Status) -> Unit) = synchronized(pf) {
        val all = try { JSONObject(Prefs(ctx).str("sync_st", "{}")) } catch (e: Exception) { JSONObject() }
        val s = Status.of(all.optJSONObject(id)); f(s); all.put(id, s.json()); Prefs(ctx).putStr("sync_st", all.toString())
    }
    /** 짝을 지우면: 기억해 둔 기준도 지움 (파일은 양쪽에 그대로) */
    fun forget(ctx: Context, id: String) {
        File(ctx.filesDir, "sync/$id.json").delete()
        synchronized(pf) { val all = try { JSONObject(Prefs(ctx).str("sync_st", "{}")) } catch (e: Exception) { JSONObject() }; all.remove(id); Prefs(ctx).putStr("sync_st", all.toString()) }
    }

    // ---------- 기다리는 짝 (앱이 꺼져도 남게 저장) ----------
    private val running = AtomicBoolean(false)
    val busy get() = running.get()
    /** 이번에 지우기를 허락받은 짝 (많이 지우게 되어 멈췄던 것) */
    private val allowDelete = ConcurrentHashMap.newKeySet<String>()
    /** 이번에 '되살리기'를 고른 짝: 한쪽에서 지운 것을 다른 쪽에서 다시 가져옴 */
    private val restoreSet = ConcurrentHashMap.newKeySet<String>()
    private fun pendingMap(ctx: Context): JSONObject = try { JSONObject(Prefs(ctx).str("sync_pending", "{}")) } catch (e: Exception) { JSONObject() }
    /** 돌릴 짝 넣기. manual = 사용자가 눌렀음 (와이파이만·꺼 둠을 따지지 않음) */
    /**
     * 돌릴 짝 넣기. manual = 사용자가 눌렀음 (와이파이만·꺼 둠을 따지지 않음)
     *   localOnly = 휴대폰 변경 알림으로만 깨어남 → 먼저 휴대폰 폴더만 보고, 바뀐 게 없으면 NAS 는 읽지 않고 끝냄
     */
    fun request(ctx: Context, ids: Collection<String>, manual: Boolean, allowDel: Boolean = false, restore: Boolean = false, localOnly: Boolean = false, why: String = "") = synchronized(pf) {
        if (ids.isEmpty()) return@synchronized
        android.util.Log.i("MydriveSync", "요청 ${ids.size}개 manual=$manual · " + Throwable().stackTrace.getOrNull(1)?.let { "${it.fileName}:${it.lineNumber}" })
        val m = pendingMap(ctx)
        val bits = (if (manual) 1 else 0) or (if (localOnly && !manual) 0 else 2)
        for (id in ids) {
            val old = m.optJSONObject(id)
            m.put(id, JSONObject().put("f", (old?.optInt("f") ?: 0) or bits).put("why", old?.optString("why")?.takeIf { it.isNotEmpty() && !manual } ?: why.ifEmpty { if (manual) WHY_MANUAL else "" }))
            if (allowDel) allowDelete += id; if (restore) restoreSet += id
        }
        Prefs(ctx).putStr("sync_pending", m.toString())
    }
    fun pendingIds(ctx: Context): Set<String> = synchronized(pf) { pendingMap(ctx).keys().asSequence().toSet() }
    private class Want(val id: String, val manual: Boolean, val localOnly: Boolean, val why: String)
    private fun take(ctx: Context): Want? = synchronized(pf) {
        val m = pendingMap(ctx); val k = m.keys().asSequence().firstOrNull() ?: return@synchronized null
        val o = m.optJSONObject(k); val f = o?.optInt("f", 3) ?: (if (m.optBoolean(k)) 3 else 2)   // 예전 판은 true/false 로 적었음
        m.remove(k); Prefs(ctx).putStr("sync_pending", m.toString())
        Want(k, f and 1 != 0, f and 2 == 0, o?.optString("why") ?: "")
    }

    /** 동기화가 방금 쓴 경로 — 그 때문에 생긴 변경 알림은 무시 (같은 짝을 또 돌리지 않게) */
    private val recent = ConcurrentHashMap<String, Long>()
    fun mark(key: String) {
        // 위 폴더들도 (안에 파일을 쓰면 폴더의 바뀐 시각도 달라져 변경으로 들어옴)
        val now = System.currentTimeMillis()
        var k = key
        while (true) { recent[k] = now; val i = k.lastIndexOf('/'); if (i <= k.indexOf('\u0000')) break; k = k.substring(0, i) }
        if (recent.size > 4000) recent.entries.removeIf { now - it.value > 120_000 }
    }
    fun ours(key: String): Boolean = recent[key]?.let { System.currentTimeMillis() - it < 90_000 } ?: false

    /**
     * 기다리는 짝을 모두 돌림. 다른 쪽(서비스·예약 작업)이 이미 돌리는 중이면 false (그쪽이 기다리는 것까지 함).
     * stop() 이 참이 되면 지금 짝을 다시 기다리는 것에 넣고 멈춤.
     */
    fun drain(ctx: Context, stop: () -> Boolean): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            while (!stop()) {
                val w = take(ctx) ?: break
                val p = pairs(ctx).firstOrNull { it.id == w.id } ?: continue
                if (!w.manual && !p.on) continue
                val finished = runPair(ctx, p, w.manual, stop, w.localOnly, w.why)
                if (!finished) { request(ctx, listOf(w.id), w.manual, why = w.why); break }
            }
        } finally { cur = null; running.set(false) }
        return true
    }

    private fun metered(ctx: Context) = try { ctx.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered } catch (e: Exception) { false }

    /** 짝 하나. 끝까지 했으면 true (실패가 있어도), 멈춰서 덜 했으면 false */
    private fun runPair(ctx: Context, p: Link, manual: Boolean, stop: () -> Boolean, localOnly: Boolean = false, why: String = ""): Boolean {
        if (!manual && p.wifiOnly && metered(ctx)) {
            setStatus(ctx, p.id) { it.msg = "와이파이를 기다립니다"; it.err = false }
            return true
        }
        val pr = Progress(p.id).also { cur = it }
        val pfs = Prefs(ctx)
        val api = pfs.api()
        // NAS 변경 번호를 먼저 받아 둠 (읽는 사이에 바뀐 것은 다음에 다시 보게)
        var epoch = ""; var seq = -1L
        if (!p.web) try { api.roots().firstOrNull { it.name == p.root }?.let { epoch = it.epoch; seq = it.seq } } catch (e: Exception) { }
        return try {
            val r = Engine(ctx, p, api, Fs(ctx, api), stop, pr, allowDelete.remove(p.id), restoreSet.remove(p.id), localOnly, parallel(ctx)).run()
            if (stop()) { setStatus(ctx, p.id) { it.msg = "멈춤 — 다음에 이어서"; it.err = false }; return false }
            if (r.skipped) return true   // 휴대폰에서 바뀐 게 없었음 → 상태도 그대로
            setStatus(ctx, p.id) { it.time = System.currentTimeMillis(); it.msg = (if (why.isNotEmpty()) "$why → " else "") + r.text; it.err = r.failed > 0; it.held = r.held; if (seq >= 0) { it.epoch = epoch; it.seq = seq } }
            if (r.failed > 0) { SyncNote.problem(ctx, p, r.text); if (!stop()) SyncSchedule.soon(ctx, 10 * 60_000L) }
            if (r.held > 0) SyncNote.problem(ctx, p, "지울 것이 ${r.held}개라 지우지 않고 멈췄습니다. 눌러서 확인해 주세요")
            true
        } catch (e: Canceled) {
            setStatus(ctx, p.id) { it.msg = "멈춤 — 다음에 이어서"; it.err = false }; false
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            setStatus(ctx, p.id) { it.time = System.currentTimeMillis(); it.msg = msg; it.err = true }
            if (e is IOException && e !is ApiException && !stop()) SyncSchedule.soon(ctx, 5 * 60_000L)   // 연결이 끊김: 조금 뒤 다시
            true
        }
    }

    // ---------- 동기화 한 번 ----------
    class Result(val text: String, val failed: Int, val held: Int, val skipped: Boolean = false)

    private class L(val size: Long, val m: Long)
    /** 기준: 지난번에 맞췄을 때 휴대폰 (크기·시각 ms) · NAS (크기·시각 ns) */
    private class Rec(var ls: Long, var lm: Long, var rs: Long, var rm: Long, val d: Boolean = false)

    private class Engine(val ctx: Context, val p: Link, val api: Api, val fs: Fs, val stop: () -> Boolean, val pr: Progress, val allowDel: Boolean, val restore: Boolean = false,
                         val localOnly: Boolean = false, val par: Int = 4) {
        val top = File(p.local)
        val stateFile = File(ctx.filesDir, "sync/${p.id}.json")
        val base = HashMap<String, Rec>()
        val ups = HashMap<String, String>()   // 이어 올리기 번호
        val lf = HashMap<String, L>(); val ld = HashSet<String>()
        val rf = HashMap<String, Entry>(); val rd = HashSet<String>()
        var nMk = 0; var nUp = 0; var nDown = 0; var nDelL = 0; var nDelR = 0; var nConf = 0; var nFail = 0; var held = 0
        val errs = ArrayList<String>()
        var lastSave = System.currentTimeMillis()

        /** 휴대폰 파일 시각 — 목록 읽기와 같은 방법으로 (File.lastModified 는 ms, nio 는 초 단위로 나와 서로 달랐음) */
        fun lmod(f: File) = try { java.nio.file.Files.getLastModifiedTime(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis() } catch (e: Exception) { f.lastModified() }
        fun rp(rel: String) = if (p.rpath.isEmpty()) rel else "${p.rpath}/$rel"
        fun skip(n: String) = n == Fs.TRASH_DIR || n.startsWith(".pending-") || n.startsWith(".trashed-") || n.endsWith(".part") || n.endsWith(".part.id") ||
            n.startsWith(".mydrive-tmp") || n == "#recycle" || n == "#snapshot" || n == "@eaDir" || n == ".DS_Store" || n == "Thumbs.db" || n == "desktop.ini"

        fun load() {
            try {
                val j = JSONObject(stateFile.readText())
                val f = j.getJSONObject("files")
                for (k in f.keys()) { val a = f.getJSONArray(k); base[k] = Rec(a.getLong(0), a.getLong(1), a.getLong(2), a.getLong(3), a.optInt(4) == 1) }
                j.optJSONObject("ups")?.let { u -> for (k in u.keys()) ups[k] = u.getString(k) }
            } catch (e: Exception) { }
        }
        fun save() = synchronized(base) {
            val f = JSONObject()
            for ((k, r) in base) f.put(k, JSONArray().put(r.ls).put(r.lm).put(r.rs).put(r.rm).put(if (r.d) 1 else 0))
            stateFile.parentFile?.mkdirs()
            val tmp = File(stateFile.path + ".tmp")
            tmp.writeText(JSONObject().put("files", f).put("ups", JSONObject(synchronized(ups) { HashMap(ups) } as Map<*, *>)).toString())
            tmp.renameTo(stateFile)
            lastSave = System.currentTimeMillis()
        }

        // ----- 양쪽 읽기 -----
        fun walkLocal() {
            val q = ArrayDeque<String>(); q += ""
            while (q.isNotEmpty()) {
                if (stop()) throw Canceled()
                val rel = q.removeFirst()
                val d = if (rel.isEmpty()) top else File(top, rel)
                try {
                    java.nio.file.Files.newDirectoryStream(d.toPath()).use { ds ->
                        for (x in ds) {
                            val n = x.fileName.toString(); if (skip(n)) continue
                            val a = try { java.nio.file.Files.readAttributes(x, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS) } catch (e: Exception) { continue }
                            val r = if (rel.isEmpty()) n else "$rel/$n"
                            if (a.isDirectory) { ld += r; q += r } else if (a.isRegularFile) lf[r] = L(a.size(), a.lastModifiedTime().toMillis())
                        }
                    }
                } catch (e: java.nio.file.AccessDeniedException) { throw IOException("휴대폰 폴더를 읽을 권한이 없습니다 (모든 파일 접근 허용 필요)") }
            }
        }

        fun listRemote() {
            // 맨 위가 없으면 '모두 지워짐'으로 보지 않고 멈춤
            val first = try { api.list(p.root, p.rpath) } catch (e: ApiException) {
                if (e.status == 404) throw IOException("NAS 폴더가 없습니다: ${p.nasText()}") else throw e }
            val pool = Executors.newFixedThreadPool(6)
            val pending = AtomicInteger(); val err = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
            val outF = ConcurrentHashMap<String, Entry>(); val outD = ConcurrentHashMap.newKeySet<String>()
            val pre = if (p.rpath.isEmpty()) "" else p.rpath + "/"
            fun add(es: List<Entry>) {
                for (e in es) {
                    if (skip(e.name) || !e.path.startsWith(pre)) continue
                    val rel = e.path.removePrefix(pre)
                    if (e.dir) { outD += rel; pending.incrementAndGet(); pool.execute {
                        try { if (!stop() && err.get() == null) add(api.list(p.root, e.path)) } catch (x: Exception) { err.compareAndSet(null, x) } finally { pending.decrementAndGet() }
                    } } else outF[rel] = e
                }
            }
            try {
                add(first)
                while (pending.get() > 0) { if (stop()) throw Canceled(); Thread.sleep(30) }
                err.get()?.let { throw it }
            } finally { pool.shutdownNow() }
            rf.putAll(outF); rd.addAll(outD)
        }

        // ----- 같은 내용인지 -----
        fun sha(f: File): String {
            val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(256 * 1024)
            FileInputStream(f).use { while (true) { if (stop()) throw Canceled(); val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        fun same(rel: String, l: L, r: Entry): Boolean {
            if (l.size != r.size) return false
            if (Math.abs(l.m - r.mtimeNs / 1_000_000) < 2000) return true
            pr.phase = "같은 파일인지 확인 중"; pr.file = rel
            return try { sha(File(top, rel)).equals(api.hash(p.root, rp(rel)), true) } catch (e: Canceled) { throw e } catch (e: Exception) { false }
        }

        // ----- 할 일 -----
        sealed class Act(val rel: String) {
            class Up(rel: String, val cond: String) : Act(rel)
            class Down(rel: String, val trashOld: Boolean = false) : Act(rel)
            class DelL(rel: String) : Act(rel)
            class DelR(rel: String, val cond: String) : Act(rel)
            class Conflict(rel: String) : Act(rel)
        }

        fun under(rel: String, dir: String) = rel.startsWith("$dir/")

        fun run(): Result {
            if (!top.isDirectory) throw IOException("휴대폰 폴더가 없습니다: ${p.local}")
            load()
            pr.phase = "휴대폰 폴더 읽는 중"; walkLocal()
            // 휴대폰 변경 알림으로만 깨어났는데 이 폴더는 그대로 → NAS 는 읽지 않고 끝 (다른 폴더나 지워진 파일 때문에 알림이 온 경우)
            if (localOnly && !restore && lf.all { (k, l) -> base[k]?.let { !it.d && it.ls == l.size && it.lm == l.m } == true } &&
                base.all { (k, b) -> if (b.d) k in ld else k in lf } && ld.all { base[it]?.d == true })
                return Result("", 0, 0, skipped = true).also { android.util.Log.i("MydriveSync", "${p.name}: 휴대폰 폴더 그대로 — NAS 는 읽지 않음") }
            pr.phase = "NAS 폴더 읽는 중"; listRemote()
            pr.phase = "비교 중"
            // 되살리기: 한쪽에만 남은 것의 기준을 잊음 → '새로 생긴 것'으로 보고 없는 쪽에 다시 만듦 (지우지 않음)
            if (restore) base.keys.removeAll { k -> val b = base[k]!!; if (b.d) (k in ld) != (k in rd) else (k in lf) != (k in rf) }
            val both = p.dir == BOTH; val up = p.dir == BOTH || p.dir == UP; val down = p.dir == BOTH || p.dir == DOWN
            fun lChanged(rel: String) = lf[rel]?.let { l -> base[rel]?.let { it.d || it.ls != l.size || it.lm != l.m } ?: true } ?: false
            fun rChanged(rel: String) = rf[rel]?.let { r -> base[rel]?.let { it.d || it.rs != r.size || it.rm != r.mtimeNs } ?: true } ?: false

            // 1) 폴더: 한쪽에서 통째로 지운 폴더 (양방향) · 새 폴더 만들기
            val gone = ArrayList<String>()        // 통째로 지울 폴더 (아래 파일은 따로 보지 않음)
            val delDirL = ArrayList<String>(); val delDirR = ArrayList<String>()
            val mkR = ArrayList<String>(); val mkL = ArrayList<String>()
            for (d in (ld + rd + base.filterValues { it.d }.keys).sorted()) {
                if (gone.any { under(d, it) }) continue
                val inL = d in ld; val inR = d in rd; val b = base[d]?.d == true
                when {
                    inL && inR -> {}
                    inL && !inR -> when {
                        b && both && lf.keys.none { under(it, d) && lChanged(it) } && ld.none { under(it, d) && base[it]?.d != true } -> { gone += d; delDirL += d }
                        b && !both -> {}
                        up -> mkR += d
                    }
                    !inL && inR -> when {
                        b && both && rf.keys.none { under(it, d) && rChanged(it) } && rd.none { under(it, d) && base[it]?.d != true } -> { gone += d; delDirR += d }
                        b && !both -> {}
                        down -> mkL += d
                    }
                    else -> base.remove(d)
                }
            }

            // 2) 파일
            val acts = ArrayList<Act>()
            for (rel in (lf.keys + rf.keys + base.filterValues { !it.d }.keys).sorted()) {
                if (gone.any { under(rel, it) }) continue
                val l = lf[rel]; val r = rf[rel]; val b = base[rel]?.takeIf { !it.d }
                if ((l != null && rel in rd) || (r != null && rel in ld)) { nFail++; errs += "파일과 폴더 이름이 겹침: $rel"; continue }
                val lc = lChanged(rel); val rc = rChanged(rel)
                when {
                    l != null && r != null -> when {
                        b == null -> if (same(rel, l, r)) adopt(rel, l, r) else when (p.dir) { BOTH -> acts += Act.Conflict(rel); UP -> acts += Act.Up(rel, ""); else -> acts += Act.Down(rel, true) }
                        !lc && !rc -> {}
                        lc && !rc -> if (up) acts += Act.Up(rel, "${b.rs}:${b.rm}") else adopt(rel, l, r)   // 받기만: 휴대폰에서 고친 것은 그대로 둠
                        !lc && rc -> if (down) acts += Act.Down(rel) else adopt(rel, l, r)                         // 올리기만: NAS 에서 고친 것은 그대로 둠
                        else -> if (same(rel, l, r)) adopt(rel, l, r) else when (p.dir) { BOTH -> acts += Act.Conflict(rel); UP -> acts += Act.Up(rel, ""); else -> acts += Act.Down(rel, true) }
                    }
                    l != null -> when {   // NAS 에 없음
                        b == null || lc -> if (up) acts += Act.Up(rel, "none")
                        both -> acts += Act.DelL(rel)        // NAS 에서 지움 → 휴대폰도
                        else -> base.remove(rel)             // 한 방향: 지운 것은 따라 하지 않음
                    }
                    r != null -> when {   // 휴대폰에 없음
                        b == null || rc -> if (down) acts += Act.Down(rel)
                        both -> acts += Act.DelR(rel, "${b.rs}:${b.rm}")
                        else -> base.remove(rel)
                    }
                    else -> base.remove(rel)
                }
            }

            // 3) 한 번에 많이 지우게 되면 멈춤 (휴대폰 폴더를 잘못 비웠거나 저장소가 빠졌을 때 NAS 를 비우지 않게)
            val dels = acts.count { it is Act.DelL || it is Act.DelR } +
                (delDirL + delDirR).sumOf { d -> base.keys.count { !base[it]!!.d && under(it, d) } }
            val baseFiles = base.values.count { !it.d }
            if (!allowDel && dels > 20 && dels * 3 > baseFiles) {
                held = dels
                acts.removeAll { it is Act.DelL || it is Act.DelR }
                delDirL.clear(); delDirR.clear()
            }

            // 4) 하기: 폴더 지우기·만들기는 차례로, 파일은 설정한 수만큼 함께
            pr.total = acts.size + delDirL.size + delDirR.size + mkL.size + mkR.size
            for (d in delDirL) step(d) { fs.toTrash(File(top, d)); dropUnder(d); nDelL++ }
            for (d in delDirR) step(d) { api.delete(p.root, rp(d)); Sync.mark(p.root + "\u0000" + rp(d)); dropUnder(d); nDelR++ }
            for (d in mkL) step(d) { Sync.mark("L\u0000" + File(top, d).path); File(top, d).mkdirs(); nMk++; synchronized(base) { base[d] = Rec(0, 0, 0, 0, true) } }
            for (d in mkR) step(d) {
                try { api.mkdir(p.root, rp(d)) } catch (e: ApiException) { if (e.status != 409) throw e }
                Sync.mark(p.root + "\u0000" + rp(d)); nMk++; synchronized(base) { base[d] = Rec(0, 0, 0, 0, true) }
            }
            pr.phase = "옮기는 중"
            val pool = Executors.newFixedThreadPool(par)
            try {
                val fs2 = acts.map { a -> pool.submit { if (!stop()) step(a.rel) { act(a) } } }
                for (f in fs2) { if (stop()) break; try { f.get() } catch (e: Exception) { } }
            } finally { pool.shutdownNow() }
            if (stop()) { save(); throw Canceled() }
            // 양쪽에 다 있는 폴더는 기준에
            synchronized(base) { for (d in ld) if (d in rd || (File(top, d).isDirectory && d in mkR)) base[d] = Rec(0, 0, 0, 0, true) }
            save()
            val parts = listOfNotNull(
                nUp.takeIf { it > 0 }?.let { "올림 $it" }, nDown.takeIf { it > 0 }?.let { "받음 $it" },
                (nDelL + nDelR).takeIf { it > 0 }?.let { "지움 $it" }, nConf.takeIf { it > 0 }?.let { "충돌 $it (둘 다 남김)" },
                nMk.takeIf { it > 0 }?.let { "새 폴더 $it" }, nFail.takeIf { it > 0 }?.let { "실패 $it" }, held.takeIf { it > 0 }?.let { "지우기 멈춤 $it" })
            android.util.Log.i("MydriveSync", "${p.name}: 끝 — 휴대폰 ${lf.size}개 · NAS ${rf.size}개 · " + parts.joinToString())
            val text = (if (parts.isEmpty()) "바뀐 것 없음" else parts.joinToString(" · ")) + (errs.firstOrNull()?.let { " — $it" } ?: "")
            return Result(text, nFail, held)
        }

        fun adopt(rel: String, l: L, r: Entry) = synchronized(base) { base[rel] = Rec(l.size, l.m, r.size, r.mtimeNs) }
        fun dropUnder(d: String) = synchronized(base) { base.keys.removeAll { it == d || under(it, d) } }

        /** 하나 하기: 실패는 세고 넘어감 (연결이 아예 끊긴 것은 위로) */
        fun step(rel: String, f: () -> Unit) {
            if (stop()) return
            pr.file = rel.substringAfterLast('/'); pr.bytes = 0; pr.size = 0
            try { f() } catch (e: Canceled) { return } catch (e: ApiException) {
                if (e.status == 409 && e.kind == "conflict") { synchronized(errs) { errs += "그사이 NAS에서 바뀜 (다음에 다시): $rel" } }
                else synchronized(errs) { nFail++; errs += "${e.message}: $rel" }
            } catch (e: Exception) { synchronized(errs) { nFail++; errs += "${e.message ?: e.javaClass.simpleName}: $rel" } }
            finally { pr.active.remove(rel) }
            synchronized(errs) { pr.done++ }
            if (System.currentTimeMillis() - lastSave > 15_000) save()
        }

        /** 이 파일의 진행 (함께 옮기는 파일마다 따로) */
        fun progOf(rel: String, up: Boolean): (Long, Long) -> Unit {
            val a = longArrayOf(0, 0, if (up) 1 else 0); pr.active[rel] = a
            return { d, t -> a[0] = d; a[1] = t; pr.bytes = d; pr.size = t }
        }

        fun upload(rel: String, cond: String) {
            val f = File(top, rel)
            val len = f.length(); val m = f.lastModified()
            val id = synchronized(ups) { ups.getOrPut(rel) { Api.newId() } }
            Sync.mark(p.root + "\u0000" + rp(rel))
            val e = api.upload(p.root, rp(rel), len, m * 1_000_000, { FileInputStream(f) }, progOf(rel, true), stop, cond, id)
            Sync.mark(p.root + "\u0000" + rp(rel))
            synchronized(ups) { ups.remove(rel) }
            synchronized(base) { base[rel] = Rec(len, lmod(f), e.size, e.mtimeNs) }
            synchronized(errs) { nUp++ }
        }

        fun download(rel: String, trashOld: Boolean) {
            val r = rf[rel] ?: return
            val f = File(top, rel)
            f.parentFile?.mkdirs()
            Sync.mark("L\u0000" + f.path)
            if (trashOld && f.exists()) fs.toTrash(f)
            api.download(p.root, Entry(rp(rel), false, r.size, r.mtimeNs), f, progOf(rel, false), stop)
            Sync.mark("L\u0000" + f.path)
            fs.scan(f)
            synchronized(base) { base[rel] = Rec(f.length(), lmod(f), r.size, r.mtimeNs) }
            synchronized(errs) { nDown++ }
        }

        fun act(a: Act) {
            android.util.Log.i("MydriveSync", "${p.name}: ${a.javaClass.simpleName} ${a.rel}")
            when (a) {
                is Act.Up -> upload(a.rel, a.cond)
                is Act.Down -> download(a.rel, a.trashOld)
                is Act.DelL -> { val f = File(top, a.rel); Sync.mark("L\u0000" + f.path); fs.toTrash(f); synchronized(base) { base.remove(a.rel) }; synchronized(errs) { nDelL++ } }
                is Act.DelR -> {
                    Sync.mark(p.root + "\u0000" + rp(a.rel))
                    api.delete(p.root, rp(a.rel), a.cond); synchronized(base) { base.remove(a.rel) }; synchronized(errs) { nDelR++ }
                }
                is Act.Conflict -> {
                    // 휴대폰 것을 '이름 (휴대폰 날짜 시각)' 으로 바꾸고, NAS 것은 원래 이름으로 받고, 바꾼 것을 올림 → 양쪽에 둘 다
                    val f = File(top, a.rel)
                    val dir = a.rel.substringBeforeLast('/', ""); val n = a.rel.substringAfterLast('/')
                    val dot = n.lastIndexOf('.').let { if (it > 0) it else n.length }
                    val stamp = SimpleDateFormat("yyyyMMdd HHmm", Locale.KOREA).format(Date())
                    var k = 1; var nn: String
                    do { nn = n.substring(0, dot) + " (휴대폰 $stamp" + (if (k > 1) " $k" else "") + ")" + n.substring(dot); k++ }
                    while (File(f.parentFile, nn).exists() || rf.containsKey(if (dir.isEmpty()) nn else "$dir/$nn"))
                    val nrel = if (dir.isEmpty()) nn else "$dir/$nn"
                    Sync.mark("L\u0000" + f.path); Sync.mark("L\u0000" + File(top, nrel).path)
                    if (!f.renameTo(File(top, nrel))) throw IOException("충돌 파일 이름을 바꾸지 못함")
                    fs.scan(f); fs.scan(File(top, nrel))
                    download(a.rel, false)
                    upload(nrel, "none")
                    synchronized(errs) { nConf++; nDown--; nUp-- }
                }
            }
        }
    }

    /** 처음 화면·목록에 쓸 한 줄 요약 */
    fun summary(ctx: Context): String {
        val ps = pairs(ctx); if (ps.isEmpty()) return "폴더를 NAS와 맞춤"
        val run = cur?.let { c -> ps.firstOrNull { it.id == c.id } }
        return run?.let { "동기화 중 · ${it.name}" } ?: "${ps.size}개 폴더" + (if (ps.any { status(ctx, it.id).err }) " · 문제 있음" else "")
    }
}
