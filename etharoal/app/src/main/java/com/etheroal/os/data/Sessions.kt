package com.etheroal.os.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class SessionStatus { RUNNING, WAITING, DONE, FAILED, STOPPED }

/** One conversation with Etheroal, and everything it did for it. Stored encrypted on the phone only. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: SessionStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

/** USER and ASSISTANT are the conversation; the rest is the agent's step-by-step transcript. */
enum class MessageRole { USER, ASSISTANT, THOUGHT, SCREEN, STEP, NOTICE }

@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: MessageRole,
    val text: String,
    val at: Long,
    val ok: Boolean = true,
    /** Which agent wrote it: "Etheroal" (orchestrator) or "Operator". */
    val agent: String? = null,
    val durationMs: Long? = null,
    /** A screenshot the operator was shown, as a private file path. */
    val imagePath: String? = null,
)

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE sessions SET status = :status, updatedAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: SessionStatus, at: Long)

    @Insert
    suspend fun add(message: MessageEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SessionEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :id ORDER BY id ASC")
    fun messages(id: String): Flow<List<MessageEntity>>

    /** The conversation so far, for the next turn: what the owner and Etheroal said, not the steps. */
    @Query("SELECT * FROM messages WHERE sessionId = :id AND role IN ('USER', 'ASSISTANT') ORDER BY id DESC LIMIT :limit")
    suspend fun dialogue(id: String, limit: Int): List<MessageEntity>

    /** A session left RUNNING by a crash or a killed process is not running any more. */
    @Query("UPDATE sessions SET status = 'STOPPED' WHERE status IN ('RUNNING', 'WAITING')")
    suspend fun markInterrupted()

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM messages WHERE sessionId = :id")
    suspend fun deleteMessages(id: String)

    @Query("SELECT id FROM sessions WHERE updatedAt < :before")
    suspend fun olderThan(before: Long): List<String>
}
