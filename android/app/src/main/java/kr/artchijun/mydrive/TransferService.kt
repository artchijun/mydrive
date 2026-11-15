package kr.artchijun.mydrive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

// 옮기는 동안만 켜지는 서비스: 알림(진행률)을 띄워 다른 앱으로 가도 올리기·받기가 이어지게
class TransferService : Service() {
    companion object {
        private const val CH = "transfers"
        private const val ID = 1
        private var self: TransferService? = null

        fun start(ctx: Context) {
            if (self?.foreground == true) return
            try { ctx.startForegroundService(Intent(ctx, TransferService::class.java)) } catch (e: Exception) { /* 화면이 켜져 있으면 알림 없이도 계속됨 */ }
        }
        fun refresh() { self?.update() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "올리기·받기", NotificationManager.IMPORTANCE_LOW))
        self = this
    }

    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 로 켜면 반드시 먼저 startForeground — 그 전에 멈추면 안드로이드가 앱을 끔 (일이 아주 빨리 끝난 경우)
        startForeground(ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        foreground = true
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() { self = null; super.onDestroy() }

    private fun build(): Notification {
        val j = Transfers.current
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (j == null) return b.setContentTitle("Mydrive").setContentText("준비 중").build()
        val what = when (j.kind) { Transfers.Kind.UP -> "올리는 중"; else -> "받는 중" }
        val more = Transfers.waiting.let { if (it > 0) " · 기다리는 것 ${it}개" else "" }
        b.setContentTitle("$what: ${j.title}").setContentText(Fmt.size(j.done) + " / " + Fmt.size(j.total) + more)
        if (j.total > 0) b.setProgress(1000, (j.done * 1000 / j.total).toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    fun update() {
        if (!foreground) return   // 아직 onStartCommand 전: 거기서 startForeground 한 뒤 다시 봄
        if (!Transfers.busy) { foreground = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        getSystemService(NotificationManager::class.java).notify(ID, build())
    }
}
