package com.etheroal.os.home

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.etheroal.os.agent.LiveSession
import com.etheroal.os.ai.CloudStatus
import com.etheroal.os.data.SessionEntity
import com.etheroal.os.data.SessionStatus
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill
import com.etheroal.os.ui.SectionLabel
import com.etheroal.os.ui.Tag
import com.etheroal.os.ui.glass

/** Places in the side panel besides the sessions. */
enum class Place(val label: String) { NOTIFICATIONS("Notifications"), APPS("Apps"), VAULT("Vault"), MEMORY("Memory"), LAB("Hybrid Lab") }

/**
 * The side panel: is the cloud reachable, what is running, every past conversation, and the
 * places that are not on the home screen.
 */
@Composable
fun SidePanel(
    contentPadding: PaddingValues,
    cloud: CloudStatus,
    live: Map<String, LiveSession>,
    sessions: List<SessionEntity>,
    onCheckCloud: () -> Unit,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPlace: (Place) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxHeight().width(320.dp).glass(MaterialTheme.shapes.large, Halo.GlassRaised.copy(alpha = 0.97f)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Etheroal", style = MaterialTheme.typography.headlineMedium) }
        item { CloudRow(cloud, onCheckCloud) }
        item { Pill("+ New conversation", onNew, filled = true, modifier = Modifier.padding(top = 4.dp)) }
        if (live.isNotEmpty()) {
            item { SectionLabel("Running") }
            items(live.values.toList(), key = { "live_" + it.id }) { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(MaterialTheme.shapes.small).clickable { onOpen(s.id) }.padding(vertical = 6.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Tag(s.question ?: "Step ${s.step} · ${s.status}", if (s.question != null) Halo.Ember else Halo.Periwinkle)
                    }
                    Pill("Stop", { onStop(s.id) })
                }
            }
        }
        item { SectionLabel("Places") }
        items(Place.entries.toList(), key = { it.name }) { p ->
            Text(
                p.label, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onPlace(p) }.padding(vertical = 10.dp, horizontal = 4.dp),
            )
        }
        val past = sessions.filter { it.id !in live }
        if (past.isNotEmpty()) {
            item { SectionLabel("Conversations") }
            items(past, key = { it.id }) { s -> SessionRow(s, onOpen, onDelete) }
        }
    }
}

@Composable
private fun CloudRow(cloud: CloudStatus, onCheck: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onCheck).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when (cloud) {
            is CloudStatus.Ok -> Tag("Cloud ready · ${cloud.latencyMs} ms", Halo.Tide)
            is CloudStatus.Down -> {
                Tag(cloud.title, Halo.Alarm)
                Text(cloud.fix, style = MaterialTheme.typography.bodySmall, color = Halo.Mist, modifier = Modifier.padding(start = 12.dp))
            }
            CloudStatus.Checking -> Tag("Checking the cloud…", Halo.Periwinkle)
            CloudStatus.Unknown -> Tag("Cloud not checked yet · tap to check", Halo.Mist)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: SessionEntity, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { menu = true }, onLongClickLabel = "Delete")
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Tag(
            DateUtils.getRelativeTimeSpanString(s.updatedAt).toString() + when (s.status) {
                SessionStatus.FAILED -> " · didn't finish"
                SessionStatus.STOPPED -> " · stopped"
                else -> ""
            },
            when (s.status) { SessionStatus.DONE -> Halo.Tide; SessionStatus.FAILED, SessionStatus.STOPPED -> Halo.Alarm; else -> Halo.Periwinkle },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Delete conversation and screenshots") }, onClick = { menu = false; onDelete(s.id) })
        }
    }
}
