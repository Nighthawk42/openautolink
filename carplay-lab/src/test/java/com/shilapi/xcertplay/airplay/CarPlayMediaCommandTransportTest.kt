package com.shilapi.xcertplay.airplay

import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class CarPlayMediaCommandTransportTest {
    private fun field(session: AirPlaySession,name: String)=session.javaClass.getDeclaredField(name).apply { isAccessible=true }
    @Test fun mediaPressAndReleaseUseAdvertisedConsumerHidOverEncryptedCommandTransport() {
        val session=AirPlaySession(Socket(),AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480)),
            AirPlayIdentity.generate(),PairingStore(),null,object : AirPlaySessionListener {},object : AirPlayMediaHandler {})
        try {
            ServerSocket(0).use { server -> Socket("127.0.0.1",server.localPort).use { phone -> server.accept().use { event ->
                val key=ByteArray(32) { (it+1).toByte() }; val peer=ControlCipher(key,key)
                field(session,"eventSocket").set(session,event); field(session,"eventCipher").set(session,ControlCipher(key,key))
                phone.soTimeout=1000
                session.sendMedia(2)
                for (index in listOf(2,0)) {
                    val input=phone.getInputStream(); val length=input.readNBytes(2)
                    val n=(length[0].toInt() and 255) or ((length[1].toInt() and 255) shl 8)
                    val plain=peer.decrypt(length+input.readNBytes(n+16)).data
                    val boundary=plain.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")+4
                    val command=BplistCodec.decode(plain.copyOfRange(boundary,plain.size)) as Map<*,*>
                    assertEquals("hidSendReport",command["type"])
                    assertEquals(AirPlayHid.MEDIA_HID_UID.toString(16),command["uuid"])
                    assertArrayEquals(byteArrayOf(index.toByte()),command["hidReport"] as ByteArray)
                }
                session.sendMedia(7)
                phone.soTimeout=100
                assertTrue("unsupported descriptor index must not go on wire",runCatching { phone.getInputStream().read() }.exceptionOrNull() is java.net.SocketTimeoutException)
            } } }
        } finally { session.close() }
    }
}
