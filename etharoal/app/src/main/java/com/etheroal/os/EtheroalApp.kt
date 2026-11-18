package com.etheroal.os

import android.app.Application
import com.etheroal.os.ai.AndroidDeviceState
import com.etheroal.os.ai.FirebaseInferenceEngine
import com.etheroal.os.ai.HybridEngine
import com.etheroal.os.ai.LocalLlm
import com.etheroal.os.ai.QuickActions
import com.etheroal.os.ai.OnDeviceModelManager
import com.etheroal.os.ai.RemoteAiConfig
import com.etheroal.os.core.ai.HybridMode
import com.etheroal.os.core.ai.InferenceRouter
import com.etheroal.os.data.EtheroalDb
import com.etheroal.os.home.AppCatalog
import com.etheroal.os.triage.TriageQueue
import com.etheroal.os.voice.OfflineVoice
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.etheroal.os.core.ai.RoutingConfig
import com.etheroal.os.ai.Tasks
import com.etheroal.os.core.ai.LedgerEntry
import com.google.firebase.Firebase
import com.google.firebase.initialize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** In-memory privacy ledger for now; it moves to the encrypted database with the triage feature. */
class MemoryLedger {
    private val _entries = MutableStateFlow<List<LedgerEntry>>(emptyList())
    val entries: StateFlow<List<LedgerEntry>> = _entries
    fun record(entry: LedgerEntry) = _entries.update { (listOf(entry) + it).take(200) }
}

/** Where the Hybrid Lab asks requests to run. AUTO lets the router decide. */
enum class EngineChoice(val forced: HybridMode?) {
    AUTO(null),
    ON_DEVICE(HybridMode.ONLY_ON_DEVICE),
    CLOUD(HybridMode.ONLY_IN_CLOUD),
}

/**
 * Where Etheroal thinks. CLOUD (the default) lets every task, sorting included, use Gemini in the
 * cloud after emails, phone numbers, links, card numbers and codes are masked. PRIVATE keeps
 * notification text on the phone: it is sorted by Gemini Nano where the phone has it, or waits.
 */
enum class PrivacyMode { CLOUD, PRIVATE }

/** Hand-wired object graph. Small enough that a DI framework would add more risk than it removes. */
class AppGraph(app: Application) {
    val config = RemoteAiConfig()
    val firebase = FirebaseInferenceEngine(config)
    val models = OnDeviceModelManager(firebase)
    val local = LocalLlm(app)
    val deviceState = AndroidDeviceState(app, models, local)
    val ledger = MemoryLedger()

    /**
     * Testing switch. It works through the router's normal per-task override, so the privacy rules
     * still apply: a PRIVATE task is never sent to the cloud, even when "Cloud" is chosen.
     */
    val engineChoice = MutableStateFlow(EngineChoice.AUTO)

    private val settings = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val privacy = MutableStateFlow(
        runCatching { PrivacyMode.valueOf(settings.getString("privacy", null) ?: config.defaultPrivacyMode) }
            .getOrDefault(PrivacyMode.CLOUD),
    )

    fun setPrivacy(mode: PrivacyMode) {
        privacy.value = mode
        settings.edit().putString("privacy", mode.name).apply()
        triage.kick()
    }

    private fun routing(): RoutingConfig {
        var base = config.routing()
        if (privacy.value == PrivacyMode.CLOUD) {
            // Remote configuration still wins over the user's switch, so it can act as a kill switch.
            base = base.copy(
                allowCloudForPrivate = true,
                modeOverrides = Tasks.all.associate { it.id to HybridMode.PREFER_IN_CLOUD } + base.modeOverrides,
            )
        }
        val forced = engineChoice.value.forced ?: return base
        return base.copy(modeOverrides = base.modeOverrides + Tasks.all.associate { it.id to forced })
    }

    val voice = OfflineVoice(app)
    val actions = QuickActions(app)

    val router = InferenceRouter(
        engine = HybridEngine(firebase, local, models::status),
        deviceState = deviceState,
        config = ::routing,
        ledger = ledger::record,
    )

    val db = EtheroalDb.open(app)
    val apps = AppCatalog(app)

    /**
     * Triage of captured notifications. In CLOUD mode it runs whenever something arrives; on-device
     * models only run while Etheroal is in front, so in PRIVATE mode it drains then.
     */
    val triage by lazy {
        TriageQueue(
            db.items(), router,
            isForeground = {
                privacy.value == PrivacyMode.CLOUD ||
                    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            },
            ownerContext = { mind.peopleContext() },
            onSorted = chief::onSorted,
        )
    }

    /** Decides what happens to each sorted notification: interrupt, digest or file. */
    val chief by lazy { com.etheroal.os.triage.ChiefOfStaff(app, db.items(), mind) }

    val cloudHealth = com.etheroal.os.ai.CloudHealth(firebase)

    /** Conversations and the agents that carry them out. */
    val vault = com.etheroal.os.vault.Vault(app, db.vaultIndex())

    /** What Etheroal learns about its owner: memories and habits. */
    val mind = com.etheroal.os.mind.Mind(app, db.mind(), db.sessions(), com.etheroal.os.agent.GeminiTextModel(firebase) { config.cloudModelFast })

    val sessions = com.etheroal.os.agent.SessionManager(app, db.sessions(), firebase, config, apps, actions, ledger, vault).apply {
        unlockSecret = { reason -> com.etheroal.os.vault.VaultUnlockActivity.unlock(app, reason) }
        ownerContext = mind::context
        extraTools = mind::tools
        onFinished = { id, status -> if (status == com.etheroal.os.data.SessionStatus.DONE) mind.learnFrom(id) }
    }

    /** Spoken conversation through the Gemini Live API. */
    val liveVoice = com.etheroal.os.voice.LiveVoice(firebase, config, sessions, mind::context)

    init {
        cloudHealth.onHealthy = {
            db.items().retryFailed(System.currentTimeMillis() - 2L * 24 * 3600 * 1000)
            triage.kick()
        }
        apps.onLaunch = { a -> mind.log(com.etheroal.os.core.agent.HabitEvent.Kind.APP_LAUNCH, a.component.packageName, a.label) }
    }

    /** Gives back the memory of every on-device model. Called whenever Etheroal leaves the screen. */
    fun releaseLocalModels() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            runCatching { local.unload() }
            runCatching { actions.unload() }
            runCatching { voice.release() }
        }
    }
}

class EtheroalApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(this)
        // App Check must be installed before any Firebase AI Logic or Remote Config call.
        AppCheckInstaller.install()
        graph = AppGraph(this)
        if (BuildConfig.DEBUG) kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val now = System.currentTimeMillis()
            graph.db.items().stats().forEach {
                android.util.Log.i("EtheroalStore", "status=${it.status} n=${it.n} oldestMinAgo=${(now - it.oldest) / 60000} newestMinAgo=${(now - it.newest) / 60000}")
            }
        }
        // Coming to the front is when on-device models may run: sort whatever arrived meanwhile.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = graph.triage.kick()

            // On a slow phone nothing heavy may stay loaded while Etheroal is not on screen.
            override fun onStop(owner: LifecycleOwner) = graph.releaseLocalModels()
        })
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            graph.config.refresh()
            graph.cloudHealth.check()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW) graph.releaseLocalModels()
    }
}
