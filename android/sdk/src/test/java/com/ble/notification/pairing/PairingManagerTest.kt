package com.ble.notification.pairing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingManagerTest {

    private lateinit var pairingManager: PairingManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        pairingManager = PairingManager(context)
        pairingManager.unpairAll()
    }

    @Test
    fun testPairedDeviceDataClass() {
        val device = PairedDevice("pc-uuid-1", "My Work PC", "DemoApp", 1000L)
        assertEquals("pc-uuid-1", device.uuid)
        assertEquals("My Work PC", device.name)
        assertEquals("DemoApp", device.appName)
        assertEquals(1000L, device.pairedAt)
    }

    @Test
    fun testSaveAndGetMultipleDevicesByUuid() {
        val uuid1 = "pc-uuid-001"
        val uuid2 = "pc-uuid-002"

        pairingManager.savePairing(
            uuid = uuid1,
            deviceName = "Work-PC",
            appName = "DemoApp",
            baseKey = byteArrayOf(1, 2, 3)
        )

        pairingManager.savePairing(
            uuid = uuid2,
            deviceName = "Home-PC",
            appName = "DemoApp",
            baseKey = byteArrayOf(4, 5, 6)
        )

        val devices = pairingManager.getPairedDevices()
        assertEquals(2, devices.size)
        assertTrue(pairingManager.isPaired(uuid1))
        assertTrue(pairingManager.isPaired(uuid2))
        assertTrue(pairingManager.isPaired())

        val dev1 = devices.find { it.uuid == uuid1 }
        assertNotNull(dev1)
        assertEquals("Work-PC", dev1?.name)

        pairingManager.unpair(uuid1)
        assertEquals(1, pairingManager.getPairedDevices().size)
        assertFalse(pairingManager.isPaired(uuid1))
        assertTrue(pairingManager.isPaired())

        pairingManager.unpairAll()
        assertEquals(0, pairingManager.getPairedDevices().size)
        assertFalse(pairingManager.isPaired())
    }
}

