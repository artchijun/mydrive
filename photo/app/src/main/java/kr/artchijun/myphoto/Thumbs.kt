package kr.artchijun.myphoto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 썸네일 불러오기: 메모리(가장 빠름) → 휴대폰 저장소 캐시 → NAS.
 *   화면에 새로 나온 것부터 먼저 받음 (나중에 부탁한 것이 앞으로: LIFO) — 빨리 넘길 때 지나간 칸은 건너뜀
 *   캐시 열쇠는 시놀로지의 cache_key (사진이 바뀌면 열쇠도 바뀜)
 */
object Thumbs {
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val queue = LinkedBlockingDeque<Runnable>()
    private val pool = ThreadPoolExecutor(5, 5, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = offerFirst(e)   // 나중 부탁이 먼저
    }.also { }).apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null
    private const val DISK_MAX = 400L * 1024 * 1024
    @Volatile private var diskUsed = -1L

    private fun disk(ctx: Context): File = dir ?: File(ctx.cacheDir, "thumbs").also { it.mkdirs(); dir = it }

    fun cached(key: String): Bitmap? = mem.get(key)

    /** iv 에 썸네일을 넣음. 같은 칸이 다른 사진으로 재활용되면 늦게 온 그림은 버림 */
    fun load(iv: ImageView, ns: String, unitId: Long, cacheKey: String, size: String, type: String = "unit", onDone: ((Bitmap?) -> Unit)? = null) {
        val key = "$ns|$type|$cacheKey|$size"
        iv.tag = key
        mem.get(key)?.let { iv.setImageBitmap(it); onDone?.invoke(it); return }
        iv.setImageDrawable(null)
        val ctx = iv.context.applicationContext
        pool.execute {
            if (iv.tag != key) return@execute   // 이미 다른 사진 칸이 됨
            val bmp = try { get(ctx, ns, unitId, cacheKey, size, type) } catch (e: Exception) { null }
            main.post { if (iv.tag == key) { if (bmp != null) iv.setImageBitmap(bmp); onDone?.invoke(bmp) } }
        }
    }

    /** 일하는 스레드에서: 썸네일 비트맵 (캐시 → NAS) */
    fun get(ctx: Context, ns: String, unitId: Long, cacheKey: String, size: String, type: String = "unit"): Bitmap? {
        val key = "$ns|$type|$cacheKey|$size"
        mem.get(key)?.let { return it }
        val f = File(disk(ctx), "${type}_${cacheKey}_$size.jpg".replace('/', '_'))
        if (f.length() > 0) BitmapFactory.decodeFile(f.path)?.let { f.setLastModified(System.currentTimeMillis()); mem.put(key, it); return it }
        val tmp = File(f.path + ".part")
        Syno(ctx).thumbStream(ns, unitId, cacheKey, size, type).use { inp -> tmp.outputStream().use { inp.copyTo(it, 64 * 1024) } }
        if (!tmp.renameTo(f)) { tmp.delete(); return null }
        trim(f.length())
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        mem.put(key, b)
        return b
    }

    /** 시놀로지가 아직 썸네일을 못 만든 작은 사진(8MB 이하): 원본을 받아 줄여서 칸에 넣음 (만든 것은 캐시에 둠) */
    fun loadFromOriginal(iv: ImageView, ns: String, it: Item, onDone: (Bitmap?) -> Unit) {
        val key = "$ns|orig|${it.cacheKey}|sm"
        iv.tag = key
        mem.get(key)?.let { b -> iv.setImageBitmap(b); onDone(b); return }
        if (it.video || it.size <= 0 || it.size > 8L * 1024 * 1024) { onDone(null); return }
        val ctx = iv.context.applicationContext
        pool.execute {
            if (iv.tag != key) return@execute
            val bmp = try {
                val f = File(disk(ctx), "orig_${it.cacheKey}_sm.jpg".replace('/', '_'))
                if (f.length() > 0) BitmapFactory.decodeFile(f.path)
                else {
                    val syno = Syno(ctx)
                    val bytes = syno.open({ syno.originalUrl(ns, it) }, 60_000).inputStream.use { inp -> inp.readBytes() }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var smp = 1; while (minOf(o.outWidth, o.outHeight) / (smp * 2) >= 320) smp *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = smp })?.also { b ->
                        f.outputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, 85, out) }; trim(f.length())
                    }
                }
            } catch (e: Exception) { null }
            if (bmp != null) mem.put(key, bmp)
            main.post { if (iv.tag == key) { if (bmp != null) iv.setImageBitmap(bmp); onDone(bmp) } }
        }
    }

    /** 캐시가 정한 크기를 넘으면 오래 안 본 것부터 지움 */
    @Synchronized private fun trim(added: Long) {
        val d = dir ?: return
        if (diskUsed < 0) diskUsed = d.listFiles()?.sumOf { it.length() } ?: 0
        diskUsed += added
        if (diskUsed <= DISK_MAX) return
        val files = d.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (x in files) { if (diskUsed <= DISK_MAX * 8 / 10) break; diskUsed -= x.length(); x.delete() }
    }

    fun clear(ctx: Context) { mem.evictAll(); disk(ctx).listFiles()?.forEach { it.delete() }; diskUsed = 0 }
    fun diskSize(ctx: Context): Long = disk(ctx).listFiles()?.sumOf { it.length() } ?: 0
}
