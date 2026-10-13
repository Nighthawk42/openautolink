package com.openautolink.app.input

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.openautolink.app.transport.ControlMessage

/**
 * Forwards NMEA sentences from Android LocationManager to the bridge via the control channel.
 * The bridge relays these to aasdk's SensorService → phone's Google Maps.
 *
 * Requires ACCESS_FINE_LOCATION permission — caller must ensure permission is granted
 * before calling start(). If permission is missing, start() logs a warning and returns.
 */
class GnssForwarderImpl(
    private val context: Context,
    private val sendMessage: (ControlMessage.Gnss) -> Unit,
    private val activateProvider: Boolean,
) : GnssForwarder {
    // Preserve AA's existing independently owned LocationManager request and constructor API.
    constructor(context: Context, sendMessage: (ControlMessage.Gnss) -> Unit) : this(context,sendMessage,false)

    companion object {
        private const val TAG = "GnssForwarder"
    }

    private var nmeaListener: OnNmeaMessageListener? = null
    private var providerListener: LocationListener? = null
    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override var isActive: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start() {
        if (isActive) return

        val lm = locationManager
        if (lm == null) {
            Log.w(TAG, "LocationManager not available")
            return
        }

        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "GPS provider not enabled")
            return
        }

        val listener = OnNmeaMessageListener { nmea, _ ->
            // Only forward standard GPS sentences
            if (isActive && (nmea.startsWith("\$GP") || nmea.startsWith("\$GN") || nmea.startsWith("\$GL"))) {
                val trimmed = nmea.trimEnd('\r', '\n')
                if (trimmed.isNotEmpty()) {
                    sendMessage(ControlMessage.Gnss(trimmed))
                }
            }
        }

        try {
            if (!lm.addNmeaListener(listener, Handler(Looper.getMainLooper()))) return
            nmeaListener = listener
            if (activateProvider) {
                // NMEA registration collects engine output; it does not own a navigation request.
                // Activate GNSS only for this subscribed CarPlay source. Never synthesize sentences
                // from Location objects or interfere with another consumer's exact listener.
                val provider = LocationListener { }
                providerListener = provider
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,250L,0f,provider,Looper.getMainLooper())
            }
            isActive = true
            Log.i(TAG, "GNSS forwarding started")
        } catch (e: SecurityException) {
            stop()
            Log.w(TAG, "GNSS permission denied")
        } catch (e: RuntimeException) {
            try { stop() } catch (cleanup: RuntimeException) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    @Synchronized
    override fun stop() {
        isActive = false
        var failure: RuntimeException? = null
        nmeaListener?.let { listener ->
            try { locationManager?.removeNmeaListener(listener); nmeaListener = null }
            catch (e: RuntimeException) { failure = e }
        }
        providerListener?.let { listener ->
            try { locationManager?.removeUpdates(listener); providerListener = null }
            catch (e: RuntimeException) { if (failure == null) failure=e else failure!!.addSuppressed(e) }
        }
        failure?.let { throw it } // Retain failed exact registrations so retirement can be retried.
        Log.i(TAG, "GNSS forwarding stopped")
    }
}
