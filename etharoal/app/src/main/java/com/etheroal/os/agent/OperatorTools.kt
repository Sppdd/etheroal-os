package com.etheroal.os.agent

import com.etheroal.os.core.agent.Observation
import com.etheroal.os.core.agent.Param
import com.etheroal.os.core.agent.ParamType
import com.etheroal.os.core.agent.ScreenPolicy
import com.etheroal.os.core.agent.Tool
import com.etheroal.os.core.agent.ToolCall
import com.etheroal.os.core.agent.ToolResult
import com.etheroal.os.core.agent.ToolSpec
import com.etheroal.os.core.agent.Verdict
import com.etheroal.os.core.agent.Verifier
import com.etheroal.os.core.agent.tool
import com.etheroal.os.home.AppCatalog
import kotlinx.coroutines.delay

/**
 * The operator's view of the phone during one task: the last screen it was shown and what it has
 * done. Each tool checks its call against [ScreenPolicy] using exactly the screen the model saw.
 */
class OperatorContext(
    private val ownPackage: String,
    private val apps: AppCatalog,
    private val verifier: Verifier?,
    private val vision: Boolean,
    private val vault: com.etheroal.os.vault.Vault,
    private val onStatus: (String) -> Unit,
) {
    /** The vault's non-secret index, read once per task so checks can name the login. */
    private var logins: Map<Long, com.etheroal.os.vault.VaultIndex> = emptyMap()

    suspend fun prepare() { logins = vault.list().associateBy { it.id } }

    var screen: Screen? = null
        private set
    var typed = false
        private set

    private val service get() = EtheroalAccessibilityService.instance

    suspend fun observe(): Observation {
        val svc = service ?: return Observation("Accessibility access was switched off. Call give_up.")
        val s = svc.snapshot().also { screen = it }
        val own = s.packageName == ownPackage
        val blocked = own || ScreenPolicy.isProtectedApp(s.packageName)
        val text = buildString {
            append("Current app: ").append(if (own) "Etheroal home screen (use open_app to start the app you need)" else s.packageName.ifEmpty { "unknown" })
            append("\nScreen elements:\n")
            append(if (own) "(hidden)" else ScreenPolicy.describe(s.elements).ifEmpty { "(nothing readable; try wait or scroll)" })
        }
        // Etheroal's own screen holds the private feed; system screens are off limits. Neither is photographed.
        val image = if (vision && !blocked) svc.screenshot() else null
        return Observation(text, image)
    }

    fun tools(): List<Tool> = listOf(
        tool(
            ToolSpec(
                "tap", "Tap an element of the current screen.",
                listOf(Param("target", ParamType.INTEGER, "Element number from the current screen list")),
            ),
            describe = { c -> "Tapping " + (screen?.element(c.int("target") ?: 0)?.label?.takeIf { it.isNotBlank() }?.let { "“${it.take(40)}”" } ?: "item ${c.int("target")}") },
            check = { c -> appCheck() ?: ScreenPolicy.checkTap(screen?.element(c.int("target") ?: 0), c.int("target") ?: 0, typed) },
        ) { c -> act(c, "tap") { svc, s -> s.node(c.int("target") ?: 0)?.let(svc::tap) ?: false } },

        tool(
            ToolSpec(
                "type", "Replace the text of a text field on the current screen.",
                listOf(
                    Param("target", ParamType.INTEGER, "Element number of the text field"),
                    Param("text", ParamType.STRING, "The text to put in the field"),
                ),
            ),
            describe = { c -> "Typing into " + (screen?.element(c.int("target") ?: 0)?.label?.takeIf { it.isNotBlank() }?.let { "“${it.take(30)}”" } ?: "a field") },
            check = { c -> appCheck() ?: ScreenPolicy.checkType(screen?.element(c.int("target") ?: 0), c.int("target") ?: 0, c.str("text")) },
        ) { c -> act(c, "type") { svc, s -> (s.node(c.int("target") ?: 0)?.let { svc.type(it, c.str("text")) } ?: false).also { if (it) typed = true } } },

        tool(
            ToolSpec("scroll", "Scroll the current screen.", listOf(Param("direction", ParamType.STRING, "Which way", options = listOf("down", "up")))),
            describe = { c -> "Scrolling ${c.str("direction").ifEmpty { "down" }}" },
            check = { appCheck() ?: Verdict.Allowed },
        ) { c -> act(c, "scroll") { svc, s -> svc.scroll(s, forward = c.str("direction") != "up") } },

        tool(ToolSpec("back", "Press the system Back button."), describe = { "Going back" }) { c ->
            act(c, "back") { svc, _ -> svc.back() }
        },

        tool(
            ToolSpec("open_app", "Start an installed app by its name.", listOf(Param("name", ParamType.STRING, "The app's name as shown on the phone"))),
            describe = { c -> "Opening ${c.str("name")}" },
            check = { c -> if (c.str("name").isBlank()) Verdict.Refused("open_app needs the app's name.") else Verdict.Allowed },
        ) { c ->
            val result = openApp(apps, c.str("name"), ownPackage)
            delay(SETTLE_MS * 2)
            result
        },

        tool(ToolSpec("vault_list", "List the owner's saved logins (labels and masked usernames only, never passwords)."), describe = { "Checking saved logins" }) {
            ToolResult(
                if (logins.isEmpty()) "The vault is empty. Call give_up and ask the owner to save this login in Etheroal's vault."
                else logins.values.joinToString("\n") { "entry_id ${it.id}: ${it.label} (${it.target}) user ${it.usernameHint}" },
            )
        },

        tool(
            ToolSpec(
                "fill_credential",
                "Fill a saved login into the sign-in form on the current screen. The owner unlocks the vault with their fingerprint; you never see the password.",
                listOf(
                    Param("entry_id", ParamType.INTEGER, "entry_id from vault_list"),
                    Param("password_target", ParamType.INTEGER, "Element number of the password field"),
                    Param("username_target", ParamType.INTEGER, "Element number of the username or email field, if it is on this screen", required = false),
                ),
                risk = com.etheroal.os.core.agent.Risk.SECRET,
            ),
            describe = { c -> "Signing in with your saved ${logins[c.int("entry_id")?.toLong()]?.label ?: "login"}" },
            check = { c ->
                val s = screen
                val entry = logins[c.int("entry_id")?.toLong()]
                val pw = s?.element(c.int("password_target") ?: 0)
                val user = c.int("username_target")?.let { s?.element(it) }
                when {
                    appCheck() != null -> appCheck()!!
                    entry == null -> Verdict.Refused("No saved login with that entry_id. Call vault_list.")
                    pw == null || !pw.password -> Verdict.Refused("password_target must be the password field on this screen.")
                    c.int("username_target") != null && (user == null || !user.editable || user.password) -> Verdict.Refused("username_target must be a text field.")
                    else -> Verdict.NeedsOwner("Sign in to ${s?.packageName} with your saved ${entry.label} login (${entry.usernameHint})?", secret = true)
                }
            },
        ) { c ->
            val svc = service ?: return@tool ToolResult("Accessibility access is off.", ok = false)
            val s = screen ?: return@tool ToolResult("No screen has been read yet.", ok = false)
            val secret = runCatching { vault.read(c.int("entry_id")!!.toLong()) }.getOrNull()
                ?: return@tool ToolResult("The vault could not be opened (locked or the entry is gone).", ok = false)
            val userOk = c.int("username_target")?.let { t -> s.node(t)?.let { svc.fillSecret(it, secret.username) } ?: false } ?: true
            val passOk = s.node(c.int("password_target") ?: 0)?.let { svc.fillSecret(it, secret.secret) } ?: false
            delay(SETTLE_MS)
            if (userOk && passOk) ToolResult("Filled the saved login. Now tap the sign-in button.")
            else ToolResult("The login could not be filled into those fields.", ok = false)
        },

        tool(ToolSpec("wait", "Wait a moment for the screen to finish loading."), describe = { "Waiting for the app" }) {
            delay(2000); ToolResult("Waited 2 seconds.")
        },

        tool(
            ToolSpec("finish", "The task is complete.", listOf(Param("result", ParamType.STRING, "What was done, in one sentence, for the owner"))),
            describe = { "Finishing" },
        ) { c -> ToolResult(c.str("result").ifBlank { "Done." }, final = true) },

        tool(
            ToolSpec("give_up", "The task cannot be completed, or only the owner can decide something.", listOf(Param("reason", ParamType.STRING, "Why, or the question for the owner"))),
            describe = { "Stopping" },
        ) { c -> ToolResult("Not finished: " + c.str("reason").ifBlank { "the operator could not continue." }, ok = false, final = true) },
    )

    private fun appCheck(): Verdict? {
        val s = screen ?: return Verdict.Refused("No screen has been read yet.")
        return (ScreenPolicy.checkApp(s.packageName, ownPackage) as? Verdict.Refused)
    }

    /** Runs one screen action, waits for the app to react, and asks the verifier whether it worked. */
    private suspend fun act(call: ToolCall, name: String, action: (EtheroalAccessibilityService, Screen) -> Boolean): ToolResult {
        val svc = service ?: return ToolResult("Accessibility access is off.", ok = false)
        val before = screen ?: return ToolResult("No screen has been read yet.", ok = false)
        onStatus(name)
        if (!action(svc, before)) return ToolResult("The $name had no effect. Try a different element.", ok = false)
        delay(SETTLE_MS)
        val v = verifier ?: return ToolResult("Done.")
        val after = svc.snapshot()
        val check = v.verify(
            intent = "$name ${call.args}",
            before = ScreenPolicy.describe(before.elements),
            after = ScreenPolicy.describe(after.elements),
        )
        return ToolResult("Done. Check: ${check.outcome.name}" + if (check.note.isNotBlank()) " – ${check.note}" else "", ok = check.outcome != Verifier.Outcome.NO_CHANGE)
    }

    companion object {
        const val SETTLE_MS = 1200L

        fun openApp(apps: AppCatalog, name: String, ownPackage: String): ToolResult {
            val wanted = name.trim()
            val list = apps.apps.value
            val app = list.firstOrNull { it.label.equals(wanted, ignoreCase = true) }
                ?: list.firstOrNull { it.label.contains(wanted, ignoreCase = true) }
                ?: return ToolResult("No app called “$wanted” is installed. Installed apps include: ${list.take(40).joinToString { it.label }}.", ok = false)
            val pkg = app.component.packageName
            if (pkg == ownPackage || ScreenPolicy.isProtectedApp(pkg)) return ToolResult("Etheroal does not operate ${app.label}.", ok = false)
            return runCatching { apps.launch(app); ToolResult("Opened ${app.label}.") }
                .getOrElse { ToolResult("${app.label} could not be opened.", ok = false) }
        }
    }
}
