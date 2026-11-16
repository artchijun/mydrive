package kr.artchijun.mydrive

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 탐색기가 여는 곳.
 *  home  : 처음 화면 (저장소 카드)
 *  local : 휴대폰 저장소 — root = 저장소 맨 위 절대 경로 (/storage/emulated/0, SD 카드 …), path = 그 아래
 *  nas   : NAS 연결 폴더 — root = 연결 폴더 이름 (desktop, drive …), path = 그 아래
 *  zip   : 압축 파일 안 (읽기만) — root = 압축 파일 절대 경로, path = 안쪽 폴더
 *  trash : 휴대폰 휴지통
 *  cloud : 클라우드 저장소 — root = gd(Google Drive)·od(OneDrive), path = 이름 경로
 *  search: 검색 결과 — root = 찾은 곳(Loc.key), path = "검색어\u0001확장자"
 */
data class Loc(val kind: String, val root: String = "", val path: String = "") {
    fun key() = kind + "\u0002" + root + "\u0002" + path
    fun child(name: String) = copy(path = if (path.isEmpty()) name else "$path/$name")
    fun parent(): Loc? = if (path.isEmpty()) null else copy(path = path.substringBeforeLast('/', ""))
    val name: String get() = path.substringAfterLast('/')
    val browsable get() = kind == "local" || kind == "nas" || kind == "zip" || kind == "cloud"
    val writable get() = kind == "local" || kind == "nas" || kind == "cloud"
    companion object {
        fun parse(k: String): Loc? { val p = k.split('\u0002'); return if (p.size == 3) Loc(p[0], p[1], p[2]) else null }
        val HOME = Loc("home")
        val TRASH = Loc("trash")
    }
}

/** 목록 한 줄. path = 그 저장소 안 경로 (검색 결과는 하위 폴더까지 포함) */
data class Item(val name: String, val dir: Boolean, val size: Long, val mtimeMs: Long, val path: String, val count: Int = -1, val extra: String = "")

class Vol(val title: String, val root: File, val removable: Boolean) {
    val total get() = try { StatFs(root.path).totalBytes } catch (e: Exception) { 0L }
    val free get() = try { StatFs(root.path).availableBytes } catch (e: Exception) { 0L }
}

class Fs(private val ctx: Context, private val api: Api) {
    companion object {
        // 이름이 겹칠 때 (붙여 넣기)
        const val SKIP = 0; const val OVERWRITE = 1; const val RENAME = 2
        /** 같은 파일일 때만 (옮기기): 받는 쪽 것을 그대로 두고 원본을 지움 = 옮긴 것으로 침 */
        const val DROP_SOURCE = 3
        const val TRASH_DIR = ".Mydrive-trash"
        private val skipLocal = setOf(TRASH_DIR)
    }

    // ---------- 휴대폰 저장소 ----------
    private var volCache: List<Vol>? = null
    private var volAt = 0L
    /** 저장소 목록 (자주 불려서 5초 동안 기억) */
    fun volumes(): List<Vol> {
        val now = System.currentTimeMillis()
        volCache?.let { if (now - volAt < 5000) return it }
        return scanVolumes().also { volCache = it; volAt = now }
    }
    private fun scanVolumes(): List<Vol> {
        val out = mutableListOf<Vol>()
        val primary = Environment.getExternalStorageDirectory()
        out += Vol("내장 저장소", primary, false)
        val sm = ctx.getSystemService(StorageManager::class.java)
        try {
            for (v in sm.storageVolumes) {
                if (v.isPrimary) continue
                if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else v.uuid?.let { File("/storage/$it") }
                if (dir != null && dir.exists() && out.none { it.root == dir }) out += Vol(v.getDescription(ctx) ?: "외부 저장소", dir, v.isRemovable)
            }
        } catch (e: Exception) { }
        return out
    }

    fun file(loc: Loc) = File(loc.root, loc.path)

