package com.etheroal.os.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.etheroal.os.ui.GlassPanel
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill

/** The live voice conversation: a breathing orb, what Etheroal heard, what it is saying, and Stop. */
@Composable
fun VoicePanel(state: LiveState, onStop: () -> Unit, modifier: Modifier = Modifier) {
    if (state is LiveState.Idle) return
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        0.85f, 1.1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "orb",
    )
    GlassPanel(modifier.fillMaxWidth().padding(horizontal = 12.dp), accent = Halo.Periwinkle) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(40.dp).scale(if (state is LiveState.Live) pulse else 1f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Halo.Pearl, Halo.Periwinkle, Halo.Ember))),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (state) {
                    LiveState.Connecting -> Text("Connecting…", style = MaterialTheme.typography.bodyMedium, color = Halo.Mist)
                    is LiveState.Live -> {
                        Text(state.heard.ifEmpty { "Listening…" }, style = MaterialTheme.typography.bodyMedium, color = Halo.Mist, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (state.said.isNotEmpty()) Text(state.said, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    is LiveState.Error -> Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Halo.Alarm)
                    LiveState.Idle -> Unit
                }
            }
            Pill(if (state is LiveState.Error) "Close" else "Stop", onStop)
        }
    }
}
