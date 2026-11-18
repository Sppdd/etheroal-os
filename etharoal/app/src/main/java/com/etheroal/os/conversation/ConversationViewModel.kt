package com.etheroal.os.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.etheroal.os.AppGraph
import com.etheroal.os.agent.LiveSession
import com.etheroal.os.data.MessageEntity
import com.etheroal.os.data.SessionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The open conversation, the list of sessions for the side panel, and what is running. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModel(private val graph: AppGraph) : ViewModel() {
    private val sessions = graph.sessions
    private val dao = graph.db.sessions()

    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current

    val messages: StateFlow<List<MessageEntity>> = _current
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recent: StateFlow<List<SessionEntity>> = dao.recent(40)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val live: StateFlow<Map<String, LiveSession>> = sessions.live

    val currentLive: StateFlow<LiveSession?> = combine(_current, sessions.live) { id, m -> id?.let(m::get) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val title: StateFlow<String> = combine(_current, recent) { id, list -> list.firstOrNull { it.id == id }?.title ?: "New conversation" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "New conversation")

    fun open(id: String?) { _current.value = id }

    /** Sends to the open conversation, or starts one. */
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = _current.value
        if (id == null) _current.value = sessions.start(t) else sessions.send(id, t)
    }

    fun stop() = _current.value?.let(sessions::stop)
    fun stop(id: String) = sessions.stop(id)
    fun answer(allow: Boolean) = _current.value?.let { sessions.answer(it, allow) }
    fun delete(id: String) { sessions.delete(id); if (_current.value == id) _current.value = null }

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory { initializer { ConversationViewModel(graph) } }
    }
}