    fun canReadAll(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------- 목록 ----------
    fun list(loc: Loc, showHidden: Boolean): List<Item> = when (loc.kind) {
        "local" -> {
            // 항목마다 정보는 한 번만 묻기 (휴대폰 저장소는 묻는 횟수가 많으면 느림). 안 항목 수는 따로 (countLater)
            val d = file(loc)
            if (!d.isDirectory) throw IOException(if (!d.exists()) "폴더가 없습니다" else "폴더가 아닙니다")
            val out = ArrayList<Item>()
            try {
                java.nio.file.Files.newDirectoryStream(d.toPath()).use { ds ->
                    for (p in ds) {
                        val n = p.fileName.toString()
                        if ((!showHidden && n.startsWith(".")) || n in skipLocal) continue
                        val a = try { java.nio.file.Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java) } catch (e: Exception) { null }
                        val dir = a?.isDirectory ?: false
                        out += Item(n, dir, if (dir || a == null) 0 else a.size(), a?.lastModifiedTime()?.toMillis() ?: 0, loc.child(n).path)
                    }
                }
            } catch (e: java.nio.file.AccessDeniedException) { throw IOException("이 폴더를 읽을 권한이 없습니다") }
            out
        }
        "nas" -> api.list(loc.root, loc.path).filter { showHidden || !it.name.startsWith(".") }.map { e ->
            Item(e.name, e.dir, e.size, e.mtimeNs / 1_000_000, e.path)
        }
        "zip" -> zipList(File(loc.root), loc.path)
        "cloud" -> cloud(loc).list(loc.path).filter { showHidden || !it.name.startsWith(".") }
        "trash" -> trashList()
        else -> emptyList()
    }

    fun childCount(d: File, showHidden: Boolean): Int = d.list()?.count { showHidden || !it.startsWith(".") } ?: -1

    // ---------- 만들기·이름 바꾸기·지우기 ----------
    fun mkdir(loc: Loc, name: String) {
        checkName(name)
        when (loc.kind) {
            "local" -> { val f = File(file(loc), name); if (f.exists()) throw IOException("같은 이름이 있습니다"); if (!f.mkdirs()) throw IOException("폴더를 만들지 못했습니다") }
            "nas" -> api.mkdir(loc.root, loc.child(name).path)
            "cloud" -> { if (cloud(loc).list(loc.path).any { it.name.equals(name, true) }) throw IOException("같은 이름이 있습니다"); cloud(loc).mkdir(loc.child(name).path) }
            else -> throw IOException("여기에는 만들 수 없습니다")
        }
    }

    fun rename(loc: Loc, it: Item, newName: String) {
        checkName(newName)
        if (newName == it.name) return
        val parent = it.path.substringBeforeLast('/', "")
        val to = if (parent.isEmpty()) newName else "$parent/$newName"
        when (loc.kind) {
            "local" -> {
                val a = File(loc.root, it.path); val b = File(loc.root, to)
                if (b.exists() && !b.path.equals(a.path, ignoreCase = true)) throw IOException("같은 이름이 있습니다")
                if (!a.renameTo(b)) throw IOException("이름을 바꾸지 못했습니다")
                scan(b)
            }
            "nas" -> try { api.move(loc.root, it.path, to) } catch (e: ApiException) { if (e.status == 409) throw IOException("같은 이름이 있습니다") else throw e }
            "cloud" -> cloud(loc).rename(it.path, newName)
            else -> throw IOException("여기서는 이름을 바꿀 수 없습니다")
        }
    }

    /** 지우기: 휴대폰 → 앱 휴지통, NAS → NAS 휴지통, 휴지통 안 → 완전히 */
    fun delete(loc: Loc, items: List<Item>) {
        for (it in items) when (loc.kind) {
            "local" -> toTrash(File(loc.root, it.path))
            "nas" -> api.delete(loc.root, it.path)
            "cloud" -> cloud(loc).trash(it.path)   // 클라우드 휴지통으로
            "trash" -> purge(it)
            else -> throw IOException("여기서는 지울 수 없습니다")
        }
    }

