package com.etheroal.os.ai

import com.etheroal.os.core.ai.AiTask
import com.etheroal.os.core.ai.Preference
import com.etheroal.os.core.ai.Sensitivity
import com.google.firebase.ai.annotations.Generable
import com.google.firebase.ai.annotations.Guide
import kotlinx.serialization.Serializable

@Serializable
enum class Urgency { URGENT, NORMAL, LOW }

@Serializable
enum class Category { PERSON, WORK, MONEY, DELIVERY, TRAVEL, SECURITY, PROMOTION, SYSTEM, OTHER }

/**
 * Structured triage of one notification. The same @Generable schema constrains the on-device
 * model (through the ML Kit Prompt API) and the cloud model, so both paths return this shape.
 */
@Serializable
@Generable(description = "Triage of a single phone notification")
data class TriageResult(
    @Guide(description = "URGENT only if a real person or a time-critical event needs the user within the hour")
    val urgency: Urgency,
    @Guide(description = "What kind of thing this is")
    val category: Category,
    @Guide(description = "True if a person is waiting for the user to answer")
    val needsReply: Boolean,
    @Guide(description = "One plain sentence of at most 15 words saying what happened")
    val summary: String,
) {
    // Required so KSP can generate TriageResult.firebaseAISchema().
    companion object
}

/** Every kind of AI work the app does. Features use these instead of calling a model directly. */
object Tasks {
    const val SCHEMA_TRIAGE = "triage"

    val Triage = AiTask(
        id = "triage",
        sensitivity = Sensitivity.PRIVATE,
        maxOutputTokens = 120,
        schemaKey = SCHEMA_TRIAGE,
        instructions = "You sort phone notifications for a busy person. Classify the notification below.",
    )

    val QuickReply = AiTask(
        id = "quick_reply",
        sensitivity = Sensitivity.REDACTABLE,
        preference = Preference.ON_DEVICE_FIRST,
        maxOutputTokens = 120,
        instructions = "Write a short, natural reply to the message below, as the recipient. " +
            "Reply with the message text only. Keep any [[PLACEHOLDER]] tokens exactly as written.",
    )

    val VoiceReply = AiTask(
        id = "voice_reply",
        sensitivity = Sensitivity.REDACTABLE,
        preference = Preference.CLOUD_FIRST,
        maxOutputTokens = 256,
        instructions = "Write a warm, well-judged reply to the conversation below, as the recipient. " +
            "Reply with the message text only. Keep any [[PLACEHOLDER]] tokens exactly as written.",
    )

    val Ask = AiTask(
        id = "ask",
        sensitivity = Sensitivity.PUBLIC,
        preference = Preference.CLOUD_FIRST,
        maxOutputTokens = 256,
    )

    const val SCHEMA_AGENT = "agent"

    /**
     * One step of a task carried out in other apps. Screens are masked before they leave the
     * phone. It needs the larger cloud model: many steps, and more than an on-device model holds.
     * The instructions are filled in per task by AgentRules.
     */
    val Agent = AiTask(
        id = "agent",
        sensitivity = Sensitivity.REDACTABLE,
        preference = Preference.CLOUD_FIRST,
        needs = com.etheroal.os.core.ai.TaskNeeds(multiTurn = true),
        maxOutputTokens = 200,
        schemaKey = SCHEMA_AGENT,
    )

    val all = listOf(Triage, QuickReply, VoiceReply, Ask)
}
