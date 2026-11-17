package kr.artchijun.mydrive

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 저장소 분석: 어디에 용량이 들었는지 (종류별·폴더별·큰 파일·오래된 큰 파일·중복 파일).
 *   휴대폰 저장소와 NAS 폴더 (NAS 는 목록을 폴더마다 물어 훑고, 중복 비교는 NAS 가 직접 해시를 계산). 폴더를 직접 훑음.
 *   결과는 앱이 켜져 있는 동안 기억 (지우면 그만큼 빼 줌). [다시 분석] 으로 새로.
 */
object Usage {
    class F(val path: String, val size: Long, val mtimeMs: Long) { val name get() = path.substringAfterLast('/') }
    class D(var size: Long = 0, var files: Int = 0)
    class Result(val base: Loc, val files: MutableList<F>, val dirs: HashMap<String, D>, val at: Long, val blocked: Int) {
        @Volatile var dups: List<List<F>>? = null
        val total get() = dirs[base.path]?.size ?: 0L
        val fileCount get() = dirs[base.path]?.files ?: 0
    }

    /** 종류 묶음: 열쇠, 이름, 아이콘에 쓸 견본 이름 */
    val cats = listOf(
        Triple("image", "사진", "a.jpg"), Triple("video", "동영상", "a.mp4"), Triple("audio", "음악·소리", "a.mp3"),
        Triple("doc", "문서", "a.pdf"), Triple("zip", "압축 파일", "a.zip"), Triple("apk", "앱 설치 파일", "a.apk"),
        Triple("cad", "CAD·3D", "a.dwg"), Triple("other", "기타", "a"))
    fun cat(name: String) = when (val k = FileIcon.kindOf(name)) {
        "pdf", "sheet", "slide", "doc" -> "doc"
        "image", "video", "audio", "zip", "apk", "cad" -> k
        else -> "other"
    }
    fun catName(k: String) = cats.firstOrNull { it.first == k }?.second ?: k
    fun catColor(k: String) = FileIcon.colorOf(if (k == "doc") "doc" else k)

    private val cache = HashMap<String, Result>()
    @Synchronized fun get(base: Loc): Result? = cache[base.key()]
    @Synchronized fun forget(base: Loc) { cache.remove(base.key()) }
    @Synchronized private fun put(r: Result) { cache[r.base.key()] = r }

    /** 파일 하나를 폴더들(그 파일 위로 분석 시작 폴더까지)에 더함 */
    private fun add(dirs: HashMap<String, D>, basePath: String, path: String, size: Long, sign: Int = 1) {
        var d = path.substringBeforeLast('/', "")
        while (true) {
            val s = dirs.getOrPut(d) { D() }; s.size += sign * size; s.files += sign
            if (d == basePath || d.isEmpty()) break
            d = d.substringBeforeLast('/', "")
        }
    }