    fun cloud(l: Loc) = CloudDrive.of(ctx, l.root)

    private fun checkName(n: String) { if (n.isBlank() || n.contains('/') || n == "." || n == "..") throw IOException("이름이 올바르지 않습니다") }

    // ---------- 휴지통 (저장소마다 맨 위 .Mydrive-trash, 원래 자리는 옆에 .json 으로) ----------
    private fun volOf(f: File): File = volumes().map { it.root }.filter { f.path.startsWith(it.path + "/") || f.path == it.path }.maxByOrNull { it.path.length } ?: Environment.getExternalStorageDirectory()

    private fun toTrash(f: File) {
        if (!f.exists()) return
        val dir = File(volOf(f), TRASH_DIR).apply { mkdirs() }
        File(dir, ".nomedia").let { if (!it.exists()) it.createNewFile() }
        val id = System.currentTimeMillis().toString() + "-" + Api.newId().take(6)
        val dst = File(dir, id)
        val meta = JSONObject().put("orig", f.path).put("name", f.name).put("time", System.currentTimeMillis()).put("dir", f.isDirectory).put("size", sizeOf(f))
        if (!f.renameTo(dst)) throw IOException("휴지통으로 옮기지 못했습니다: ${f.name}")
        File(dir, "$id.json").writeText(meta.toString())
        scan(f)
    }

    private fun trashDirs() = volumes().map { File(it.root, TRASH_DIR) }.filter { it.isDirectory }

    private fun trashList(): List<Item> {
        val out = mutableListOf<Item>()
        for (d in trashDirs()) for (m in d.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()) {
            try {
                val j = JSONObject(m.readText()); val body = File(d, m.name.removeSuffix(".json"))
                if (!body.exists()) { m.delete(); continue }
                out += Item(j.getString("name"), j.optBoolean("dir"), j.optLong("size"), j.optLong("time"), body.path, extra = j.getString("orig"))
            } catch (e: Exception) { }
        }
        return out.sortedByDescending { it.mtimeMs }
    }

    fun restore(items: List<Item>): Int {
        var n = 0
        for (it in items) {
            val body = File(it.path); val orig = File(it.extra)
            orig.parentFile?.mkdirs()
            val dst = if (!orig.exists()) orig else File(orig.parentFile, Fmt.uniqueName(orig.name) { File(orig.parentFile, it).exists() })
            if (body.renameTo(dst)) { File(body.path + ".json").delete(); scan(dst); n++ }
        }
        return n
    }

    private fun purge(it: Item) { val body = File(it.path); body.deleteRecursively(); File(body.path + ".json").delete() }

    fun emptyTrash() { for (d in trashDirs()) d.listFiles()?.forEach { if (it.name != ".nomedia") it.deleteRecursively() } }

    /** 그 저장소 휴지통이 차지한 용량 */
    fun trashBytes(vol: File): Long = File(vol, TRASH_DIR).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun trashSize(): Pair<Int, Long> { val l = trashList(); return l.size to l.sumOf { it.size } }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 사진·동영상 앱이 바뀐 것을 알게 */
    fun scan(f: File) { try { MediaScannerConnection.scanFile(ctx, arrayOf(f.path), null, null) } catch (e: Exception) { } }

    // ---------- 압축 파일 ----------
    private fun openZip(f: File): ZipFile {
        // 한국 윈도에서 만든 ZIP 은 이름이 CP949 인 경우가 많음 → UTF-8 로 읽다 깨지면 CP949 로
        val z = ZipFile(f, Charsets.UTF_8)
        return try { z.entries().toList(); z } catch (e: IllegalArgumentException) { z.close(); ZipFile(f, Charset.forName("MS949")) }
    }

