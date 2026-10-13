package com.openautolink.app.transport.bluetooth

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32], manifest=Config.NONE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarPlayBluetoothEvidenceTest {
    private val audio = "android.bluetooth.headsetclient.profile.action.AUDIO_STATE_CHANGED"
    private val ag = "android.bluetooth.headsetclient.profile.action.AG_EVENT"
    private val voice = "android.bluetooth.headsetclient.extra.VOICE_RECOGNITION"
    private val state = "android.bluetooth.profile.extra.STATE"
    private val previous = "android.bluetooth.profile.extra.PREVIOUS_STATE"

    @Test fun enabledVehicleObserverRegistersSourceVerifiedHandsFreeAudioAndVoiceActions() = runTest {
        val app = mockk<Application>(relaxed=true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val filter = slot<IntentFilter>()
        every { app.registerReceiver(any(),capture(filter),Manifest.permission.BLUETOOTH_CONNECT,null) } returns null
        val observer = CarBluetoothConnectionObserver(app,MutableStateFlow(true),backgroundScope,sdkInt=32)
        try {
            observer.start();runCurrent()
            assertTrue("source-verified vehicle HFP audio action must be observed",filter.captured.hasAction(audio))
            assertTrue("Siri voice-recognition AG events must be observed",filter.captured.hasAction(ag))
        } finally { observer.close();unmockkAll() }
    }

    @Test fun profileEvidenceAndPowerEdgesRemainFiniteWithoutIdentifyingASelectedPhone() = runTest {
        val app = mockk<Application>(relaxed=true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        every { app.registerReceiver(capture(callback),any(),Manifest.permission.BLUETOOTH_CONNECT,null) } returns null
        val observer = CarBluetoothConnectionObserver(app,MutableStateFlow(true),backgroundScope,sdkInt=32)
        val receipts = mutableListOf<String>()
        var subscription: AutoCloseable? = null
        try {
            observer.start();runCurrent()
            subscription = observer.subscribeEvidence { receipts.add(it) }
            val device = mockk<android.bluetooth.BluetoothDevice>(relaxed=true)
            callback.captured.onReceive(app,Intent(CarBluetoothConnectionObserver.A2DP_SINK_ACTION)
                .putExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE,device).putExtra(state,2).putExtra(previous,1))
            assertTrue("A2DP connection is evidence, not proof of playback",receipts.any { it.contains("event=a2dp-sink state=connected previous=connecting scope=device owner=unknown") })
            callback.captured.onReceive(app,Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.STATE_OFF))
            assertTrue(receipts.any { it.contains("event=adapter state=off") })
            val count = receipts.size
            callback.captured.onReceive(app,Intent(ag).putExtra(voice,1))
            assertEquals("adapter-off rejects late speech evidence",count,receipts.size)
        } finally { subscription?.close();observer.close();unmockkAll() }
    }

    @Test fun actualBroadcastEvidenceIsFiniteOwnerUnknownAndStopsAtSubscriptionRetirement() = runTest {
        val app = mockk<Application>(relaxed=true)
        val manager = mockk<BluetoothManager>()
        every { app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) } returns PackageManager.PERMISSION_GRANTED
        every { app.getSystemService(BluetoothManager::class.java) } returns manager
        every { manager.adapter } returns mockk<BluetoothAdapter>()
        val callback = slot<BroadcastReceiver>()
        every { app.registerReceiver(capture(callback),any(),Manifest.permission.BLUETOOTH_CONNECT,null) } returns null
        val enabled = MutableStateFlow(true)
        val observer = CarBluetoothConnectionObserver(app,enabled,backgroundScope,sdkInt=32)
        val receipts = mutableListOf<String>()
        var subscription: AutoCloseable? = null
        try {
            observer.start();runCurrent()
            val method = observer.javaClass.methods.firstOrNull { it.name == "subscribeEvidence" }
            assertNotNull("exact-link diagnostics need a passive bounded evidence subscription",method)
            subscription = method!!.invoke(observer,{ line: String -> receipts.add(line) }) as AutoCloseable
            callback.captured.onReceive(app,Intent(audio).putExtra(state,2).putExtra(previous,1).putExtra("private","never-export"))
            callback.captured.onReceive(app,Intent(ag).putExtra(voice,1).putExtra("phone_number","never-export"))
            // Unrelated AG fields cannot be misclassified as Siri transitions.
            callback.captured.onReceive(app,Intent(ag).putExtra("android.bluetooth.headsetclient.extra.NETWORK_STATUS",1))
            assertEquals(1,receipts.count { it.contains("event=hfp-audio state=connected previous=connecting") })
            assertEquals(1,receipts.count { it.contains("event=hfp-voice state=started previous=unknown") })
            assertTrue(receipts.all { it.contains("scope=device owner=unknown") && !it.contains("never-export") })
            subscription!!.close();val count=receipts.size
            callback.captured.onReceive(app,Intent(ag).putExtra(voice,0))
            assertEquals(count,receipts.size)
        } finally { subscription?.close();observer.close();unmockkAll() }
    }
}
