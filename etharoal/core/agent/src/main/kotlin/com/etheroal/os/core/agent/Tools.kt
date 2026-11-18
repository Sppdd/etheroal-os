package com.etheroal.os.core.agent

/** How much a tool can change in the world, which decides what the owner must approve. */
enum class Risk {
    /** Reads, navigates or opens a pre-filled screen the owner still confirms in the app. */
    SAFE,

    /** Sends, buys, deletes or otherwise commits: the owner says yes first. */
    CONFIRM,

    /** Touches a stored secret: the owner proves who they are (biometrics) first. */
    SECRET,
}

enum class ParamType { STRING, INTEGER, BOOLEAN }

data class Param(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val options: List<String> = emptyList(),
)

/** What the model is told about a tool. Becomes a Firebase AI Logic FunctionDeclaration in the app. */
data class ToolSpec(val name: String, val description: String, val params: List<Param> = emptyList(), val risk: Risk = Risk.SAFE)

/** A function call from the model, with arguments already converted to plain values. */
data class ToolCall(val id: String?, val name: String, val args: Map<String, Any?>) {
    fun str(key: String): String = args[key]?.toString()?.trim().orEmpty()
    fun int(key: String): Int? = when (val v = args[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }
    fun bool(key: String): Boolean = when (val v = args[key]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> false
    }
}

/**
 * What a tool hands back. [text] goes to the model; [ownerText] is shown in the session instead
 * when it differs. A [final] result ends the agent that called it (finish, give up).
 */
data class ToolResult(val text: String, val ok: Boolean = true, val final: Boolean = false, val ownerText: String? = null)

sealed interface Verdict {
    data object Allowed : Verdict
    data class NeedsOwner(val question: String, val secret: Boolean = false) : Verdict
    data class Refused(val why: String) : Verdict
}

interface Tool {
    val spec: ToolSpec

    /** A few words for the session log and status notification, e.g. "Opening Clock". */
    fun describe(call: ToolCall): String = spec.name.replace('_', ' ')

    /**
     * The safety gate, run in plain code before [run]. The default follows [Risk]; tools that act
     * on the screen add their own rules on top.
     */
    fun check(call: ToolCall): Verdict = when (spec.risk) {
        Risk.SAFE -> Verdict.Allowed
        Risk.CONFIRM -> Verdict.NeedsOwner("${describe(call)}?")
        Risk.SECRET -> Verdict.NeedsOwner("${describe(call)}?", secret = true)
    }

    suspend fun run(call: ToolCall): ToolResult
}

/** A small way to write a tool inline. */
fun tool(
    spec: ToolSpec,
    describe: (ToolCall) -> String = { spec.name.replace('_', ' ') },
    check: ((ToolCall) -> Verdict)? = null,
    run: suspend (ToolCall) -> ToolResult,
): Tool = object : Tool {
    override val spec = spec
    override fun describe(call: ToolCall) = describe(call)
    override fun check(call: ToolCall): Verdict = check?.invoke(call) ?: super.check(call)
    override suspend fun run(call: ToolCall) = run(call)
}
