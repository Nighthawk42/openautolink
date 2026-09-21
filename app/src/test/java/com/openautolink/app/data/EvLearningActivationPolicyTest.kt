package com.openautolink.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvLearningActivationPolicyTest {
    @Test
    fun learnedRequestReportsReadinessAndContractSafetyHoldWithoutWireActivation() {
        val warming = EvLearningActivationPolicy.evaluate(
            tuningEnabled = true,
            requestedMode = "learned",
            learnerReady = false,
        )
        assertEquals("learned", warming.requestedMode)
        assertEquals(false, warming.learnerReady)
        assertEquals("existing-unlearned", warming.wireEffectiveMode)
        assertTrue(warming.safetyHolds.contains("learner-not-ready"))
        assertTrue(warming.safetyHolds.contains("external-model-contract-unvalidated"))

        val ready = EvLearningActivationPolicy.evaluate(
            tuningEnabled = true,
            requestedMode = "learned",
            learnerReady = true,
        )
        assertEquals(true, ready.learnerReady)
        assertEquals("existing-unlearned", ready.wireEffectiveMode)
        assertEquals(setOf("external-model-contract-unvalidated"), ready.safetyHolds)
        assertTrue(ready.explanation.contains("coefficient semantics"))
    }

    @Test
    fun disabledTuningReportsRequestedModeButExistingWireBehavior() {
        val result = EvLearningActivationPolicy.evaluate(
            tuningEnabled = false,
            requestedMode = "learned",
            learnerReady = true,
        )
        assertEquals("learned", result.requestedMode)
        assertEquals("existing-unlearned", result.wireEffectiveMode)
        assertEquals(setOf("tuning-disabled"), result.safetyHolds)
    }

    @Test
    fun `all unvalidated tuning requests are held off the wire`() {
        listOf("manual", "multiplier", "learned").forEach { requested ->
            val result = EvLearningActivationPolicy.evaluate(
                tuningEnabled = true,
                requestedMode = requested,
                learnerReady = requested == "learned",
            )

            assertFalse(result.wireTuningAllowed)
            assertEquals("existing-unlearned", result.wireEffectiveMode)
            assertTrue(result.safetyHolds.contains("external-model-contract-unvalidated"))
        }
    }
}
