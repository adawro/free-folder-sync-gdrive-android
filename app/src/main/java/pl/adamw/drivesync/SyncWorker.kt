package pl.adamw.drivesync

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = sync()

    /** Wymagane dla zadań expedited na Androidzie 10-11 (tam działają jako usługa pierwszoplanowa). */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext.getString(R.string.notif_checking), 0)

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
        /** Wysyłka uruchomiona przez budzik o godzinie z ustawień. */
        const val SCHEDULED = "sync-scheduled"
        private const val OLD_NIGHTLY = "sync-nightly"    // wersje 0.2-0.4: opóźnione zadanie WorkManagera
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
         * Ustawia budzik na najbliższą godzinę z ustawień (setExactAndAllowWhileIdle - działa także w Doze).
         * Bez zgody na dokładne budziki: budzik przybliżony (Android może go przesunąć).
         * Wołane po każdej zmianie ustawień i przy starcie aplikacji - nowy budzik zastępuje poprzedni.
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(OLD_PERIODIC)
            // Stare opóźnione zadanie (sprzed budzika) - tylko jeśli jeszcze czeka, nie przerywamy trwającej wysyłki
            val old = wm.getWorkInfosForUniqueWork(OLD_NIGHTLY)
            old.addListener({
                if (runCatching { old.get() }.getOrDefault(emptyList()).any { it.state == WorkInfo.State.ENQUEUED }) {
                    wm.cancelUniqueWork(OLD_NIGHTLY)
                }
            }, Runnable::run)
            val s = Settings(context)
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = alarmIntent(context)
            if (s.treeUri == null || s.drivePath.isEmpty()) {
                alarms.cancel(pending)
                return
            }
            val at = nextRun(s).timeInMillis
            if (canScheduleExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            s.scheduledAt = at
        }

        /** Android 12+: zgoda „Alarmy i przypomnienia” (na Androidzie 14+ domyślnie wyłączona). */
        fun canScheduleExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, SyncAlarmReceiver::class.java).setAction(SyncAlarmReceiver.ACTION_RUN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /**
         * Wysyłka z budzika: zadanie expedited (system uruchamia je od razu, także w Doze, z dostępem do sieci).
         * Gdy warunki nie są spełnione (np. brak sieci), czeka i rusza, gdy tylko będą.
         */
        fun runScheduled(context: Context) {
            val s = Settings(context)
            val builder = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints(s))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            // Zadania expedited mogą wymagać tylko sieci - przy „tylko podczas ładowania” zwykłe zadanie
            if (!s.chargingOnly) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            WorkManager.getInstance(context).enqueueUniqueWork(SCHEDULED, ExistingWorkPolicy.KEEP, builder.build())
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