    fun scan(base: Loc, progress: (Int) -> Unit, canceled: () -> Boolean, api: Api? = null): Result {
        val files = ArrayList<F>(4096); val dirs = HashMap<String, D>(); var blocked = 0
        dirs[base.path] = D()
        when (base.kind) {
            "local" -> {
                val root = File(base.root); val cut = root.path.length + 1
                val stack = ArrayDeque<File>().apply { add(File(root, base.path)) }
                while (stack.isNotEmpty()) {
                    if (canceled()) throw IOException("취소함")
                    val d = stack.removeLast()
                    val list = d.listFiles()
                    if (list == null) { blocked++; continue }
                    for (f in list) {
                        if (f.name == Fs.TRASH_DIR && d == root) continue
                        val rel = f.path.substring(cut)
                        if (f.isDirectory) {
                            if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue
                            dirs.getOrPut(rel) { D() }; stack.add(f)
                        } else {
                            val e = F(rel, f.length(), f.lastModified()); files += e; add(dirs, base.path, rel, e.size)
                            if (files.size % 500 == 0) progress(files.size)
                        }
                    }
                    // Android 11 부터 Android/data·obb 는 다른 앱 것이라 목록이 비어 보임
                    if (list.isEmpty() && (rel(d, root) == "Android/data" || rel(d, root) == "Android/obb")) blocked++
                }
            }
            "nas" -> {
                val nas = api ?: throw IOException("NAS 에 연결되어 있지 않습니다")
                val stack = ArrayDeque<String>().apply { add(base.path) }
                while (stack.isNotEmpty()) {
                    if (canceled()) throw IOException("취소함")
                    val d = stack.removeLast()
                    val list = try { nas.list(base.root, d) } catch (e: Exception) { if (d == base.path) throw e; blocked++; continue }
                    for (e in list) {
                        val name = e.path.substringAfterLast('/')
                        if (name.startsWith("#") || name.startsWith("@") || name == ".mydrive-versions") continue   // 휴지통·시놀로지 내부 폴더
                        if (e.dir) { dirs.getOrPut(e.path) { D() }; stack.add(e.path) }
                        else {
                            val f = F(e.path, e.size, e.mtimeNs / 1_000_000); files += f; add(dirs, base.path, e.path, f.size)
                            if (files.size % 200 == 0) progress(files.size)
                        }
                    }
                }
            }
            else -> throw IOException("여기는 분석할 수 없습니다")
        }
        val r = Result(base, files, dirs, System.currentTimeMillis(), blocked)
        put(r); return r
    }
    private fun rel(d: File, root: File) = if (d.path.length > root.path.length) d.path.substring(root.path.length + 1) else ""

    /** 지운 파일을 결과에서 뺌 (폴더째 지웠으면 그 안 모두). store = 같은 저장소(kind·root)의 분석 결과 모두 */
    @Synchronized fun removed(store: Loc, paths: Collection<String>) {
        val set = paths.toHashSet()
        fun gone(p: String): Boolean { if (p in set) return true; var d = p.substringBeforeLast('/', ""); while (d.isNotEmpty()) { if (d in set) return true; d = d.substringBeforeLast('/', "") }; return false }
        for (r in cache.values) {
            if (r.base.kind != store.kind || r.base.root != store.root) continue
            val out = r.files.filter { gone(it.path) }
            for (f in out) add(r.dirs, r.base.path, f.path, f.size, -1)
            r.files.removeAll(out.toHashSet())
            r.dirs.keys.filter { gone(it) && it != r.base.path }.forEach { r.dirs.remove(it) }
            r.dups = r.dups?.map { g -> g.filter { !gone(it.path) } }?.filter { it.size > 1 }
        }
    }

    // ---------- 보기 ----------
    fun children(r: Result, dir: String): Pair<List<Pair<String, D>>, List<F>> {
        val pre = if (dir.isEmpty()) "" else "$dir/"
        val ds = r.dirs.entries.filter { (k, _) -> k != dir && k.startsWith(pre) && k.indexOf('/', pre.length) < 0 && (dir.isNotEmpty() || k.isNotEmpty()) }
            .map { it.key to it.value }.sortedByDescending { it.second.size }
        val fl = r.files.filter { it.path.startsWith(pre) && it.path.indexOf('/', pre.length) < 0 }.sortedByDescending { it.size }
        return ds to fl
    }
    fun byCat(r: Result): List<Triple<String, Long, Int>> = r.files.groupBy { cat(it.name) }.map { (k, l) -> Triple(k, l.sumOf { it.size }, l.size) }.sortedByDescending { it.second }
    fun ofCat(r: Result, k: String, limit: Int = 500) = r.files.filter { cat(it.name) == k }.sortedByDescending { it.size }.take(limit)
    fun big(r: Result, limit: Int = 200) = r.files.sortedByDescending { it.size }.take(limit)
    const val OLD_DAYS = 365
    const val OLD_MIN = 10L * 1024 * 1024
    fun old(r: Result, limit: Int = 500): List<F> {
        val cut = System.currentTimeMillis() - OLD_DAYS * 86_400_000L
        return r.files.filter { it.size >= OLD_MIN && it.mtimeMs in 1 until cut }.sortedByDescending { it.size }.take(limit)
    }

