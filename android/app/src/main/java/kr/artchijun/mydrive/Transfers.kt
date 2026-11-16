package kr.artchijun.mydrive

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.Executors

// 올리기·받기 줄: 하나씩 차례로. 다른 앱으로 가도 계속되게 TransferService 가 알림과 함께 붙잡아 둠
object Transfers {
    enum class Kind { UP, OPEN, SAVE, COPY, MOVE, SHARE, ZIP, LOGIN }

    class Job(val kind: Kind, val root: String, val title: String, val run: (Job) -> String?) {
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var cancel = false
    }

    interface Listener { fun onTransfers() ; fun onFinished(job: Job, error: String?, result: String?) }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Job>()
    @Volatile var current: Job? = null
        private set
    var listener: Listener? = null
    private var lastNotify = 0L

    val waiting: Int get() = synchronized(queue) { queue.size }
    val busy: Boolean get() = current != null || waiting > 0

    fun add(ctx: Context, job: Job) {
        synchronized(queue) { queue.addLast(job) }
        TransferService.start(ctx.applicationContext)
        pool.execute { runOne() }
        changed()
    }

    fun verb(k: Kind) = when (k) { Kind.UP -> "올리는 중"; Kind.COPY -> "복사하는 중"; Kind.MOVE -> "옮기는 중"; Kind.ZIP -> "압축하는 중"; Kind.LOGIN -> "로그인 기다리는 중"; else -> "받는 중" }

    fun cancelAll() {
        synchronized(queue) { queue.clear() }
        current?.cancel = true
        changed()
    }

    fun progress(job: Job, done: Long, total: Long) {
        job.done = done; job.total = total
        val now = System.currentTimeMillis()
        if (now - lastNotify > 300) { lastNotify = now; changed() }
    }

    private fun runOne() {
        val job = synchronized(queue) { queue.removeFirstOrNull() } ?: return
        current = job; changed()
        var err: String? = null
        var res: String? = null
        try { res = job.run(job) } catch (e: Canceled) { err = "취소함" } catch (e: Exception) {
            err = e.message ?: e.javaClass.simpleName
            android.util.Log.w("Mydrive", "${job.kind} ${job.title} 실패", e)
        }
        current = null
        main.post { listener?.onFinished(job, err, res) }
        changed()
    }

    private fun changed() = main.post { listener?.onTransfers(); TransferService.refresh() }

    // ---------- 받은 파일 다루기 ----------

    /** 열려고 받은 파일 자리 (앱 캐시 — 공간이 모자라면 안드로이드가 지움) */
    fun openFile(ctx: Context, root: String, path: String) = File(File(ctx.cacheDir, "open/$root"), path)

    /** 받은 파일을 휴대폰 '다운로드/Mydrive' 폴더로 옮김 → 저장된 이름 */
    fun saveToDownloads(ctx: Context, src: File, name: String): String {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mydrive")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw java.io.IOException("다운로드 폴더에 만들 수 없음")
        try {
            cr.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) { cr.delete(uri, null, null); throw e }
        src.delete()
        return "다운로드/Mydrive/$name"
    }

    fun viewIntent(ctx: Context, f: File): Intent {
        val uri: Uri = FilesProvider.uriFor(ctx, f)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, FilesProvider.mimeOf(f.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
