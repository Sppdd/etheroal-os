package com.etheroal.os.core.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VerifierTest {
    @Test fun `parses the verdict word and note`() {
        assertEquals(Verifier.Check(Verifier.Outcome.NO_CHANGE, "button inactive"), Verifier.parse("NO_CHANGE: button inactive"))
        assertEquals(Verifier.Outcome.UNEXPECTED, Verifier.parse("unexpected: a dialog opened").outcome)
        assertEquals(Verifier.Outcome.PROGRESSED, Verifier.parse("Progressed").outcome)
        assertEquals(Verifier.Outcome.PROGRESSED, Verifier.parse("gibberish").outcome)
    }

    @Test fun `an unchanged screen is known without asking the model`() = runTest {
        val v = Verifier { _, _ -> error("should not be called") }
        assertEquals(Verifier.Outcome.NO_CHANGE, v.verify("tap", "same", "same").outcome)
    }

    @Test fun `a failing model never blocks the operator`() = runTest {
        val v = Verifier { _, _ -> throw ModelFailure("down") }
        assertEquals(Verifier.Outcome.PROGRESSED, v.verify("tap", "a", "b").outcome)
    }
}
