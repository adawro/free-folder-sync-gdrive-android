package pl.adamw.drivesync

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
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
import java.time.Duration
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = sync()

    /** Wymagane dla zadań expedited na Androidzie 10-11 (tam działają jako usługa pierwszoplanowa). */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext.getString(R.string.notif_checking), 0)

    private suspend fun sync(): Result = withContext(Dispatchers.IO) {
        val settings = Settings(applicationContext)
        settings.lastAttemptAt = System.currentTimeMillis()
        val watch = inputData.getBoolean(KEY_WATCH, false)
        // Czuwanie bierze aktualnie zaznaczone foldery (mogły się zmienić od zaplanowania zadania)
        val only = if (watch) settings.pairs.filter { it.watch }.map { it.id }.toSet().ifEmpty { return@withContext Result.success() }
            else inputData.getLongArray(KEY_PAIRS)?.toSet()
        val (result, error) = runSync(settings, only)
        settings.lastError = error  // do pokazania na ekranie stanu (null = udało się)
        if (!watch) return@withContext result
        // Wysyłka od razu: czuwanie ustawiamy od nowa (wyzwalacz działa raz). Błędy nadrobi
        // codzienna wysyłka - ponawianie tego zadania czekałoby i tak na kolejną zmianę plików.
        if (!settings.needsSignIn) armWatch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        Result.success()
    }

    private suspend fun runSync(settings: Settings, only: Set<Long>?): Pair<Result, String?> {
        val db = Db.get(applicationContext)
        var foreground = false
        var lastNotify = 0L
        val engine = SyncEngine(applicationContext) { file, sent, total ->
            val now = System.currentTimeMillis()
            if (!foreground || now - lastNotify > 1000 || sent == total) {
                lastNotify = now
                val pct = if (total > 0) (sent * 100 / total).toInt() else 100
                setProgress(workDataOf("file" to file, "pct" to pct))
                // Usługa pierwszoplanowa dopiero, gdy jest co wysłać (inaczej powiadomienie przy każdym sprawdzeniu)
                runCatching { setForeground(foregroundInfo(file, pct)) }
                foreground = true
            }
        }
        try {
            val summary = engine.run(only)
            settings.needsSignIn = false
            if (summary.uploaded > 0 || summary.failed > 0) {
                db.log(applicationContext.getString(R.string.log_summary, summary.uploaded, summary.failed, summary.pending) +
                    applicationContext.getString(R.string.log_requests, engine.requests), error = summary.failed > 0)
            }
            if (summary.alreadyThere > 0) db.log(applicationContext.getString(R.string.log_already_there, summary.alreadyThere))
            if (only == null) settings.pendingCount = summary.pending  // licznik dotyczy wszystkich folderów
            // Nowy plik jest jeszcze zapisywany (wysyłamy dopiero po 2 min bez zmian) - sprawdź za chwilę,
            // zamiast czekać do nocy. Najwyżej FOLLOW_UPS razy, gdyby plik zmieniał się bez końca.
            val followUp = inputData.getInt(KEY_FOLLOW_UP, 0)
            if (only != null && summary.waiting > 0 && followUp < FOLLOW_UPS) {
                checkAgain(applicationContext, only, followUp + 1)
            }
            return if (summary.failed > 0) {
                Result.retry() to applicationContext.getString(R.string.log_summary, summary.uploaded, summary.failed, summary.pending)
            } else {
                Result.success() to null
            }
        } catch (e: NeedsSignInException) {
            settings.needsSignIn = true
            val msg = applicationContext.getString(R.string.log_needs_sign_in)
            db.log(msg, error = true)
            notifySignIn()
            return Result.failure() to msg
        } catch (e: DriveException) {
            val msg = applicationContext.getString(if (e.isRateLimit) R.string.log_rate_limited else R.string.log_network_error, e.message) +
                applicationContext.getString(R.string.log_requests, engine.requests)
            db.log(msg, error = true)
            return Result.retry() to msg
        } catch (e: IOException) {
            val msg = applicationContext.getString(R.string.log_network_error, e.message)
            db.log(msg, error = true)
            return Result.retry() to msg
        } catch (e: IllegalStateException) {
            val msg = e.message ?: applicationContext.getString(R.string.log_config_error)
            db.log(msg, error = true)
            return Result.failure() to msg
        } catch (e: Exception) {
            val msg = applicationContext.getString(R.string.log_unexpected, e.toString())  // np. cofnięty dostęp do folderu
            db.log(msg, error = true)
            return Result.failure() to msg
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
        /** Czekające zadanie „wysyłka od razu” (wyzwalane zmianą plików w pamięci telefonu). */
        private const val WATCH = "sync-watch"
        private const val CHECK_AGAIN = "sync-check-again"
        /** Wszystkie zadania wysyłki - ekran po tym znaczniku pokazuje postęp. */
        const val TAG = "sync"
        private const val KEY_PAIRS = "pairs"
        private const val KEY_WATCH = "watch"
        private const val KEY_FOLLOW_UP = "follow_up"
        private const val FOLLOW_UPS = 10

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
            armWatch(context)
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pending = alarmIntent(context)
            if (s.pairs.isEmpty()) {
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
                .addTag(TAG)
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

        /**
         * Ręcznie z przycisku: tylko wymóg sieci (zgodny z ustawieniami), bez wymogu ładowania.
         * pair = jeden folder (przycisk przy folderze), null = wszystkie.
         * REPLACE: poprzednia ręczna próba po błędzie czeka na ponowienie (nawet godzinami) - KEEP ignorowałby kliknięcie.
         * Trwającej wysyłki to nie przerywa, bo przyciski są wtedy wyłączone.
         */
        fun syncNow(context: Context, pair: Long? = null) {
            val s = Settings(context)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkType(s))
                        .build()
                )
                .apply { if (pair != null) setInputData(workDataOf(KEY_PAIRS to longArrayOf(pair))) }
                .build()
            val name = if (pair == null) NOW else "$NOW-$pair"
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Wysyłka od razu dla folderów z zaznaczonym watch: zadanie czeka, aż w pamięci telefonu pojawi się
         * albo zmieni plik. Każdy zapis przez system plików trafia do MediaStore (Android 11+), więc wyzwalacz
         * na MediaStore łapie też pliki z folderów wybranych przez SAF - także tych innych aplikacji (np. Signala).
         * Wyzwalacz nie mówi, który folder się zmienił; zadanie sprawdza wszystkie zaznaczone foldery
         * (bez nowych plików kończy się na liście plików, bez zapytań do Drive).
         * KEEP: wołane przy każdym otwarciu aplikacji - czekającego zadania nie ruszamy. Po zmianie sieci
         * albo „tylko podczas ładowania” REPLACE (constraintsChanged), bo warunki są zapisane w zadaniu.
         */
        fun armWatch(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val s = Settings(context)
            val wm = WorkManager.getInstance(context)
            if (s.pairs.none { it.watch }) {
                wm.cancelUniqueWork(WATCH)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType(s))
                .setRequiresCharging(s.chargingOnly)
                .addContentUriTrigger(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                .addContentUriTrigger(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), true)
                // Seria zmian (np. kopiowanie wielu plików) = jedno uruchomienie
                .setTriggerContentUpdateDelay(Duration.ofSeconds(30))
                .setTriggerContentMaxDelay(Duration.ofMinutes(2))
                .build()
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG)
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_WATCH to true))
                .build()
            wm.enqueueUniqueWork(WATCH, policy, request)
        }

        fun constraintsChanged(context: Context) = armWatch(context, ExistingWorkPolicy.REPLACE)

        /**
         * Ponowne sprawdzenie folderów za kilka minut, gdy nowy plik był jeszcze zapisywany.
         * APPEND_OR_REPLACE: wołane także z trwającego sprawdzenia - REPLACE przerwałoby je samo.
         */
        private fun checkAgain(context: Context, pairs: Set<Long>, followUp: Int) {
            val s = Settings(context)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(TAG)
                .setConstraints(constraints(s))
                .setInitialDelay(3, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_PAIRS to pairs.toLongArray(), KEY_FOLLOW_UP to followUp))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHECK_AGAIN, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
