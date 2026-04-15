package nl.vitasyncer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var store: CredentialStore
    private lateinit var hcManager: HealthConnectManager
    private lateinit var requestPermissions: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CredentialStore(this)
        hcManager = HealthConnectManager(this)
        requestPermissions = registerForActivityResult(
            HealthConnectManager.createPermissionContract()
        ) { }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VitaSyncerScreen(
                        store = store,
                        hcManager = hcManager,
                        onRequestPermissions = {
                            requestPermissions.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                        },
                        onSyncNow = {
                            WorkManager.getInstance(this)
                                .enqueue(OneTimeWorkRequestBuilder<SyncWorker>().build())
                        },
                        onToggleAutoSync = { enabled ->
                            store.autoSyncEnabled = enabled
                            if (enabled) {
                                val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                                    .setConstraints(Constraints.Builder()
                                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                                    .build()
                                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                                    SyncWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
                            } else {
                                WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.WORK_NAME)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun VitaSyncerScreen(
    store: CredentialStore,
    hcManager: HealthConnectManager,
    onRequestPermissions: () -> Unit,
    onSyncNow: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit
) {
    var cookieString by remember { mutableStateOf(store.cookieString) }
    var username by remember { mutableStateOf(store.username) }
    var password by remember { mutableStateOf(store.password) }
    var showPassword by remember { mutableStateOf(false) }
    var autoSync by remember { mutableStateOf(store.autoSyncEnabled) }
    var syncStatus by remember { mutableStateOf(store.lastSyncStatus) }
    var hcPermissionsGranted by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var rawApiJson by remember { mutableStateOf("") }
    var showApiExplorer by remember { mutableStateOf(false) }
    var showIdSettings by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    LaunchedEffect(cookieString) { store.cookieString = cookieString }
    LaunchedEffect(username) { store.username = username }
    LaunchedEffect(password) { store.password = password }

    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        hcPermissionsGranted = hcManager.isAvailable() && hcManager.hasPermissions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("VitaSyncer", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Synchroniseert Virtuagym lichaamsmetingen naar Health Connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        // ── COOKIE AUTH (primair) ──────────────────────────────
        Text("Virtuagym authenticatie", style = MaterialTheme.typography.titleMedium)

        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📋 Cookie methode (aanbevolen)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "1. Open virtuagym.com in je browser en log in\n" +
                    "2. Druk F12 → Application → Cookies → virtuagym.com\n" +
                    "3. Kopieer de waarden van virtuagym_k en virtuagym_u\n" +
                    "4. Plak hieronder: virtuagym_k=XXX; virtuagym_u=YYY",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        OutlinedTextField(
            value = cookieString,
            onValueChange = { cookieString = it },
            label = { Text("Cookie string") },
            placeholder = { Text("virtuagym_k=abc123; virtuagym_u=21380084") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )

        // ── GEAVANCEERD: Basic Auth ────────────────────────────
        OutlinedButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showAdvanced) "▲ Verberg Basic Auth" else "▼ Geavanceerd: Basic Auth (fallback)")
        }

        if (showAdvanced) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("E-mailadres") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Wachtwoord") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Verberg" else "Toon")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        HorizontalDivider()

        // ── HEALTH CONNECT ─────────────────────────────────────
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)

        if (!hcManager.isAvailable()) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Health Connect niet beschikbaar op dit toestel.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        } else if (!hcPermissionsGranted) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⚠️ Toestemmingen nodig.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Button(onClick = {
                        onRequestPermissions()
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            hcPermissionsGranted = hcManager.hasPermissions()
                        }
                    }) { Text("Toestemmingen verlenen") }
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text("✅ Health Connect toestemmingen verleend.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        HorizontalDivider()

        // ── SYNCHRONISATIE ─────────────────────────────────────
        Text("Synchronisatie", style = MaterialTheme.typography.titleMedium)

        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Auto-sync elke 6 uur", modifier = Modifier.weight(1f))
            Switch(checked = autoSync, onCheckedChange = { checked ->
                autoSync = checked; onToggleAutoSync(checked)
            })
        }

        val heeftAuth = cookieString.isNotBlank() ||
            (username.isNotBlank() && password.isNotBlank())

        Button(
            onClick = {
                isBusy = true
                syncStatus = "⏳ Bezig met synchroniseren..."
                onSyncNow()
                scope.launch {
                    repeat(15) {
                        kotlinx.coroutines.delay(2000)
                        syncStatus = store.lastSyncStatus
                        if (!store.lastSyncStatus.contains("Bezig")) {
                            isBusy = false; return@launch
                        }
                    }
                    isBusy = false
                }
            },
            enabled = !isBusy && hcPermissionsGranted && heeftAuth,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Nu synchroniseren")
        }

        if (syncStatus.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(syncStatus,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
            }
        }

        HorizontalDivider()

        // ── DIAGNOSE ───────────────────────────────────────────
        Text("Diagnose", style = MaterialTheme.typography.titleMedium)

        OutlinedButton(
            onClick = {
                scope.launch {
                    rawApiJson = "⏳ Bezig..."
                    showApiExplorer = true
                    val result = withContext(Dispatchers.IO) {
                        VirtuagymApi(username, password, cookieString.ifBlank { null })
                            .getBodyMetrics(fromDate = java.time.LocalDate.now().minusDays(30))
                    }
                    rawApiJson = when (result) {
                        is ApiResult.Success -> result.rawJson
                        is ApiResult.Error -> "FOUT: ${result.message}"
                    }
                }
            },
            enabled = heeftAuth,
            modifier = Modifier.fillMaxWidth()
        ) { Text("🔍 Verken Virtuagym API (laatste 30 dagen)") }

        if (showApiExplorer && rawApiJson.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Respons:", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(rawApiJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }

        OutlinedButton(
            onClick = { showIdSettings = !showIdSettings },
            modifier = Modifier.fillMaxWidth()
        ) { Text("⚙️ Definition ID instellingen") }

        if (showIdSettings) { DefinitionIdSettings(store = store) }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DefinitionIdSettings(store: CredentialStore) {
    var idWeight by remember { mutableStateOf(store.idWeight.toString()) }
    var idFat by remember { mutableStateOf(store.idBodyFat.toString()) }
    var idMuscle by remember { mutableStateOf(store.idMuscleMass.toString()) }
    var idWater by remember { mutableStateOf(store.idWater.toString()) }
    var idBone by remember { mutableStateOf(store.idBoneMass.toString()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Zie de API respons voor de juiste ID waarden.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            IdField("Gewicht ID", idWeight) { idWeight = it; it.toIntOrNull()?.let { n -> store.idWeight = n } }
            IdField("Vetpercentage ID", idFat) { idFat = it; it.toIntOrNull()?.let { n -> store.idBodyFat = n } }
            IdField("Spiermassa ID", idMuscle) { idMuscle = it; it.toIntOrNull()?.let { n -> store.idMuscleMass = n } }
            IdField("Waterpercentage ID", idWater) { idWater = it; it.toIntOrNull()?.let { n -> store.idWater = n } }
            IdField("Botmassa ID", idBone) { idBone = it; it.toIntOrNull()?.let { n -> store.idBoneMass = n } }
        }
    }
}

@Composable
fun IdField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
