package com.example.id

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseException
import com.example.id.ui.theme.IdTheme
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ScreenBackground = Color(0xFFF4F7FB)
private val Ink = Color(0xFF17243A)
private val MutedInk = Color(0xFF68758A)
private val Teal = Color(0xFF087E8B)
private val PaleTeal = Color(0xFFE6F4F4)
private val Orange = Color(0xFFE5A13B)
private val PaleOrange = Color(0xFFFFF5E5)
private val Red = Color(0xFFB83B3B)
private val PaleRed = Color(0xFFFCEAEA)
private const val DEFAULT_SECURITY_CONTACT = "Carabineros de Chile (emergencias)"
private const val DEFAULT_SECURITY_PHONE = "133"
private const val DEFAULT_SUPPORT_CONTACT = "Bomberos de Chile (emergencias)"
private const val DEFAULT_SUPPORT_PHONE = "132"

private enum class DashboardTab(val label: String) {
    HOME("Inicio"),
    DEVICE("Dispositivo"),
    CREDENTIALS("Accesos"),
    USERS("Usuarios"),
    RECORDS("Registros"),
    SETTINGS("Ajustes"),
    USER_ACCESS("Consultar UID"),
    ALERTS("Avisar"),
    CONTACTS("Contactos")
}

private enum class CredentialAction {
    BLOCK,
    DELETE
}

internal fun isUnregisteredDeniedEvent(
    event: AccessEvent,
    registeredCredentials: List<ManagedCredential>
): Boolean {
    if (event.type != "ACCESO" || event.status != "DENEGADO") return false
    val eventUid = normalizeUidForLookup(event.uid)
    return registeredCredentials.none { normalizeUidForLookup(it.uid) == eventUid }
}

private fun normalizeUidForLookup(uid: String): String =
    uid.trim().replace(":", "").replace("-", "").uppercase()

private fun accessEventKey(event: AccessEvent): String =
    "${event.timestamp}|${normalizeUidForLookup(event.uid)}|${event.status}|${event.type}"

internal data class DeviceEventSyncResult(
    val recentEvents: List<AccessEvent>,
    val pendingSyncError: String?
)

internal data class AccessHistoryRefreshResult(
    val deviceEvents: List<AccessEvent>?,
    val firebaseEvents: List<AccessEvent>?,
    val deviceError: String?,
    val firebaseError: String?
)

internal suspend fun refreshAccessHistory(
    deviceUrl: String,
    isTestMode: () -> Boolean,
    eventRepository: AccessEventRepository
): AccessHistoryRefreshResult {
    val deviceResponse = runCatching {
        withContext(Dispatchers.IO) {
            val client = Esp32RfidClient(deviceUrl)
            val recentEvents = runEsp32OnlyOutsideTestMode(isTestMode) {
                client.getEvents()
            }
            val pendingSyncError = try {
                val pendingEvents = runEsp32OnlyOutsideTestMode(isTestMode) {
                    client.getPendingEvents()
                }
                check(!isTestMode()) {
                    "La conexión con el ESP32 está desactivada en modo de prueba."
                }
                eventRepository.savePendingEvents(pendingEvents)
                pendingEvents.lastOrNull()?.let { event ->
                    runEsp32OnlyOutsideTestMode(isTestMode) {
                        client.confirmEventsThrough(event.eventId)
                    }
                }
                null
            } catch (error: Exception) {
                error.localizedMessage ?: "No se pudieron sincronizar los eventos pendientes."
            }
            DeviceEventSyncResult(recentEvents, pendingSyncError)
        }
    }

    val firebaseResponse = runCatching {
        withContext(Dispatchers.IO) {
            eventRepository.getRecentEvents()
        }
    }

    val deviceResult = deviceResponse.getOrNull()
    return AccessHistoryRefreshResult(
        deviceEvents = deviceResult?.recentEvents,
        firebaseEvents = firebaseResponse.getOrNull(),
        deviceError = deviceResponse.exceptionOrNull()?.localizedMessage
            ?: deviceResult?.pendingSyncError,
        firebaseError = firebaseResponse.exceptionOrNull()?.localizedMessage
    )
}

