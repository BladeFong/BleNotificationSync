package com.ble.notification.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingManagerTest {

    @Test
    fun `PairedDevice data class works`() {
        val device = PairedDevice("com.example.app", "AA:BB:CC:DD:EE:FF", "App")
        assertEquals("com.example.app", device.packageName)
        assertEquals("AA:BB:CC:DD:EE:FF", device.mac)
        assertEquals("App", device.appName)
    }

    @Test
    fun `PairedDevice equals works`() {
        val d1 = PairedDevice("com.a", "mac1", "App")
        val d2 = PairedDevice("com.a", "mac1", "App")
        val d3 = PairedDevice("com.b", "mac1", "App")
        assertEquals(d1, d2)
        assertEquals(d1.hashCode(), d2.hashCode())
        assert(!d1.equals(d3))
    }

    @Test
    fun `PairingState enum values`() {
        assertEquals(4, PairingState.entries.size)
    }
}
