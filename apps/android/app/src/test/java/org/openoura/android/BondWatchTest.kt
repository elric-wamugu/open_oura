package org.openoura.android

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openoura.android.ble.BondPoll
import org.openoura.android.ble.BondWatch

/**
 * Pairing with the ring is polled rather than listened for, because Android 17 never
 * delivered `ACTION_BOND_STATE_CHANGED` to the receiver that used to wait for it — a bond
 * that had succeeded was reported as a 30-second timeout. Getting the poll wrong is just as
 * misleading and just as invisible without a ring on a charger, so the sequence logic lives
 * in [BondWatch] and is checked here instead.
 */
class BondWatchTest {

    @Test
    fun `a bond that succeeds is reported, not timed out`() {
        val watch = BondWatch()
        assertEquals(BondPoll.WAITING, watch.poll(BluetoothDevice.BOND_NONE))
        assertEquals(BondPoll.WAITING, watch.poll(BluetoothDevice.BOND_BONDING))
        assertEquals(BondPoll.BONDED, watch.poll(BluetoothDevice.BOND_BONDED))
    }

    @Test
    fun `BOND_NONE before bonding starts is not a refusal`() {
        val watch = BondWatch()
        repeat(5) { assertEquals(BondPoll.WAITING, watch.poll(BluetoothDevice.BOND_NONE)) }
    }

    @Test
    fun `BOND_NONE after bonding started is a refusal`() {
        val watch = BondWatch()
        watch.poll(BluetoothDevice.BOND_BONDING)
        assertEquals(BondPoll.REFUSED, watch.poll(BluetoothDevice.BOND_NONE))
    }

    @Test
    fun `a bond seen only once it has completed still counts`() {
        // The ring paired in ~6 s on its charger, but a fast one can finish inside a single
        // 200 ms poll interval and BOND_BONDING is then never observed.
        val watch = BondWatch()
        assertEquals(BondPoll.BONDED, watch.poll(BluetoothDevice.BOND_BONDED))
    }
}
