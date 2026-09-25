package com.openautolink.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvVehicleSampleDedupTest {
    @Test fun `same state repeated within a minute is omitted after successful write`() {
        val dedup = EvVehicleSampleDedup()
        val first = """{"schema":2,"type":"vehicle","elapsedBucketS":120,"batteryWh":40000}"""
        assertTrue(dedup.shouldRecord(first))
        assertTrue(dedup.shouldRecord(first)) // failed append must not suppress retry
        dedup.recorded(first)
        assertFalse(dedup.shouldRecord(first))
        assertTrue(dedup.shouldRecord(first.replace("120", "180")))
        assertTrue(dedup.shouldRecord(first.replace("40000", "39990")))
        dedup.reset()
        assertTrue(dedup.shouldRecord(first))
    }

    @Test fun `a changed state is retained even if an earlier state returns`() {
        val dedup = EvVehicleSampleDedup()
        val first = "a"
        val second = "b"
        dedup.recorded(first)
        assertTrue(dedup.shouldRecord(second))
        dedup.recorded(second)
        assertTrue(dedup.shouldRecord(first))
    }
}
