package kr.artchijun.mydrive

import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * 모아 보기 (홈의 이미지·오디오·동영상·문서·앱·새 파일): 휴대폰의 파일 목록(MediaStore)에서 종류별로 모음.
 *   폴더를 일일이 훑지 않아 빠름. 휴대폰이 아직 색인하지 않은 파일은 늦게 나타날 수 있음.
 */
object Lib {
    class Cat(val key: String, val title: String, val glyph: String, val color: Int)
    val cats = listOf(
        Cat("image", "이미지", "image", 0xFFB07CD8.toInt()), Cat("audio", "오디오", "music", 0xFF35B5B0.toInt()),
        Cat("video", "동영상", "film", 0xFFE0565B.toInt()), Cat("doc", "문서", "doc", 0xFF4F8DF5.toInt()),
        Cat("apk", "앱", "android", 0xFF8BC34A.toInt()), Cat("new", "새 파일", "history", 0xFF8FA3A3.toInt()))
    fun cat(key: String) = cats.first { it.key == key }
    const val NEW_DAYS = 7

    class Sum(var size: Long = 0, var count: Int = 0)

    /** 볼륨(root) 안 파일 전부: (절대경로, 크기, 바뀐 때 ms, 종류) */
    private fun query(ctx: Context, root: String, each: (String, Long, Long, Int) -> Unit) {
        val uri = MediaStore.Files.getContentUri("external")
        @Suppress("DEPRECATION") val data = MediaStore.Files.FileColumns.DATA
        val cols = arrayOf(data, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val cur = try { ctx.contentResolver.query(uri, cols, "$data LIKE ?", arrayOf("$root/%"), null) } catch (e: Exception) { null }
        if (cur == null) { walk(root, each); return }   // 휴대폰 파일 목록을 못 쓰면 폴더를 직접 훑음
        cur.use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0) ?: continue
                if (p.contains("/.") ) continue                       // 숨김·앱 휴지통(.Mydrive-trash)
                val sz = c.getLong(1); if (sz <= 0 && File(p).isDirectory) continue
                each(p, sz, c.getLong(2) * 1000, c.getInt(3))
            }
        }
    }

    private fun walk(root: String, each: (String, Long, Long, Int) -> Unit) {
        val stack = ArrayDeque<File>().apply { add(File(root)) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            for (f in d.listFiles() ?: continue) {
                if (f.name.startsWith(".") || (d.path == root && f.name == "Android")) continue
                if (f.isDirectory) stack.add(f) else each(f.path, f.length(), f.lastModified(), MediaStore.Files.FileColumns.MEDIA_TYPE_NONE)
            }
        }
    }

    private fun keyOf(path: String, mediaType: Int): String? = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> "image"
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> "audio"
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> "video"
        else -> when (FileIcon.kindOf(path.substringAfterLast('/'))) {
            "image" -> "image"; "video" -> "video"; "audio" -> "audio"
            "pdf", "doc", "sheet", "slide" -> "doc"; "apk" -> "apk"; else -> null
        }
    }

    /** 종류마다 합계 (홈 아이콘 아래 글) */
    fun sums(ctx: Context, root: String): Map<String, Sum> {
        val out = cats.associate { it.key to Sum() }
        val cut = System.currentTimeMillis() - NEW_DAYS * 86_400_000L
        query(ctx, root) { p, sz, m, t ->
            keyOf(p, t)?.let { out[it]!!.apply { size += sz; count++ } }
            if (m >= cut && !File(p).isDirectory) out["new"]!!.apply { size += sz; count++ }
        }
        return out
    }

    /** 한 종류의 파일들 (새것부터) → 목록 줄. path 는 볼륨 기준 */
    fun list(ctx: Context, root: String, key: String, limit: Int = 3000): List<Item> {
        val out = ArrayList<Item>()
        val cut = System.currentTimeMillis() - NEW_DAYS * 86_400_000L
        query(ctx, root) { p, sz, m, t ->
            val hit = if (key == "new") m >= cut && !File(p).isDirectory else keyOf(p, t) == key
            if (hit && File(p).exists()) out += Item(p.substringAfterLast('/'), false, sz, m, p.substring(root.length + 1))
        }
        return out.sortedByDescending { it.mtimeMs }.take(limit)
    }
}
