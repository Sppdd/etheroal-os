package com.etheroal.os.core.agent

/** What happens to a notification after it is sorted. */
enum class Disposition {
    /** Worth breaking the owner's focus: Etheroal raises its own alert with a one-line summary. */
    INTERRUPT,

    /** Kept for the digest on the home screen; can leave the shade if the owner chose a quiet shade. */
    DIGEST,

    /** Filed away: promotions and things the owner always ignores. Still visible, collapsed, and reversible. */
    FILE,
}

/**
 * The Chief of Staff's rules. Plain code over the model's verdict and the owner's habits, so the
 * owner can always be told why ("because you usually ignore these"), and so it is tested.
 */
object NotificationPolicy {

    data class Decision(val disposition: Disposition, val reason: String)

    fun decide(urgency: String, category: String, needsReply: Boolean, ignoredByOwner: Boolean, answeredByOwner: Boolean, sender: String): Decision = when {
        urgency == "URGENT" -> Decision(Disposition.INTERRUPT, "Urgent")
        answeredByOwner && needsReply -> Decision(Disposition.INTERRUPT, "You usually answer $sender quickly")
        ignoredByOwner -> Decision(Disposition.FILE, "You always clear these without opening them")
        category == "PROMOTION" -> Decision(Disposition.FILE, "Promotion")
        category == "SYSTEM" && urgency == "LOW" -> Decision(Disposition.FILE, "Routine system message")
        needsReply -> Decision(Disposition.DIGEST, "Waiting for your reply")
        else -> Decision(Disposition.DIGEST, "For your information")
    }
}
