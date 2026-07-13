package com.ble.notification.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingManagerTest {

    private lateinit var manager: PairingManager

    @Before
    fun setUp() {
        manager = PairingManager()
    }

    @Test
    fun `isPaired returns false for unknown package`() {
        assertFalse(manager.isPaired("com.example.unknown"))
    }

    @Test
    fun `savePairing makes isPaired return true`() {
        manager.savePairing("com.example.app", "AA:BB:CC:DD:EE:FF", "Example App")

        assertTrue(manager.isPaired("com.example.app"))
    }

    @Test
    fun `getPairedMac returns saved MAC`() {
        manager.savePairing("com.example.app", "AA:BB:CC:DD:EE:FF", "Example App")

        assertEquals("AA:BB:CC:DD:EE:FF", manager.getPairedMac("com.example.app"))
    }

    @Test
    fun `getPairedMac returns null for unknown package`() {
        assertNull(manager.getPairedMac("com.example.unknown"))
    }

    @Test
    fun `savePairing overwrites existing pairing`() {
        manager.savePairing("com.example.app", "AA:BB:CC:DD:EE:FF", "Example App")
        manager.savePairing("com.example.app", "11:22:33:44:55:66", "Example App v2")

        assertEquals("11:22:33:44:55:66", manager.getPairedMac("com.example.app"))
    }

    @Test
    fun `multiple packages are independent`() {
        manager.savePairing("com.example.app1", "AA:BB:CC:DD:EE:FF", "App1")
        manager.savePairing("com.example.app2", "11:22:33:44:55:66", "App2")

        assertTrue(manager.isPaired("com.example.app1"))
        assertTrue(manager.isPaired("com.example.app2"))
        assertEquals("AA:BB:CC:DD:EE:FF", manager.getPairedMac("com.example.app1"))
        assertEquals("11:22:33:44:55:66", manager.getPairedMac("com.example.app2"))
    }

    @Test
    fun `initial state is IDLE`() {
        assertEquals(PairingState.IDLE, manager.currentState)
    }
}
