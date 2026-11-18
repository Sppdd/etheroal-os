package com.etheroal.os.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etheroal.os.agent.SessionManager
import com.etheroal.os.data.MessageEntity
import com.etheroal.os.data.MessageRole
import com.etheroal.os.ui.GlassPanel
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill
import com.etheroal.os.ui.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A conversation with Etheroal: what the owner asked, what Etheroal answered, and between them the
 * full transcript of how it got there — every screen it looked at (with the screenshot), every
 * thought, every action and how long each took — like a coding agent's log.
 */
@Composable
fun ConversationScreen(
    vm: ConversationViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    composer: @Composable () -> Unit,
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val live by vm.currentLive.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    var enlarged by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(messages.size, live?.question) {
        val last = messages.size + (if (live != null) 1 else 0)
        if (last > 0) list.animateScrollToItem(last - 1 + 1)
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("‹ Home", onBack)
            Text(
                title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (live != null) Pill("Stop", { vm.stop() })
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty() && live == null) {
                item { EmptyHint() }
            }
            items(messages, key = { it.id }) { m -> MessageRow(m) { enlarged = it } }
            live?.let { l ->
                item(key = "live") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        l.question?.let { q ->
                            GlassPanel(Modifier.fillMaxWidth(), accent = Halo.Ember) {
                                Text(if (l.secret) "Etheroal needs you to unlock the vault" else "Etheroal needs your OK", style = MaterialTheme.typography.labelLarge, color = Halo.Ember)
                                Text(q, style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Pill("Allow", { vm.answer(true) }, filled = true)
                                    Pill("Skip", { vm.answer(false) })
                                    Pill("Stop", { vm.stop() })
                                }
                            }
                        } ?: Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Halo.Periwinkle)
                            Text("${l.status}  ·  step ${l.step}", style = MaterialTheme.typography.bodySmall, color = Halo.Mist)
                        }
                    }
                }
            }
            item(key = "end") { Box(Modifier.size(1.dp)) }
        }
        composer()
    }

    enlarged?.let { path ->
        Dialog(onDismissRequest = { enlarged = null }) {
            Shot(path, Modifier.fillMaxWidth().clickable { enlarged = null }, full = true)
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.padding(top = 32.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What should Etheroal do?", style = MaterialTheme.typography.headlineMedium)
        listOf(
            "Set a timer for 10 minutes",
            "What's the weather in Manama tomorrow?",
            "Open Clock and start the stopwatch",
            "Text Sam on WhatsApp that I'm running late",
        ).forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium, color = Halo.Mist) }
    }
}

@Composable
private fun MessageRow(m: MessageEntity, onShot: (String) -> Unit) {
    val operator = m.agent == SessionManager.OPERATOR
    when (m.role) {
        MessageRole.USER -> Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                m.text, style = MaterialTheme.typography.bodyLarge, color = Halo.Ink,
                modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(Halo.Pearl)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        MessageRole.ASSISTANT -> GlassPanel(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), accent = if (m.ok) null else Halo.Alarm) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
        }
        MessageRole.NOTICE -> Text(
            m.text, style = MaterialTheme.typography.bodySmall, color = if (m.ok) Halo.Tide else Halo.Ember,
            modifier = Modifier.padding(start = 4.dp),
        )
        MessageRole.THOUGHT -> Step(operator) { Thought(m) }
        MessageRole.SCREEN -> Step(operator) {
            Line("◉", Halo.Periwinkle, m.text, m.durationMs)
            m.imagePath?.let { p ->
                Shot(p, Modifier.padding(start = 20.dp, top = 4.dp).heightIn(max = 220.dp).clickable(role = Role.Image) { onShot(p) })
            }
        }
        MessageRole.STEP -> Step(operator) {
            val head = m.text.substringBefore('\n')
            val note = m.text.substringAfter('\n', "")
            Line(if (m.ok) "●" else "✕", if (m.ok) Halo.Tide else Halo.Alarm, head, m.durationMs)
            if (note.isNotBlank()) Text(
                "⎿ $note", style = MaterialTheme.typography.bodySmall, color = Halo.Mist, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}

/** Operator steps are indented under a thin rule, so the hand-off from Etheroal to the operator reads clearly. */
@Composable
private fun Step(operator: Boolean, content: @Composable () -> Unit) {
    if (!operator) { Column { content() }; return }
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.padding(start = 6.dp, end = 12.dp).width(2.dp).fillMaxHeight().background(Color(0x4493A0FF)))
        Column(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun Line(mark: String, color: Color, text: String, ms: Long?) {
    Row(verticalAlignment = Alignment.Top) {
        Text(mark, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ms?.let { Text(seconds(it), style = MaterialTheme.typography.labelSmall, color = Halo.Mist, modifier = Modifier.padding(start = 8.dp)) }
    }
}

/** "✻ Thought for 1.4s", expandable: the model's own summary of its reasoning. */
@Composable
private fun Thought(m: MessageEntity) {
    var open by rememberSaveable(m.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.animateContentSize().padding(vertical = 2.dp),
    ) {
        Row {
            Text("✻", color = Halo.Ember, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            Text(
                "Thought" + (m.durationMs?.let { " for ${seconds(it)}" } ?: "") + if (open) "" else "  ›",
                style = MaterialTheme.typography.bodyMedium, color = Halo.Mist,
            )
        }
        Text(
            m.text, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = Halo.Mist,
            maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

@Composable
private fun Shot(path: String, modifier: Modifier, full: Boolean = false) {
    val bmp by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = if (full) 1 else 2 })
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(
            it.asImageBitmap(), contentDescription = "Screenshot the operator looked at",
            contentScale = ContentScale.Fit,
            modifier = modifier.glass(RoundedCornerShape(12.dp)),
        )
    }
}

private fun seconds(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
