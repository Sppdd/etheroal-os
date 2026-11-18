package com.etheroal.os.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.etheroal.os.agent.EtheroalAccessibilityService
import com.etheroal.os.agent.LiveSession
import com.etheroal.os.feed.Feed
import com.etheroal.os.feed.FeedCard
import com.etheroal.os.triage.NotificationCaptureService
import com.etheroal.os.ui.GlassPanel
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.SectionLabel
import com.etheroal.os.ui.Tag
import com.etheroal.os.ui.glass

/**
 * The home screen: time, what is running, the few things that matter now, and nothing else.
 * Everything else is reached through the command bar at the bottom or the side panel.
 */
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    feed: Feed,
    live: Map<String, LiveSession>,
    onOpenPanel: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    suggested: List<AppEntry> = emptyList(),
    onLaunch: (AppEntry) -> Unit = {},
    commandBar: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { ClockHeader() }
                Box(
                    Modifier.padding(top = 28.dp).size(48.dp).clip(CircleShape).glass(CircleShape)
                        .clickable(role = Role.Button, onClick = onOpenPanel).semantics { contentDescription = "Sessions and settings" },
                    contentAlignment = Alignment.Center,
                ) { Text("☰", style = MaterialTheme.typography.titleMedium) }
            }
            SetupCard()
            if (suggested.isNotEmpty()) {
                SectionLabel("You usually open")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    suggested.forEach { a ->
                        Text(
                            a.label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            modifier = Modifier.clip(CircleShape).glass(CircleShape).clickable { onLaunch(a) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            if (live.isNotEmpty()) {
                SectionLabel("Running")
                live.values.forEach { s ->
                    GlassPanel(Modifier.fillMaxWidth().clickable { onOpenSession(s.id) }, accent = if (s.question != null) Halo.Ember else Halo.Periwinkle) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.question == null) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = Halo.Periwinkle)
                            Text(s.question ?: s.status, style = MaterialTheme.typography.bodySmall, color = if (s.question != null) Halo.Ember else Halo.Mist, maxLines = 2)
                        }
                    }
                }
            }
            val now = (feed.urgent + feed.needsReply).take(3)
            if (now.isNotEmpty()) {
                SectionLabel("Now")
                now.forEach { NowCard(it, onOpenNotifications) }
            }
            val rest = feed.urgent.size + feed.needsReply.size + feed.fyi.size + feed.waiting.size - now.size
            if (rest > 0) {
                Text(
                    "$rest more sorted notifications ›", style = MaterialTheme.typography.bodyMedium, color = Halo.Mist,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenNotifications).padding(8.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
        }
        commandBar()
    }
}

@Composable
private fun NowCard(card: FeedCard, onOpen: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth().clickable(onClick = onOpen), accent = if (card.latest.urgency == "URGENT") Halo.Alarm else null) {
        Text("${card.appLabel} · ${card.title}", style = MaterialTheme.typography.labelMedium, color = Halo.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The bar at the bottom of every main screen: voice, a prompt field, and the apps grid.
 * Sending opens the conversation.
 */
@Composable
fun CommandBar(
    onSend: (String) -> Unit,
    onVoice: () -> Unit,
    onApps: (() -> Unit)?,
    listening: Boolean = false,
    placeholder: String = "Ask or tell Etheroal…",
) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = { if (text.isNotBlank()) { onSend(text); text = "" } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(if (listening) "■" else "🎙", if (listening) "Stop listening" else "Talk to Etheroal", onVoice, glow = listening)
        val clear = Color.Transparent
        TextField(
            value = text,
            // The keyboard's Enter key sends: a newline typed at the end means "send".
            onValueChange = { v -> if (v.endsWith("\n")) { text = v.trimEnd(); send() } else text = v },
            placeholder = { Text(placeholder, color = Halo.Mist, maxLines = 1) },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = clear, unfocusedContainerColor = clear,
                focusedIndicatorColor = clear, unfocusedIndicatorColor = clear,
            ),
            modifier = Modifier.weight(1f).glass(MaterialTheme.shapes.extraLarge, Halo.GlassRaised),
        )
        if (text.isNotBlank()) RoundButton("↑", "Send", send, filled = true)
        else onApps?.let { RoundButton("▦", "All apps", it) }
    }
}

@Composable
private fun RoundButton(label: String, description: String, onClick: () -> Unit, filled: Boolean = false, glow: Boolean = false) {
    Box(
        Modifier.size(52.dp).clip(CircleShape)
            .then(
                when {
                    filled -> Modifier.background(Halo.Pearl)
                    glow -> Modifier.background(Brush.linearGradient(listOf(Halo.Periwinkle, Halo.Ember)))
                    else -> Modifier.glass(CircleShape, Halo.GlassRaised)
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.titleMedium, color = if (filled) Halo.Ink else Halo.Pearl) }
}

/** What Etheroal still needs from the owner to work fully. Disappears when everything is granted. */
@Composable
private fun SetupCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresh++; onPauseOrDispose { } }
    val steps = remember(refresh) { setupSteps(context) }
    if (steps.isEmpty()) return
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("Finish setting up Etheroal", style = MaterialTheme.typography.titleMedium)
        steps.forEach { (label, why, intent) ->
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                    .clickable { runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    .padding(vertical = 6.dp),
            ) {
                Tag(label, Halo.Ember)
                Text(why, style = MaterialTheme.typography.bodySmall, color = Halo.Mist, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

private fun setupSteps(context: Context): List<Triple<String, String, Intent>> = buildList {
    if (!AppCatalog.isDefaultHome(context)) add(
        Triple("Make Etheroal your home screen", "So it is there every time you go home. Switch back any time in Default apps.", AppCatalog.defaultAppsSettings()),
    )
    if (!NotificationCaptureService.isEnabled(context)) add(
        Triple("Let it read notifications", "To sort them for you. If the switch is greyed out: App info › ⋮ › Allow restricted settings.", NotificationCaptureService.settingsIntent()),
    )
    if (EtheroalAccessibilityService.instance == null) add(
        Triple("Let it use apps for you", "Turn on “Etheroal tasks” in Accessibility. It acts only during tasks you start, and asks before it sends, pays or signs in.", Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
    )
    val assistant = Settings.Secure.getString(context.contentResolver, "assistant").orEmpty()
    if (!assistant.startsWith(context.packageName)) add(
        Triple("Call it from any app", "Choose Etheroal as Digital assistant, then long-press power or home anywhere. Turn on “Use text from screen” so it can see what you see.",
            AppCatalog.defaultAppsSettings()),
    )
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(
        Triple("Allow its notifications", "To show running tasks in the status bar and ask you before sensitive steps.",
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)),
    )
}
