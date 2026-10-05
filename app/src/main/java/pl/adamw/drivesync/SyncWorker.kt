package pl.adamw.drivesync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = sync()
        // Nocne zadanie samo planuje następną noc (po retry WorkManager powtórzy to samo zadanie)
        if (NIGHTLY in tags && result !is Result.Retry) schedule(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return result
    }

    private suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val db = Db.get(applicationContext)
        val settings = Settings(applicationContext)
        var foreground = false
        var lastNotify = 0L
        try {
            val summary = SyncEngine(applicationContext) { file, sent, total ->
                val now = System.currentTimeMillis()
                if (!foreground || now - lastNotify > 1000 || sent == total) {
                    lastNotify = now
                    val pct = if (total > 0) (sent * 100 / total).toInt() else 100
                    setProgress(workDataOf("file" to file, "pct" to pct))
                    // Usługa pierwszoplanowa dopiero, gdy jest co wysłać (inaczej powiadomienie przy każdym sprawdzeniu)
                    runCatching { setForeground(foregroundInfo(file, pct)) }
                    foreground = true
                }
            }.run()
            settings.needsSignIn = false
            if (summary.uploaded > 0 || summary.failed > 0) {
                db.log(applicationContext.getString(R.string.log_summary, summary.uploaded, summary.failed, summary.pending),
                    error = summary.failed > 0)
            }
            if (summary.failed > 0) Result.retry() else Result.success()
        } catch (e: NeedsSignInException) {
            settings.needsSignIn = true
            db.log(applicationContext.getString(R.string.log_needs_sign_in), error = true)
            notifySignIn()
            Result.failure()
        } catch (e: IOException) {
            db.log(applicationContext.getString(R.string.log_network_error, e.message), error = true)
            Result.retry()
        } catch (e: IllegalStateException) {
            db.log(e.message ?: applicationContext.getString(R.string.log_config_error), error = true)
            Result.failure()
        } catch (e: Exception) {
            db.log(applicationContext.getString(R.string.log_unexpected, e.toString()), error = true)  // np. cofnięty dostęp do folderu
            Result.failure()
        }
    }

    private fun foregroundInfo(file: String, pct: Int): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(applicationContext.getString(R.string.notif_uploading))
            .setContentText(file)
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFY_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifySignIn() {
        ensureChannel(applicationContext)
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(applicationContext.getString(R.string.notif_sign_in_title))
            .setContentText(applicationContext.getString(R.string.notif_sign_in_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFY_SIGN_IN, n) }
    }

    companion object {
        private const val CHANNEL = "sync"
        private const val NOTIFY_PROGRESS = 1
        private const val NOTIFY_SIGN_IN = 2
        const val NIGHTLY = "sync-nightly"
        private const val OLD_PERIODIC = "sync-periodic"  // wersja 0.1: co godzinę
        const val NOW = "sync-now"

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
            )
        }

        /** Wi-Fi = sieć bez limitu, dane komórkowe = sieć z limitem (METERED); obie = dowolna poza roamingiem. */
        private fun networkType(s: Settings) = when {
            s.allowWifi && s.allowMobile -> NetworkType.NOT_ROAMING
            s.allowMobile -> NetworkType.METERED
            else -> NetworkType.UNMETERED
        }

        private fun constraints(s: Settings) = Constraints.Builder()
            .setRequiredNetworkType(networkType(s))
            .setRequiresCharging(s.chargingOnly)
            .build()

        /**
         * Raz na dobę o godzinie z ustawień (jednorazowe zadanie z opóźnieniem do najbliższej takiej godziny,
         * po wykonaniu planuje kolejne). Gdy o tej godzinie warunki nie są spełnione (np. brak Wi-Fi), wysyłka nastąpi, gdy tylko będą.
         * Wołane po każdej zmianie ustawień - REPLACE podmienia warunki.
         */
        fun schedule(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(OLD_PERIODIC)
            val s = Settings(context)
            if (s.treeUri == null || s.drivePath.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(nextRun(s).timeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(constraints(s))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(NIGHTLY)
                .build()
            wm.enqueueUniqueWork(NIGHTLY, policy, request)
        }

        fun nextRun(s: Settings): Calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, s.syncTimeMinutes / 60); set(Calendar.MINUTE, s.syncTimeMinutes % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            // Godzina już minęła (z zapasem, żeby właśnie wykonane zadanie nie zaplanowało się na ten sam dzień)
            if (timeInMillis <= System.currentTimeMillis() + 60_000) add(Calendar.DAY_OF_MONTH, 1)
        }

        /** Ręcznie z przycisku: tylko wymóg sieci (zgodny z ustawieniami), bez wymogu ładowania. */
        fun syncNow(context: Context) {
            val s = Settings(context)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType(s))
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }
    }
}
