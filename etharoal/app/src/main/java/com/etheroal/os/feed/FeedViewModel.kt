package com.etheroal.os.feed

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.etheroal.os.AppGraph
import com.etheroal.os.ai.Tasks
import com.etheroal.os.core.ai.AiOutcome
import com.etheroal.os.data.ItemStatus
import com.etheroal.os.data.NotificationItem
import com.etheroal.os.triage.NotificationCaptureService
import com.etheroal.os.triage.ReplyActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One card in the feed: a conversation, shown once however many notifications it sent. */
data class FeedCard(
    val conversation: String,
    val latest: NotificationItem,
    val count: Int,
) {
    val appLabel get() = latest.appLabel
    val title get() = latest.title
    val summary get() = latest.summary ?: latest.text
    val triaged get() = latest.status == ItemStatus.TRIAGED
}

data class Feed(
    val urgent: List<FeedCard> = emptyList(),
    val needsReply: List<FeedCard> = emptyList(),
    val fyi: List<FeedCard> = emptyList(),
    val waiting: List<FeedCard> = emptyList(),
    /** Promotions and senders the owner always ignores, kept out of the way. */
    val filed: List<FeedCard> = emptyList(),
)

sealed interface DraftState {
    data object Writing : DraftState
    data class Ready(val text: String, val source: String, val note: String) : DraftState
    data class Sent(val text: String) : DraftState
    data class Error(val message: String) : DraftState
}

class FeedViewModel(private val app: Application, private val graph: AppGraph) : ViewModel() {

    val feed: StateFlow<Feed> = graph.db.items().feed(System.currentTimeMillis() - WINDOW_MS)
        .map(::group)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Feed())

    val queueStatus: StateFlow<String?> = graph.triage.status

    private val _drafts = MutableStateFlow<Map<String, DraftState>>(emptyMap())
    val drafts: StateFlow<Map<String, DraftState>> = _drafts.asStateFlow()

    private val _accessGranted = MutableStateFlow(NotificationCaptureService.isEnabled(app))
    val accessGranted: StateFlow<Boolean> = _accessGranted.asStateFlow()

    val speed: StateFlow<com.etheroal.os.PrivacyMode> = graph.privacy
    fun setSpeed(mode: com.etheroal.os.PrivacyMode) = graph.setPrivacy(mode)

    fun onResume() {
        _accessGranted.value = NotificationCaptureService.isEnabled(app)
        graph.triage.kick()
    }

    /** Drafts a reply with the router (redacted before any cloud use), shown for the user to edit. */
    fun draft(card: FeedCard) {
        _drafts.update { it + (card.conversation to DraftState.Writing) }
        viewModelScope.launch {
            val thread = graph.db.items().thread(card.conversation).takeLast(6)
                .joinToString("\n") { it.text }.ifBlank { card.latest.text }
            // What Etheroal knows about the owner's people and tone helps the reply sound like them.
            val known = runCatching { graph.mind.peopleContext() }.getOrDefault("")
            val input = (if (known.isNotBlank()) "What the owner's assistant knows about them and their people:\n$known\n\n" else "") +
                "Conversation with ${card.title} on ${card.appLabel}:\n$thread"
            val state = when (val out = graph.router.run(Tasks.QuickReply, input)) {
                is AiOutcome.Success -> DraftState.Ready(
                    out.text.trim().removeSurrounding("\""),
                    if (out.source.name == "ON_DEVICE") "On device" else "Cloud",
                    out.modelName ?: "",
                )
                is AiOutcome.Deferred -> DraftState.Error(out.reason)
                is AiOutcome.Failed -> DraftState.Error(out.reason)
            }
            _drafts.update { it + (card.conversation to state) }
        }
    }

    fun edit(card: FeedCard, text: String) = _drafts.update { map ->
        val current = map[card.conversation] as? DraftState.Ready ?: return@update map
        map + (card.conversation to current.copy(text = text))
    }

    /** Sends only after the user taps Send, through the app's own notification reply. */
    fun send(card: FeedCard) {
        val draft = _drafts.value[card.conversation] as? DraftState.Ready ?: return
        val error = ReplyActions.send(app, card.latest.notificationKey, draft.text)
        if (error != null) {
            _drafts.update { it + (card.conversation to DraftState.Error(error)) }
            return
        }
        _drafts.update { it + (card.conversation to DraftState.Sent(draft.text)) }
        habit(com.etheroal.os.core.agent.HabitEvent.Kind.NOTIF_REPLIED, card)
        viewModelScope.launch { graph.db.items().replied(card.conversation) }
    }

    fun discardDraft(card: FeedCard) = _drafts.update { it - card.conversation }

    /** The conversation just marked done, offered for undo until the next dismissal. */
    private val _lastDone = MutableStateFlow<FeedCard?>(null)
    val lastDone: StateFlow<FeedCard?> = _lastDone.asStateFlow()

    private fun habit(kind: com.etheroal.os.core.agent.HabitEvent.Kind, card: FeedCard) =
        graph.mind.log(kind, card.conversation, "${card.title.ifBlank { card.appLabel }} (${card.appLabel})")

    fun dismiss(card: FeedCard) {
        habit(com.etheroal.os.core.agent.HabitEvent.Kind.NOTIF_DONE, card)
        android.util.Log.i("EtheroalFeed", "marked done: ${card.appLabel}")   // no content
        viewModelScope.launch { graph.db.items().dismissConversation(card.conversation) }
        _drafts.update { it - card.conversation }
        _lastDone.value = card
    }

    fun undoDone() {
        val card = _lastDone.value ?: return
        _lastDone.value = null
        viewModelScope.launch { graph.db.items().undismissConversation(card.conversation) }
    }

    fun clearUndo() { _lastDone.value = null }

    fun unfile(card: FeedCard) = viewModelScope.launch { graph.db.items().unfile(card.conversation) }

    val quietShade: StateFlow<Boolean> = graph.chief.quietShade
    fun setQuietShade(on: Boolean) = graph.chief.setQuietShade(on)

    fun open(card: FeedCard) {
        habit(com.etheroal.os.core.agent.HabitEvent.Kind.NOTIF_OPENED, card)
        if (!ReplyActions.open(card.latest.notificationKey)) {
            app.packageManager.getLaunchIntentForPackage(card.latest.packageName)
                ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)?.let(app::startActivity)
        }
    }

    private fun group(items: List<NotificationItem>): Feed {
        val cards = items.groupBy { it.conversation }.map { (conversation, list) ->
            // Items arrive newest first; the newest triaged one speaks for the conversation.
            val latest = list.firstOrNull { it.status == ItemStatus.TRIAGED } ?: list.first()
            FeedCard(conversation, latest.copy(text = list.first().text), list.size)
        }
        val (sorted, waiting) = cards.partition { it.triaged }
        val (filed, triaged) = sorted.partition { it.latest.disposition == "FILE" }
        return Feed(
            filed = filed,
            urgent = triaged.filter { it.latest.urgency == "URGENT" },
            needsReply = triaged.filter { it.latest.urgency != "URGENT" && it.latest.needsReply },
            fyi = triaged.filter { it.latest.urgency != "URGENT" && !it.latest.needsReply },
            waiting = waiting,
        )
    }

    companion object {
        private const val WINDOW_MS = 2L * 24 * 3600 * 1000

        fun factory(app: Application, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { FeedViewModel(app, graph) }
        }
    }
}
