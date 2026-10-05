package pl.adamw.drivesync

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

@Composable
private fun Screen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as ComponentActivity
    val settings = remember { Settings(context) }
    val db = remember { Db.get(context) }
    val scope = rememberCoroutineScope()

    // Licznik wymuszający odświeżenie widoku po zmianach ustawień / logu
    var refresh by remember { mutableIntStateOf(0) }
    var signInError by remember { mutableStateOf<String?>(null) }
    var drivePath by remember { mutableStateOf(settings.drivePath) }
    var clientId by remember { mutableStateOf(settings.customClientId) }
    var clientSecret by remember { mutableStateOf(CustomOAuth.clientSecret(context).orEmpty()) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data) }
            .onSuccess { settings.needsSignIn = false; signInError = null; SyncWorker.schedule(context) }
            .onFailure { signInError = context.getString(R.string.connect_failed, it.message) }
        refresh++
    }

    fun signIn() = scope.launch {
        runCatching { Auth.authorize(context) }
            .onSuccess { r ->
                if (r.hasResolution()) {
                    consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                } else {
                    settings.needsSignIn = false
                    signInError = null
                    SyncWorker.schedule(context)
                    refresh++
                }
            }
            .onFailure { signInError = context.getString(R.string.connect_failed, it.message) }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.treeUri = uri
        if (settings.drivePath.isEmpty()) {
            settings.drivePath = context.getString(R.string.default_drive_parent) + "/" + (LocalFiles.treeName(context, uri) ?: "folder")
            drivePath = settings.drivePath
        }
        SyncWorker.schedule(context)
        refresh++
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }

    // Stan zadań WorkManagera (ręczne + okresowe)
    val now by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.NOW).collectAsState(emptyList())
    val periodic by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(SyncWorker.NIGHTLY).collectAsState(emptyList())
    val running = (now + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING }

    // Log i liczniki z bazy (co 2 s, bo zapisuje je worker)
    var log by remember { mutableStateOf(emptyList<LogEntry>()) }
    var uploadedCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        while (true) {
            withContext(Dispatchers.IO) {
                log = db.recentLog(40)
                uploadedCount = db.uploadedCount()
            }
            delay(2000)
        }
    }

    val treeName = remember(refresh) { settings.treeUri?.let { LocalFiles.treeName(context, it) } }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()) }

    Column(
        Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.app_subtitle),
            style = MaterialTheme.typography.bodySmall)

        Section(stringResource(R.string.section_account)) {
            val connected = !settings.needsSignIn.also { refresh }
            Text(stringResource(if (connected) R.string.connected else R.string.not_connected))
            signInError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (BuildConfig.BUILTIN_AUTH) {
                Toggle(stringResource(R.string.builtin_auth), settings.useBuiltinAuth.also { refresh }) {
                    settings.useBuiltinAuth = it
                    settings.needsSignIn = true
                    signInError = null
                    refresh++
                }
            }
            if (settings.useBuiltinAuth) {
                Button(onClick = { signIn() }) { Text(stringResource(if (connected) R.string.reconnect else R.string.connect)) }
            } else {
                Text(
                    stringResource(R.string.custom_client_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it }, enabled = !connected,
                    label = { Text(stringResource(R.string.client_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSecret, onValueChange = { clientSecret = it }, enabled = !connected,
                    label = { Text(stringResource(R.string.client_secret)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                // Po zalogowaniu pola są zablokowane; zmiana klienta dopiero po wylogowaniu
                if (connected) {
                    OutlinedButton(onClick = { CustomOAuth.signOut(context); refresh++ }) {
                        Text(stringResource(R.string.sign_out))
                    }
                } else {
                    Button(
                        onClick = {
                            CustomOAuth.saveClient(context, clientId, clientSecret)
                            signInError = null
                            CustomOAuth.signIn(context) { error ->
                                signInError = error
                                if (error == null) SyncWorker.schedule(context)
                                refresh++
                            }
                        },
                        enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
                    ) { Text(stringResource(R.string.sign_in_browser)) }
                }
            }
        }

        Section(stringResource(R.string.section_phone_folder)) {
            Text(treeName ?: stringResource(R.string.not_selected))
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(stringResource(R.string.choose_folder)) }
        }

        Section(stringResource(R.string.section_drive_folder)) {
            OutlinedTextField(
                value = drivePath,
                onValueChange = { drivePath = it },
                label = { Text(stringResource(R.string.drive_path_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (drivePath.trim('/', ' ') != settings.drivePath) {
                Button(onClick = {
                    settings.drivePath = drivePath
                    drivePath = settings.drivePath
                    SyncWorker.schedule(context)
                    refresh++
                }) { Text(stringResource(R.string.save)) }
            }
        }

        Section(stringResource(R.string.section_when)) {
            Text(stringResource(R.string.upload_via), style = MaterialTheme.typography.bodyMedium)
            Toggle(stringResource(R.string.wifi), settings.allowWifi.also { refresh }) {
                settings.allowWifi = it
                if (!it) settings.allowMobile = true  // co najmniej jedna sieć
                SyncWorker.schedule(context); refresh++
            }
            Toggle(stringResource(R.string.mobile_data), settings.allowMobile.also { refresh }) {
                settings.allowMobile = it
                if (!it) settings.allowWifi = true
                SyncWorker.schedule(context); refresh++
            }
            Toggle(stringResource(R.string.charging_only), settings.chargingOnly.also { refresh }) {
                settings.chargingOnly = it; SyncWorker.schedule(context); refresh++
            }
            val time = settings.syncTimeMinutes.also { refresh }
            val hhmm = "%d:%02d".format(time / 60, time % 60)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.daily_at), Modifier.weight(1f))
                OutlinedButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        settings.syncTimeMinutes = h * 60 + m
                        SyncWorker.schedule(context)
                        refresh++
                    }, time / 60, time % 60, true).show()
                }) { Text(hhmm) }
            }
            Text(
                stringResource(R.string.next_run, fmt.format(SyncWorker.nextRun(settings).time), hhmm),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section(stringResource(R.string.section_status)) {
            Text(stringResource(R.string.uploaded_count, uploadedCount))
            val last = settings.lastSyncAt.also { refresh }
            Text(stringResource(R.string.last_sync, if (last > 0) fmt.format(Date(last)) else stringResource(R.string.never)))
            if (running != null) {
                val file = running.progress.getString("file")
                val pct = running.progress.getInt("pct", 0)
                Text(if (file != null) stringResource(R.string.uploading_file, file, pct) else stringResource(R.string.scanning))
                LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = { SyncWorker.syncNow(context); refresh++ },
                enabled = running == null && settings.treeUri != null && !settings.needsSignIn,
            ) { Text(stringResource(R.string.sync_now)) }
        }

        Section(stringResource(R.string.section_log)) {
            if (log.isEmpty()) Text(stringResource(R.string.log_empty), style = MaterialTheme.typography.bodySmall)
            log.forEach { e ->
                Row {
                    Text(fmt.format(Date(e.ts)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        e.message, style = MaterialTheme.typography.bodySmall,
                        color = if (e.error) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
