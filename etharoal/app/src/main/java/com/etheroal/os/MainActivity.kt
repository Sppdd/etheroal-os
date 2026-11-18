package com.etheroal.os

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.etheroal.os.agent.AgentService
import com.etheroal.os.conversation.ConversationScreen
import com.etheroal.os.conversation.ConversationViewModel
import com.etheroal.os.feed.FeedScreen
import com.etheroal.os.feed.FeedViewModel
import com.etheroal.os.home.AppDrawer
import com.etheroal.os.home.CommandBar
import com.etheroal.os.home.HomeScreen
import com.etheroal.os.home.Place
import com.etheroal.os.home.SidePanel
import com.etheroal.os.lab.HybridLabScreen
import com.etheroal.os.lab.HybridLabViewModel
import com.etheroal.os.ui.Atmosphere
import com.etheroal.os.ui.EtheroalTheme
import com.etheroal.os.ui.Halo
import com.etheroal.os.ui.Pill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where the main window is. The home screen is the default and the place Home and Back return to. */
sealed interface Screen {
    data object Home : Screen
    data object Conversation : Screen
    data class At(val place: Place) : Screen
}

class MainActivity : ComponentActivity() {

    companion object {
        /** Opens one of the side panel's places, e.g. "NOTIFICATIONS" from Etheroal's alerts. */
        const val EXTRA_PLACE = "place"
    }

    private val graph get() = (application as EtheroalApp).graph
    private val lab: HybridLabViewModel by viewModels { HybridLabViewModel.factory(graph) }
    private val feed: FeedViewModel by viewModels { FeedViewModel.factory(application, graph) }
    private val chat: ConversationViewModel by viewModels { ConversationViewModel.factory(graph) }

    /** Home button presses (Etheroal is the launcher) and "open this session" from notifications. */
    private val nav = MutableStateFlow<Pair<Int, String?>>(0 to null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val session = intent.getStringExtra(AgentService.EXTRA_SESSION)
            ?: intent.getStringExtra(EXTRA_PLACE)?.let { "place:$it" }
        if (session != null || intent.hasCategory(Intent.CATEGORY_HOME)) nav.value = (nav.value.first + 1) to session
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The interface is always dark, so the system bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        AgentService.channels(this)
        handle(intent)
        setContent { EtheroalTheme { Root() } }
    }

