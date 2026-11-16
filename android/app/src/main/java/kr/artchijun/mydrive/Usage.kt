package kr.artchijun.mydrive

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 저장소 분석: 어디에 용량이 들었는지 (종류별·폴더별·큰 파일·오래된 큰 파일·중복 파일).
 *   휴대폰 저장소만 (NAS 는 PC·관리 화면에서). 폴더를 직접 훑음.
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

    fun scan(base: Loc, progress: (Int) -> Unit, canceled: () -> Boolean): Result {
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

    fun findDups(r: Result, progress: (String) -> Unit, canceled: () -> Boolean): List<List<F>> {
        val same = synchronized(this) { r.files.toList() }.filter { it.size >= DUP_MIN_LOCAL }.groupBy { it.size }.values.filter { it.size > 1 }.sortedByDescending { it[0].size * (it.size - 1) }
        val out = ArrayList<List<F>>(); val root = File(r.base.root)
        val total = same.sumOf { it.size }; var done = 0
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
}