@Composable
private fun TheftReportScreen(
    message: String,
    onReport: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Reportar robo",
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Envía el UID asignado de tu tarjeta al administrador para que pueda revisarlo y bloquearlo. El reporte funciona también en modo de prueba y no se conecta al ESP32.",
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = onReport,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Red)
        ) {
            Text("Reportar robo de mi tarjeta")
        }
        if (message.isNotBlank()) {
            Text(message, color = if (message.startsWith("No se pudo")) Red else Teal)
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var authRepository: AuthRepository
    private lateinit var credentialRepository: CredentialRepository
    private val notificationUid = mutableStateOf<String?>(null)
    private val notificationReportId = mutableStateOf<String?>(null)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureTheftNotificationChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        authRepository = AuthRepository(this)
        credentialRepository = CredentialRepository(this)
        notificationUid.value = readTheftUid(intent)
        notificationReportId.value = readTheftReportId(intent)
        enableEdgeToEdge()
        setContent {
            IdTheme {
                AppShell(
                    authRepository = authRepository,
                    credentialRepository = credentialRepository,
                    notificationUid = notificationUid.value,
                    notificationReportId = notificationReportId.value
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationUid.value = readTheftUid(intent)
        notificationReportId.value = readTheftReportId(intent)
    }

    private fun readTheftUid(intent: android.content.Intent): String? =
        intent.getStringExtra(EXTRA_THEFT_UID)
            ?: intent.getStringExtra("uid")?.takeIf {
                intent.getStringExtra("reportId") != null
            }

    private fun readTheftReportId(intent: android.content.Intent): String? =
        intent.getStringExtra(EXTRA_THEFT_REPORT_ID)
            ?: intent.getStringExtra("reportId")

    companion object {
        const val EXTRA_THEFT_UID = "theft_uid"
        const val EXTRA_THEFT_REPORT_ID = "theft_report_id"
    }
}

@Composable
fun AppShell(
    authRepository: AuthRepository,
    credentialRepository: CredentialRepository,
    notificationUid: String? = null,
    notificationReportId: String? = null
) {
    val isAuthenticated = remember { mutableStateOf(authRepository.isAuthenticated()) }
    val requestedTheftUid = remember(notificationUid, notificationReportId) {
        mutableStateOf(notificationUid?.uppercase())
    }
    var sessionReady by remember { mutableStateOf(!isAuthenticated.value) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var retryProfileLoad by remember { mutableStateOf(0) }

    LaunchedEffect(isAuthenticated.value, retryProfileLoad) {
        if (isAuthenticated.value) {
            sessionReady = false
            try {
                authRepository.refreshCurrentProfile()
                profileError = null
            } catch (error: FirebaseException) {
                profileError = error.localizedMessage
                    ?: "No se pudo cargar el perfil de Firestore."
            } catch (error: IllegalStateException) {
                profileError = error.localizedMessage
                    ?: "No se pudo cargar el perfil de Firestore."
            } finally {
                sessionReady = true
            }
        } else {
            sessionReady = true
            profileError = null
        }
    }

    if (!isAuthenticated.value) {
        LoginScreen(
            authRepository = authRepository,
            onAuthenticated = {
                profileError = null
                isAuthenticated.value = true
            }
        )
    } else if (!sessionReady) {
        Text("Conectando con Firebase…")
    } else if (profileError != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBackground)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("No se pudo cargar tu perfil: $profileError", color = Red)
            TextButton(onClick = { retryProfileLoad++ }) {
                Text("Reintentar")
            }
            TextButton(onClick = {
                authRepository.logout()
                isAuthenticated.value = false
            }) {
                Text("Cerrar sesión")
            }
        }
    } else {
        DashboardScreen(
            authRepository = authRepository,
            credentialRepository = credentialRepository,
            notificationUid = requestedTheftUid.value,
            notificationReportId = notificationReportId,
            onLogout = {
                authRepository.logout()
                isAuthenticated.value = false
            }
        )
    }
}

@Composable
fun LoginScreen(authRepository: AuthRepository, onAuthenticated: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Header(
                    title = "ID Secure",
                    subtitle = if (registering) "Crear cuenta de usuario" else "Acceso al sistema"
                )

                Text(
                    text = if (registering) {
                        "La cuenta se crea en Firebase y podrás usarla desde el emulador y tu teléfono."
                    } else {
                        "Inicia sesión con el correo y la contraseña de Firebase."
                    },
                    color = MutedInk,
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Correo electrónico") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )

                if (registering) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it },
                        label = { Text("Confirmar contraseña") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }

                if (statusMessage.isNotBlank()) {
                    Text(
                        text = statusMessage,
                        color = if (
                            statusMessage.contains("correctamente", true) ||
                            statusMessage.contains("cuenta creada", true)
                        ) Teal else Red,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Button(
                    onClick = {
                        when {
                            email.isBlank() ->
                                statusMessage = "Ingresa tu correo electrónico."
                            password.length < 8 ->
                                statusMessage = "Usa una contraseña de al menos 8 caracteres."
                            registering && password != confirmation ->
                                statusMessage = "Las contraseñas no coinciden."
                            else -> scope.launch {
                                try {
                                    if (registering) {
                                        authRepository.register(email, password)
                                    } else {
                                        authRepository.login(email, password)
                                    }
                                    onAuthenticated()
                                } catch (error: FirebaseException) {
                                    statusMessage = error.localizedMessage
                                        ?: "No se pudo completar la operación con Firebase."
                                } catch (error: IllegalStateException) {
                                    statusMessage = error.localizedMessage
                                        ?: "No se pudo completar la operación con Firebase."
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) {
                    Text(if (registering) "Crear cuenta" else "Iniciar sesión")
                }

                TextButton(
                    onClick = {
                        registering = !registering
                        statusMessage = ""
                        email = ""
                        password = ""
                        confirmation = ""
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (registering) "Volver al inicio de sesión" else "Crear cuenta de usuario")
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    authRepository: AuthRepository,
    credentialRepository: CredentialRepository,
    notificationUid: String?,
    notificationReportId: String?,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val accessEventRepository = remember { AccessEventRepository() }
    val firebaseCredentialRepository = remember { FirebaseCredentialRepository() }
    val settings = remember(context) {
        context.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE).also { prefs ->
            if (!prefs.getBoolean("default_emergency_contacts_initialized", false)) {
                prefs.edit()
                    .putString(
                        "security_contact",
                        prefs.getString("security_contact", null)
                            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SECURITY_CONTACT
                    )
                    .putString(
                        "security_phone",
                        prefs.getString("security_phone", null)
                            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SECURITY_PHONE
                    )
                    .putString(
                        "support_contact",
                        prefs.getString("support_contact", null)
                            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SUPPORT_CONTACT
                    )
                    .putString(
                        "support_phone",
                        prefs.getString("support_phone", null)
                            ?.takeIf { it.isNotBlank() } ?: DEFAULT_SUPPORT_PHONE
                    )
                    .putBoolean("default_emergency_contacts_initialized", true)
                    .apply()
            }
        }
    }
    var deviceUrl by remember {
        mutableStateOf(settings.getString("device_url", "http://192.168.1.50") ?: "http://192.168.1.50")
    }
    var demoMode by remember { mutableStateOf(settings.getBoolean("demo_mode", true)) }
    var deviceStatus by remember { mutableStateOf("Simulación local lista") }
    var lastUid by remember { mutableStateOf("Sin lecturas todavía") }
    var selectedTab by remember {
        mutableStateOf(
            if (notificationUid.isNullOrBlank()) DashboardTab.HOME else DashboardTab.CREDENTIALS
        )
    }
    var credentials by remember { mutableStateOf(credentialRepository.getAll()) }
    var requestedBlockCredential by remember { mutableStateOf<ManagedCredential?>(null) }
    var processedNotificationId by remember { mutableStateOf<String?>(null) }
    var credentialsMessage by remember { mutableStateOf("") }
    var sharedEvents by remember { mutableStateOf(emptyList<AccessEvent>()) }
    var sharedMessage by remember {
        mutableStateOf("El historial se sincroniza con Firebase al conectar el ESP32.")
    }
    var unregisteredEvents by remember { mutableStateOf(emptyList<AccessEvent>()) }
    var confirmingUnknownUidAddition by remember { mutableStateOf(false) }
    var unknownUidActionMessage by remember { mutableStateOf("") }
    var isProcessingUnknownUid by remember { mutableStateOf(false) }
    var hasInitialEventSnapshot by remember { mutableStateOf(false) }
    var observedEventKeys by remember { mutableStateOf(emptySet<String>()) }
    var uidCheckMessage by remember { mutableStateOf("") }
    var assignedCardUid by remember { mutableStateOf("") }
    var users by remember { mutableStateOf(emptyList<FirebaseUserProfile>()) }
    var usersMessage by remember { mutableStateOf("") }
    var alertMessage by remember { mutableStateOf("") }
    var securityContact by remember {
        mutableStateOf(settings.getString("security_contact", "") ?: "")
    }
    var securityPhone by remember {
        mutableStateOf(settings.getString("security_phone", "") ?: "")
    }
    var supportContact by remember {
        mutableStateOf(settings.getString("support_contact", "") ?: "")
    }
    var supportPhone by remember {
        mutableStateOf(settings.getString("support_phone", "") ?: "")
    }
    val latestCredentials by rememberUpdatedState(credentials)
    val latestUnregisteredEvents by rememberUpdatedState(unregisteredEvents)
    val scope = rememberCoroutineScope()
    val saveCredentialInFirebase: (ManagedCredential) -> Unit = { credential ->
        credentialsMessage = "UID guardada localmente; sincronizando con Firebase..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    firebaseCredentialRepository.save(credential)
                }
                credentialsMessage = "UID sincronizada con Firebase."
            } catch (error: Exception) {
                credentialsMessage = "UID guardada en este teléfono, pero Firebase no confirmó " +
                    "la sincronización: ${error.localizedMessage ?: "error"}"
            }
        }
    }
    val deleteCredentialFromFirebase: (ManagedCredential) -> Unit = { credential ->
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    firebaseCredentialRepository.delete(credential)
                }
                credentialsMessage = "UID eliminada de Firebase."
            } catch (error: Exception) {
                credentialsMessage = "UID eliminada localmente, pero Firebase no confirmó " +
                    "la eliminación: ${error.localizedMessage ?: "error"}"
            }
        }
    }
    val updateHistoryDisplay: (AccessHistoryRefreshResult) -> Unit = { result ->
        when {
            result.firebaseEvents != null -> {
                sharedEvents = result.firebaseEvents
                sharedMessage = when {
                    result.deviceEvents == null ->
                        "Mostrando el historial de Firebase; ESP32 desconectado."
                    result.deviceError != null ->
                        "Eventos pendientes de sincronizar: ${result.deviceError}"
                    result.firebaseEvents.isEmpty() ->
                        "Aún no hay eventos registrados en Firebase."
                    else -> "Historial sincronizado con Firebase."
                }
            }
            result.deviceEvents != null -> {
                sharedEvents = result.deviceEvents
                sharedMessage =
                    "Firebase no disponible: ${result.firebaseError ?: "error"}. " +
                        "Se muestran los eventos recientes del ESP32."
            }
            else -> {
                sharedMessage = "No se pudo actualizar el historial. " +
                    "ESP32: ${result.deviceError ?: "sin conexión"}; " +
                    "Firebase: ${result.firebaseError ?: "sin conexión"}."
            }
        }
    }
    val processUnknownUid: (AccessEvent, Boolean) -> Unit = { event, block ->
        if (!isProcessingUnknownUid) {
            isProcessingUnknownUid = true
            unknownUidActionMessage = ""
            scope.launch {
                val response = runCatching {
                    withContext(Dispatchers.IO) {
                        runEsp32OnlyOutsideTestMode(
                            isTestMode = { settings.getBoolean("demo_mode", true) }
                        ) {
                            val client = Esp32RfidClient(deviceUrl)
                            if (block) {
                                client.setBlockedTokenUid(event.uid)
                            } else {
                                client.setAllowedCardUid(event.uid)
                            }
                        }
                    }
                }

                response.onSuccess {
                    val type = if (block) CredentialType.TOKEN else CredentialType.CARD
                    val status = if (block) {
                        CredentialStatus.UNAUTHORIZED
                    } else {
                        CredentialStatus.AUTHORIZED
                    }
                    val localSave = runCatching {
                        withContext(Dispatchers.IO) {
                            val saved = credentialRepository.add(event.uid, type, status)
                            saved to credentialRepository.getAll()
                        }
                    }
                    localSave.onSuccess { (saved, updatedCredentials) ->
                        credentials = updatedCredentials
                        if (saved) {
                            deviceStatus = if (block) {
                                "UID bloqueada en el ESP32."
                            } else {
                                "UID añadida y autorizada en el ESP32."
                            }
                            credentialsMessage = deviceStatus
                            saveCredentialInFirebase(
                                ManagedCredential(
                                    id = 0,
                                    uid = event.uid,
                                    type = type,
                                    status = status
                                )
                            )
                            val processedUid = normalizeUidForLookup(event.uid)
                            unregisteredEvents = unregisteredEvents.filterNot {
                                normalizeUidForLookup(it.uid) == processedUid
                            }
                            confirmingUnknownUidAddition = false
                        } else {
                            unknownUidActionMessage =
                                "El ESP32 se actualizó, pero no se pudo guardar el UID en la app."
                        }
                    }.onFailure { error ->
                        unknownUidActionMessage =
                            "El ESP32 se actualizó, pero no se pudo guardar el UID en la app: " +
                                (error.message ?: "error")
                    }
                }.onFailure {
                    unknownUidActionMessage =
                        "No se pudo actualizar el ESP32: ${it.message ?: "error"}"
                }
                isProcessingUnknownUid = false
            }
        }
    }
    LaunchedEffect(notificationUid, notificationReportId, credentials) {
        val targetUid = notificationUid?.uppercase() ?: return@LaunchedEffect
        val reportId = notificationReportId ?: targetUid
        if (processedNotificationId == reportId) return@LaunchedEffect
        selectedTab = DashboardTab.CREDENTIALS
        val matchingCredential = credentials.firstOrNull {
            it.uid == targetUid && it.type == CredentialType.CARD
        }
        if (matchingCredential != null) {
            requestedBlockCredential = matchingCredential
            processedNotificationId = reportId
        } else if (credentialRepository.add(
                targetUid,
                CredentialType.CARD,
                CredentialStatus.AUTHORIZED
            )
        ) {
            credentials = credentialRepository.getAll()
            saveCredentialInFirebase(
                ManagedCredential(
                    id = 0,
                    uid = targetUid,
                    type = CredentialType.CARD,
                    status = CredentialStatus.AUTHORIZED
                )
            )
        }
    }
    LaunchedEffect(Unit) {
        if (authRepository.isAdmin()) {
            try {
                authRepository.registerNotificationToken()
            } catch (error: Exception) {
                usersMessage = "Notificaciones no habilitadas en este dispositivo: ${
                    error.localizedMessage ?: "error de Firebase"
                }"
            }
        }
    }
    LaunchedEffect(authRepository.isAdmin()) {
        if (!authRepository.isAdmin()) return@LaunchedEffect

        while (true) {
            try {
                credentials = withContext(Dispatchers.IO) {
                    firebaseCredentialRepository.synchronize(credentialRepository)
                }
                credentialsMessage = "Lista de UID sincronizada con Firebase."
            } catch (error: Exception) {
                credentialsMessage = "No se pudo sincronizar la lista de UID: ${
                    error.localizedMessage ?: "error de Firebase"
                }"
            }
            delay(15_000)
        }
    }
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            DashboardTab.USER_ACCESS -> {
                try {
                    assignedCardUid = authRepository.getCurrentProfile()?.cardUid.orEmpty()
                    uidCheckMessage = ""
                } catch (error: FirebaseException) {
                    uidCheckMessage = "No se pudo consultar la asignación: ${
                        error.localizedMessage ?: "error de Firestore"
                    }"
                } catch (error: IllegalStateException) {
                    uidCheckMessage = error.localizedMessage
                        ?: "No se pudo consultar la asignación."
                }
            }
            DashboardTab.USERS -> {
                try {
                    users = authRepository.listUsers()
                    usersMessage = "Cuentas sincronizadas con Firestore."
                } catch (error: FirebaseException) {
                    usersMessage = "No se pudieron cargar las cuentas: ${
                        error.localizedMessage ?: "error de Firestore"
                    }"
                }
            }
            else -> Unit
        }
    }
    val visibleTabs = if (authRepository.isAdmin()) {
        listOf(
            DashboardTab.HOME,
            DashboardTab.DEVICE,
            DashboardTab.CREDENTIALS,
            DashboardTab.USERS,
            DashboardTab.RECORDS,
            DashboardTab.SETTINGS
        )
    } else {
        listOf(
            DashboardTab.HOME,
            DashboardTab.USER_ACCESS,
            DashboardTab.ALERTS,
            DashboardTab.CONTACTS,
            DashboardTab.SETTINGS
        )
    }
    val sendAlert: (String) -> Unit = { type ->
        scope.launch {
            if (demoMode) {
                alertMessage = "Desactiva el modo de prueba para enviar el aviso al ESP32."
            } else {
                val response = runCatching {
                    withContext(Dispatchers.IO) {
                        runEsp32OnlyOutsideTestMode(
                            isTestMode = { settings.getBoolean("demo_mode", true) }
                        ) {
                            Esp32RfidClient(deviceUrl).reportAlert(type)
                        }
                    }
                }
                response.onSuccess {
                    alertMessage = if (type == "robo") {
                        "Reporte de robo recibido por el ESP32."
                    } else {
                        "Aviso enviado al ESP32; estará visible en el historial compartido."
                    }
                }.onFailure {
                    alertMessage = "No se pudo enviar el aviso: ${it.message ?: "error"}"
                }
            }
        }
    }

    LaunchedEffect(demoMode, deviceUrl, authRepository.isAdmin()) {
        hasInitialEventSnapshot = false
        observedEventKeys = emptySet()
        unregisteredEvents = emptyList()
        confirmingUnknownUidAddition = false
        unknownUidActionMessage = ""
        if (demoMode || !authRepository.isAdmin()) return@LaunchedEffect

        while (true) {
            val result = refreshAccessHistory(
                deviceUrl,
                { settings.getBoolean("demo_mode", true) },
                accessEventRepository
            )
            updateHistoryDisplay(result)
            result.deviceEvents?.let { events ->
                val currentEventKeys = events.map(::accessEventKey).toSet()
                val newEvents = if (hasInitialEventSnapshot) {
                    events.filter { accessEventKey(it) !in observedEventKeys }
                } else {
                    emptyList()
                }
                observedEventKeys = currentEventKeys
                hasInitialEventSnapshot = true

                if (authRepository.isAdmin() && newEvents.isNotEmpty()) {
                    val alreadyQueued =
                        latestUnregisteredEvents.map(::accessEventKey).toSet()
                    val newlyUnregistered = newEvents
                        .asReversed()
                        .filter { isUnregisteredDeniedEvent(it, latestCredentials) }
                        .filter { accessEventKey(it) !in alreadyQueued }
                    unregisteredEvents = latestUnregisteredEvents + newlyUnregistered
                }
            }
            delay(5_000)
        }
    }

    Scaffold(
        containerColor = ScreenBackground,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                visibleTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {},
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(
                title = "ID Secure",
                subtitle = if (authRepository.isAdmin()) {
                    "Panel de control • Administrador"
                } else {
                    "Panel de usuario"
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onLogout) {
                    Text("Cerrar sesión")
                }
            }

            when (selectedTab) {
                DashboardTab.HOME -> {
                    LastReadCard(lastUid, authRepository)
                    StatusSummaryCard(demoMode, authRepository.isAdmin())
                    if (authRepository.isAdmin()) {
                        AlertsScreen(
                            demoMode = demoMode,
                            message = alertMessage,
                            onReport = sendAlert
                        )
                    }
                }

                DashboardTab.DEVICE -> {
                    ConnectionCard(
                        deviceUrl = deviceUrl,
                        onUrlChange = {
                            deviceUrl = it
                            settings.edit().putString("device_url", it).apply()
                        },
                        deviceStatus = deviceStatus,
                        demoMode = demoMode,
                        onConnect = {
                            scope.launch {
                                if (demoMode) {
                                    deviceStatus = "Modo de prueba activo: dispositivo RFID simulado"
                                    lastUid = "Simulación local lista para usar"
                                } else {
                                    val response = runCatching {
                                        withContext(Dispatchers.IO) {
                                            runEsp32OnlyOutsideTestMode(
                                                isTestMode = { settings.getBoolean("demo_mode", true) }
                                            ) {
                                                Esp32RfidClient(deviceUrl).getStatus()
                                            }
                                        }
                                    }
                                    deviceStatus = response.getOrElse {
                                        "Error: ${it.message ?: "no se pudo conectar"}"
                                    }
                                    if (response.isSuccess) {
                                        val normalized =
                                            response.getOrThrow().ifBlank { "ESP32 conectado" }
                                        deviceStatus = normalized
                                        lastUid = "Última respuesta del ESP32: $normalized"
                                    }
                                }
                            }

                        }
                    )

                }

                DashboardTab.CREDENTIALS -> {
                    CredentialManagementScreen(
                        credentials = credentials,
                        operationMessage = credentialsMessage,
                        requestedBlockCredential = requestedBlockCredential,
                        onBlockRequestHandled = { requestedBlockCredential = null },
                        onAdd = { uid, type, status ->
                            val supportedConfiguration =
                                type == CredentialType.CARD &&
                                    status == CredentialStatus.AUTHORIZED ||
                                    type == CredentialType.TOKEN &&
                                    status == CredentialStatus.UNAUTHORIZED
                            val saved = supportedConfiguration &&
                                credentialRepository.add(uid, type, status)
                            if (saved) {
                                credentials = credentialRepository.getAll()
                                saveCredentialInFirebase(
                                    ManagedCredential(
                                        id = 0,
                                        uid = uid,
                                        type = type,
                                        status = status
                                    )
                                )
                            }
                            saved
                        },
                        onApply = { credential ->
                            scope.launch {
                                if (demoMode) {
                                    deviceStatus = "Credencial aplicada en modo de prueba"
                                    lastUid =
                                        "${credential.type.label} ${credential.status.label}: ${credential.uid}"
                                } else {
                                    val response = runCatching {
                                        withContext(Dispatchers.IO) {
                                            runEsp32OnlyOutsideTestMode(
                                                isTestMode = { settings.getBoolean("demo_mode", true) }
                                            ) {
                                                val client = Esp32RfidClient(deviceUrl)
                                                when {
                                                    credential.type == CredentialType.CARD &&
                                                        credential.status == CredentialStatus.AUTHORIZED ->
                                                        client.setAllowedCardUid(credential.uid)
                                                    credential.type == CredentialType.CARD &&
                                                        credential.status == CredentialStatus.UNAUTHORIZED ->
                                                        client.setBlockedCardUid(credential.uid)
                                                    credential.type == CredentialType.TOKEN &&
                                                        credential.status == CredentialStatus.UNAUTHORIZED ->
                                                        client.setBlockedTokenUid(credential.uid)
                                                    else -> throw IOException(
                                                        "El firmware no admite esta combinación de tipo y estado."
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (response.isSuccess) {
                                        deviceStatus = "Credencial aplicada al ESP32"
                                        lastUid =
                                            "${credential.type.label} ${credential.status.label}: ${credential.uid}"
                                    } else {
                                        deviceStatus =
                                            "Error al aplicar credencial: ${response.exceptionOrNull()?.message ?: "desconocido"}"
                                    }
                                }
                            }
                        },
                        onStatusChange = { credential, newStatus ->
                            scope.launch {
                                if (demoMode) {
                                    if (credentialRepository.updateStatus(credential.id, newStatus)) {
                                        credentials = credentialRepository.getAll()
                                        saveCredentialInFirebase(credential.copy(status = newStatus))
                                    }
                                    deviceStatus = "Estado actualizado en la simulación local."
                                    credentialsMessage = "El cambio se guardó localmente; el ESP32 no se modificó en modo de prueba."
                                } else {
                                    val response = runCatching {
                                        withContext(Dispatchers.IO) {
                                            runEsp32OnlyOutsideTestMode(
                                                isTestMode = { settings.getBoolean("demo_mode", true) }
                                            ) {
                                                val client = Esp32RfidClient(deviceUrl)
                                                if (newStatus == CredentialStatus.AUTHORIZED) {
                                                    client.setAllowedCardUid(credential.uid)
                                                } else {
                                                    client.setBlockedCardUid(credential.uid)
                                                }
                                            }
                                        }
                                    }
                                    if (response.isSuccess) {
                                        if (credentialRepository.updateStatus(credential.id, newStatus)) {
                                            credentials = credentialRepository.getAll()
                                            saveCredentialInFirebase(credential.copy(status = newStatus))
                                        }
                                        deviceStatus = "Estado de tarjeta actualizado en el ESP32."
                                        credentialsMessage = "UID bloqueado en el ESP32."
                                    } else {
                                        deviceStatus =
                                            "No se pudo cambiar el acceso: ${response.exceptionOrNull()?.message ?: "error"}"
                                        credentialsMessage = deviceStatus
                                    }
                                }
                            }
                        },
                        onDelete = { credential ->
                            scope.launch {
                                try {
                                    if (!demoMode &&
                                        credential.type == CredentialType.CARD &&
                                        credential.status == CredentialStatus.AUTHORIZED
                                    ) {
                                        withContext(Dispatchers.IO) {
                                            runEsp32OnlyOutsideTestMode(
                                                isTestMode = {
                                                    settings.getBoolean("demo_mode", true)
                                                }
                                            ) {
                                                Esp32RfidClient(deviceUrl)
                                                    .setBlockedCardUid(credential.uid)
                                            }
                                        }
                                    }
                                    if (credentialRepository.delete(credential.id)) {
                                        credentials = credentialRepository.getAll()
                                        usersMessage = if (demoMode) {
                                            "UID eliminado de la app y de la cuenta. El modo de prueba impidió modificar el ESP32."
                                        } else {
                                            "UID eliminado de la cuenta y de la lista."
                                        }
                                        credentialsMessage = usersMessage
                                        deleteCredentialFromFirebase(credential)
                                        try {
                                            authRepository.removeCardUid(credential.uid)
                                        } catch (error: Exception) {
                                            usersMessage =
                                                "UID eliminado de la lista, pero no se pudo quitar su asignación: ${
                                                    error.localizedMessage ?: "error"
                                                }"
                                            credentialsMessage = usersMessage
                                        }
                                    }
                                } catch (error: Exception) {
                                    credentialsMessage = "No se pudo eliminar el UID: ${
                                        error.localizedMessage ?: "error"
                                    }"
                                }
                            }
                        }
                    )
                }

                DashboardTab.USERS -> {
                    UserManagementScreen(
                        users = users.filter { it.role != "admin" },
                        message = usersMessage,
                        onRefresh = {
                            scope.launch {
                                try {
                                    users = authRepository.listUsers()
                                    usersMessage = "Cuentas sincronizadas con Firestore."
                                } catch (error: FirebaseException) {
                                    usersMessage = "No se pudieron cargar las cuentas: ${
                                        error.localizedMessage ?: "error de Firestore"
                                    }"
                                }
                            }
                        },
                        onAssign = { user, uid ->
                            scope.launch {
                                try {
                                    authRepository.assignCardUid(user.id, uid)
                                    users = authRepository.listUsers()
                                    usersMessage = "UID asignado a ${user.email}."
                                } catch (error: FirebaseException) {
                                    usersMessage = error.localizedMessage
                                        ?: "No se pudo guardar la asignación en Firestore."
                                } catch (error: IllegalArgumentException) {
                                    usersMessage = error.localizedMessage
                                        ?: "El UID RFID no tiene un formato válido."
                                }
                            }
                        }
                    )
                }

                DashboardTab.RECORDS -> {
                    SharedEventsScreen(
                        events = sharedEvents,
                        message = sharedMessage,
                        demoMode = demoMode,
                        onRefresh = {
                            scope.launch {
                                updateHistoryDisplay(
                                    refreshAccessHistory(
                                        deviceUrl,
                                        { settings.getBoolean("demo_mode", true) },
                                        accessEventRepository
                                    )
                                )
                            }
                        }
                    )
                }

                DashboardTab.USER_ACCESS -> {
                    UserAccessScreen(
                        deviceUrl = deviceUrl,
                        onUrlChange = {
                            deviceUrl = it
                            settings.edit().putString("device_url", it).apply()
                        },
                        assignedUid = assignedCardUid,
                        demoMode = demoMode,
                        resultMessage = uidCheckMessage,
                        onRefreshAssignment = {
                            scope.launch {
                                try {
                                    assignedCardUid =
                                        authRepository.getCurrentProfile()?.cardUid.orEmpty()
                                    uidCheckMessage = if (assignedCardUid.isBlank()) {
                                        "El administrador todavía no asignó un UID."
                                    } else {
                                        "Asignación actualizada desde Firestore."
                                    }
                                } catch (error: FirebaseException) {
                                    uidCheckMessage = "No se pudo consultar la asignación: ${
                                        error.localizedMessage ?: "error de Firestore"
                                    }"
                                } catch (error: IllegalStateException) {
                                    uidCheckMessage = error.localizedMessage
                                        ?: "No se pudo consultar la asignación."
                                }
                            }
                        },
                        onCheck = {
                            scope.launch {
                                if (assignedCardUid.isBlank()) {
                                    uidCheckMessage = "El administrador todavía no asignó un UID."
                                } else if (demoMode) {
                                    uidCheckMessage = "Desactiva el modo de prueba para consultar el ESP32."
                                } else {
                                    val response = runCatching {
                                        withContext(Dispatchers.IO) {
                                            runEsp32OnlyOutsideTestMode(
                                                isTestMode = { settings.getBoolean("demo_mode", true) }
                                            ) {
                                                Esp32RfidClient(deviceUrl).checkUid(assignedCardUid)
                                            }
                                        }
                                    }
                                    uidCheckMessage = response.getOrElse {
                                        "No se pudo verificar el UID: ${it.message ?: "error"}"
                                    }
                                }
                            }
                        }
                    )
                }

                DashboardTab.ALERTS -> {
                    TheftReportScreen(
                        message = alertMessage,
                        onReport = {
                            scope.launch {
                                try {
                                    authRepository.submitTheftReport()
                                    alertMessage =
                                        "Reporte enviado al administrador. Mantén la app instalada para recibir confirmación."
                                } catch (error: Exception) {
                                    alertMessage = error.localizedMessage
                                        ?: "No se pudo enviar el reporte."
                                }
                            }
                        }
                    )
                }

                DashboardTab.CONTACTS -> {
                    ContactInfoScreen(
                        securityContact = securityContact,
                        securityPhone = securityPhone,
                        supportContact = supportContact,
                        supportPhone = supportPhone,
                        onSave = { securityName, securityNumber, supportName, supportNumber ->
                            securityContact = securityName
                            securityPhone = securityNumber
                            supportContact = supportName
                            supportPhone = supportNumber
                            settings.edit()
                                .putString("security_contact", securityName)
                                .putString("security_phone", securityNumber)
                                .putString("support_contact", supportName)
                                .putString("support_phone", supportNumber)
                                .apply()
                        }
                    )
                }

                DashboardTab.SETTINGS -> {
                    SettingsCard(
                        demoMode = demoMode,
                        onDemoModeChange = { enabled ->
                            demoMode = enabled
                            settings.edit().putBoolean("demo_mode", enabled).apply()
                            if (enabled) {
                                sharedEvents = emptyList()
                                sharedMessage = "El modo de prueba no consulta ni modifica el ESP32."
                                uidCheckMessage = ""
                                alertMessage = ""
                            }
                            deviceStatus = if (enabled) {
                                "Modo de prueba activo: no se realizan conexiones al ESP32"
                            } else {
                                "Sin conexión verificada"
                            }
                        }
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "SEGURIDAD",
                                color = MutedInk,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.1.sp
                            )
                            Text(
                                text = "El acceso usa Firebase Authentication y los perfiles se sincronizan con Firestore. La comunicación con este firmware usa HTTP, no HTTPS: usa una red Wi‑Fi privada y no publiques el ESP32 en Internet. Esto es una demostración educativa, no una certificación ISO 27400.",
                                color = Ink,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    unregisteredEvents.firstOrNull()?.let { event ->
        UnknownUidDialog(
            event = event,
            confirmingAddition = confirmingUnknownUidAddition,
            processing = isProcessingUnknownUid,
            message = unknownUidActionMessage,
            onAdd = {
                confirmingUnknownUidAddition = true
                unknownUidActionMessage = ""
            },
            onBlock = { processUnknownUid(event, true) },
            onConfirmAddition = { processUnknownUid(event, false) },
            onBack = {
                confirmingUnknownUidAddition = false
                unknownUidActionMessage = ""
            },
            onDismiss = {
                unregisteredEvents = unregisteredEvents.drop(1)
                unknownUidActionMessage = ""
            }
        )
    }
}

@Composable
private fun UnknownUidDialog(
    event: AccessEvent,
    confirmingAddition: Boolean,
    processing: Boolean,
    message: String,
    onAdd: () -> Unit,
    onBlock: () -> Unit,
    onConfirmAddition: () -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    if (confirmingAddition) {
        AlertDialog(
            onDismissRequest = { if (!processing) onBack() },
            title = { Text("¿Confirmas añadir esta UID?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Se añadirá ${event.uid} como tarjeta autorizada. El ESP32 solo admite " +
                            "una tarjeta autorizada, por lo que reemplazará la actual."
                    )
                    if (message.isNotBlank()) {
                        Text(message, color = Red)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !processing,
                    onClick = onConfirmAddition
                ) {
                    Text(if (processing) "Aplicando..." else "Sí, añadir UID")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !processing,
                    onClick = onBack
                ) {
                    Text("Volver")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = { if (!processing) onDismiss() },
            title = { Text("UID no registrada") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Se detectó ${event.uid} como no autorizada. Puedes añadirla como tarjeta " +
                            "autorizada o bloquearla como token. El lector conserva una sola " +
                            "tarjeta autorizada y un solo token bloqueado; cada cambio reemplaza " +
                            "el UID anterior de ese tipo."
                    )
                    if (message.isNotBlank()) {
                        Text(message, color = Red)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !processing,
                        onClick = onAdd
                    ) {
                        Text("Añadir UID")
                    }
                    TextButton(
                        enabled = !processing,
                        onClick = onBlock
                    ) {
                        Text(if (processing) "Aplicando..." else "Bloquear")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !processing,
                    onClick = onDismiss
                ) {
                    Text("Ahora no")
                }
            }
        )
    }
}

@Composable
private fun UserManagementScreen(
    users: List<FirebaseUserProfile>,
    message: String,
    onRefresh: () -> Unit,
    onAssign: (FirebaseUserProfile, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Cuentas registradas",
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Las cuentas nuevas se registran con correo y contraseña. Asigna aquí el UID de la tarjeta del usuario.",
            color = MutedInk,
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            Text("Actualizar cuentas")
        }
        if (message.isNotBlank()) {
            Text(message, color = if (message.startsWith("No se pudo")) Red else MutedInk)
        }
        if (users.isEmpty()) {
            Text("No hay cuentas de usuario registradas en Firestore.", color = MutedInk)
        }
        users.forEach { user ->
            var uid by remember(user.id, user.cardUid) {
                mutableStateOf(user.cardUid)
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(user.email, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (user.cardUid.isBlank()) {
                            "Sin UID asignado"
                        } else {
                            "UID actual: ${user.cardUid}"
                        },
                        color = MutedInk
                    )
                    OutlinedTextField(
                        value = uid,
                        onValueChange = { uid = it.uppercase() },
                        label = { Text("UID RFID (8, 14 o 20 caracteres hex)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(
                        onClick = { onAssign(user, uid) },
                        enabled = uid.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal)
                    ) {
                        Text("Asignar UID")
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedEventsScreen(
    events: List<AccessEvent>,
    message: String,
    demoMode: Boolean,
    onRefresh: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Historial compartido en Firebase",
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (demoMode) {
                "Desactiva el modo de prueba para ver las lecturas reales del lector."
            } else {
                "$message El ESP32 conserva hasta 500 eventos pendientes durante desconexiones. " +
                    "Firebase guarda el historial; se muestran los 200 más recientes. Hora UTC."
            },
            color = MutedInk,
            style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth(),
            enabled = !demoMode,
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            Text("Actualizar historial")
        }
        if (events.isEmpty()) {
            Text("Todavía no hay eventos compartidos.", color = MutedInk)
        } else {
            events.forEach { event ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (event.type == "ALERTA") PaleOrange else PaleTeal
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (event.type == "ALERTA") event.status else "Acceso ${event.status.lowercase()}",
                            color = Ink,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (event.uid != "-") {
                            Text("UID: ${event.uid}", color = Ink)
                        }
                        Text(
                            text = if (event.timestamp == "SIN_HORA_NTP") {
                                "Hora no sincronizada"
                            } else {
                                "${event.timestamp} UTC"
                            },
                            color = MutedInk,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserAccessScreen(
    deviceUrl: String,
    onUrlChange: (String) -> Unit,
    assignedUid: String,
    demoMode: Boolean,
    resultMessage: String,
    onRefreshAssignment: () -> Unit,
    onCheck: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Consultar estado de mi UID",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "UID asignado por el administrador: ${
                    assignedUid.ifBlank { "Pendiente de asignación" }
                }",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
            TextButton(onClick = onRefreshAssignment) {
                Text("Actualizar asignación")
            }
            OutlinedTextField(
                value = deviceUrl,
                onValueChange = onUrlChange,
                label = { Text("URL del ESP32") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = onCheck,
                modifier = Modifier.fillMaxWidth(),
                enabled = assignedUid.isNotBlank() && !demoMode,
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                Text("Consultar acceso")
            }
            if (demoMode) {
                Text("Activa la conexión real desde Ajustes.", color = MutedInk)
            }
            if (resultMessage.isNotBlank()) {
                Text(
                    text = resultMessage,
                    color = if (resultMessage == "AUTORIZADA") Teal else Red,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun AlertsScreen(
    demoMode: Boolean,
    message: String,
    onReport: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Avisos de seguridad",
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Los avisos quedan registrados en el ESP32 y aparecen en el historial compartido de los teléfonos conectados.",
            color = MutedInk,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = { onReport("robo") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !demoMode,
            colors = ButtonDefaults.buttonColors(containerColor = Red)
        ) {
            Text("Reportar robo de dispositivo")
        }
        Button(
            onClick = { onReport("seguridad") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !demoMode,
            colors = ButtonDefaults.buttonColors(containerColor = Teal)
        ) {
            Text("Avisar a seguridad")
        }
        if (demoMode) {
            Text("Activa la conexión real desde Ajustes para enviar avisos.", color = MutedInk)
        }
        if (message.isNotBlank()) {
            Text(message, color = if (message.startsWith("No se pudo")) Red else Teal)
        }
    }
}

@Composable
private fun ContactInfoScreen(
    securityContact: String,
    securityPhone: String,
    supportContact: String,
    supportPhone: String,
    onSave: (String, String, String, String) -> Unit
) {
    var securityName by remember { mutableStateOf(securityContact) }
    var securityNumber by remember { mutableStateOf(securityPhone) }
    var supportName by remember { mutableStateOf(supportContact) }
    var supportNumber by remember { mutableStateOf(supportPhone) }
    var saved by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Contactos de ayuda",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Números de emergencia precargados para Chile. Verifícalos y cámbialos si necesitas otros contactos.",
                color = MutedInk
            )
            OutlinedTextField(
                value = securityName,
                onValueChange = { securityName = it },
                label = { Text("Contacto de seguridad") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = securityNumber,
                onValueChange = { securityNumber = it },
                label = { Text("Teléfono de seguridad") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = supportName,
                onValueChange = { supportName = it },
                label = { Text("Segundo contacto de emergencia") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = supportNumber,
                onValueChange = { supportNumber = it },
                label = { Text("Teléfono del segundo contacto") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = {
                    onSave(securityName, securityNumber, supportName, supportNumber)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                Text("Guardar contactos")
            }
            if (saved) {
                Text("Contactos guardados en este teléfono.", color = Teal)
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Teal, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "ID",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = title,
                color = Ink,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SettingsCard(
    demoMode: Boolean,
    onDemoModeChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Configuración",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Modo de prueba (bloquea toda comunicación con el ESP32)",
                        color = Ink,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (demoMode) {
                            "Activo: no se consulta ni modifica el ESP32; las acciones se simulan."
                        } else {
                            "Inactivo: la app intentará comunicarse con el ESP32."
                        },
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = demoMode,
                    onCheckedChange = onDemoModeChange
                )
            }
        }
    }
}

@Composable
private fun CredentialManagementScreen(
    credentials: List<ManagedCredential>,
    operationMessage: String,
    requestedBlockCredential: ManagedCredential?,
    onBlockRequestHandled: () -> Unit,
    onAdd: (String, CredentialType, CredentialStatus) -> Boolean,
    onApply: (ManagedCredential) -> Unit,
    onStatusChange: (ManagedCredential, CredentialStatus) -> Unit,
    onDelete: (ManagedCredential) -> Unit
) {
    var uid by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CredentialType.CARD) }
    var status by remember { mutableStateOf(CredentialStatus.AUTHORIZED) }
    var typeFilter by remember { mutableStateOf<CredentialType?>(null) }
    var statusFilter by remember { mutableStateOf<CredentialStatus?>(null) }
    var message by remember { mutableStateOf("") }
    var confirmationCredential by remember { mutableStateOf<ManagedCredential?>(null) }
    var confirmationAction by remember { mutableStateOf<CredentialAction?>(null) }
    var confirmationStep by remember { mutableStateOf(1) }

    LaunchedEffect(requestedBlockCredential) {
        requestedBlockCredential?.let {
            confirmationCredential = it
            confirmationAction = CredentialAction.BLOCK
            confirmationStep = 1
            onBlockRequestHandled()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Agregar tarjeta o token",
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = uid,
                    onValueChange = { uid = it },
                    label = { Text("UID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CredentialType.entries.forEach { option ->
                        Button(
                            onClick = {
                                type = option
                                status = if (option == CredentialType.CARD) {
                                    CredentialStatus.AUTHORIZED
                                } else {
                                    CredentialStatus.UNAUTHORIZED
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (type == option) Teal else Color(0xFFE5EAF3)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = option.label,
                                color = if (type == option) Color.White else Ink
                            )
                        }
                    }
                }
                Text(
                    text = if (type == CredentialType.CARD) {
                        "Una tarjeta registrada aquí se aplicará como autorizada."
                    } else {
                        "Un token registrado aquí se aplicará como bloqueado."
                    },
                    color = MutedInk,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        message = when {
                            uid.isBlank() -> "Ingresa un UID para guardar."
                            onAdd(uid, type, status) -> {
                                uid = ""
                                "Credencial guardada."
                            }
                            else -> "No se pudo guardar la credencial."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) {
                    Text("Agregar credencial")
                }
                Text(
                    text = message.ifBlank {
                        "Los UID se normalizan a mayúsculas. Se aplica una tarjeta autorizada y un token bloqueado."
                    },
                    color = MutedInk,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text(
            text = "Credenciales registradas",
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (operationMessage.isNotBlank()) {
            Text(
                text = operationMessage,
                color = if (operationMessage.startsWith("No se pudo")) Red else MutedInk
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(null) + CredentialType.entries).forEach { option ->
                val selected = typeFilter == option
                Button(
                    onClick = { typeFilter = option },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Teal else Color(0xFFE5EAF3)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = option?.label ?: "Todas",
                        color = if (selected) Color.White else Ink
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(null) + CredentialStatus.entries).forEach { option ->
                val selected = statusFilter == option
                Button(
                    onClick = { statusFilter = option },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) Teal else Color(0xFFE5EAF3)
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = option?.label ?: "Todos los estados",
                        color = if (selected) Color.White else Ink
                    )
                }
            }
        }

        val visibleCredentials = credentials.filter { credential ->
            (typeFilter == null || credential.type == typeFilter) &&
                (statusFilter == null || credential.status == statusFilter)
        }
        if (visibleCredentials.isEmpty()) {
            Text(
                text = "No hay credenciales para estos filtros.",
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            visibleCredentials.forEach { credential ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (credential.status == CredentialStatus.AUTHORIZED) {
                            PaleTeal
                        } else {
                            PaleOrange
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${credential.type.label} • ${credential.status.label}",
                            color = Ink,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = credential.uid,
                            color = Ink,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        TextButton(onClick = {
                            confirmationCredential = credential
                            confirmationAction = CredentialAction.DELETE
                            confirmationStep = 1
                        }) {
                            Text("Eliminar UID", color = Red)
                        }
                        if (credential.type == CredentialType.CARD) {
                            TextButton(onClick = {
                                if (credential.status == CredentialStatus.AUTHORIZED) {
                                    confirmationCredential = credential
                                    confirmationAction = CredentialAction.BLOCK
                                    confirmationStep = 1
                                } else {
                                    onStatusChange(credential, CredentialStatus.AUTHORIZED)
                                }
                            }) {
                                Text(
                                    if (credential.status == CredentialStatus.AUTHORIZED) {
                                        "Bloquear UID"
                                    } else {
                                        "Autorizar UID"
                                    }
                                )
                            }
                        }
                        if (
                            credential.type == CredentialType.CARD ||
                            credential.type == CredentialType.TOKEN &&
                            credential.status == CredentialStatus.UNAUTHORIZED
                        ) {
                            Button(
                                onClick = { onApply(credential) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Teal)
                            ) {
                                Text("Aplicar al lector")
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Text(
                text = "Las tarjetas y los tokens se sincronizan con Firebase y se conservan en este teléfono. El ESP32 conserva una tarjeta autorizada y un token bloqueado; al aplicar otro UID del mismo tipo, se reemplaza el anterior.",
                modifier = Modifier.padding(16.dp),
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    val selectedCredential = confirmationCredential
    val selectedAction = confirmationAction
    if (selectedCredential != null && selectedAction != null) {
        val blocking = selectedAction == CredentialAction.BLOCK
        val title = when {
            blocking && confirmationStep == 1 -> "¿Bloquear este UID?"
            blocking -> "Confirma el bloqueo"
            confirmationStep == 1 -> "¿Eliminar este UID?"
            else -> "Confirma la eliminación"
        }
        val description = when {
            blocking && confirmationStep == 1 ->
                "Se bloqueará la tarjeta ${selectedCredential.uid}."
            blocking ->
                "Confirma por segunda vez que quieres bloquear ${selectedCredential.uid}."
            confirmationStep == 1 ->
                "Se quitará ${selectedCredential.uid} de la lista y de la asignación del usuario."
            else ->
                "Esta es la segunda confirmación. ¿Eliminar definitivamente ${selectedCredential.uid}?"
        }
        AlertDialog(
            onDismissRequest = {
                confirmationCredential = null
                confirmationAction = null
            },
            title = { Text(title) },
            text = { Text(description) },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmationStep == 1) {
                        confirmationStep = 2
                    } else {
                        if (blocking) {
                            onStatusChange(
                                selectedCredential,
                                CredentialStatus.UNAUTHORIZED
                            )
                        } else {
                            onDelete(selectedCredential)
                        }
                        confirmationCredential = null
                        confirmationAction = null
                    }
                }) {
                    Text(if (confirmationStep == 1) "Continuar" else "Confirmar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmationCredential = null
                    confirmationAction = null
                }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun StatusSummaryCard(demoMode: Boolean, isAdmin: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Estado del sistema",
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (demoMode) "Modo de prueba activo" else "Modo ESP32",
                color = Teal,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (demoMode) {
                    "Las acciones RFID se simulan localmente. Desactiva el modo de prueba para usar el ESP32."
                } else {
                    if (isAdmin) {
                        "La conexión y el estado detallado se consultan en Dispositivo."
                    } else {
                        "Consulta tu UID, envía avisos y revisa los contactos desde la barra inferior."
                    }
                },
                color = MutedInk,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (isAdmin) {
                    "Usa la barra inferior para administrar el lector, revisar eventos y cambiar ajustes."
                } else {
                    "Usa la barra inferior para consultar acceso, enviar avisos y ver contactos."
                },
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ConnectionCard(
    deviceUrl: String,
    onUrlChange: (String) -> Unit,
    deviceStatus: String,
    demoMode: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dispositivo",
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = CircleShape,
                    color = when {
                        deviceStatus.contains("Error", true) || deviceStatus.contains("Sin", true) -> PaleOrange
                        else -> PaleTeal
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (deviceStatus.contains("Error", true) || deviceStatus.contains("Sin", true)) Orange else Teal,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = when {
                                deviceStatus.contains("Error", true) || deviceStatus.contains("Sin", true) -> "Lector pendiente"
                                demoMode -> "Simulación activa"
                                else -> "Lector conectado"
                            },
                            color = if (deviceStatus.contains("Error", true) || deviceStatus.contains("Sin", true)) Color(0xFF81530E) else Color(0xFF0D5F63),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Text(
                text = "ESP32 RFID",
                color = Ink,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = deviceUrl,
                onValueChange = onUrlChange,
                label = { Text("URL del ESP32") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal)
            ) {
                Text("Probar conexión")
            }

            Text(
                text = deviceStatus,
                color = if (deviceStatus.contains("Error", true)) Red else MutedInk,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun LastReadCard(lastUid: String, authRepository: AuthRepository) {
    var isRevealed by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Ink)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text(
                text = "ÚLTIMA LECTURA",
                color = Color(0xFFB7C7D9),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp
            )
            if (isRevealed) {
                Text(
                    text = lastUid,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = {
                    isRevealed = false
                    password = ""
                    message = ""
                }) {
                    Text("Ocultar UID", color = Color.White)
                }
            } else {
                Text(
                    text = "La última lectura está protegida. Confirma tu contraseña para ver el UID.",
                    color = Color(0xFFD0DAE6),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Contraseña") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                if (message.isNotBlank()) {
                    Text(text = message, color = Color(0xFFFFB4AB))
                }
                Button(
                    onClick = {
                        scope.launch {
                            try {
                                val verified = withContext(Dispatchers.Default) {
                                    authRepository.verifyCurrentPassword(password)
                                }
                                if (verified) {
                                    isRevealed = true
                                    password = ""
                                    message = ""
                                } else {
                                    message = "No se pudo verificar la cuenta actual."
                                }
                            } catch (error: FirebaseException) {
                                message = error.localizedMessage
                                    ?: "No se pudo verificar la contraseña con Firebase."
                            } catch (error: IllegalStateException) {
                                message = error.localizedMessage
                                    ?: "No se pudo verificar la contraseña con Firebase."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)
                ) {
                    Text("Ver última lectura")
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun IdSecureScreenPreview() {
    IdTheme {
        LoginScreen(
            authRepository = AuthRepository(LocalContext.current),
            onAuthenticated = {}
        )
    }
}