    // ---------- 중복 파일 (크기가 같은 것끼리만 내용 비교) ----------
    const val DUP_MIN_LOCAL = 100L * 1024

    fun findDups(r: Result, progress: (String) -> Unit, canceled: () -> Boolean, api: Api? = null): List<List<F>> {
        val same = synchronized(this) { r.files.toList() }.filter { it.size >= DUP_MIN_LOCAL }.groupBy { it.size }.values.filter { it.size > 1 }.sortedByDescending { it[0].size * (it.size - 1) }
        val out = ArrayList<List<F>>(); val root = File(r.base.root)
        val total = same.sumOf { it.size }; var done = 0
        if (r.base.kind == "nas") {
            // NAS: 크기가 같은 것만 NAS 에게 해시를 물음 (4개씩 함께) — 파일을 받지 않음
            val nas = api ?: throw IOException("NAS 에 연결되어 있지 않습니다")
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val n = java.util.concurrent.atomic.AtomicInteger()
            try {
                val futures = same.map { g -> pool.submit<List<List<F>>> {
                    if (canceled()) return@submit emptyList()
                    val by = g.groupBy { try { nas.hash(r.base.root, it.path) } catch (e: Exception) { "x" + it.path } }
                    progress("중복 파일 찾는 중… ${n.addAndGet(g.size)} / $total")
                    by.filter { (k, v) -> v.size > 1 && !k.startsWith("x") }.values.map { it.sortedBy { f -> f.mtimeMs } }
                } }
                for (f in futures) { if (canceled()) throw IOException("취소함"); out.addAll(f.get()) }
            } finally { pool.shutdownNow() }
            out.sortByDescending { it[0].size * (it.size - 1) }
            r.dups = out
            return out
        }
        for (g in same) {
            if (canceled()) throw IOException("취소함")
            // 앞뒤 64KB 로 먼저 가르고, 같은 것만 끝까지 읽어 확인
            val quick = g.groupBy { try { quickHash(File(root, it.path)) } catch (e: Exception) { "x" + it.path } }.values.filter { it.size > 1 }
            quick.flatMap { q -> q.groupBy { try { fullHash(File(root, it.path), canceled) } catch (e: IOException) { if (canceled()) throw e; "x" + it.path } }.values }
                .filter { it.size > 1 }.forEach { out += it.sortedBy { f -> f.mtimeMs } }
            done += g.size
            progress("중복 파일 찾는 중… $done / $total")
        }
        out.sortByDescending { it[0].size * (it.size - 1) }
        r.dups = out
        return out
    }