    private fun zipList(f: File, dir: String): List<Item> = openZip(f).use { z ->
        val pre = if (dir.isEmpty()) "" else "$dir/"
        val out = linkedMapOf<String, Item>()
        for (e in z.entries()) {
            val n = e.name.trimEnd('/')
            if (!n.startsWith(pre) || n == dir) continue
            val rest = n.removePrefix(pre)
            val first = rest.substringBefore('/')
            if (rest.contains('/') || e.isDirectory) { if (!out.containsKey(first)) out[first] = Item(first, true, 0, e.time, pre + first) }
            else out[first] = Item(first, false, e.size, e.time, pre + first)
        }
        out.values.toList()
    }

    private fun zipWalk(f: File, under: String): List<ZipEntry> = openZip(f).use { z ->
        z.entries().toList().filter { !it.isDirectory && (it.name == under || it.name.startsWith("$under/")) }
    }

    /** 고른 휴대폰 파일·폴더를 ZIP 하나로 → 만든 파일 */
    fun compress(job: Transfers.Job, loc: Loc, items: List<Item>, zipName: String): File {
        val dir = file(loc)
        val dst = File(dir, Fmt.uniqueName(zipName) { File(dir, it).exists() })
        val files = items.flatMap { val f = File(loc.root, it.path); if (f.isFile) listOf(f to f.name) else f.walkTopDown().filter { x -> x.isFile }.map { x -> x to (f.name + "/" + x.relativeTo(f).path) }.toList() }
        job.total = files.sumOf { it.first.length() }
        try {
            ZipOutputStream(dst.outputStream().buffered()).use { zo ->
                for ((f, name) in files) {
                    if (job.cancel) throw Canceled()
                    zo.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                    f.inputStream().use { pump(job, it, zo) }
                    zo.closeEntry()
                }
            }
        } catch (e: Exception) { dst.delete(); throw e }
        scan(dst)
        return dst
    }

    // ---------- 복사·옮기기 (휴대폰 ↔ NAS ↔ 압축 파일 안) ----------
    private class Node(val rel: String, val dir: Boolean, val size: Long, val mtimeMs: Long)

