package kr.artchijun.myphoto

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 서버(공유 공간)의 중복 사진 찾기 — 지우는 것은 화면(DupActivity)에서 사람이 확인한 뒤.
 *   1) 정확한 중복: 크기와 찍은 시각이 같고, 파일 앞부분(큰 파일은 가운데·뒷부분도) 64KB 내용까지 같은 것 (내용 비교는 후보만 조금 받아서)
 *   2) 작게 줄인 사본: 찍은 시각이 같고 가로세로 비율이 같은데 해상도가 다른 것 (카톡 등으로 줄어든 사본)
 *   남길 것 고르기: 사람이 순서를 정한 규칙을 차례로 적용 (선호 폴더 · 해상도 · 파일 크기 · 이름 · 날짜)
 */
object Dups {
    class Entry(val it: Item, var path: String = "") {   // path: 폴더 경로 ("/JUN PHONE/DCIM/Camera")
        val full get() = "$path/${it.name}"
    }
    class Group(val kind: String, val entries: List<Entry>) {   // kind: "same" 정확한 중복 · "small" 줄인 사본
        val keep = HashSet<Long>()   // 남길 사진 id
        val drop get() = entries.filter { it.it.id !in keep }
    }

    // ---------- 규칙 ----------
    data class Rule(val key: String, val on: Boolean)
    val ruleNames = mapOf(
        "folder" to "위치 순위가 높은 것 (위치 순위 정하기)",
        "res" to "해상도가 높은 것",
        "size" to "파일이 큰 것",
        "name" to "이름이 깔끔한 것 ('(1)'·'_1'·'복사본'·'copy' 가 없는 것)",
        "path" to "경로가 짧은 것 (폴더 경로 글자 수)",
        "short" to "이름이 짧은 것 (확장자를 뺀 글자 수)",
        "old" to "먼저 들어온 것 (시놀로지에 올라온 때)",
        "new" to "나중에 들어온 것",
    )
    fun rules(ctx: Context): List<Rule> {
        val s = Prefs(ctx).str("dup_rules", "folder:1,res:1,size:1,name:1,path:1,short:1,old:1,new:0")
        val out = s.split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 && it[0] in ruleNames }?.let { Rule(it[0], it[1] == "1") } }.toMutableList()
        // 예전에 저장한 규칙에 없던 '이름이 짧은 것'은 '이름이 깔끔한 것' 바로 뒤에 켜서 넣음 (나머지 새 규칙은 끈 채로 끝에)
        if (out.none { it.key == "short" }) out.add(out.indexOfFirst { it.key == "name" }.let { if (it >= 0) it + 1 else out.size }, Rule("short", true))
        // '경로가 짧은 것'은 예전에 '이름이 깔끔한 것' 안에 들어 있던 것 → 그 바로 뒤에, 그 규칙과 같은 켜짐으로
        if (out.none { it.key == "path" }) out.indexOfFirst { it.key == "name" }.let { i -> out.add(if (i >= 0) i + 1 else out.size, Rule("path", if (i >= 0) out[i].on else true)) }
        ruleNames.keys.filter { k -> out.none { it.key == k } }.forEach { out += Rule(it, false) }
        return out
    }
    fun saveRules(ctx: Context, r: List<Rule>) = Prefs(ctx).putStr("dup_rules", r.joinToString(",") { it.key + ":" + (if (it.on) "1" else "0") })
    /** 위치 순위 (앞일수록 남김) — 폴더 경로. "*" 는 '순위를 정하지 않은 위치'의 자리 (없으면 맨 끝) */
    const val REST = "*"
    fun preferred(ctx: Context): List<String> = Prefs(ctx).str("dup_pref", "").split('\n').filter { it.isNotEmpty() }
    fun savePreferred(ctx: Context, l: List<String>) = Prefs(ctx).putStr("dup_pref", l.joinToString("\n"))

    // 사본 표시: '(1)' · '복사본' · 'copy' · ' - 2' · '_1'~'_99' (끝에 붙은 것만 — 찍은 시각 '_110617' 같은 긴 숫자는 아님)
    private val copyMark = Regex("""(\(\d+\)|복사본|사본|copy|\s-\s\d+|_\d{1,2})(\.[^.]+)?$""", RegexOption.IGNORE_CASE)

    /** 이 위치의 순위: 순위 목록에서 가장 자세히 맞는 경로(가장 긴 앞부분)의 자리. 없으면 '나머지' 자리 */
    fun rankOf(pref: List<String>, path: String): Int {
        var best = -1; var len = -1
        pref.forEachIndexed { i, p -> if (p != REST && (path == p || path.startsWith("$p/")) && p.length > len) { best = i; len = p.length } }
        if (best >= 0) return best
        val rest = pref.indexOf(REST)
        return if (rest >= 0) rest else pref.size
    }

    /** a 가 b 보다 남길 만하면 음수 (prefOverride: 순위를 바꾸는 중에 미리 보기) */
    fun comparator(ctx: Context, prefOverride: List<String>? = null): Comparator<Entry> {
        val rs = rules(ctx).filter { it.on }
        val pref = prefOverride ?: preferred(ctx)
        fun prefRank(e: Entry): Int = rankOf(pref, e.path)
        fun messy(e: Entry) = if (copyMark.containsMatchIn(e.it.name)) 1 else 0
        fun stem(e: Entry) = e.it.name.substringBeforeLast('.').length
        return Comparator { a, b ->
            for (r in rs) {
                val c = when (r.key) {
                    "folder" -> prefRank(a).compareTo(prefRank(b))
                    "res" -> (b.it.w.toLong() * b.it.h).compareTo(a.it.w.toLong() * a.it.h)
                    "size" -> b.it.size.compareTo(a.it.size)
                    "name" -> messy(a).compareTo(messy(b))
                    "path" -> a.path.length.compareTo(b.path.length)
                    "short" -> stem(a).compareTo(stem(b))
                    "old" -> a.it.indexed.compareTo(b.it.indexed)
                    "new" -> b.it.indexed.compareTo(a.it.indexed)
                    else -> 0
                }
                if (c != 0) return@Comparator c
            }
            a.it.id.compareTo(b.it.id)
        }
    }

    /** 규칙대로 묶음마다 하나만 남김 */
    fun decide(ctx: Context, groups: List<Group>, prefOverride: List<String>? = null) {
        val cmp = comparator(ctx, prefOverride)
        for (g in groups) { g.keep.clear(); g.keep += g.entries.minWith(cmp).it.id }
    }

    // ---------- 찾기 ----------
    class Progress(val stage: String, val done: Int, val total: Int)

    /** 찾을 범위: folders 가 비면 서버 전체. outside = 고른 폴더의 사진과 같은 것이 서버 다른 곳에 있어도 찾음 */
    class Scope(val folders: List<String> = emptyList(), val outside: Boolean = false)
    fun scope(ctx: Context): Scope {
        val pf = Prefs(ctx)
        if (!pf.bool("dup_scope_folders", false)) return Scope()
        return Scope(pf.str("dup_folders", "").split('\n').filter { it.isNotEmpty() }, pf.bool("dup_outside", false))
    }

    /** 고른 폴더들과 그 하위 폴더 모두: 폴더 번호 → 경로 (6개씩 함께 읽음) */
    private fun subtree(ph: Photos, ns: String, roots: List<String>, stop: () -> Boolean): Map<Long, String> {
        val out = java.util.concurrent.ConcurrentHashMap<Long, String>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val pending = java.util.concurrent.atomic.AtomicInteger()
        val err = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        fun visit(id: Long, path: String) {
            out[id] = path; pending.incrementAndGet()
            pool.execute {
                try { if (!stop() && err.get() == null) for (f in ph.folders(ns, id)) visit(f.id, f.path) }
                catch (e: Exception) { err.compareAndSet(null, e) } finally { pending.decrementAndGet() }
            }
        }
        for (r in roots) { val id = ph.folderByPath(ns, r, false) ?: continue; visit(id, r) }
        while (pending.get() > 0 && !stop()) Thread.sleep(50)
        pool.shutdownNow(); err.get()?.let { throw it }
        return out
    }

    fun scan(ctx: Context, small: Boolean, stop: () -> Boolean, progress: (Progress) -> Unit): List<Group> {
        val ph = Photos(ctx)
        val ns = Photos.TEAM
        val sc = scope(ctx)
        val all = ArrayList<Item>()
        // 고른 폴더만이면: 그 폴더들(하위 포함)의 번호·경로
        val inFolders: Map<Long, String>? = if (sc.folders.isEmpty()) null else {
            progress(Progress("고른 폴더의 하위 폴더 확인 중", 0, 0))
            subtree(ph, ns, sc.folders, stop).also { if (it.isEmpty()) throw java.io.IOException("고른 폴더를 서버에서 찾지 못했습니다") }
        }
        if (inFolders != null && !sc.outside) {
            // 1) 고른 폴더 안의 사진만 (폴더마다 4개씩 함께)
            val ids = inFolders.keys.toList()
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val done = java.util.concurrent.atomic.AtomicInteger()
            try {
                val fs = ids.map { fid -> pool.submit<List<Item>> {
                    val l = ArrayList<Item>(); var off = 0
                    while (!stop()) { val page = ph.list(ns, off, 5000, mapOf("folder_id" to fid)); l += page; off += page.size; if (page.size < 5000) break }
                    progress(Progress("고른 폴더의 사진 목록 읽는 중 (폴더)", done.incrementAndGet(), ids.size)); l
                } }
                for (f in fs) { if (stop()) return emptyList(); all += f.get() }
            } finally { pool.shutdownNow() }
        } else {
            // 1) 모든 사진 목록 (가벼운 정보만)
            val total = try { ph.syno.call("$ns.Browse.Item", "count", 7).optInt("count") } catch (e: Exception) { 0 }
            var off = 0
            while (true) {
                if (stop()) return emptyList()
                val page = ph.list(ns, off, 5000)
                all += page; off += page.size
                progress(Progress("사진 목록 읽는 중", all.size, maxOf(total, all.size)))
                if (page.size < 5000) break
            }
        }
        val uniq = all.distinctBy { it.id }; all.clear(); all.addAll(uniq)
        val groups = ArrayList<Group>()
        // 2) 정확한 중복 후보: 크기 + 찍은 시각
        val cand = all.filter { it.size > 0 }.groupBy { it.size to it.time }.values.filter { it.size > 1 }
        // 후보마다 앞부분(큰 파일은 뒷부분도) 내용을 조금 받아 비교 — 8개씩 함께
        // 한 번 비교한 사진은 기억해 두고 다음 찾기에서는 받지 않음 (id·크기·시각이 같을 때만 믿음)
        val cache = SampleCache.load(ctx)
        val fresh = java.util.concurrent.ConcurrentHashMap<Long, Triple<Long, Long, String>>()
        fun hashOf(i: Item): String {
            cache[i.id]?.let { (sz, tm, h) -> if (sz == i.size && tm == i.time) return h }
            val h = sample(ph.syno, ns, i); fresh[i.id] = Triple(i.size, i.time, h); return h
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(12)
        val done = java.util.concurrent.atomic.AtomicInteger()
        try {
            val futures = cand.map { c -> pool.submit<List<List<Item>>> {
                if (stop()) return@submit emptyList()
                val by = c.groupBy { try { hashOf(it) } catch (e: Exception) { "?" + it.id } }
                progress(Progress("내용 비교 중", done.incrementAndGet(), cand.size))
                by.filter { (k, v) -> v.size > 1 && !k.startsWith("?") }.values.toList()
            } }
            for (f in futures) { if (stop()) return emptyList(); for (v in f.get()) groups += Group("same", v.map { Entry(it) }) }
        } finally { pool.shutdownNow(); SampleCache.save(ctx, fresh) }   // 멈춰도 받은 만큼은 저장
        // 3) 줄인 사본: 찍은 시각이 같고 비율이 같고 해상도가 다른 것
        if (small) {
            val inSame = groups.flatMap { g -> g.entries.map { it.it.id } }.toHashSet()
            for (c in all.filter { it.time > 0 && it.w > 0 && it.h > 0 && !it.video }.groupBy { it.time }.values.filter { it.size > 1 }) {
                val big = c.maxBy { it.w.toLong() * it.h }
                val ratio = { i: Item -> maxOf(i.w, i.h).toDouble() / minOf(i.w, i.h) }
                val copies = c.filter { it.id != big.id && it.id !in inSame && Math.abs(ratio(it) - ratio(big)) < 0.02 && maxOf(it.w, it.h) < maxOf(big.w, big.h) * 0.85 }
                if (copies.isNotEmpty()) groups += Group("small", (listOf(big) + copies).map { Entry(it) })
            }
        }
        // 고른 폴더 + 다른 곳: 고른 폴더의 사진이 하나라도 든 묶음만
        if (inFolders != null && sc.outside) groups.retainAll { g -> g.entries.any { it.it.folderId in inFolders } }
        // 4) 폴더 경로 (고른 폴더 안의 것은 이미 앎)
        val paths = HashMap<Long, String>(inFolders ?: emptyMap())
        val folderIds = groups.flatMap { g -> g.entries.map { it.it.folderId } }.distinct().filter { it !in paths }
        folderIds.forEachIndexed { i, f ->
            if (stop()) return emptyList()
            progress(Progress("폴더 확인 중", i + 1, folderIds.size))
            paths[f] = try { ph.folder(ns, f).path } catch (e: Exception) { "?" }
        }
        groups.forEach { g -> g.entries.forEach { it.path = paths[it.it.folderId] ?: "?" } }
        decide(ctx, groups)
        return groups.sortedByDescending { g -> g.drop.sumOf { it.it.size } }
    }

    /** 파일 앞 64KB (8MB 넘으면 가운데·뒤 64KB 도) 의 해시 (원본 받기를 Range 로 조금만) */
    private fun sample(syno: Syno, ns: String, it: Item): String {
        val md = MessageDigest.getInstance("SHA-256")
        val k = 64L * 1024
        val ranges = when {
            it.size <= k -> listOf(0L to it.size - 1)
            it.size < 8L * 1024 * 1024 -> listOf(0L to k - 1)   // 사진: 앞 64KB (EXIF·그림 앞부분) — 크기·찍은 시각까지 같으므로 충분
            else -> { val m = it.size / 2 - k / 2; listOf(0L to k - 1, m to m + k - 1, it.size - k to it.size - 1) }   // 큰 파일(동영상)은 가운데·뒷부분도
        }
        for ((a, b) in ranges) {
            val c = URL(syno.originalUrl(ns, it)).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("Range", "bytes=$a-$b")
            try {
                if (c.responseCode !in setOf(200, 206)) throw java.io.IOException("HTTP ${c.responseCode}")
                c.inputStream.use { inp -> val buf = ByteArray(16 * 1024); var left = b - a + 1
                    while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; md.update(buf, 0, n); left -= n } }
            } finally { c.disconnect() }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 내용 비교 결과 기억 (dups.db) — 다시 찾을 때 이미 비교한 사진은 서버에서 받지 않음 */
object SampleCache {
    private class Db(ctx: Context) : android.database.sqlite.SQLiteOpenHelper(ctx, "dups.db", null, 2) {
        override fun onCreate(d: android.database.sqlite.SQLiteDatabase) { d.execSQL("CREATE TABLE s(id INTEGER PRIMARY KEY, size INTEGER, time INTEGER, hash TEXT)") }
        override fun onUpgrade(d: android.database.sqlite.SQLiteDatabase, a: Int, b: Int) { d.execSQL("DELETE FROM s") }   // 비교 방식이 바뀌면 다시 받음 (2: 큰 파일은 가운데도)
    }
    @Volatile private var db: Db? = null
    private fun db(ctx: Context) = (db ?: synchronized(this) { db ?: Db(ctx.applicationContext).also { db = it } }).writableDatabase

    fun load(ctx: Context): Map<Long, Triple<Long, Long, String>> {
        val m = HashMap<Long, Triple<Long, Long, String>>()
        db(ctx).rawQuery("SELECT id,size,time,hash FROM s", null).use { c -> while (c.moveToNext()) m[c.getLong(0)] = Triple(c.getLong(1), c.getLong(2), c.getString(3)) }
        return m
    }
    fun save(ctx: Context, m: Map<Long, Triple<Long, Long, String>>) {
        if (m.isEmpty()) return
        val d = db(ctx); d.beginTransaction()
        try {
            for ((id, v) in m) d.execSQL("INSERT OR REPLACE INTO s(id,size,time,hash) VALUES(?,?,?,?)", arrayOf(id, v.first, v.second, v.third))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }
    fun clear(ctx: Context) = db(ctx).execSQL("DELETE FROM s")
}
