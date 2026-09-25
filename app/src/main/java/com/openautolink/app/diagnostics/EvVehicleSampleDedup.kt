package com.openautolink.app.diagnostics

/** Suppress only consecutive identical persisted compact vehicle observations.
 * A failed append never claims the row, so the next VHAL tick can retry it.
 * Distinct states, including battery changes and the next time bucket, survive.
 */
internal class EvVehicleSampleDedup {
    private var lastRecorded: String? = null

    fun shouldRecord(line: String): Boolean = line != lastRecorded
    fun recorded(line: String) { lastRecorded = line }
    fun reset() { lastRecorded = null }
}
