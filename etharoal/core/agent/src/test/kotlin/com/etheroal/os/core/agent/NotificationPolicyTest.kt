package com.etheroal.os.core.agent

import com.etheroal.os.core.agent.Disposition.DIGEST
import com.etheroal.os.core.agent.Disposition.FILE
import com.etheroal.os.core.agent.Disposition.INTERRUPT
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPolicyTest {
    private fun d(urgency: String = "NORMAL", category: String = "PERSON", reply: Boolean = false, ignored: Boolean = false, answered: Boolean = false) =
        NotificationPolicy.decide(urgency, category, reply, ignored, answered, "Sam").disposition

    @Test fun `urgent always interrupts, even from an ignored sender`() {
        assertEquals(INTERRUPT, d(urgency = "URGENT"))
        assertEquals(INTERRUPT, d(urgency = "URGENT", ignored = true))
    }

    @Test fun `people the owner answers interrupt when they need a reply`() {
        assertEquals(INTERRUPT, d(reply = true, answered = true))
        assertEquals(DIGEST, d(reply = false, answered = true))
        assertEquals(DIGEST, d(reply = true))
        assertEquals("You usually answer Sam quickly", NotificationPolicy.decide("NORMAL", "PERSON", true, false, true, "Sam").reason)
    }

    @Test fun `promotions, routine system messages and ignored senders are filed`() {
        assertEquals(FILE, d(category = "PROMOTION"))
        assertEquals(FILE, d(category = "SYSTEM", urgency = "LOW"))
        assertEquals(DIGEST, d(category = "SYSTEM"))
        assertEquals(FILE, d(ignored = true))
    }
}
