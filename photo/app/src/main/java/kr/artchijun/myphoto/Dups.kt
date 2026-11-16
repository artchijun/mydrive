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
        "folder" to "선호 폴더에 있는 것",
        "res" to "해상도가 높은 것",
        "size" to "파일이 큰 것",
        "name" to "이름이 깔끔한 것 ('(1)'·'복사본'·'copy' 가 없고 경로가 짧은 것)",
        "old" to "먼저 들어온 것 (시놀로지에 올라온 때)",
        "new" to "나중에 들어온 것",
    )
    fun rules(ctx: Context): List<Rule> {
        val s = Prefs(ctx).str("dup_rules", "folder:1,res:1,size:1,name:1,old:1,new:0")
        val out = s.split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 && it[0] in ruleNames }?.let { Rule(it[0], it[1] == "1") } }.toMutableList()
        ruleNames.keys.filter { k -> out.none { it.key == k } }.forEach { out += Rule(it, false) }
        return out
    }
    fun saveRules(ctx: Context, r: List<Rule>) = Prefs(ctx).putStr("dup_rules", r.joinToString(",") { it.key + ":" + (if (it.on) "1" else "0") })
    /** 선호 폴더 (앞일수록 먼저) — 경로 앞부분 */
    fun preferred(ctx: Context): List<String> = Prefs(ctx).str("dup_pref", "").split('\n').filter { it.isNotEmpty() }
    fun savePreferred(ctx: Context, l: List<String>) = Prefs(ctx).putStr("dup_pref", l.joinToString("\n"))

    private val copyMark = Regex("""(\(\d+\)|복사본|사본|copy|\s-\s\d+|_\d)(\.[^.]+)?$""", RegexOption.IGNORE_CASE)

    /** a 가 b 보다 남길 만하면 음수 */
    fun comparator(ctx: Context): Comparator<Entry> {
        val rs = rules(ctx).filter { it.on }
        val pref = preferred(ctx)
        fun prefRank(e: Entry): Int { val i = pref.indexOfFirst { e.path == it || e.path.startsWith("$it/") }; return if (i < 0) pref.size else i }
        fun messy(e: Entry) = (if (copyMark.containsMatchIn(e.it.name)) 1000 else 0) + e.full.length
        return Comparator { a, b ->
            for (r in rs) {
                val c = when (r.key) {
                    "folder" -> prefRank(a).compareTo(prefRank(b))
                    "res" -> (b.it.w.toLong() * b.it.h).compareTo(a.it.w.toLong() * a.it.h)
                    "size" -> b.it.size.compareTo(a.it.size)
                    "name" -> messy(a).compareTo(messy(b))
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
    fun decide(ctx: Context, groups: List<Group>) {
        val cmp = comparator(ctx)
        for (g in groups) { g.keep.clear(); g.keep += g.entries.minWith(cmp).it.id }
    }

    // ---------- 찾기 ----------
    class Progress(val stage: String, val done: Int, val total: Int)

    fun scan(ctx: Context, small: Boolean, stop: () -> Boolean, progress: (Progress) -> Unit): List<Group> {
        val ph = Photos(ctx)
        val ns = Photos.TEAM
        // 1) 모든 사진 목록 (가벼운 정보만)
        val all = ArrayList<Item>()
        val total = try { ph.syno.call("$ns.Browse.Item", "count", 7).optInt("count") } catch (e: Exception) { 0 }
        var off = 0
        while (true) {
            if (stop()) return emptyList()
            val page = ph.list(ns, off, 5000)
            all += page; off += page.size
            progress(Progress("사진 목록 읽는 중", all.size, maxOf(total, all.size)))
            if (page.size < 5000) break
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
        // 4) 폴더 경로
        val paths = HashMap<Long, String>()
        val folderIds = groups.flatMap { g -> g.entries.map { it.it.folderId } }.distinct()
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
