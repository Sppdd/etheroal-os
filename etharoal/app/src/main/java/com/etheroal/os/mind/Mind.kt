package com.etheroal.os.mind

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.etheroal.os.agent.GeminiTextModel
import com.etheroal.os.core.agent.HabitEvent
import com.etheroal.os.core.agent.Habits
import com.etheroal.os.core.agent.MemoryAgent
import com.etheroal.os.core.agent.Param
import com.etheroal.os.core.agent.ParamType
import com.etheroal.os.core.agent.SessionRedactor
import com.etheroal.os.core.agent.Tool
import com.etheroal.os.core.agent.ToolResult
import com.etheroal.os.core.agent.ToolSpec
import com.etheroal.os.core.agent.Verdict
import com.etheroal.os.core.agent.tool
import com.etheroal.os.data.MessageRole
import com.etheroal.os.data.SessionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/** Something Etheroal knows about the owner, in a sentence. Shown to the owner, who can delete it. */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** "conversation" (learned by the memory agent) or "you" (the owner or agent saved it on request). */
    val source: String,
    val createdAt: Long,
)

/** One habit event: what kind, which app or sender, when. No content. */
@Entity(tableName = "habits", indices = [Index("at")])
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val key: String,
    val label: String,
    val at: Long,
    val hour: Int,
    val weekday: Boolean,
)

@Dao
interface MindDao {
    @Insert suspend fun addMemory(m: MemoryEntity): Long
    @Query("SELECT * FROM memories ORDER BY createdAt DESC") fun memories(): Flow<List<MemoryEntity>>
    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit") suspend fun recentMemories(limit: Int): List<MemoryEntity>
    @Query("SELECT * FROM memories WHERE text LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 10") suspend fun search(q: String): List<MemoryEntity>
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteMemory(id: Long)
    @Query("DELETE FROM memories") suspend fun clearMemories()

    @Insert suspend fun addHabit(h: HabitEntity)
    @Query("SELECT * FROM habits WHERE at > :since") suspend fun habitsSince(since: Long): List<HabitEntity>
    @Query("DELETE FROM habits WHERE at < :before") suspend fun pruneHabits(before: Long)
    @Query("DELETE FROM habits") suspend fun clearHabits()
}

/**
 * What Etheroal learns about its owner: a notebook of facts (from conversations, by the memory
 * agent, or saved on request) and habit counts (which apps when, which senders get answered).
 * Both stay in the encrypted database on the phone; only short summaries go into the
 * orchestrator's instructions. The owner can switch learning off and clear everything.
 */
class Mind(context: Context, private val dao: MindDao, private val sessions: SessionDao, private val fastModel: GeminiTextModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("mind", Context.MODE_PRIVATE)

    private val _learning = MutableStateFlow(prefs.getBoolean("learning", true))
    /** Whether Etheroal learns from conversations and habits. Off stops both. */
    val learning: StateFlow<Boolean> = _learning

    val memories: Flow<List<MemoryEntity>> = dao.memories()

    init { scope.launch { dao.pruneHabits(System.currentTimeMillis() - HABIT_DAYS * DAY) } }

    fun setLearning(on: Boolean) {
        _learning.value = on
        prefs.edit().putBoolean("learning", on).apply()
    }

    fun log(kind: HabitEvent.Kind, key: String, label: String) {
        if (!_learning.value) return
        val now = Calendar.getInstance()
        val weekday = isWeekday(now)
        scope.launch {
            dao.addHabit(HabitEntity(kind = kind.name, key = key.take(200), label = label.take(80), at = now.timeInMillis, hour = now.get(Calendar.HOUR_OF_DAY), weekday = weekday))
        }
    }

    suspend fun remember(text: String, source: String) {
        val t = text.trim().take(200)
        if (t.isEmpty()) return
        val known = dao.recentMemories(200).map { it.text.lowercase() }
        if (t.lowercase() !in known) dao.addMemory(MemoryEntity(text = t, source = source, createdAt = System.currentTimeMillis()))
    }

    fun forget(id: Long) = scope.launch { dao.deleteMemory(id) }
    fun clearAll() = scope.launch { dao.clearMemories(); dao.clearHabits() }

    /** (habits, memories) for the orchestrator's instructions. */
    suspend fun context(): Pair<String, String> {
        val habits = Habits.summarize(events())
        val memories = dao.recentMemories(30).joinToString("\n") { "- ${it.text}" }
        return habits to memories
    }

    suspend fun suggestedApps(): List<String> {
        val now = Calendar.getInstance()
        return Habits.suggestApps(events().filter { it.kind == HabitEvent.Kind.APP_LAUNCH }, now.get(Calendar.HOUR_OF_DAY), isWeekday(now))
    }

    suspend fun ignoredSenders(): Set<String> = Habits.ignored(events())
    suspend fun answeredSenders(): Set<String> = Habits.answered(events())

    /** A few lines about who matters, for sorting notifications and drafting replies. */
    suspend fun peopleContext(): String = dao.recentMemories(15).joinToString("\n") { "- ${it.text}" }

    /** Runs the memory agent over a finished conversation. Only the dialogue, masked; never steps or screens. */
    fun learnFrom(sessionId: String) {
        if (!_learning.value) return
        scope.launch {
            val dialogue = sessions.dialogue(sessionId, 20).reversed()
            if (dialogue.size < 2) return@launch
            val redactor = SessionRedactor()
            val text = dialogue.joinToString("\n") { (if (it.role == MessageRole.USER) "Owner: " else "Etheroal: ") + redactor.redact(it.text) }
            val known = dao.recentMemories(60).map { it.text }
            MemoryAgent(fastModel).extract(text, known).forEach { remember(it, "conversation") }
        }
    }

    /** remember / recall, for the orchestrator. */
    fun tools(): List<Tool> = listOf(
        tool(
            ToolSpec("remember", "Save a lasting fact about the owner when they ask you to remember something.", listOf(Param("fact", ParamType.STRING, "One sentence"))),
            describe = { "Remembering that" },
            check = { c -> if (c.str("fact").isBlank()) Verdict.Refused("Nothing to remember.") else Verdict.Allowed },
        ) { c -> remember(c.str("fact"), "you"); ToolResult("Saved to memory.") },
        tool(
            ToolSpec("recall", "Search what Etheroal remembers about the owner.", listOf(Param("query", ParamType.STRING, "A word or name to look for"))),
            describe = { c -> "Checking memory for “${c.str("query").take(30)}”" },
        ) { c -> ToolResult(dao.search(c.str("query").take(60)).joinToString("\n") { it.text }.ifEmpty { "Nothing remembered about that." }) },
    )

    private suspend fun events(): List<HabitEvent> = dao.habitsSince(System.currentTimeMillis() - HABIT_DAYS * DAY).mapNotNull { h ->
        runCatching { HabitEvent(HabitEvent.Kind.valueOf(h.kind), h.key, h.label, h.at, h.hour, h.weekday) }.getOrNull()
    }

    private fun isWeekday(c: Calendar) = !weekend(c.get(Calendar.DAY_OF_WEEK))
    /** Friday–Saturday or Saturday–Sunday weekends both count as weekend days here. */
    private fun weekend(day: Int) = day == Calendar.FRIDAY || day == Calendar.SATURDAY || day == Calendar.SUNDAY

    private companion object {
        const val DAY = 24L * 3600 * 1000
        const val HABIT_DAYS = 60L
    }
}
