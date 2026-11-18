package com.etheroal.os.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePolicyTest {

    private val ready = DeviceState(ModelStatus.AVAILABLE, appInForeground = true, online = true)
    private val config = RoutingConfig()

    private val triage = AiTask("triage", Sensitivity.PRIVATE)
    private val draft = AiTask("draft", Sensitivity.REDACTABLE)
    private val voice = AiTask("voice_reply", Sensitivity.REDACTABLE, Preference.CLOUD_FIRST)
    private val agent = AiTask("agent", Sensitivity.REDACTABLE, needs = TaskNeeds(tools = true, multiTurn = true))

    private fun decide(task: AiTask, state: DeviceState = ready, cfg: RoutingConfig = config, tokens: Int = 200) =
        RoutePolicy.decide(task, tokens, state, cfg)

    private fun run(d: RouteDecision) = d as RouteDecision.Run

    @Test fun `private task is locked to on-device and never redacted`() {
        val d = run(decide(triage))
        assertEquals(HybridMode.ONLY_ON_DEVICE, d.mode)
        assertFalse(d.redact)
    }

    @Test fun `private task never picks a cloud-capable mode in any device state`() {
        for (status in ModelStatus.entries) for (fg in listOf(true, false)) for (online in listOf(true, false))
            for (cool in listOf(true, false)) for (tokens in listOf(100, 50_000)) {
                val d = decide(triage, DeviceState(status, fg, online, cool), tokens = tokens)
                if (d is RouteDecision.Run) assertFalse("$status $fg $online $cool", d.mode.mayUseCloud)
            }
    }

    @Test fun `private task waits when the app is in the background`() {
        assertTrue(decide(triage, ready.copy(appInForeground = false)) is RouteDecision.Defer)
    }

    @Test fun `private task waits while the model downloads`() {
        assertTrue(decide(triage, ready.copy(modelStatus = ModelStatus.DOWNLOADING)) is RouteDecision.Defer)
    }

    @Test fun `private task is rejected on a phone with no on-device model`() {
        assertTrue(decide(triage, ready.copy(modelStatus = ModelStatus.UNAVAILABLE)) is RouteDecision.Reject)
    }

    @Test fun `private task too large for on-device is rejected, not sent to cloud`() {
        assertTrue(decide(triage, tokens = 9000) is RouteDecision.Reject)
    }

    @Test fun `user opt-in lets private tasks use cloud, but only redacted`() {
        val state = ready.copy(modelStatus = ModelStatus.UNAVAILABLE)
        val d = run(decide(triage, state, config.copy(allowCloudForPrivate = true)))
        assertEquals(HybridMode.ONLY_IN_CLOUD, d.mode)
        assertTrue(d.redact)
    }

    @Test fun `fast mode sends private tasks cloud-first, redacted, with the phone as fallback`() {
        val fast = config.copy(allowCloudForPrivate = true, modeOverrides = mapOf(triage.id to HybridMode.PREFER_IN_CLOUD))
        val d = run(decide(triage, ready, fast))
        assertEquals(HybridMode.PREFER_IN_CLOUD, d.mode)
        assertTrue(d.redact)
        // Offline, the same setting keeps sorting on the phone.
        assertEquals(HybridMode.ONLY_ON_DEVICE, run(decide(triage, ready.copy(online = false), fast)).mode)
    }

    @Test fun `offline with no on-device model waits for the connection instead of failing`() {
        val noDevice = ready.copy(modelStatus = ModelStatus.UNAVAILABLE, online = false)
        assertTrue(decide(draft, noDevice) is RouteDecision.Defer)
        assertTrue(decide(triage, noDevice, config.copy(allowCloudForPrivate = true)) is RouteDecision.Defer)
        // Private with the cloud not allowed still says plainly that this phone cannot do it.
        assertTrue(decide(triage, noDevice) is RouteDecision.Reject)
    }

    @Test fun `redactable task prefers on-device with redacted cloud fallback`() {
        val d = run(decide(draft))
        assertEquals(HybridMode.PREFER_ON_DEVICE, d.mode)
        assertTrue(d.redact)
    }

    @Test fun `offline falls to on-device only and skips redaction`() {
        val d = run(decide(draft, ready.copy(online = false)))
        assertEquals(HybridMode.ONLY_ON_DEVICE, d.mode)
        assertFalse(d.redact)
    }

    @Test fun `no on-device model sends a redactable task to cloud`() {
        val d = run(decide(draft, ready.copy(modelStatus = ModelStatus.UNAVAILABLE)))
        assertEquals(HybridMode.ONLY_IN_CLOUD, d.mode)
        assertTrue(d.redact)
    }

    @Test fun `background sends a redactable task to cloud`() {
        assertEquals(HybridMode.ONLY_IN_CLOUD, run(decide(draft, ready.copy(appInForeground = false))).mode)
    }

    @Test fun `cloud-first task uses prefer-in-cloud and falls to device offline`() {
        assertEquals(HybridMode.PREFER_IN_CLOUD, run(decide(voice)).mode)
        assertEquals(HybridMode.ONLY_ON_DEVICE, run(decide(voice, ready.copy(online = false))).mode)
    }

    @Test fun `tool-using agent is cloud only and waits when offline`() {
        assertEquals(HybridMode.ONLY_IN_CLOUD, run(decide(agent)).mode)
        assertTrue(decide(agent, ready.copy(online = false)) is RouteDecision.Defer)
    }

    @Test fun `oversized input goes to cloud`() {
        assertEquals(HybridMode.ONLY_IN_CLOUD, run(decide(draft, tokens = 3500)).mode)
    }

    @Test fun `cloud kill switch keeps everything on-device`() {
        val off = config.copy(cloudEnabled = false)
        assertEquals(HybridMode.ONLY_ON_DEVICE, run(decide(draft, cfg = off)).mode)
        assertTrue(decide(agent, cfg = off) is RouteDecision.Reject)
    }

    @Test fun `public task is never redacted`() {
        assertFalse(run(decide(AiTask("lookup", Sensitivity.PUBLIC, Preference.CLOUD_FIRST))).redact)
    }

    @Test fun `remote override applies to non-private tasks only`() {
        val cfg = config.copy(modeOverrides = mapOf("draft" to HybridMode.ONLY_IN_CLOUD, "triage" to HybridMode.ONLY_IN_CLOUD))
        assertEquals(HybridMode.ONLY_IN_CLOUD, run(decide(draft, cfg = cfg)).mode)
        assertEquals(HybridMode.ONLY_ON_DEVICE, run(decide(triage, cfg = cfg)).mode)
    }

    @Test fun `unusable override falls back to normal routing`() {
        val cfg = config.copy(modeOverrides = mapOf("draft" to HybridMode.ONLY_ON_DEVICE))
        val d = run(decide(draft, ready.copy(modelStatus = ModelStatus.UNAVAILABLE), cfg))
        assertEquals(HybridMode.ONLY_IN_CLOUD, d.mode)
    }
}
