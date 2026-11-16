package kr.artchijun.myphoto

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
import android.os.IBinder
import android.provider.MediaStore

/**
 * 백업을 언제 돌릴지 (앱이 꺼져 있어도):
 *   - 새 사진이 생기면 (사진 저장소가 바뀌면) 잠시 뒤
 *   - 6시간마다 한 번 (빠진 것 줍기)
 *   - 휴대폰을 켰을 때·앱을 새로 깔았을 때 다시 예약
 * 돌리는 일 자체는 시스템이 준 시간 안에서 하고, 시간이 다 되면 시스템이 다시 불러 이어서 함.
 * 앱에서 [지금 백업]을 누르면 BackupService 가 알림을 띄우고 끝까지 돌림.
 */
object BackupSchedule {
    private const val JOB_CONTENT = 11
    private const val JOB_PERIODIC = 12
    private const val JOB_NOW = 13

    private fun net(ctx: Context) = if (Backup.Settings(ctx).wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY

    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        val st = Backup.Settings(ctx)
        if (!st.on) { js.cancel(JOB_CONTENT); js.cancel(JOB_PERIODIC); js.cancel(JOB_NOW); return }
        val cn = ComponentName(ctx, BackupJob::class.java)
        // 새 사진 감시 (한 번 불리면 끝나므로 돌 때마다 다시 예약)
        js.schedule(JobInfo.Builder(JOB_CONTENT, cn)
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(15_000).setTriggerContentMaxDelay(120_000)
            .setRequiredNetworkType(net(ctx)).setRequiresCharging(st.chargingOnly).build())
        if (js.getPendingJob(JOB_PERIODIC) == null)
            js.schedule(JobInfo.Builder(JOB_PERIODIC, cn).setPeriodic(6 * 3600_000L).setPersisted(true)
                .setRequiredNetworkType(net(ctx)).setRequiresCharging(st.chargingOnly).build())
    }

    /** 조건(와이파이·충전)이 맞으면 곧 돌리기 — 망이 끊겨 멈췄을 때 다시 잇기용 */
    fun soon(ctx: Context) {
        if (!Backup.Settings(ctx).on) return
        ctx.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(JOB_NOW, ComponentName(ctx, BackupJob::class.java))
            .setRequiredNetworkType(net(ctx)).setRequiresCharging(Backup.Settings(ctx).chargingOnly)
            .setMinimumLatency(30_000).setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
}

class BackupJob : JobService() {
    @Volatile private var stop = false
    private var worker: Thread? = null

    override fun onStartJob(p: JobParameters): Boolean {
        stop = false
        worker = Thread {
            val done = try { Backup.run(this, { stop }) { pr -> Notes.progress(this, pr) } } catch (e: Exception) { false }
            BackupSchedule.schedule(this)   // 새 사진 감시 다시 걸기
            if (!done && !stop) BackupSchedule.soon(this)
            Notes.summary(this)
            jobFinished(p, false)
        }.apply { start() }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        stop = true   // 시스템이 시간을 거둠 → 지금 파일은 버리고 다음에 이어서
        return true
    }
}

/** [지금 백업]: 알림을 띄운 채 끝까지 (앱을 닫아도 계속) */
class BackupService : Service() {
    companion object {
        @Volatile var stopAsked = false
        fun start(ctx: Context) { stopAsked = false; ctx.startForegroundService(Intent(ctx, BackupService::class.java)) }
    }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopAsked = true; return START_NOT_STICKY }
        startForeground(Notes.ID_RUN, Notes.running(this, null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Thread {
            val done = try { Backup.run(this, { stopAsked }) { pr -> Notes.update(this, pr) } } catch (e: Exception) { false }
            if (!done && !stopAsked) BackupSchedule.soon(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            Notes.summary(this)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { BackupSchedule.schedule(ctx) }
}

/** 알림: 백업 중(진행) · 끝(요약) */
object Notes {
    const val ID_RUN = 1
    private const val ID_SUM = 2
    private const val CH = "backup"
    private var last = 0L

    private fun channel(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CH, "사진 백업", NotificationManager.IMPORTANCE_LOW))

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, BackupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    fun running(ctx: Context, p: Backup.Progress?): Notification {
        channel(ctx)
        val stop = PendingIntent.getService(ctx, 1, Intent(ctx, BackupService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setOngoing(true).setContentIntent(open(ctx))
            .setContentTitle(if (p == null) "사진 백업 준비 중" else "사진 백업 ${p.done}/${p.total}")
            .setContentText(p?.name ?: "")
            .addAction(Notification.Action.Builder(null, "멈추기", stop).build())
        if (p != null) b.setProgress(1000, if (p.size > 0) (p.bytes * 1000 / p.size).toInt() else 0, false)
        return b.build()
    }

    /** 너무 자주 고치지 않게 (0.7초에 한 번) */
    fun update(ctx: Context, p: Backup.Progress?) {
        val now = System.currentTimeMillis()
        if (p != null && now - last < 700) return
        last = now
        ctx.getSystemService(NotificationManager::class.java).notify(ID_RUN, running(ctx, p))
    }

    /** 배경 백업 중: 알림을 띄우되 밀어서 끌 수 있게 */
    fun progress(ctx: Context, p: Backup.Progress?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (p == null) { nm.cancel(ID_RUN); return }
        val now = System.currentTimeMillis()
        if (now - last < 1000) return
        last = now
        channel(ctx)
        nm.notify(ID_RUN, Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setContentIntent(open(ctx)).setOnlyAlertOnce(true)
            .setContentTitle("사진 백업 ${p.done}/${p.total}").setContentText(p.name)
            .setProgress(1000, if (p.size > 0) (p.bytes * 1000 / p.size).toInt() else 0, false).build())
    }

    /** 끝나면: 올린 것이 있거나 실패가 있을 때만 조용히 요약 */
    fun summary(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_RUN)
        val c = Backup.counts(ctx)
        val st = Backup.Settings(ctx)
        if (c.failed == 0 && !st.lastMsg.startsWith("끝 — 올림") ) return
        if (st.lastMsg.startsWith("끝 — 올림 0 ") && c.failed == 0) return
        channel(ctx)
        nm.notify(ID_SUM, Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat).setContentIntent(open(ctx)).setAutoCancel(true)
            .setContentTitle(if (c.failed > 0) "사진 백업: 실패 ${c.failed}개" else "사진 백업 완료")
            .setContentText(st.lastMsg + (if (c.waiting > 0) " · 남음 ${c.waiting}" else "")).build())
    }
}
