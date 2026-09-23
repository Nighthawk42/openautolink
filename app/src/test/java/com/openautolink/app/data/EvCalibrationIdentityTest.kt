package com.openautolink.app.data

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class EvCalibrationIdentityTest {
    private fun vehicle(capacityWh: Float?, make: String? = "Make", model: String? = "Model") =
        ControlMessage.VehicleData(
            carMake = make,
            carModel = model,
            carYear = "2024",
            evBatteryCapacityWh = capacityWh,
        )

    @Test fun `two capacity configurations never share calibration`() {
        val small = EvLearnedRateEstimator.calibrationKey(vehicle(60_000f))
        val large = EvLearnedRateEstimator.calibrationKey(vehicle(90_000f))
        assertNotNull(small)
        assertNotNull(large)
        assertNotEquals(small, large)
        assertTrue(small!!.contains("rev2"))
        assertTrue(small.contains("absolute-wh"))
    }

    @Test fun `unknown identity or capacity cannot cross reuse`() {
        assertNull(EvLearnedRateEstimator.calibrationKey(vehicle(null)))
        assertNull(EvLearnedRateEstimator.calibrationKey(vehicle(60_000f, make = null)))
        assertNull(EvLearnedRateEstimator.calibrationKey(vehicle(0f)))
    }
}
