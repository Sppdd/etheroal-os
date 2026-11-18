package com.etheroal.os.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etheroal.os.ui.GlassPanel
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill
import com.etheroal.os.ui.Tag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The owner's view of the vault. Adding, revealing and deleting all need an unlock; the list itself
 * shows only labels and masked usernames, the same view the agent gets.
 */
@Composable
fun VaultScreen(vault: Vault, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val entries by vault.entries.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf<Pair<Long, String>?>(null) }

    LaunchedEffect(revealed) { if (revealed != null) { delay(15_000); revealed = null } }

    /** Unlocks, then runs [block] inside the 30-second window. */
    fun unlocked(reason: String, block: suspend () -> Unit) = scope.launch {
        if (!vault.deviceSecure()) { message = "Set a screen lock on this phone first: the vault is protected by it."; return@launch }
        if (!VaultUnlockActivity.unlock(context, reason)) { message = "Not unlocked."; return@launch }
        runCatching { block() }.onFailure { message = if (it is VaultLockedException) "The vault stayed locked. Try again." else "That didn't work." }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Logins Etheroal can use to sign in for you. Each use asks for your fingerprint or screen lock. " +
                    "Passwords never go to any AI model: the phone puts them straight into the app's password field.",
                style = MaterialTheme.typography.bodyMedium, color = Halo.Mist,
            )
        }
        message?.let { m -> item { Tag(m, Halo.Ember) } }
        item {
            if (adding) AddLogin(onCancel = { adding = false }) { label, target, user, pass ->
                unlocked("Save the $label login") {
                    vault.save(null, label, target, user, pass)
                    adding = false
                    message = "Saved $label."
                }
            } else Pill("+ Add a login", { adding = true; message = null }, filled = true)
        }
        items(entries, key = { it.id }) { e ->
            GlassPanel(Modifier.fillMaxWidth()) {
                Text(e.label, style = MaterialTheme.typography.titleMedium)
                Text("${e.target} · ${e.usernameHint}", style = MaterialTheme.typography.bodySmall, color = Halo.Mist)
                revealed?.takeIf { it.first == e.id }?.let { Text(it.second, style = MaterialTheme.typography.bodyLarge) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Show for 15 s", { unlocked("Show the ${e.label} password") { revealed = e.id to (vault.read(e.id)?.secret ?: "") } })
                    Pill("Delete", { unlocked("Delete the ${e.label} login") { vault.delete(e.id); message = "Deleted ${e.label}." } })
                }
            }
        }
    }
}

@Composable
private fun AddLogin(onCancel: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    GlassPanel(Modifier.fillMaxWidth()) {
        OutlinedTextField(label, { label = it }, label = { Text("Name, e.g. Uber") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(target, { target = it }, label = { Text("App or website, e.g. uber.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Username or email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Save", { onSave(label, target, user, pass) }, filled = true, enabled = label.isNotBlank() && pass.isNotBlank())
            Pill("Cancel", onCancel)
        }
    }
}
