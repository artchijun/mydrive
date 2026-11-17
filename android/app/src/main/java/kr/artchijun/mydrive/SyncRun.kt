package kr.artchijun.mydrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore

/**
 * 동기화를 언제 돌릴지 (앱이 꺼져 있어도):
 *   - 휴대폰 쪽: 저장소가 바뀌면 (사진·문서 무엇이든) 몇 초 뒤 — 바뀐 파일이 동기화 폴더 안일 때만
 *   - NAS 쪽: '실시간 감시'를 켜 두면 알림을 띄운 채 NAS 변경을 기다렸다가 바로. 꺼 두면 15분마다 확인
 *   - N시간마다 짝: 15분마다 깨어 때가 된 것만
 *   - 휴대폰을 켰을 때·앱을 새로 깔았을 때 다시 예약
 */
object SyncSchedule {
    private const val JOB_CONTENT = 31
    private const val JOB_PERIODIC = 32
    private const val JOB_NOW = 33
    /** 지금 도는 예약 작업 — 같은 번호로 다시 예약하면 안드로이드가 돌던 것을 멈춤 */
    @Volatile var runningJob = 0

    fun schedule(ctx: Context) = try { arm(ctx) } catch (e: Exception) { }
    private fun arm(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        val ps = Sync.pairs(ctx).filter { it.on }
        val cn = ComponentName(ctx, SyncJob::class.java)
        if (ps.any { it.mode == Sync.REALTIME && it.dir != Sync.DOWN }) {
            if (runningJob != JOB_CONTENT) js.schedule(JobInfo.Builder(JOB_CONTENT, cn)
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Files.getContentUri("external"), JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(3_000).setTriggerContentMaxDelay(30_000)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        } else js.cancel(JOB_CONTENT)
        if (ps.any { it.mode != Sync.MANUAL }) {
            if (js.getPendingJob(JOB_PERIODIC) == null)
                js.schedule(JobInfo.Builder(JOB_PERIODIC, cn).setPeriodic(15 * 60_000L).setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
        } else js.cancel(JOB_PERIODIC)
    }

    /** 조금 뒤 다시 (연결이 끊겼거나 시간이 다 돼 멈췄을 때) */
    fun soon(ctx: Context, latency: Long = 60_000) {
        if (runningJob == JOB_NOW) return
        try { ctx.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(JOB_NOW, ComponentName(ctx, SyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(latency)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) } catch (e: Exception) { }
    }

    /** 15분마다: 때가 된 짝 (실시간 짝은 NAS 에서 바뀐 것이 있을 때만) */
    fun due(ctx: Context): List<String> {
        val now = System.currentTimeMillis()
        val out = ArrayList<String>()
        val ps = Sync.pairs(ctx).filter { it.on && it.mode != Sync.MANUAL }
        val api = Prefs(ctx).api()
        val roots = if (ps.any { it.mode == Sync.REALTIME && !it.web }) try { api.roots().associateBy { it.name } } catch (e: Exception) { null } else null
        for (p in ps) {
            val st = Sync.status(ctx, p.id)
            when {
                p.mode == Sync.TIMED -> if (now - st.time >= p.hours * 3600_000L - 5 * 60_000L) out += p.id
                now - st.time > 6 * 3600_000L || st.err -> out += p.id          // 오래됐거나 지난번에 문제 → 한 번 다 봄
                p.web -> if (now - st.time > 14 * 60_000L) out += p.id           // 웹 폴더는 변경 기록이 없어 직접 읽어 봄
                else -> {
                    val ri = roots?.get(p.root) ?: continue
                    if (ri.epoch != st.epoch || st.seq < 0) { out += p.id; continue }
                    if (ri.seq == st.seq) continue
                    try {
                        val (reset, seq, paths) = api.changes(p.root, st.epoch, st.seq, 0)
                        if (reset || hits(p, paths)) out += p.id else Sync.setStatus(ctx, p.id) { it.seq = seq }
                    } catch (e: Exception) { }
                }
            }
        }
        return out
    }

    /** NAS 변경 경로 가운데 이 짝 안의 것 (동기화가 스스로 쓴 것은 빼고) */
    fun hits(p: Sync.Link, paths: List<String>): Boolean = paths.any { x ->
        (p.rpath.isEmpty() || x == p.rpath || x.startsWith(p.rpath + "/")) && !Sync.ours(p.root + "\u0000" + x)
    }
}

class SyncJob : JobService() {
    @Volatile private var stop = false

    override fun onStartJob(params: JobParameters): Boolean {
        stop = false
        Thread {
            SyncSchedule.runningJob = params.jobId
            try {
                val ids = when (params.jobId) {
                    32 -> SyncSchedule.due(this)
                    31 -> changedPairs(params)
                    else -> emptyList()
                }
                Sync.request(this, ids, manual = false)
                if (Sync.pendingIds(this).isNotEmpty()) {
                    val svc = SyncService.self
                    if (svc != null) svc.kick()
                    else if (Sync.drain(this, { stop }) && stop) SyncSchedule.soon(this)
                }
            } catch (e: Exception) { }
            SyncSchedule.runningJob = 0
            SyncSchedule.schedule(this)   // 휴대폰 변경 감시 다시 걸기 (한 번 불리면 끝나므로)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { stop = true; return true }

    /** 바뀐 파일이 든 실시간 짝 (어느 파일인지 모르면 실시간 짝 모두) */
    private fun changedPairs(params: JobParameters): List<String> {
        val ps = Sync.pairs(this).filter { it.on && it.mode == Sync.REALTIME && it.dir != Sync.DOWN }
        if (ps.isEmpty()) return emptyList()
        val uris = params.triggeredContentUris ?: return ps.map { it.id }
        val out = LinkedHashSet<String>()
        var unknown = false
        for (u in uris) {
            val path = try { contentResolver.query(u, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } catch (e: Exception) { null }
            if (path == null) { if (u.lastPathSegment?.toLongOrNull() != null) unknown = true; continue }   // 지워진 파일: 어디 것인지 모름
            if (Sync.ours("L\u0000" + path)) continue
            ps.filter { path.startsWith(it.local.trimEnd('/') + "/") }.forEach { out += it.id }
        }
        if (unknown) ps.forEach { out += it.id }
        android.util.Log.i("MydriveSync", "휴대폰 변경 ${uris.size}개 (모름=$unknown) → ${out.size}개 짝")
        return out.toList()
    }
}

/**
 * 동기화 서비스: 알림을 띄우고 돌림 ([지금 동기화], 그리고 'NAS 변경 바로 받기'를 켰을 때 계속 떠 있으며 NAS 변경을 기다림).
 */
class SyncService : Service() {
    companion object {
        @Volatile var self: SyncService? = null
        private const val ID = 31
        private const val CH = "sync"

        fun watchWanted(ctx: Context) = Prefs(ctx).bool("sync_watch", true) && Sync.pairs(ctx).any { it.on && it.mode == Sync.REALTIME && !it.web && it.dir != Sync.UP }

        /** [지금 동기화] */
        fun now(ctx: Context, ids: List<String>, allowDel: Boolean = false) {
            Sync.request(ctx, ids, manual = true, allowDel = allowDel)
            start(ctx)
        }
        fun start(ctx: Context) {
            self?.let { it.kick(); return }
            try { ctx.startForegroundService(Intent(ctx, SyncService::class.java)) }
            catch (e: Exception) { SyncSchedule.soon(ctx, 1000) }   // 배경에서 켤 수 없음 → 예약 작업으로
        }
        /** 짝이나 감시 설정이 바뀌었을 때 */
        fun refresh(ctx: Context) {
            SyncSchedule.schedule(ctx)
            if (watchWanted(ctx)) start(ctx) else self?.reconfigure()
        }
    }

    private val lock = Object()
    @Volatile private var quit = false
    @Volatile private var gen = 0
    private val h = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH, "폴더 동기화", NotificationManager.IMPORTANCE_LOW))
        tick()
    }

    @Volatile private var looping = false
    @Volatile private var lastStart = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(ID, note())
        if (intent?.action == "unwatch") Prefs(this).putBool("sync_watch", false)
        self = this
        if (!looping) { looping = true; Thread { loop() }.start() }
        reconfigure()
        return START_STICKY
    }

    override fun onDestroy() { quit = true; gen++; self = null; h.removeCallbacksAndMessages(null); kick(); super.onDestroy() }

    fun kick() = synchronized(lock) { lock.notifyAll() }

    /** 기다리는 짝을 돌리고, 감시가 필요 없으면 끝냄 */
    private fun loop() {
        while (!quit) {
            if (Sync.pendingIds(this).isNotEmpty()) {
                Thread.sleep(800)   // 한꺼번에 생기는 변경을 묶음
                if (!Sync.drain(this, { quit })) Thread.sleep(2000)   // 예약 작업이 돌리는 중 → 끝나면 이어서
                SyncSchedule.schedule(this)
                continue
            }
            if (!watchWanted(this)) break
            synchronized(lock) { lock.wait(60_000) }
        }
        self = null; looping = false
        val id = lastStart
        h.post { if (stopSelfResult(id)) stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    /** NAS 변경 기다리기: 실시간 짝이 있는 연결 폴더마다 하나씩 */
    fun reconfigure() {
        val g = ++gen
        kick()
        if (quit) return
        if (!watchWanted(this)) return
        val roots = Sync.pairs(this).filter { it.on && it.mode == Sync.REALTIME && !it.web && it.dir != Sync.UP }.map { it.root }.distinct()
        for (root in roots) Thread { watch(root, g) }.start()
    }

    private fun watch(root: String, g: Int) {
        val api = Prefs(this).api()
        var epoch = ""; var seq = -1L
        while (g == gen && !quit) {
            try {
                if (seq < 0) {
                    val ri = api.roots().firstOrNull { it.name == root } ?: return
                    epoch = ri.epoch; seq = ri.seq
                }
                val (reset, s, paths) = api.changes(root, epoch, seq, 50)
                if (g != gen) return
                val ps = Sync.pairs(this).filter { it.on && it.mode == Sync.REALTIME && it.root == root && it.dir != Sync.UP }
                if (reset) { seq = -1; Sync.request(this, ps.map { it.id }, false); kick(); continue }
                seq = s
                val hit = ps.filter { SyncSchedule.hits(it, paths) }
                if (paths.isNotEmpty()) android.util.Log.i("MydriveSync", "NAS 변경 $root: ${paths.take(5)} → ${hit.size}개 짝")
                if (hit.isNotEmpty()) { Thread.sleep(1500); Sync.request(this, hit.map { it.id }, false); kick() }
            } catch (e: Exception) {
                seq = -1
                try { Thread.sleep(20_000) } catch (x: InterruptedException) { }
            }
        }
    }

    /** 알림: 도는 중이면 진행, 아니면 '실시간 감시 중' (1초마다 고침) */
    private fun tick() {
        if (quit) return
        try { getSystemService(NotificationManager::class.java).notify(ID, note()) } catch (e: Exception) { }
        h.postDelayed({ tick() }, 1000)
    }

    private fun note(): Notification {
        val open = PendingIntent.getActivity(this, 3, Intent(this, SyncActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH).setSmallIcon(R.drawable.ic_stat).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
        val c = Sync.cur
        val p = c?.let { x -> Sync.pairs(this).firstOrNull { it.id == x.id } }
        if (c != null && p != null) {
            b.setContentTitle("동기화 중 · ${p.name}" + if (c.total > 0) "  ${c.done}/${c.total}" else "")
                .setContentText(c.phase + if (c.file.isNotEmpty()) " · ${c.file}" else "")
            if (c.size > 0) b.setProgress(1000, (c.bytes * 1000 / c.size).toInt(), false) else if (c.total > 0) b.setProgress(c.total, c.done, false) else b.setProgress(0, 0, true)
        } else {
            val n = Sync.pairs(this).count { it.on && it.mode == Sync.REALTIME }
            b.setContentTitle(if (watchWanted(this)) "실시간 동기화 켜짐 · ${n}개 폴더" else "동기화 준비 중")
                .setContentText("NAS나 휴대폰에서 바뀌면 바로 맞춥니다")
            if (watchWanted(this)) b.addAction(Notification.Action.Builder(null, "NAS 감시 끄기",
                PendingIntent.getService(this, 4, Intent(this, SyncService::class.java).setAction("unwatch"), PendingIntent.FLAG_IMMUTABLE)).build())
        }
        return b.build()
    }
}

class SyncBoot : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        SyncSchedule.schedule(ctx)
        if (SyncService.watchWanted(ctx)) try { ctx.startForegroundService(Intent(ctx, SyncService::class.java)) } catch (e: Exception) { }
    }
}

/** 문제 알림 (실패·지우기 멈춤) — 눌러서 동기화 화면으로 */
object SyncNote {
    fun problem(ctx: Context, p: Sync.Link, msg: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sync_err", "동기화 문제", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, SyncActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(("sync" + p.id).hashCode(), Notification.Builder(ctx, "sync_err").setSmallIcon(R.drawable.ic_stat).setContentIntent(open).setAutoCancel(true)
            .setContentTitle("동기화 · ${p.name}").setContentText(msg).setStyle(Notification.BigTextStyle().bigText(msg)).build())
    }
}
