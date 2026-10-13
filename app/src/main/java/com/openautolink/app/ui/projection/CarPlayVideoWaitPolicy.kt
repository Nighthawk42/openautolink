package com.openautolink.app.ui.projection

import com.openautolink.app.carplay.CarPlayUiState

/** Presentation only: no touch, permission, transport or session ownership. */
internal object CarPlayVideoWaitPolicy {
    fun shouldShow(state: CarPlayUiState, enabled: Boolean, obscured: Boolean): Boolean =
        enabled && !obscured && state.waitingForVideo
}
