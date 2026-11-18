package com.etheroal.os.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etheroal.os.EtheroalApp
import com.etheroal.os.MainActivity
import com.etheroal.os.agent.AgentService
import com.etheroal.os.home.CommandBar
import com.etheroal.os.ui.EtheroalTheme
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill
import com.etheroal.os.ui.Tag
import com.etheroal.os.ui.glass
import com.etheroal.os.voice.VoicePanel

/**
 * Etheroal over another app: a sheet at the bottom with the command bar and voice. Whatever the
 * owner asks is sent with the screen they were looking at, so "reply to this" or "add this to my
 * calendar" just works. The task then runs in the background with its status in the status bar.
 */
class AssistActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as EtheroalApp).graph
        setContent {
            EtheroalTheme {
                CompositionLocalProvider(LocalContentColor provides Halo.Pearl) {
                    val screen by AssistContext.screen.collectAsStateWithLifecycle()
                    val live by graph.sessions.live.collectAsStateWithLifecycle()
                    val voice by graph.liveVoice.state.collectAsStateWithLifecycle()
                    var started by remember { mutableStateOf<String?>(null) }
                    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
                        if (ok) runCatching { graph.liveVoice.start() }
                    }
                    Box(
                        Modifier.fillMaxSize().background(Color(0x66000000))
                            .clickable(remember { MutableInteractionSource() }, null) { finish() },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Column(
                            Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
                                .glass(MaterialTheme.shapes.extraLarge, Halo.GlassRaised.copy(alpha = 0.97f))
                                .clickable(remember { MutableInteractionSource() }, null) { }
                                .padding(top = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Etheroal", style = MaterialTheme.typography.titleLarge)
                                val s = screen
                                when {
                                    s == null || (s.text.isBlank() && s.image == null) ->
                                        Tag("Not reading this screen. To let Etheroal see it, turn on “Use text from screen” in Digital assistant settings.", Halo.Mist)
                                    else -> Tag("Sees this screen" + if (s.app.isNotBlank()) " · ${appLabel(s.app)}" else "", Halo.Tide)
                                }
                                started?.let { id ->
                                    val l = live[id]
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (l != null) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Halo.Periwinkle)
                                        Text(l?.let { it.question ?: it.status } ?: "Finished · open to see the result", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        Pill("Open", { openInEtheroal(id) })
                                    }
                                }
                            }
                            VoicePanel(voice, onStop = graph.liveVoice::stop)
                            CommandBar(
                                onSend = { text -> started = graph.sessions.start(text, AssistContext.screen.value) },
                                onVoice = {
                                    if (voice is com.etheroal.os.voice.LiveState.Live) graph.liveVoice.stop()
                                    else if (ContextCompat.checkSelfPermission(this@AssistActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) graph.liveVoice.start()
                                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                onApps = null,
                                listening = voice is com.etheroal.os.voice.LiveState.Live,
                                placeholder = "Ask about this screen…",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun openInEtheroal(id: String) {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AgentService.EXTRA_SESSION, id))
        finish()
    }

    override fun onDestroy() {
        AssistContext.finish()
        super.onDestroy()
    }
}
