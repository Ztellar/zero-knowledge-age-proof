package cl.zkage.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * Runs [ProofJob] as a foreground service, so the proof survives the user leaving the app
 * or turning the screen off. Type `specialUse`: none of the standard types describes an
 * on-device cryptographic proof, `shortService` stops at 3 minutes (too tight for slower
 * phones) and `dataSync` is for moving data.
 */
class ProverService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Generación de la prueba", NotificationManager.IMPORTANCE_LOW),
        )
        // The `specialUse` type exists from API 34; older versions take no type here.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ONGOING_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ONGOING_ID, ongoing())
        }

        if (worker?.isAlive != true) {
            worker = thread(name = "prover") {
                // The CPU must keep running with the screen off; 10 minutes caps a stuck run.
                val lock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zkage:prover")
                lock.acquire(10 * 60 * 1000L)
                val result = try {
                    ProofJob.runPending(this)
                } finally {
                    lock.release()
                }
                manager.notify(DONE_ID, finished(result))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // If the system kills the process, the chip data is gone: do not restart.
        return START_NOT_STICKY
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoing(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setContentTitle("Generando la prueba de edad")
        .setContentText("Tarda entre uno y tres minutos. Puedes salir de la app.")
        .setUsesChronometer(true)
        .setProgress(0, 0, true)
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    private fun finished(result: ProofJob.State): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setContentTitle(if (result is ProofJob.State.Done) "Prueba generada" else "No se pudo generar la prueba")
        .setContentText(
            when (result) {
                is ProofJob.State.Done -> "Lista en ${"%.0f".format(result.seconds)} s. Toca para verla."
                is ProofJob.State.Failed -> result.message
                else -> ""
            },
        )
        .setAutoCancel(true)
        .setContentIntent(openApp())
        .build()

    private companion object {
        const val CHANNEL = "prover"
        const val ONGOING_ID = 1
        const val DONE_ID = 2
    }
}