    @Composable
    private fun Root() {
        var screen by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val navEvent by nav.collectAsState()
        val live by chat.live.collectAsStateWithLifecycle()
        val recent by chat.recent.collectAsStateWithLifecycle()
        val cloud by graph.cloudHealth.status.collectAsStateWithLifecycle()
        val feedState by feed.feed.collectAsStateWithLifecycle()
        val apps by graph.apps.apps.collectAsStateWithLifecycle()
        // Apps the owner usually opens around now, from habit counts; refreshed when home is shown.
        var suggestedKeys by androidx.compose.runtime.remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(screen, navEvent) { if (screen == Screen.Home) suggestedKeys = graph.mind.suggestedApps() }
        val suggested = suggestedKeys.mapNotNull { pkg -> apps.firstOrNull { it.component.packageName == pkg } }

        LaunchedEffect(navEvent) {
            if (navEvent.first == 0) return@LaunchedEffect
            drawer.close()
            val session = navEvent.second
            when {
                session == null -> screen = Screen.Home
                session.startsWith("place:") -> screen = runCatching { Screen.At(Place.valueOf(session.removePrefix("place:"))) }.getOrDefault(Screen.Home)
                else -> { chat.open(session); screen = Screen.Conversation }
            }
        }
        BackHandler(enabled = true) {
            if (drawer.isOpen) scope.launch { drawer.close() } else screen = Screen.Home
        }

        val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val voiceState by graph.liveVoice.state.collectAsStateWithLifecycle()
        val privacy by graph.privacy.collectAsStateWithLifecycle()
        val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) runCatching { graph.liveVoice.start() }
        }
        val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard ->
                chat.send(heard); screen = Screen.Conversation
            }
        }
        fun send(text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            chat.send(text)
            screen = Screen.Conversation
        }
        fun voice() {
            // Voice is a live conversation with Gemini in the cloud. In Private mode, or if the owner
            // declines the microphone, the phone's own speech-to-text turns speech into a typed prompt.
            if (voiceState is com.etheroal.os.voice.LiveState.Live || voiceState is com.etheroal.os.voice.LiveState.Connecting) {
                graph.liveVoice.stop(); return
            }
            if (privacy == PrivacyMode.CLOUD) {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) graph.liveVoice.start()
                else mic.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell Etheroal what to do")
            runCatching { speech.launch(intent) }
        }

        val bars = WindowInsets.systemBars.asPaddingValues()
        val imeBottom = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
        val padding = PaddingValues(top = bars.calculateTopPadding(), bottom = maxOf(bars.calculateBottomPadding(), imeBottom))

        Box(Modifier.fillMaxSize().background(Halo.Ink)) {
            Atmosphere(Modifier.fillMaxSize())
            CompositionLocalProvider(LocalContentColor provides Halo.Pearl) {
                ModalNavigationDrawer(
                    drawerState = drawer,
                    scrimColor = Color(0x99000000),
                    drawerContent = {
                        SidePanel(
                            contentPadding = bars, cloud = cloud, live = live, sessions = recent,
                            onCheckCloud = { scope.launch { graph.cloudHealth.check() } },
                            onNew = { chat.open(null); screen = Screen.Conversation; scope.launch { drawer.close() } },
                            onOpen = { chat.open(it); screen = Screen.Conversation; scope.launch { drawer.close() } },
                            onStop = chat::stop,
                            onDelete = chat::delete,
                            onPlace = { screen = Screen.At(it); scope.launch { drawer.close() } },
                        )
                    },
                ) {
                    when (val s = screen) {
                        Screen.Home -> HomeScreen(
                            contentPadding = padding, feed = feedState, live = live,
                            onOpenPanel = { scope.launch { drawer.open() } },
                            onOpenSession = { chat.open(it); screen = Screen.Conversation },
                            onOpenNotifications = { screen = Screen.At(Place.NOTIFICATIONS) },
                            suggested = suggested,
                            onLaunch = graph.apps::launch,
                        ) {
                            Column {
                                com.etheroal.os.voice.VoicePanel(voiceState, onStop = graph.liveVoice::stop)
                                CommandBar(onSend = ::send, onVoice = ::voice, onApps = { screen = Screen.At(Place.APPS) }, listening = voiceState is com.etheroal.os.voice.LiveState.Live)
                            }
                        }

                        Screen.Conversation -> ConversationScreen(chat, padding, onBack = { screen = Screen.Home }) {
                            Column {
                                com.etheroal.os.voice.VoicePanel(voiceState, onStop = graph.liveVoice::stop)
                                CommandBar(onSend = ::send, onVoice = ::voice, onApps = null, placeholder = "Reply to Etheroal…", listening = voiceState is com.etheroal.os.voice.LiveState.Live)
                            }
                        }

                        is Screen.At -> Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().padding(top = bars.calculateTopPadding() + 4.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Pill("‹ Home", { screen = Screen.Home })
                                Text(s.place.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                            }
                            val inner = PaddingValues(bottom = padding.calculateBottomPadding())
                            Box(Modifier.weight(1f)) {
                                when (s.place) {
                                    Place.NOTIFICATIONS -> FeedScreen(feed, inner)
                                    Place.APPS -> AppDrawer(graph.apps, inner)
                                    Place.LAB -> HybridLabScreen(lab)
                                    Place.VAULT -> com.etheroal.os.vault.VaultScreen(graph.vault, inner)
                                    Place.MEMORY -> com.etheroal.os.mind.MemoryScreen(graph.mind, inner)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PlaceholderPlace(text: String) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Halo.Mist)
        }
    }
}

/** Saves the current screen across configuration changes. */
private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>(
    save = { s -> when (s) { Screen.Home -> "home"; Screen.Conversation -> "chat"; is Screen.At -> s.place.name } },
    restore = { v -> when (v) { "home" -> Screen.Home; "chat" -> Screen.Conversation; else -> runCatching { Screen.At(Place.valueOf(v)) }.getOrDefault(Screen.Home) } },
)
