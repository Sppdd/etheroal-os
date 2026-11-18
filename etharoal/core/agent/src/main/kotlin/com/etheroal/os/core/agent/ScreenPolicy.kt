package com.etheroal.os.core.agent

/** One thing on the screen the operator can refer to by number. */
data class ScreenElement(
    val index: Int,
    val kind: String,
    val label: String,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val scrollable: Boolean = false,
)

/**
 * The rules for acting on other apps' screens: which taps need the owner, what is never touched.
 * Plain code, no model involved, so the model cannot talk its way past them.
 */
object ScreenPolicy {
    const val MAX_TEXT = 500

    fun checkTap(el: ScreenElement?, target: Int, typedEarlier: Boolean): Verdict = when {
        el == null -> Verdict.Refused("There is no element $target on this screen. Look at the current screen list.")
        el.password -> Verdict.Refused("Etheroal does not touch password fields. Use fill_credential for sign-in.")
        isSensitive(el.label) -> Verdict.NeedsOwner("Tap “${el.label.take(40)}”?")
        el.label.isBlank() && typedEarlier -> Verdict.NeedsOwner("Tap an unlabelled button? It may send what was typed.")
        else -> Verdict.Allowed
    }

    fun checkType(el: ScreenElement?, target: Int, text: String): Verdict = when {
        el == null -> Verdict.Refused("There is no element $target on this screen.")
        el.password -> Verdict.Refused("Etheroal never types into password fields. Use fill_credential.")
        !el.editable -> Verdict.Refused("Element $target is not a text field.")
        text.isBlank() -> Verdict.Refused("Nothing to type.")
        text.length > MAX_TEXT -> Verdict.Refused("That text is too long (max $MAX_TEXT characters).")
        else -> Verdict.Allowed
    }

    /** Settings, permission and install screens, and Etheroal itself, are never operated. */
    fun checkApp(packageName: String, ownPackage: String): Verdict = when {
        packageName.isEmpty() -> Verdict.Allowed
        packageName == ownPackage -> Verdict.Refused("That is Etheroal's own screen. Use open_app to go to the app the task needs.")
        isProtectedApp(packageName) -> Verdict.Refused("Etheroal does not operate system settings, permission or install screens.")
        else -> Verdict.Allowed
    }

    /** Buttons that commit to something: they send, spend, delete, sign in or grant. */
    fun isSensitive(label: String): Boolean {
        val words = label.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.any { it in SENSITIVE_WORDS }) return true
        val joined = words.joinToString(" ")
        return SENSITIVE_PHRASES.any { it in joined }
    }

    fun isProtectedApp(packageName: String): Boolean {
        val p = packageName.lowercase()
        return PROTECTED_PARTS.any { it in p }
    }

    /** The screen as the model reads it: numbered lines, one element each. Password text is never included. */
    fun describe(elements: List<ScreenElement>): String = elements.joinToString("\n") { el ->
        val traits = buildList {
            if (el.password) add("password field, use fill_credential")
            else if (el.editable) add("text field")
            if (el.clickable) add("tappable")
            if (el.scrollable) add("scrollable")
        }
        "[${el.index}] ${el.kind}" + (if (el.label.isNotBlank() && !el.password) " \"${el.label}\"" else "") +
            (if (traits.isNotEmpty()) " (${traits.joinToString(", ")})" else "")
    }

    private val SENSITIVE_WORDS = setOf(
        "send", "pay", "buy", "purchase", "order", "checkout", "confirm", "delete", "remove", "erase", "transfer",
        "submit", "post", "publish", "share", "install", "uninstall", "allow", "accept", "agree", "subscribe",
        "book", "donate", "call", "dial", "withdraw", "deposit", "trade", "sell", "bid", "unsubscribe", "block",
        "report", "reset", "login", "signin", "signup", "register", "authorize", "authorise", "grant", "approve",
        "reply", "forward", "tweet", "upload",
    )
    private val SENSITIVE_PHRASES = listOf("sign in", "log in", "sign up", "place order", "add to cart", "turn on", "turn off")
    private val PROTECTED_PARTS = listOf(
        "settings", "permissioncontroller", "packageinstaller", "systemui", "securitycenter", "systemmanager",
        "com.android.vending",
    )
}
