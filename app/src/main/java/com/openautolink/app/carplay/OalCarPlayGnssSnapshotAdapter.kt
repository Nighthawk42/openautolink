package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage

class OalCarPlayGnssSnapshotAdapter(
    private val elapsedMs: () -> Long,
    private val createSource: ((ControlMessage.Gnss) -> Unit) -> GnssForwarder,
) : AutoCloseable {
    private var cache = com.openautolink.carplaylab.intake.OalNmeaCache(elapsedMs)
    private var source: GnssForwarder? = null
    private var active = false
    private var retired = false
    private var generation = 0L

    /** Caller holds this adapter's monitor. Do not lose an owner whose OS removal failed. */
    private fun retireSource() {
        val owner=source ?: return
        owner.stop()
        if (source===owner) source=null
    }

    @Synchronized fun setActive(requestedActive: Boolean) {
        if (retired || (requestedActive == active && (active || source == null))) return
        generation++
        active = false
        cache = com.openautolink.carplaylab.intake.OalNmeaCache(elapsedMs)
        retireSource()
        if (!requestedActive) return
        active=true
        val ownerGeneration = generation
        val owner = try { createSource { message ->
            synchronized(this) {
                if (active && !retired && ownerGeneration == generation) cache.accept(message.nmea)
            }
        } } catch (failure: Exception) {
            active=false;generation++;throw failure
        }
        source = owner
        try {
            owner.start()
            if (!owner.isActive) { active=false;generation++;retireSource() }
        } catch (failure: Exception) {
            active=false;generation++
            try {retireSource()} catch (cleanup: Exception) {failure.addSuppressed(cleanup)}
            throw failure // Reporter catches without exporting text; failed exact owner stays retained.
        }
    }

    @Synchronized fun latest(requested: Set<Int>): String? =
        if (active && !retired) cache.latest(requested) else null

    @Synchronized override fun close() {
        if (!retired) {
            retired=true;active=false;generation++
            cache=com.openautolink.carplaylab.intake.OalNmeaCache(elapsedMs)
        }
        retireSource() // Retry a failed removal even after the permanent callback fence is set.
    }
}
