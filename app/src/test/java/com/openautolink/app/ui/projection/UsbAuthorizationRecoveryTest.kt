package com.openautolink.app.ui.projection

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.openautolink.app.carplay.UsbCarPlayPermission
import com.openautolink.app.carplay.UsbHotPlugSwitchPolicy
import com.openautolink.app.transport.usb.UsbConnectionManager
import com.openautolink.app.transport.usb.UsbConnectionState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import java.io.File
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsbAuthorizationRecoveryTest {
    private val usb = mockk<UsbManager>(relaxed = true)
    private val context = spyk(RuntimeEnvironment.getApplication()) {
        every { getSystemService(Context.USB_SERVICE) } returns usb
    }
    private val scope = TestScope()
    private val mux = mockk<UsbInterface>(relaxed = true) {
        every { interfaceClass } returns 255
        every { interfaceSubclass } returns 254
        every { interfaceProtocol } returns 2
    }
    private fun phone(name: String = "/dev/bus/usb/001/002", id: Int = 2) = mockk<UsbDevice>(relaxed = true) {
        every { deviceName } returns name
        every { deviceId } returns id
        every { vendorId } returns 0x05ac
        every { productId } returns 0x12a8
        every { interfaceCount } returns 1
        every { getInterface(0) } returns mux
    }
    private val apple = phone()
    private var offered: Long? = null
    private var starts = 0
    private var outcome: UsbCarPlayPermission.Result? = null
    private var granted = false
    private var cancelled = false
    private val manager = UsbConnectionManager(context, scope, {},
        onCandidate = { generation, _ -> offered = generation },
        onIphoneSelected = { selected ->
            starts++
            outcome = UsbCarPlayPermission.await(context, usb, selected, 20) { cancelled }
            true
        })
    private fun start() {
        every { usb.deviceList } returns hashMapOf(apple.deviceName to apple)
        every { usb.hasPermission(apple) } answers { granted }
        every { usb.requestPermission(apple, any<PendingIntent>()) } answers { granted = true }
        manager.start()
        scope.runCurrent()
    }
    private fun visible(enabled: Boolean = true, settings: Boolean = false, diagnostics: Boolean = false,
        transport: String = "wpp") =
        UsbPickerVisibility.shouldShow(transport, settings, diagnostics, enabled,
            UsbConnectionManager.connectionState.value, UsbConnectionManager.availableDevices.value)
    private fun attach(device: UsbDevice) {
        manager.javaClass.getDeclaredMethod("handleDeviceAttached", UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(manager, device)
    }
    @After fun cleanup() { manager.stop(); unmockkAll() }

    @Test fun coldPlugWithoutOsGrantImmediatelyOffersConsentRatherThanSilentlyWaiting() {
        start()
        assertNotNull("Static USBMUX matching must not suppress the production Switch/Stay offer", offered)
        assertEquals(0, starts)
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        assertTrue(manager.accept(offered!!))
        assertEquals(UsbCarPlayPermission.Result.GRANTED, outcome)
        verify(exactly = 1) { usb.requestPermission(apple, any<PendingIntent>()) }
    }

    @Test fun ungrantedUsbOffersSameCableConsentAndPreservesSavedWirelessSettings() = runBlocking {
        val directory = File("build/tmp/usb-recovery-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "settings.preferences_pb") })
        val constructor = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }
        val preferences = constructor.newInstance(store)
        try {
            preferences.setDirectTransport("wpp")
            preferences.setCarPlaySettings(CarPlaySettings(enabled = true, connectionMode = "usb"))
            val saved = store.data.first()
            start()
            assertEquals(UsbConnectionState.AWAITING_USER_SELECTION, UsbConnectionManager.connectionState.value)
            assertNotNull(offered)
            verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
            assertTrue("WPP + enabled CarPlay USB must expose the pending exact-device picker",
                visible(enabled = preferences.carPlaySettings.first().enabled, transport = preferences.directTransport.first()))
            scope.advanceTimeBy(120_001); scope.runCurrent()
            assertTrue("Timeout must retain same-cable recovery", visible())
            assertEquals(listOf(apple.deviceName), UsbConnectionManager.availableDevices.value.map { it.deviceName })
            UsbConnectionManager.selectDevice(apple.deviceName)
            assertNotNull(offered)
            assertEquals(0, starts) // Picking authorizes recovery, not switching projection.
            verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
            assertTrue(manager.accept(offered!!)) // The existing Switch/admission path.
            assertEquals(UsbCarPlayPermission.Result.GRANTED, outcome)
            assertEquals(1, starts)
            verify(exactly = 1) { usb.requestPermission(apple, any<PendingIntent>()) }
            assertFalse(visible())
            assertEquals(saved, store.data.first())
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun selectedInitialAndReplacementWithoutOsGrantsContinueUnderSameOwnerWithoutAnotherPicker() {
        start()
        UsbConnectionManager.selectDevice(apple.deviceName)
        val admission = offered!!
        assertTrue(manager.accept(admission))
        assertEquals(UsbCarPlayPermission.Result.GRANTED, outcome)
        val ownership = manager.javaClass.getDeclaredField("dispatchOwnership")
            .apply { isAccessible = true }.get(manager) as com.openautolink.app.transport.usb.UsbDispatchOwnership
        val owner = ownership.current()!!
        val transition = manager.iphoneReenumerationCallbacks(apple)
        assertTrue(transition.first())
        manager.javaClass.getDeclaredMethod("handleDeviceDetached", UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(manager, apple)
        val replacement = phone("/dev/bus/usb/001/003", 3)
        every { usb.deviceList } returns hashMapOf(replacement.deviceName to replacement)
        granted = false // The initial grant is NOT a replacement grant.
        every { usb.hasPermission(replacement) } answers { granted }
        every { usb.requestPermission(replacement, any<PendingIntent>()) } answers { granted = true }
        attach(replacement)
        assertSame(owner, ownership.current())
        assertTrue(owner.reenumerating)
        assertEquals(UsbCarPlayPermission.Result.GRANTED,
            UsbCarPlayPermission.await(context, usb, replacement, 20) { cancelled })
        assertTrue(transition.second(replacement)) // Runtime's serial/configuration-qualified acknowledgement.
        scope.advanceTimeBy(15_001); scope.runCurrent()
        assertSame(owner, ownership.current())
        assertEquals(replacement.deviceName, owner.deviceName)
        assertFalse(owner.retiring)
        assertEquals(admission, offered)
        assertEquals(1, starts)
        assertEquals(UsbConnectionState.CONNECTING, UsbConnectionManager.connectionState.value)
        verify(exactly = 1) { usb.requestPermission(apple, any<PendingIntent>()) }
        verify(exactly = 1) { usb.requestPermission(replacement, any<PendingIntent>()) }
    }

    @Test fun stayRevokesAdmissionAndNeverRequestsPermission() {
        start()
        assertTrue(visible())
        UsbConnectionManager.selectDevice(apple.deviceName)
        manager.stay(offered!!)
        assertFalse(manager.accept(offered!!))
        assertEquals(0, starts)
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
    }

    @Test fun pendingRecoveryNeverCoversSettingsDiagnosticsOrDisabledCarPlay() {
        start()
        assertTrue(visible())
        assertFalse(visible(enabled = false))
        assertFalse(visible(settings = true))
        assertFalse(visible(diagnostics = true))
        manager.stop()
        assertFalse(visible())
    }

    @Test fun switchAwaitsRetirementAndCancellationPreventsRequest() {
        start()
        assertTrue(visible())
        UsbConnectionManager.selectDevice(apple.deviceName)
        val policy = UsbHotPlugSwitchPolicy()
        val retired = CompletableDeferred<Unit>()
        scope.launch {
            policy.accept(offered!!,
                resolve = { manager.pendingDevice(offered!!)?.let { UsbHotPlugSwitchPolicy.Protocol.CARPLAY } },
                eligible = { true }, claimed = {}, retire = { retired.await() },
                admit = { manager.accept(offered!!) }, cleared = {}, restore = {})
        }
        scope.runCurrent()
        assertEquals(0, starts)
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
        cancelled = true
        retired.complete(Unit)
        scope.runCurrent()
        assertEquals(UsbCarPlayPermission.Result.CANCELLED, outcome)
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
    }

    @Test fun stoppedPickerCannotAuthorizeAStaleManagerOrReplacementDevice() {
        start()
        assertTrue(visible())
        manager.stop()
        val replacement = phone(id = 3)
        every { usb.deviceList } returns hashMapOf(replacement.deviceName to replacement)
        UsbConnectionManager.selectDevice(apple.deviceName)
        scope.advanceTimeBy(120_001); scope.runCurrent()
        assertFalse(manager.accept(offered!!))
        assertEquals(0, starts)
        verify(exactly = 0) { usb.requestPermission(any<UsbDevice>(), any<PendingIntent>()) }
    }

    @Test fun lateAttachCannotReplaceAdmittedOwner() {
        start()
        assertTrue(visible())
        UsbConnectionManager.selectDevice(apple.deviceName)
        assertTrue(manager.accept(offered!!))
        val generation = offered!!
        scope.advanceTimeBy(120_001); scope.runCurrent()
        attach(apple)
        assertEquals(generation, offered)
        assertEquals(1, starts)
        assertFalse(manager.accept(generation))
        verify(exactly = 1) { usb.requestPermission(apple, any<PendingIntent>()) }
    }
}
