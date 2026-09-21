package com.openautolink.app.data

/**
 * Reports EV learner activation separately from the existing VEM wire path.
 *
 * Learned output remains observation-only until the external model's coefficient
 * semantics and receiving implementation have been validated. This policy is
 * deliberately pure and is not wired into VEM construction.
 */
object EvLearningActivationPolicy {
    const val WIRE_EXISTING_UNLEARNED = "existing-unlearned"

    data class State(
        val requestedMode: String,
        val learnerReady: Boolean,
        val wireTuningAllowed: Boolean,
        val wireEffectiveMode: String,
        val safetyHolds: Set<String>,
        val explanation: String,
    )

    fun evaluate(
        tuningEnabled: Boolean,
        requestedMode: String,
        learnerReady: Boolean,
    ): State {
        if (!tuningEnabled) {
            return State(
                requestedMode = requestedMode,
                learnerReady = learnerReady,
                wireTuningAllowed = false,
                wireEffectiveMode = WIRE_EXISTING_UNLEARNED,
                safetyHolds = setOf("tuning-disabled"),
                explanation = "EV tuning is disabled; the existing unlearned wire model remains effective.",
            )
        }

        val holds = linkedSetOf<String>()
        if (requestedMode == "learned" && !learnerReady) holds += "learner-not-ready"
        holds += "external-model-contract-unvalidated"
        return State(
            requestedMode = requestedMode,
            learnerReady = learnerReady,
            wireTuningAllowed = false,
            wireEffectiveMode = WIRE_EXISTING_UNLEARNED,
            safetyHolds = holds,
            explanation = if (requestedMode == "learned") {
                "Learned mode is observation-only: external coefficient semantics and receiver behavior are unvalidated."
            } else {
                "This policy does not activate tuned values on the wire."
            },
        )
    }
}
