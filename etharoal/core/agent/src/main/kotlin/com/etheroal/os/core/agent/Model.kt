package com.etheroal.os.core.agent

/** One entry of an agent's conversation with its model. */
sealed interface Turn {
    /** [image] is an optional platform image (the screen the owner was looking at when they asked). */
    data class User(val text: String, val image: Any? = null) : Turn

    /**
     * What the model said. [raw] is the provider's own message, replayed as-is on the next call:
     * Gemini 3 attaches thought signatures to function calls and needs them back.
     */
    data class Model(val text: String, val calls: List<ToolCall>, val raw: Any? = null) : Turn

    data class Results(val results: List<Pair<ToolCall, ToolResult>>) : Turn
}

/** The current screen (or other live state), sent with the next model call only and never kept. */
data class Observation(val text: String, val image: Any? = null)

/** [thoughts] is the model's own summary of its reasoning, shown to the owner like a transcript. */
data class ModelReply(val text: String, val calls: List<ToolCall>, val raw: Any? = null, val thoughts: String = "")

/** A model that can call tools. Implemented with Firebase AI Logic in the app and faked in tests. */
fun interface AgentModel {
    suspend fun next(turns: List<Turn>, observation: Observation?): ModelReply
}

/** A model asked for plain text, without tools. */
fun interface TextModel {
    suspend fun complete(system: String, user: String): String
}

class ModelFailure(message: String, val retryable: Boolean = false, cause: Throwable? = null) : Exception(message, cause)
