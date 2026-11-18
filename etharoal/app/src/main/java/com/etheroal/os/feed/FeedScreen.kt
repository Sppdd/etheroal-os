package com.etheroal.os.feed

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etheroal.os.PrivacyMode
import com.etheroal.os.triage.NotificationCaptureService
import com.etheroal.os.ui.GlassPanel
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill
import com.etheroal.os.ui.SectionLabel
import com.etheroal.os.ui.Tag
import kotlinx.coroutines.delay

@Composable
fun FeedScreen(vm: FeedViewModel, contentPadding: PaddingValues, header: @Composable () -> Unit = {}) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val access by vm.accessGranted.collectAsStateWithLifecycle()
    val queue by vm.queueStatus.collectAsStateWithLifecycle()
    val lastDone by vm.lastDone.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val quiet by vm.quietShade.collectAsStateWithLifecycle()
    var showFiled by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var confirmFast by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    if (confirmFast) {
        AlertDialog(
            onDismissRequest = { confirmFast = false },
            title = { Text("Use the cloud for speed?") },
            text = {
                Text(
                    "Notifications will be sorted by Gemini in the cloud instead of on this phone. Email addresses, " +
                        "phone numbers, links, card numbers and codes are masked first; the rest of the text, " +
                        "including names, is sent. You can switch back at any time.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.setSpeed(PrivacyMode.CLOUD); confirmFast = false }) { Text("Use cloud") } },
            dismissButton = { TextButton(onClick = { confirmFast = false }) { Text("Stay private") } },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { header() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Where Etheroal thinks")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Private · on phone", { vm.setSpeed(PrivacyMode.PRIVATE) }, filled = speed == PrivacyMode.PRIVATE)
                    Pill("Fast · cloud", { if (speed != PrivacyMode.CLOUD) confirmFast = true }, filled = speed == PrivacyMode.CLOUD)
                }
                Text(
                    if (speed == PrivacyMode.PRIVATE) "Notifications are sorted on this phone. Their text never leaves it."
                    else "Sorted in the cloud. Emails, numbers, links and codes are masked first; the phone takes over when offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Halo.Mist,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        item {
            GlassPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quiet the shade", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = quiet, onCheckedChange = vm::setQuietShade)
                }
                Text(
                    if (quiet) "Only what matters stays in your notification shade, as Etheroal's own alert. Everything else waits here and in one quiet digest; replies still work."
                    else "Etheroal sorts notifications but leaves the shade as it is. Turn on to keep only what matters in the shade.",
                    style = MaterialTheme.typography.bodySmall, color = Halo.Mist,
                )
            }
        }
        if (!access) {
            item {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Text("Let Etheroal read notifications", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Etheroal sorts them and keeps them encrypted on this phone. One-time codes are " +
                            "hidden from it by Android. If the switch is greyed out, open Etheroal's App info, " +
                            "tap ⋮ › Allow restricted settings, then try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Halo.Mist,
                    )
                    Pill("Open notification access", { context.startActivity(NotificationCaptureService.settingsIntent()) }, filled = true)
                }
            }
        }
        lastDone?.let { done ->
            item(key = "undo") {
                LaunchedEffect(done) { delay(8_000); vm.clearUndo() }
                GlassPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Marked “${done.title.ifBlank { done.appLabel }}” as done",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                        )
                        Pill("Undo", vm::undoDone)
                    }
                }
            }
        }
        queue?.let { status ->
            item {
                Row(
                    Modifier.padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = Halo.Mist)
                }
            }
        }
        section("Urgent", feed.urgent, drafts, vm)
        section("Needs a reply", feed.needsReply, drafts, vm)
        section("For your information", feed.fyi, drafts, vm)
        section("Not sorted yet", feed.waiting, drafts, vm)
        if (feed.filed.isNotEmpty()) {
            item(key = "filed") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Filed · ${feed.filed.size}", Modifier.weight(1f))
                    Pill(if (showFiled) "Hide" else "Show", { showFiled = !showFiled })
                }
            }
            if (showFiled) items(feed.filed, key = { "f_" + it.conversation }) { card ->
                GlassPanel(Modifier.fillMaxWidth()) {
                    Text("${card.appLabel} · ${card.title}", style = MaterialTheme.typography.labelMedium, color = Halo.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(card.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Tag("Filed: ${card.latest.reason ?: "low priority"}", Halo.Mist)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Not junk, keep it", { vm.unfile(card) })
                        Pill("Done", { vm.dismiss(card) })
                    }
                }
            }
        }
        if (access && feed.urgent.isEmpty() && feed.needsReply.isEmpty() && feed.fyi.isEmpty() && feed.waiting.isEmpty() && feed.filed.isEmpty()) {
            item {
                Text(
                    "All clear. New notifications will show up here, sorted.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Halo.Mist,
                    modifier = Modifier.padding(top = 24.dp, start = 4.dp),
                )
            }
        }
    }
}

private fun LazyListScope.section(title: String, cards: List<FeedCard>, drafts: Map<String, DraftState>, vm: FeedViewModel) {
    if (cards.isEmpty()) return
    item(key = "h_$title") { SectionLabel("$title · ${cards.size}") }
    items(cards, key = { it.conversation }) { card -> FeedCardView(card, drafts[card.conversation], vm) }
}

@Composable
private fun FeedCardView(card: FeedCard, draft: DraftState?, vm: FeedViewModel) {
    val urgent = card.latest.urgency == "URGENT"
    GlassPanel(Modifier.fillMaxWidth(), accent = if (urgent) Halo.Alarm else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.appLabel + (if (card.count > 1) " · ${card.count} messages" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = Halo.Mist,
                modifier = Modifier.weight(1f),
            )
            Text(
                DateUtils.getRelativeTimeSpanString(card.latest.postedAt).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = Halo.Mist,
            )
        }
        if (card.title.isNotBlank()) Text(card.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(card.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (card.triaged) {
            val onDevice = card.latest.source == "ON_DEVICE"
            Tag(
                (if (onDevice) "Sorted on this phone" else "Sorted in the cloud") + (card.latest.reason?.let { " · $it" } ?: ""),
                if (onDevice) Halo.Tide else Halo.Ember,
            )
        } else if (card.latest.failure != null) {
            Text("Couldn't sort: ${card.latest.failure}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        DraftArea(card, draft, vm)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.latest.canReply && draft == null) Pill("Draft reply", { vm.draft(card) }, filled = true)
            Pill("Open", { vm.open(card) })
            Pill("Done", { vm.dismiss(card) })
        }
    }
}

@Composable
private fun DraftArea(card: FeedCard, draft: DraftState?, vm: FeedViewModel) {
    when (draft) {
        null -> Unit
        DraftState.Writing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Drafting…", style = MaterialTheme.typography.bodySmall)
        }
        is DraftState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = draft.text,
                onValueChange = { vm.edit(card, it) },
                label = { Text("Your reply") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Drafted ${draft.source.lowercase()}" + (if (draft.note.isNotBlank()) " · ${draft.note}" else "") +
                    ". It's sent only when you tap Send.",
                style = MaterialTheme.typography.bodySmall,
                color = Halo.Mist,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Send", { vm.send(card) }, filled = true, enabled = draft.text.isNotBlank())
                Pill("Discard", { vm.discardDraft(card) })
            }
        }
        is DraftState.Sent -> Text("Sent: “${draft.text}”", style = MaterialTheme.typography.bodySmall, color = Halo.Mist)
        is DraftState.Error -> Column {
            Text(draft.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Pill("OK", { vm.discardDraft(card) })
        }
    }
}
