package streaming.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Clock
import streaming.mobile.contracts.*

@Composable
fun AuthScreen(api: Api, mode: String, navigate: (String) -> Unit, done: () -> Unit) {
    var login by rememberSaveable(mode) { mutableStateOf("") }
    var email by rememberSaveable(mode) { mutableStateOf("") }
    var handle by rememberSaveable(mode) { mutableStateOf("") }
    // Passwords and recovery tokens never enter saved state or logs.
    var password by remember(mode) { mutableStateOf("") }
    var confirmation by remember(mode) { mutableStateOf("") }
    var showPassword by remember(mode) { mutableStateOf(false) }
    var token by remember(mode) { mutableStateOf("") }
    var pending by remember(mode) { mutableStateOf(false) }
    var failure by remember(mode) { mutableStateOf<String?>(null) }
    var success by remember(mode) { mutableStateOf<String?>(null) }
    val cooldown by api.loginCooldown.collectAsState()
    var retrySeconds by remember(mode, login) { mutableLongStateOf(0) }
    LaunchedEffect(mode, login, cooldown) {
        val limit = cooldown?.takeIf { mode == "login" && it.identifier == login.trim().lowercase() }
        do {
            retrySeconds = limit?.remainingSeconds(Clock.System.now()) ?: 0
            if (retrySeconds > 0) delay(1_000)
        } while (retrySeconds > 0)
    }
    val textKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    val passwordKeyboard = textKeyboard.copy(keyboardType = KeyboardType.Password)
    var operation by remember(mode) { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    fun key(payload: String): String {
        if (operation?.first != payload) operation = payload to randomId()
        return operation!!.second
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeading(when (mode) {
            "register" -> "Crea tu cuenta"
            "recovery" -> "Recupera tu cuenta"
            "reset" -> "Elige una nueva contraseña"
            else -> "Bienvenido de nuevo"
        }, "Tu comunidad también está aquí.")
        when (mode) {
            "login" -> OutlinedTextField(login, { login = it }, label = { Text("Correo o usuario") },
                keyboardOptions = textKeyboard, enabled = !pending, singleLine = true, modifier = Modifier.fillMaxWidth())
            "register" -> {
                OutlinedTextField(email, { email = it }, label = { Text("Correo electrónico") }, keyboardOptions = textKeyboard.copy(keyboardType = KeyboardType.Email), enabled = !pending, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(handle, { handle = it }, label = { Text("Usuario") }, keyboardOptions = textKeyboard.copy(keyboardType = KeyboardType.Ascii), enabled = !pending, supportingText = { Text("4–25 letras, números o guion bajo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            "recovery" -> OutlinedTextField(email, { email = it }, label = { Text("Correo electrónico") }, keyboardOptions = textKeyboard.copy(keyboardType = KeyboardType.Email), enabled = !pending, singleLine = true, modifier = Modifier.fillMaxWidth())
            "reset" -> OutlinedTextField(token, { token = it }, label = { Text("Token del enlace de recuperación") }, keyboardOptions = passwordKeyboard, enabled = !pending, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        if (mode != "recovery") OutlinedTextField(password, { password = it },
            label = { Text(if (mode == "reset") "Nueva contraseña" else "Contraseña") },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = passwordKeyboard, enabled = !pending, singleLine = true, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Ocultar" else "Mostrar") } },
            supportingText = if (mode != "login") ({ Text("Entre 12 y 128 caracteres") }) else null)
        if (mode == "register") OutlinedTextField(confirmation, { confirmation = it },
            label = { Text("Confirma la contraseña") }, keyboardOptions = passwordKeyboard, enabled = !pending,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            isError = confirmation.isNotEmpty() && confirmation != password,
            supportingText = { if (confirmation.isNotEmpty() && confirmation != password) Text("Las contraseñas no coinciden.") })
        failure?.let { Notice(it, true) }
        success?.let { Notice(it) }
        if (retrySeconds > 0) Notice("El acceso está bloqueado temporalmente por intentos fallidos. Espera a que termine el contador o recupera tu contraseña.")
        Button(enabled = !pending && retrySeconds == 0L, modifier = Modifier.fillMaxWidth(), onClick = {
            if (pending || retrySeconds > 0) return@Button
            pending = true; failure = null; success = null
            scope.launch {
                try {
                    when (mode) {
                        "login" -> { require(login.isNotBlank() && password.isNotEmpty()) { "Introduce tu usuario y contraseña." }; api.login(login, password); password = ""; done() }
                        "register" -> {
                            require(email.contains('@') && handle.matches(Regex("[A-Za-z0-9_]{4,25}"))) { "Revisa el correo y el nombre de usuario." }
                            require(codePoints(password) in 12..128 && password.encodeToByteArray().size <= 512) { "La contraseña debe tener entre 12 y 128 caracteres." }
                            require(password == confirmation) { "Las contraseñas no coinciden." }
                            api.register(email, handle, password, key("$email\u0000$handle\u0000$password"))
                            password = ""; confirmation = ""; showPassword = false; operation = null
                            success = "Tu cuenta está lista. Inicia sesión para continuar."
                        }
                        "recovery" -> {
                            require(email.contains('@')) { "Introduce tu correo electrónico." }
                            api.call(HttpMethod.Post, "/api/identity/recovery-requests", P2RecoveryRequest(email), key = key(email))
                            success = "Si la cuenta permite recuperación, recibirás un enlace para restablecer tu contraseña."
                        }
                        "reset" -> {
                            require(token.isNotBlank() && codePoints(password) in 12..128) { "Revisa el token y la nueva contraseña." }
                            api.call(HttpMethod.Post, "/api/identity/recoveries/confirm", P2RecoveryConfirm(token, password), key = key("$token\u0000$password"))
                            token = ""; password = ""; operation = null; api.session.clear()
                            success = "Contraseña actualizada. Inicia sesión de nuevo."
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { failure = e.userMessage() }
                finally { pending = false }
            }
        }) { Text(if (pending) "Procesando…" else if (retrySeconds > 0) "Reintentar en ${retrySeconds / 60}:${(retrySeconds % 60).toString().padStart(2, '0')}"
            else when (mode) { "register" -> "Crear cuenta"; "recovery" -> "Enviar enlace"; "reset" -> "Restablecer contraseña"; else -> "Iniciar sesión" }) }
        if (mode == "login") {
            TextButton(onClick = { navigate("recovery") }) { Text("¿Olvidaste tu contraseña?") }
            OutlinedButton(onClick = { navigate("register") }, modifier = Modifier.fillMaxWidth()) { Text("Crear cuenta") }
        } else {
            TextButton(onClick = { navigate("login") }) { Text("Ir a iniciar sesión") }
            if (mode == "recovery") TextButton(onClick = { navigate("reset") }) { Text("Ya tengo un enlace de recuperación") }
        }
    }
}

@Composable
fun AccountScreen(api: Api, navigate: (String) -> Unit) {
    val login by api.session.state.collectAsState()
    var profile by remember(login?.userId) { mutableStateOf<Profile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(login?.userId) {
        if (login != null) recoverable { api.get<Profile>("/api/profile/me", authenticated = true) }
            .onSuccess { profile = it }.onFailure { error = it.userMessage() }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeading(profile?.displayName ?: "Mi cuenta", profile?.bio?.takeIf { it.isNotBlank() })
        error?.let { Notice(it, true) }
        if (login == null) Button(onClick = { navigate("login") }) { Text("Iniciar sesión") }
        else {
            Text("Sesión activa en Vault tv", color = Muted)
            OutlinedButton(enabled = !working, onClick = {
                working = true; error = null
                scope.launch {
                    try { api.logout(); navigate("explore") }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = "No se pudo cerrar la sesión en el servidor. ${e.userMessage()}" }
                    finally { working = false }
                }
            }) { Text(if (working) "Cerrando sesión…" else "Cerrar sesión") }
        }
    }
}
