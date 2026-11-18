package com.etheroal.os.mind

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etheroal.os.ui.GlassPanel
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill
import com.etheroal.os.ui.SectionLabel

/** Everything Etheroal has learned about the owner, in plain sentences, each one deletable. */
@Composable
fun MemoryScreen(mind: Mind, contentPadding: PaddingValues) {
    val memories by mind.memories.collectAsStateWithLifecycle(emptyList())
    val learning by mind.learning.collectAsStateWithLifecycle()
    var habits by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(memories.size, learning) { habits = mind.context().first }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            GlassPanel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Learn from me", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = learning, onCheckedChange = mind::setLearning)
                }
                Text(
                    "After a conversation, a small model notes lasting facts (who people are, what you prefer). Etheroal also counts which apps you " +
                        "open when and which notifications you answer, never their content. All of it stays encrypted on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = Halo.Mist,
                )
            }
        }
        if (habits.isNotBlank()) {
            item { SectionLabel("Habits it has noticed") }
            item { GlassPanel(Modifier.fillMaxWidth()) { Text(habits, style = MaterialTheme.typography.bodyMedium) } }
        }
        item { SectionLabel("What it remembers · ${memories.size}") }
        if (memories.isEmpty()) item { Text("Nothing yet. Tell Etheroal “remember that…” or just talk to it.", color = Halo.Mist, style = MaterialTheme.typography.bodyMedium) }
        items(memories, key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        (if (m.source == "you") "You asked · " else "Learned from a conversation · ") + DateUtils.getRelativeTimeSpanString(m.createdAt),
                        style = MaterialTheme.typography.labelSmall, color = Halo.Mist,
                    )
                }
                Pill("Forget", { mind.forget(m.id) })
            }
        }
        item {
            if (confirmClear) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Yes, forget everything", { mind.clearAll(); confirmClear = false }, filled = true)
                Pill("Cancel", { confirmClear = false })
            } else Pill("Forget everything", { confirmClear = true })
        }
    }
}