    private fun walk(loc: Loc, it: Item, out: MutableList<Node>, base: String) {
        val here = if (base.isEmpty()) it.name else "$base/${it.name}"
        out += Node(here, it.dir, it.size, it.mtimeMs)
        if (!it.dir) return
        when (loc.kind) {
            "local" -> File(loc.root, it.path).listFiles()?.forEach { f -> walk(loc, Item(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), "${it.path}/${f.name}"), out, here) }
            "nas" -> api.list(loc.root, it.path).forEach { e -> walk(loc, Item(e.name, e.dir, e.size, e.mtimeNs / 1_000_000, e.path), out, here) }
            "zip" -> zipList(File(loc.root), it.path).forEach { c -> walk(loc, c, out, here) }
            "cloud" -> cloud(loc).list(it.path).forEach { c -> walk(loc, c, out, here) }
        }
    }

    private fun srcPath(src: Loc, top: Item, rel: String): String {
        val tail = rel.substringAfter('/', "")
        return if (tail.isEmpty() || !rel.contains('/')) top.path else top.path + "/" + tail
    }

    /** 이름이 겹치면 '이름 (2)' — 덮어쓰지 않음 */
    private fun freeName(dst: Loc, name: String, taken: MutableSet<String>): String {
        val n = Fmt.uniqueName(name) { taken.contains(it.lowercase()) }
        taken += n.lowercase(); return n
    }

    // ---------- 이름이 겹칠 때 ----------
    private class Report { var dropped = 0; var renamed = 0; var skipped = 0; var same = 0; var overwritten = 0; var merged = 0 }
    /** 크기가 같고 바뀐 때가 2초 안쪽이면 같은 파일로 봄 (묻지 않고 건너뜀) */
    private fun sameFile(a: Item, b: Item) = !a.dir && !b.dir && a.size == b.size && Math.abs(a.mtimeMs - b.mtimeMs) <= 2000

    /** 붙여 넣기 전에: 받는 폴더에 이미 있는 이름 (파일·폴더 수, 그중 같은 파일 수) */
    fun conflicts(src: Loc, items: List<Item>, dst: Loc): Triple<Int, Int, Int> {
        var files = 0; var dirs = 0; var same = 0
        // 합쳐질 폴더 안까지 세어 봄 (안에서 겹치는 파일도 물어보려고)
        fun walkIn(its: List<Item>, d: Loc) {
            val by = list(d, true).associateBy { it.name.lowercase() }
            for (it in its) {
                val ex = by[it.name.lowercase()] ?: continue
                if (ex.dir && it.dir) { dirs++; walkIn(list(src.copy(path = it.path), true), d.child(ex.name)) }
                else if (sameFile(ex, it)) same++ else files++
            }
        }
        walkIn(items, dst)
        return Triple(files, dirs, same)
    }

    /**
     * src 의 items 를 dst 폴더로. move = 옮기기 → 알릴 말
     *   이름이 겹치면: 폴더끼리는 합침 (안에서 겹치는 파일도 같은 방식) · 크기·바뀐 때가 같은 파일은 samePolicy 대로(건너뛰기·원본 지우기·덮어쓰기·번호) ·
     *   나머지 파일은 policy 대로 — SKIP 건너뛰기 · OVERWRITE 덮어쓰기(덮이는 판은 NAS 이전 버전/휴대폰·클라우드 휴지통으로) · RENAME '이름 (2)'
     *   옮기기에서 건너뛴 파일은 원래 자리에 그대로 둠
     */
    fun transfer(job: Transfers.Job, src: Loc, items: List<Item>, dst: Loc, move: Boolean, policy: Int = RENAME, samePolicy: Int = SKIP): String? {
        if (!dst.writable) throw IOException("여기에는 붙여 넣을 수 없습니다")
        if (src.kind == dst.kind && src.root == dst.root && items.any { dst.path == it.path || dst.path.startsWith(it.path + "/") })
            throw IOException("폴더를 자기 안으로 옮길 수 없습니다")
        val rep = Report()
        val here = list(dst, true).associateBy { it.name.lowercase() }.toMutableMap()
        val slow = ArrayList<Item>()
        // 같은 곳 안에서 옮기기는 이름만 바꿈 (빠름). 폴더 합치기는 아래 일반 방식으로
        if (move && src.kind == dst.kind && src.root == dst.root && (src.kind == "local" || src.kind == "nas" || src.kind == "cloud")) {
            job.stage = "같은 저장소 안에서 옮기는 중 (이름만 바꿈)"; job.files = items.size
            for (it in items) {
                if (job.cancel) throw Canceled()
                job.file = it.path; job.filesDone++
                if (it.path.substringBeforeLast('/', "") == dst.path) continue   // 제자리
                val ex = here[it.name.lowercase()]
                var n = it.name
                if (ex != null) {
                    if (ex.dir && it.dir) { slow += it; continue }
                    val same = sameFile(ex, it); val pol = if (same) samePolicy else policy
                    if (same && pol == SKIP) { rep.same++; job.skipped++; continue }
                    if (same && pol == DROP_SOURCE) { deleteSource(src, it); rep.dropped++; continue }
                    if (ex.dir != it.dir || pol == RENAME) { n = Fmt.uniqueName(it.name) { here.containsKey(it.lowercase()) }; rep.renamed++ }
                    else if (pol == SKIP) { rep.skipped++; job.skipped++; continue }
                    else { delete(dst, listOf(ex)); rep.overwritten++ }
                }
                moveSame(src, it, dst, n)
                here[n.lowercase()] = it.copy(name = n)
            }
        } else slow.addAll(items)
        if (slow.isNotEmpty()) {
            job.stage = "파일 세는 중"
            val nodes = slow.map { t -> mutableListOf<Node>().also { l -> walk(src, t, l, "") }.filter { !it.dir } }
            job.files = nodes.sumOf { it.size }; job.filesDone = 0
            job.total = nodes.sumOf { l -> l.sumOf { it.size } }
            for (it in slow) {
                if (job.cancel) throw Canceled()
                val moved = ArrayList<Item>()
                val all = put(job, src, it, dst, here, policy, samePolicy, rep, moved)
                if (move) {
                    if (all) deleteSource(src, it)               // 다 옮겼으면 통째로
                    else moved.forEach { f -> deleteSource(src, f) }   // 일부 건너뛰었으면 옮긴 파일만
                }
            }
        }
        val parts = listOfNotNull(
            if (rep.merged > 0) "폴더 ${rep.merged}개는 합침" else null,
            if (rep.same > 0) "같은 파일 ${rep.same}개는 건너뜀" + (if (move) "(원본 그대로)" else "") else null,
            if (rep.dropped > 0) "같은 파일 ${rep.dropped}개는 받는 쪽 것을 두고 원본을 지움" else null,
            if (rep.skipped > 0) "겹치는 ${rep.skipped}개는 건너뜀" else null,
            if (rep.overwritten > 0) "${rep.overwritten}개 덮어씀" else null,
            if (rep.renamed > 0) "${rep.renamed}개는 번호를 붙임" else null)
        return if (parts.isEmpty()) null else (if (move) "옮겼습니다 — " else "붙여 넣었습니다 — ") + parts.joinToString(", ")
    }

    /** 같은 저장소 안에서 이름만 바꿔 옮기기 */
    private fun moveSame(src: Loc, it: Item, dst: Loc, n: String) {
        val to = dst.child(n).path
        when (src.kind) {
            "local" -> {
                val a = File(src.root, it.path); val b = File(src.root, to)
                if (!a.renameTo(b)) throw IOException("옮기지 못했습니다: ${it.name}") else { scan(a); scan(b) }
            }
            "cloud" -> { cloud(src).move(it.path, dst.path); if (n != it.name) cloud(src).rename(dst.child(it.name).path, n) }
            else -> api.move(src.root, it.path, to)
        }
    }

    private fun deleteSource(src: Loc, it: Item) = when (src.kind) {
        "local" -> { val f = File(src.root, it.path); f.deleteRecursively(); scan(f) }
        "nas" -> api.delete(src.root, it.path)
        "cloud" -> cloud(src).trash(it.path)
        else -> {}
    }

    /** 하나(파일이나 폴더)를 dst 폴더에 넣음. here = dst 안 이름들. 건너뛴 것이 없으면 true. moved = 넣은 파일들(옮기기용) */
    private fun put(job: Transfers.Job, src: Loc, it: Item, dst: Loc, here: MutableMap<String, Item>, policy: Int, samePolicy: Int, rep: Report, moved: MutableList<Item>): Boolean {
        if (job.cancel) throw Canceled()
        val ex = here[it.name.lowercase()]
        if (it.dir) {
            if (ex != null && ex.dir) {   // 폴더끼리: 합침
                rep.merged++
                val into = dst.child(ex.name)
                val inner = list(into, true).associateBy { c -> c.name.lowercase() }.toMutableMap()
                var all = true
                for (c in list(src.copy(path = it.path), true)) if (!put(job, src, c, into, inner, policy, samePolicy, rep, moved)) all = false
                return all
            }
            val n = if (ex == null) it.name else { rep.renamed++; Fmt.uniqueName(it.name) { here.containsKey(it.lowercase()) } }
            copyTree(job, src, it, dst, n)
            here[n.lowercase()] = it.copy(name = n)
            moved += it
            return true
        }
        var n = it.name
        if (ex != null) {
            when {
                sameFile(ex, it) && samePolicy == SKIP -> { rep.same++; job.done += it.size; job.skipped++; job.filesDone++; job.file = it.path; return false }
                sameFile(ex, it) && samePolicy == DROP_SOURCE -> {   // 받는 쪽에 같은 파일이 있으니 옮긴 것으로 침 → 원본은 지움
                    rep.dropped++; job.done += it.size; job.skipped++; job.filesDone++; job.file = it.path; moved += it; return true }
                ex.dir || (if (sameFile(ex, it)) samePolicy else policy) == RENAME -> { n = Fmt.uniqueName(it.name) { here.containsKey(it.lowercase()) }; rep.renamed++ }
                !sameFile(ex, it) && policy == SKIP -> { rep.skipped++; job.done += it.size; job.skipped++; job.filesDone++; job.file = it.path; return false }
                else -> {   // 덮어쓰기: NAS 는 덮는 판을 이전 버전으로 남김, 휴대폰·클라우드는 덮이는 파일을 휴지통으로
                    if (dst.kind != "nas") delete(dst, listOf(ex))
                    rep.overwritten++
                }
            }
        }
        copyFile(job, src, it.path, Node(n, false, it.size, it.mtimeMs), dst, dst.child(n))
        here[n.lowercase()] = it.copy(name = n)
        moved += it
        return true
    }

    private fun copyTree(job: Transfers.Job, src: Loc, top: Item, dst: Loc, topName: String) {
        val nodes = mutableListOf<Node>().also { walk(src, top, it, "") }
        for (nd in nodes) {
            if (job.cancel) throw Canceled()
            val rel = topName + nd.rel.removePrefix(top.name)
            val to = dst.child(rel)
            if (nd.dir) { when (dst.kind) { "local" -> file(to).mkdirs(); "nas" -> try { api.mkdir(dst.root, to.path) } catch (e: ApiException) { if (e.status != 409) throw e }; "cloud" -> cloud(dst).mkdir(to.path) }; continue }
            copyFile(job, src, srcPath(src, top, nd.rel), nd, dst, to)
        }
    }

    private fun copyFile(job: Transfers.Job, src: Loc, sp: String, nd: Node, dst: Loc, to: Loc) {
        val base = job.done
        val sn = where(src.kind); val dn = where(dst.kind)
        job.startFile(sp, nd.size, when {
            src.kind == "local" && dst.kind == "local" -> "휴대폰 안에서 복사하는 중"
            dst.kind == "local" -> "${sn}에서 받는 중"
            src.kind == "local" -> "${dn}(으)로 올리는 중"
            else -> "${sn}에서 앱 안에 받아 두는 중 → ${dn}(으)로 올림"
        })
        val prog: (Long, Long) -> Unit = { d, _ -> Transfers.progress(job, base + d, job.total) }
        when {
            dst.kind == "local" -> {
                val out = file(to); out.parentFile?.mkdirs()
                when (src.kind) {
                    "local" -> { File(src.root, sp).inputStream().use { inp -> out.outputStream().use { pump(job, inp, it) } } }
                    "nas" -> api.download(src.root, Entry(sp, false, nd.size, nd.mtimeMs * 1_000_000), out, prog, { job.cancel })
                    "cloud" -> cloud(src).download(sp, nd.size, out, prog) { job.cancel }
                    "zip" -> openZip(File(src.root)).use { z -> z.getInputStream(z.getEntry(sp)).use { inp -> out.outputStream().use { pump(job, inp, it) } } }
                }
                if (nd.mtimeMs > 0) out.setLastModified(nd.mtimeMs)
                scan(out)
            }
            dst.kind == "nas" || dst.kind == "cloud" -> {
                var tmp: File? = null
                var size = nd.size
                val open: () -> InputStream = when (src.kind) {
                    "local" -> { { File(src.root, sp).inputStream() } }
                    else -> {   // NAS·압축 파일 → 일단 앱 안에 받아 두고 올림
                        val t = File(File(ctx.cacheDir, "xfer").apply { mkdirs() }, Api.newId()); tmp = t
                        if (src.kind == "nas") api.download(src.root, Entry(sp, false, nd.size, nd.mtimeMs * 1_000_000), t, { _, _ -> }, { job.cancel })
                        else if (src.kind == "cloud") cloud(src).download(sp, nd.size, t, { _, _ -> }) { job.cancel }
                        else openZip(File(src.root)).use { z -> z.getInputStream(z.getEntry(sp)).use { inp -> t.outputStream().use { inp.copyTo(it) } } }
                        ({ t.inputStream() })
                    }
                }
                tmp?.let { size = it.length(); job.stage = "${dn}(으)로 올리는 중 (받아 둔 것)" }   // 구글 문서는 받으면서 크기가 정해짐
                try {
                    if (dst.kind == "nas") api.upload(dst.root, to.path, size, maxOf(nd.mtimeMs, 1) * 1_000_000, open, prog, { job.cancel })
                    else cloud(dst).upload(to.path.substringBeforeLast('/', ""), to.name, size, open, prog) { job.cancel }
                } finally { tmp?.delete() }
            }
        }
        job.done = base + nd.size; job.filesDone++; Transfers.progress(job, job.done, job.total)
    }
    private fun where(kind: String) = when (kind) { "nas" -> "NAS"; "cloud" -> "클라우드"; "zip" -> "압축 파일"; else -> "휴대폰" }

    private fun pump(job: Transfers.Job, inp: InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(256 * 1024)
        while (true) {
            if (job.cancel) throw Canceled()
            val r = inp.read(buf); if (r < 0) break
            out.write(buf, 0, r); job.done += r; Transfers.progress(job, job.done, job.total)
        }
    }

    /** 압축 풀기: 압축 파일 옆에 같은 이름 폴더로 */
    fun extractAll(job: Transfers.Job, zip: File, intoLoc: Loc): String {
        val base = zip.name.substringBeforeLast('.')
        val dir = file(intoLoc)
        val name = Fmt.uniqueName(base) { File(dir, it).exists() }
        val target = File(dir, name).apply { mkdirs() }
        openZip(zip).use { z ->
            val es = z.entries().toList()
            job.total = es.sumOf { maxOf(it.size, 0) }
            for (e in es) {
                if (job.cancel) throw Canceled()
                val out = File(target, e.name).canonicalFile
                if (!out.path.startsWith(target.canonicalPath + "/")) continue   // ../ 로 밖에 쓰려는 항목은 건너뜀
                if (e.isDirectory) { out.mkdirs(); continue }
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { inp -> out.outputStream().use { pump(job, inp, it) } }
                if (e.time > 0) out.setLastModified(e.time)
            }
        }
        scan(target)
        return name
    }

    // ---------- 검색 ----------
    fun search(base: Loc, q: String, ext: String, showHidden: Boolean, canceled: () -> Boolean): Pair<List<Item>, Boolean> {
        val exts = ext.lowercase().split(',', ' ').map { it.trim().trim('.') }.filter { it.isNotEmpty() }
        val ql = q.trim().lowercase()
        return when (base.kind) {
            "nas" -> api.search(base.root, base.path, q.trim(), exts.joinToString(",")).let { (es, more) -> es.map { Item(it.name, it.dir, it.size, it.mtimeNs / 1_000_000, it.path) } to more }
            "local" -> {
                val out = mutableListOf<Item>(); val limit = 1000
                val start = file(base)
                val stack = ArrayDeque<File>().apply { add(start) }
                while (stack.isNotEmpty() && out.size < limit && !canceled()) {
                    val d = stack.removeLast()
                    for (f in d.listFiles() ?: continue) {
                        if (f.name == TRASH_DIR || (!showHidden && f.name.startsWith("."))) continue
                        if (f.isDirectory) stack.add(f)
                        val n = f.name.lowercase()
                        if (ql.isNotEmpty() && !n.contains(ql)) continue
                        if (exts.isNotEmpty() && (f.isDirectory || exts.none { n.endsWith(".$it") })) continue
                        out += Item(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), f.relativeTo(File(base.root)).path)
                        if (out.size >= limit) break
                    }
                }
                out to (out.size >= limit)
            }
            "cloud" -> throw IOException("클라우드 저장소에서는 아직 찾기를 할 수 없습니다")
            else -> emptyList<Item>() to false
        }
    }
}