    private fun quickHash(f: File): String = RandomAccessFile(f, "r").use { raf ->
        val md = MessageDigest.getInstance("SHA-256"); val n = raf.length(); val b = ByteArray(65536)
        var k = raf.read(b, 0, minOf(n, 65536L).toInt()); if (k > 0) md.update(b, 0, k)
        if (n > 65536) { raf.seek(maxOf(65536L, n - 65536)); k = raf.read(b); if (k > 0) md.update(b, 0, k) }
        hex(md.digest())
    }
    private fun fullHash(f: File, canceled: () -> Boolean): String = f.inputStream().use { inp ->
        val md = MessageDigest.getInstance("SHA-256"); val b = ByteArray(1 shl 20)
        while (true) { if (canceled()) throw IOException("취소함"); val k = inp.read(b); if (k < 0) break; md.update(b, 0, k) }
        hex(md.digest())
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ---------- 남길 것 고르는 규칙 (위에서부터 차례로 비교) ----------
    val ruleNames = linkedMapOf(
        "pref" to "선호 폴더에 있는 것",
        "clean" to "이름이 깔끔한 것 ('(1)'·'_1'·'복사본'·'copy' 가 없는 것)",
        "short" to "이름이 짧은 것 (확장자를 뺀 글자 수)",
        "path" to "경로가 짧은 것 (폴더 경로 글자 수)",
        "old" to "먼저 만든 것 (바뀐 때가 이른 것)",
        "new" to "나중에 바뀐 것",
        "shallow" to "얕은 폴더에 있는 것 (폴더 단계가 적은 것)",
        "deep" to "깊은 폴더에 있는 것",
    )
    data class Rule(val key: String, val on: Boolean)
    fun rules(pf: android.content.SharedPreferences): List<Rule> {
        val s = pf.getString("dup_rules", "pref:1,clean:1,path:1,short:1,old:1,shallow:1,new:0,deep:0") ?: ""
        val out = s.split(',').mapNotNull { p -> p.split(':').takeIf { it.size == 2 && it[0] in ruleNames }?.let { Rule(it[0], it[1] == "1") } }.toMutableList()
        // 예전에 저장한 규칙에 없던 '경로가 짧은 것'·'이름이 짧은 것'은 '이름이 깔끔한 것' 바로 뒤에 켜서 넣음
        for (k in listOf("short", "path")) if (out.none { it.key == k }) out.add(out.indexOfFirst { it.key == "clean" }.let { if (it >= 0) it + 1 else out.size }, Rule(k, true))
        ruleNames.keys.filter { k -> out.none { it.key == k } }.forEach { out += Rule(it, false) }
        return out
    }
    fun saveRules(pf: android.content.SharedPreferences, r: List<Rule>) = pf.edit().putString("dup_rules", r.joinToString(",") { it.key + ":" + (if (it.on) "1" else "0") }).apply()
    fun preferred(pf: android.content.SharedPreferences): List<String> = (pf.getString("dup_pref", "") ?: "").split('\n').filter { it.isNotEmpty() }
    fun savePreferred(pf: android.content.SharedPreferences, l: List<String>) = pf.edit().putString("dup_pref", l.joinToString("\n")).apply()

    // 사본 표시: '(1)' · '복사본' · 'copy' · ' - 2' · '_1'~'_99' (끝에 붙은 것만 — 찍은 시각 '_110617' 같은 긴 숫자는 아님)
    private val copyMark = Regex("""(\(\d+\)|복사본|사본|copy|\s-\s\d+|_\d{1,2})(\.[^.]+)?$""", RegexOption.IGNORE_CASE)
    /** 묶음마다 규칙으로 하나를 남김 → 지울 것들 */
    fun pickToDelete(pf: android.content.SharedPreferences, groups: List<List<F>>): List<F> {
        val rs = rules(pf).filter { it.on }; val pref = preferred(pf)
        fun prefRank(f: F): Int { val d = f.path.substringBeforeLast('/', ""); val i = pref.indexOfFirst { d == it || d.startsWith("$it/") }; return if (i < 0) pref.size else i }
        fun messy(f: F) = if (copyMark.containsMatchIn(f.name)) 1 else 0
        fun depth(f: F) = f.path.count { it == '/' }
        fun dirLen(f: F) = f.path.substringBeforeLast('/', "").length
        fun stem(f: F) = f.name.substringBeforeLast('.').length
        val cmp = Comparator<F> { a, b ->
            for (r in rs) {
                val c = when (r.key) {
                    "pref" -> prefRank(a).compareTo(prefRank(b))
                    "clean" -> messy(a).compareTo(messy(b))
                    "short" -> stem(a).compareTo(stem(b))
                    "path" -> dirLen(a).compareTo(dirLen(b))
                    "old" -> a.mtimeMs.compareTo(b.mtimeMs)
                    "new" -> b.mtimeMs.compareTo(a.mtimeMs)
                    "shallow" -> depth(a).compareTo(depth(b))
                    "deep" -> depth(b).compareTo(depth(a))
                    else -> 0
                }
                if (c != 0) return@Comparator c
            }
            a.path.length.compareTo(b.path.length).takeIf { it != 0 } ?: a.path.compareTo(b.path)
        }
        return groups.flatMap { g -> val keep = g.minWith(cmp); g.filter { it !== keep } }
    }
}
