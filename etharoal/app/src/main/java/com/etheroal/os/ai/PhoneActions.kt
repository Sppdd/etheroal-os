package com.etheroal.os.ai

import java.time.LocalDateTime

/**
 * Turns a model's function call into a [PhoneAction], or refuses it. This is the safety boundary
 * between the action model and the phone, so it has no Android or LiteRT dependency and is unit-tested.
 */
object PhoneActions {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}")

    fun isActionModel(name: String) =
        name.endsWith(".litertlm") && (name.contains("functiongemma", true) || name.contains("mobile_actions", true))

    /** Validates the model's arguments into an action, or null. */
    fun build(name: String, args: kotlin.collections.Map<String, Any?>): PhoneAction? {
        fun str(key: String, max: Int) = (args[key] as? String)?.trim()?.take(max).orEmpty()
        return when (name) {
            "turn_on_flashlight" -> PhoneAction.Flashlight(true)
            "turn_off_flashlight" -> PhoneAction.Flashlight(false)
            "open_wifi_settings" -> PhoneAction.WifiSettings
            "create_calendar_event" -> {
                val title = str("title", 200).ifEmpty { return null }
                val start = runCatching { LocalDateTime.parse(str("datetime", 25)) }.getOrNull() ?: return null
                PhoneAction.CalendarEvent(title, start)
            }
            "show_map" -> PhoneAction.Map(str("query", 200).ifEmpty { return null })
            "send_email" -> {
                val to = str("to", 254)
                if (!EMAIL.matches(to)) return null
                PhoneAction.Email(to, str("subject", 200), str("body", 2000))
            }
            "create_contact" -> {
                val first = str("first_name", 80); val last = str("last_name", 80)
                if (first.isEmpty() && last.isEmpty()) return null
                val phone = str("phone_number", 32).filter { it.isDigit() || it in "+ -()" }
                val email = str("email", 254).takeIf { it.isEmpty() || EMAIL.matches(it) } ?: return null
                PhoneAction.Contact(first, last, phone, email)
            }
            else -> null
        }
    }
}
