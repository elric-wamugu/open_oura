package org.openoura.android.sync

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.UUID
import java.util.concurrent.Executor

/**
 * The ring as Android's companion device.
 *
 * Linking the ring to the app through [CompanionDeviceManager] does two things for syncing:
 *
 *  * **Background exemption.** An app associated with a companion device, and holding
 *    `REQUEST_COMPANION_RUN_IN_BACKGROUND`, is left out of Doze and App Standby — the
 *    reason an hourly job used to run every four or five. This is how watch and earbud
 *    apps stay current.
 *  * **Presence.** Android watches for the ring itself and wakes [RingPresenceService] when
 *    it comes back into range, which starts a sync without waiting for the next hour.
 *
 * Linking is the system's own dialog: Android scans for a device advertising the ring's
 * service, the person picks it, and the association is kept until they unlink it here or
 * in Android's settings. API 33 and up, where presence callbacks carry the association;
 * below that the row is not offered.
 */
object RingCompanion {
    /** The ring's GATT service, which it advertises (see `BleTransport`). */
    private val SERVICE_UUID: UUID = UUID.fromString("98ED0001-A541-11E4-B6A0-0002A5D5C51B")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun manager(ctx: Context): CompanionDeviceManager? =
        ctx.getSystemService(CompanionDeviceManager::class.java)

    /** The ring's association, if it is linked. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun association(ctx: Context): AssociationInfo? =
        runCatching { manager(ctx)?.myAssociations?.firstOrNull() }.getOrNull()

    fun linked(ctx: Context): Boolean = supported && association(ctx) != null

    /**
     * Ask Android to link the ring. [onChooser] gets the system dialog to launch; once the
     * person picks the ring, [onLinked] runs, and presence is being observed.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun link(
        ctx: Context,
        executor: Executor,
        onChooser: (IntentSender) -> Unit,
        onLinked: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        val cdm = manager(ctx) ?: return onFailed("This phone has no companion device support.")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        cdm.associate(
            request,
            executor,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    observe(ctx, associationInfo)
                    SyncLog.record(ctx, "COMPANION", SyncLogResult.RING_NEAR)
                    onLinked()
                }

                override fun onFailure(error: CharSequence?) =
                    onFailed(error?.toString() ?: "The ring wasn't linked.")
            },
        )
    }

    /** Start (or restart) presence observation for the linked ring. Safe to repeat. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun observe(ctx: Context, info: AssociationInfo? = association(ctx)) {
        val cdm = manager(ctx) ?: return
        info ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                cdm.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                info.deviceMacAddress?.toString()?.let { cdm.startObservingDevicePresence(it) }
            }
        }.onFailure { Log.w(TAG, "could not observe the ring's presence", it) }
    }

    /** Unlink: Android forgets the association and the exemption with it. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun unlink(ctx: Context) {
        val cdm = manager(ctx) ?: return
        runCatching { cdm.myAssociations.forEach { cdm.disassociate(it.id) } }
            .onFailure { Log.w(TAG, "could not unlink the ring", it) }
    }

    private const val TAG = "OpenOuraCompanion"
}

/**
 * Woken by Android when the linked ring comes into range or leaves it. Coming back asks for a
 * sync, which [AutoSync.decide] holds to its own floor and to quiet hours, so a ring that
 * drops out for a minute in the next room does not start one every time.
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class RingPresenceService : CompanionDeviceService() {

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    @Deprecated("Replaced by onDevicePresenceEvent on API 36")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = disappeared()

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> appeared()
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> disappeared()
            else -> Unit
        }
    }

    /**
     * The ring stops advertising while a sync holds the link, so Android reports it gone and
     * back around every sync. Within the presence floor of the last attempt, that is what
     * this is, and it is neither logged nor acted on.
     */
    private fun ownSync(): Boolean {
        val since = System.currentTimeMillis() / 1000 - AutoSync.lastAttempt(applicationContext)
        return since in 0 until AutoSync.minIntervalFor(SyncTrigger.PRESENCE) ||
            org.openoura.android.ble.RingSyncService.isRunning ||
            org.openoura.android.ble.LiveHeartRate.isRunning
    }

    private fun appeared() {
        if (ownSync()) return
        Log.i(TAG, "ring in range")
        SyncLog.record(applicationContext, SyncTrigger.PRESENCE.name, SyncLogResult.RING_NEAR)
        AutoSync.requestSync(applicationContext, SyncTrigger.PRESENCE)
    }

    private fun disappeared() {
        if (ownSync()) return
        Log.i(TAG, "ring out of range")
        SyncLog.record(applicationContext, SyncTrigger.PRESENCE.name, SyncLogResult.RING_AWAY)
    }

    private companion object {
        const val TAG = "OpenOuraCompanion"
    }
}
