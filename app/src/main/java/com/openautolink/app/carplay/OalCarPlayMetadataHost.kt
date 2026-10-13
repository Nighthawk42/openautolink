package com.openautolink.app.carplay

import android.content.Context
import com.openautolink.app.cluster.ClusterManager
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal interface CarPlayMetadataHost : AutoCloseable {
    fun start(): Boolean
    fun publish(media: CarPlayMediaMetadata?, navigation: CarPlayNavigationMetadata?, artwork: ByteArray?)
}

/** Exact authenticated Wi-Fi link owns the existing host adapters, never a new MediaSession. */
internal class OalCarPlayMetadataHost(private val context: Context,
    private val mediaCommand: (Int) -> Boolean = { false }) : CarPlayMetadataHost {
    private val owner = Any()
    private var cluster: ClusterManager? = null
    private var mediaManager: OalMediaSessionManager? = null
    private var previousMedia: CarPlayMediaMetadata? = null
    private var previousArt: ByteArray? = null
    private var previousNavigation: CarPlayNavigationMetadata? = null
    override fun start(): Boolean {
        val preferences = AppPreferences.getInstance(context)
        val enabled = runBlocking { preferences.clusterNavigation.first() }
        val units = runBlocking { preferences.distanceUnits.first() }
        if (!MetadataHostOwnership.process.claim(owner)) return false
        MetadataHostOwnership.process.publish(owner) {
            ClusterNavigationState.clearHost()
            ClusterNavigationState.distanceUnits = units
            mediaManager = OalMediaSessionManager.getInstance(context).also { it.initialize(); it.resetHostToIdle() }
            mediaManager?.installCarPlayControls(owner, object : OalMediaSessionManager.MediaControlCallback {
                override fun onCommand(command: com.openautolink.app.media.GmMediaControlPolicy.Command) {
                    // Consumer HID descriptor: Play B0, Pause B1, Next B5, Previous B6.
                    // No Stop usage is advertised; GM STOP quiesces playback with explicit Pause,
                    // matching the existing AA policy. Never send the state-dependent toggle.
                    val index = when (command) {
                        com.openautolink.app.media.GmMediaControlPolicy.Command.PLAY -> 1
                        com.openautolink.app.media.GmMediaControlPolicy.Command.PAUSE,
                        com.openautolink.app.media.GmMediaControlPolicy.Command.STOP -> 2
                        com.openautolink.app.media.GmMediaControlPolicy.Command.NEXT -> 4
                        com.openautolink.app.media.GmMediaControlPolicy.Command.PREVIOUS -> 5
                    }
                    mediaCommand(index)
                }
            })
            if (enabled) cluster = ClusterManager(context).also { it.setClusterEnabled(true) }
        }
        return true
    }
    override fun publish(media: CarPlayMediaMetadata?, navigation: CarPlayNavigationMetadata?, artwork: ByteArray?) {
        MetadataHostOwnership.process.publish(owner) {
            if (navigation != previousNavigation) {
                val maneuver = navigation?.let(CarPlayClusterMetadataAdapter::maneuver)
                if (maneuver != null) ClusterNavigationState.updateHost(maneuver) else ClusterNavigationState.clearHost()
                previousNavigation = navigation
            }
            if (media != null && (media != previousMedia || !artwork.contentEquals(previousArt))) {
                mediaManager?.updateCarPlay(owner, media, artwork, clearArtwork = artwork == null)
                previousMedia = media
                previousArt = artwork?.copyOf()
            }
        }
    }
    override fun close() {
        MetadataHostOwnership.process.retire(owner) {
            mediaManager?.retireCarPlayControls(owner)
            ClusterNavigationState.clearHost()
            mediaManager?.resetHostToIdle()
            cluster?.release()
            cluster = null
            previousMedia = null; previousArt = null; previousNavigation = null
        }
    }
}
