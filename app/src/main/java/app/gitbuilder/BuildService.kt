package app.gitbuilder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import java.util.concurrent.atomic.AtomicBoolean

/** Serviço em primeiro plano: mantém a compilação viva com o app fechado e mostra o andamento na notificação. */
class BuildService : Service() {
    companion object {
        const val ACTION_RESUME = "app.gitbuilder.RESUME"
        private const val CH_RUN = "run"
        private const val CH_DONE = "done"
        private const val ID_RUN = 1
        private const val ID_DONE = 2
    }

    private val h = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    private var wake: PowerManager.WakeLock? = null
    private var worker: Thread? = null
    private var session: BuildSession? = null
    private var listener: (() -> Unit)? = null
    @Volatile private var done = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        val s = Sessions.current
        // Obrigatório logo após startForegroundService
        startForeground(ID_RUN, runNotification(s), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (s == null || !s.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (worker?.isAlive == true) return START_NOT_STICKY   // já acompanhando

        session = s
        val l: () -> Unit = { scheduleUpdate() }
        listener = l
        s.addListener(l)
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gitbuilder:build").apply { acquire(3 * 60 * 60 * 1000L) }
        val resume = intent?.action == ACTION_RESUME
        worker = Thread {
            try { if (resume) s.runResume() else s.runNew() } catch (e: Exception) { }
            h.post { finishService(s) }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun scheduleUpdate() {
        if (pending.compareAndSet(false, true)) h.postDelayed({
            pending.set(false)
            if (!done) getSystemService(NotificationManager::class.java).notify(ID_RUN, runNotification(session))
        }, 1000)
    }

    private fun finishService(s: BuildSession) {
        done = true
        listener?.let { s.removeListener(it) }
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        stopForeground(STOP_FOREGROUND_REMOVE)
        val ok = s.meta.optString("result") == "success"
        val title = s.finalStatus ?: "Compilação finalizada"
        val text = s.meta.optString("zipName") + if (ok && s.artifactId > 0) " — toque para baixar o APK" else " — toque para ver os logs"
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(s.id)).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(ID_DONE, n)
        stopSelf()
    }

    private fun openIntent(id: String?): PendingIntent {
        val i = Intent(this, BuildActivity::class.java).putExtra("history_id", id ?: "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, (id ?: "").hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun runNotification(s: BuildSession?): Notification {
        val b = Notification.Builder(this, CH_RUN)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (s == null) return b.setContentTitle("GitBuilder").setContentText("Preparando…").build()
        val line = s.currentLine()
        val (doneCount, total) = s.progress()
        return b.setWhen(s.meta.optLong("startedAt")).setUsesChronometer(true).setShowWhen(true)
            .setSubText("$doneCount/$total etapas")
            .setContentTitle("Compilando ${s.meta.optString("zipName")}")
            .setContentText(line).setStyle(Notification.BigTextStyle().bigText(line))
            .setProgress(total, doneCount, false)
            .setContentIntent(openIntent(s.id)).build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "Compilação em andamento", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Resultado da compilação", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onDestroy() {
        try { if (wake?.isHeld == true) wake?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}
