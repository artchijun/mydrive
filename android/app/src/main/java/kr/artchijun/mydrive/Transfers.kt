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
        // 자세히 보기용
        @Volatile var from = ""          // 어디서 (예: NAS 사진/2024)
        @Volatile var to = ""            // 어디로
        @Volatile var stage = ""         // 지금 하는 일 (세는 중 · 받는 중 · 올리는 중 …)
        @Volatile var file = ""          // 지금 다루는 파일 (경로)
        @Volatile var fileSize = 0L
        @Volatile var fileBase = 0L      // 이 파일을 시작할 때의 done → 파일 진행 = done - fileBase
        @Volatile var files = 0          // 다룰 파일 수 (모르면 0)
        @Volatile var filesDone = 0
        @Volatile var skipped = 0        // 건너뛴 파일 (같은 파일·겹쳐서 건너뜀)
        @Volatile var added = 0L         // 줄에 넣은 때
        @Volatile var started = 0L
        @Volatile var ended = 0L
        @Volatile var error: String? = null
        @Volatile var result: String? = null
        internal val samples = ArrayDeque<LongArray>()   // (때, done) — 빠르기 계산

        /** 파일 하나를 시작할 때 */
        fun startFile(path: String, size: Long, what: String? = null) { file = path; fileSize = size; fileBase = done; if (what != null) stage = what }
        /** 최근 5초 동안의 빠르기 (바이트/초) */
        fun speed(): Long = synchronized(samples) {
            if (samples.size < 2) return 0
            val a = samples.first(); val b = samples.last(); val dt = b[0] - a[0]
            if (dt < 300) 0 else (b[1] - a[1]) * 1000 / dt
        }
        /** 남은 시간 (초, 모르면 -1) */
        fun eta(): Long { val v = speed(); return if (v <= 0 || total <= 0 || done >= total) -1 else (total - done) / v }
    }

    interface Listener { fun onTransfers() ; fun onFinished(job: Job, error: String?, result: String?); fun onAsk(q: Ask) }

    /** 받는 폴더에 같은 이름이 있음 → 화면에서 고를 때까지 작업이 기다림 */
    class Ask(val job: Job, val src: Item, val dst: Item, val same: Boolean, val move: Boolean, val folder: String) {
        @Volatile var answer = -1   // Fs.SKIP · OVERWRITE · RENAME
        @Volatile var all = false   // 다른 모든 항목에 적용
    }
    @Volatile var asking: Ask? = null
        private set
    /** 일하는 쪽(백그라운드)에서 부름: 화면이 답할 때까지 기다림. 앱 화면이 없으면 알림으로 알리고 화면이 열릴 때 물음 */
    fun ask(q: Ask) {
        asking = q
        changed()
        main.post { listener?.onAsk(q) }
        try { while (q.answer < 0) { if (q.job.cancel) throw Canceled(); Thread.sleep(100) } }
        finally { asking = null; changed() }
    }

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Job>()
    @Volatile var current: Job? = null
        private set
    var listener: Listener? = null
    private var lastNotify = 0L

    val waiting: Int get() = synchronized(queue) { queue.size }
    val busy: Boolean get() = current != null || waiting > 0

    /** 끝난 작업 (최근 30개, 새것 먼저) */
    val history = ArrayDeque<Job>()
    /** 기다리는 작업들 (차례대로) */
    fun pending(): List<Job> = synchronized(queue) { queue.toList() }
    /** 기다리는 작업 하나를 줄에서 뺌 */
    fun remove(job: Job) { synchronized(queue) { queue.remove(job) }; changed() }

    fun add(ctx: Context, job: Job) {
        job.added = System.currentTimeMillis()
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
        synchronized(job.samples) {
            val l = job.samples.lastOrNull()
            if (l == null || now - l[0] >= 250) { job.samples.addLast(longArrayOf(now, done)); while (job.samples.size > 2 && now - job.samples.first()[0] > 5000) job.samples.removeFirst() }
        }
        if (now - lastNotify > 300) { lastNotify = now; changed() }
    }

    private fun runOne() {
        val job = synchronized(queue) { queue.removeFirstOrNull() } ?: return
        current = job; job.started = System.currentTimeMillis(); changed()
        var err: String? = null
        var res: String? = null
        try { res = job.run(job) } catch (e: Canceled) { err = "취소함" } catch (e: Exception) {
            err = e.message ?: e.javaClass.simpleName
            android.util.Log.w("Mydrive", "${job.kind} ${job.title} 실패", e)
        }
        job.ended = System.currentTimeMillis(); job.error = err; job.result = res
        synchronized(history) { history.addFirst(job); while (history.size > 30) history.removeLast() }
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
