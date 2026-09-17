package org.openoura.android

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openoura.android.ble.BOND_REFUSAL_CONFIRMATIONS
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

    private fun BondWatch.pollAll(vararg states: Int) = states.map { poll(it) }

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
        repeat(10) { assertEquals(BondPoll.WAITING, watch.poll(BluetoothDevice.BOND_NONE)) }
    }

    @Test
    fun `a refusal is only called once BOND_NONE has held`() {
        val watch = BondWatch()
        watch.poll(BluetoothDevice.BOND_BONDING)
        repeat(BOND_REFUSAL_CONFIRMATIONS - 1) {
            assertEquals(BondPoll.WAITING, watch.poll(BluetoothDevice.BOND_NONE))
        }
        assertEquals(BondPoll.REFUSED, watch.poll(BluetoothDevice.BOND_NONE))
    }

    @Test
    fun `a single BOND_NONE blip mid-pairing is not a refusal`() {
        // The ring is addressed by a rotating private address while the stack resolves it
        // to the ring's identity. A momentary NONE across that hand-over must not be
        // reported as "the ring refused pairing" — that bond is about to succeed.
        val watch = BondWatch()
        val seen = watch.pollAll(
            BluetoothDevice.BOND_BONDING,
            BluetoothDevice.BOND_NONE,
            BluetoothDevice.BOND_BONDING,
            BluetoothDevice.BOND_BONDED,
        )
        assertEquals(
            listOf(BondPoll.WAITING, BondPoll.WAITING, BondPoll.WAITING, BondPoll.BONDED),
            seen,
        )
    }

    @Test
    fun `a blip does not count towards a later refusal`() {
        val watch = BondWatch()
        watch.pollAll(BluetoothDevice.BOND_BONDING, BluetoothDevice.BOND_NONE)
        // Back to bonding: the earlier NONE is spent, so a real refusal still needs its
        // full run of confirmations rather than being one poll away.
        watch.poll(BluetoothDevice.BOND_BONDING)
        repeat(BOND_REFUSAL_CONFIRMATIONS - 1) {
            assertEquals(BondPoll.WAITING, watch.poll(BluetoothDevice.BOND_NONE))
        }
        assertEquals(BondPoll.REFUSED, watch.poll(BluetoothDevice.BOND_NONE))
    }

    @Test
    fun `a bond seen only once it has completed still counts`() {
        // The ring paired in ~6 s on its charger, but a fast one can finish inside a single
        // 200 ms poll interval and BOND_BONDING is then never observed.
        val watch = BondWatch()
        assertEquals(BondPoll.BONDED, watch.poll(BluetoothDevice.BOND_BONDED))
    }

    @Test
    fun `confirming a refusal costs well under the pairing timeout`() {
        // 3 polls at 200 ms. The guard must stay cheap enough that a genuine SMP_FAIL is
        // still reported as a refusal rather than running out the 30 s deadline.
        assertEquals(true, BOND_REFUSAL_CONFIRMATIONS in 2..10)
    }
}
