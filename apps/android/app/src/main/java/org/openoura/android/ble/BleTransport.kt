package org.openoura.android.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "OpenOuraBle"

/** Gen-3/4 expose `…0003`; Ring 5 adds `…0004/0005/0006`. We subscribe to whatever is there. */
private val SERVICE_UUID: UUID = UUID.fromString("98ED0001-A541-11E4-B6A0-0002A5D5C51B")
private val WRITE_UUID: UUID = UUID.fromString("98ED0002-A541-11E4-B6A0-0002A5D5C51B")

/** Client Characteristic Configuration Descriptor — the standard notify/indicate switch. */
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Requested ATT MTU. The ring bundles many events per notification on a large MTU; 517 is
 * the maximum an Android peripheral connection will grant, and the stack negotiates down.
 */
private const val REQUESTED_MTU = 517

/** Pause between stopping the scan and opening the link — see the note at the call site. */
private const val SCAN_SETTLE_MS = 400L

/** GATT 133 is usually a transient stack race, so the connect phase is retried. */
private const val CONNECT_ATTEMPTS = 3
private const val RETRY_BACKOFF_MS = 700L

/**
 * Below this the link is on the weak side and worth mentioning in a failure — but only as a
 * footnote. A connect that *establishes* and is then dropped by the ring is not a range
 * problem however tempting the low number looks; see [ensureBonded].
 */
private const val WEAK_RSSI_DBM = -80

/** Pairing can surface a system dialog, so it gets a human-scale deadline. */
private const val BOND_TIMEOUT_MS = 30_000L

private const val CONNECT_TIMEOUT_MS = 30_000L
private const val DISCOVER_TIMEOUT_MS = 15_000L
private const val MTU_TIMEOUT_MS = 5_000L
private const val DESCRIPTOR_TIMEOUT_MS = 5_000L
private const val WRITE_TIMEOUT_MS = 10_000L

class BleException(message: String) : Exception(message)

/** Runtime permissions the scan/connect path needs, which differ sharply across API levels. */
object BlePermissions {
    fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Pre-12 a BLE scan counted as a location capability, and without this the scan
            // silently returns zero results rather than failing.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(ctx: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(ctx: Context): List<String> = required().filter {
        ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
    }
}

/**
 * A connected BLE link to a ring — the Android counterpart to `oura-link::ble::BleTransport`
 * and to iOS's `BLETransport`, conforming to the same tiny shape the Rust core drives:
 * write a request frame, and observe the merged stream of inbound notification frames. All
 * protocol work (auth, the app-stream setup, the event drain) stays in Rust; this only
 * moves bytes.
 *
 * Four Android-specific behaviours are load-bearing here, and each one fails silently
 * rather than loudly if you skip it:
 *
 * 1. **The CCCD descriptor write.** `setCharacteristicNotification` only sets a local flag;
 *    the peripheral is not told to send anything until the `00002902` descriptor is written.
 *    CoreBluetooth does this implicitly, Android does not, and the result is a link that
 *    connects perfectly and delivers no data.
 * 2. **One GATT operation at a time.** The stack has a single operation slot and *silently
 *    discards* anything issued while another is in flight. Every write here holds [opLock]
 *    and waits for its completion callback.
 * 3. **`TRANSPORT_LE` on `connectGatt`.** Leaving the transport unspecified lets the stack
 *    guess BR/EDR and fail with the infamous status 133.
 * 4. **Merging every notify characteristic.** Which of `…0003`–`…0006` a ring actually uses
 *    varies by generation, so we subscribe to all notify/indicate characteristics in the
 *    service and merge them into one stream.
 */
class BleTransport private constructor(
    private val gatt: BluetoothGatt,
    private val callback: RingGattCallback,
    private val writeChar: BluetoothGattCharacteristic,
    /** Negotiated ATT MTU. Payload capacity is this minus the 3-byte ATT header. */
    val mtu: Int,
    val deviceName: String,
    val subscribedCount: Int,
    /** Advertisement strength at connect time, in dBm. Below about -80 the link is fragile. */
    val rssi: Int,
) {
    /** Every notify/indicate characteristic merged into one stream of raw frames. */
    val frames: Flow<ByteArray> get() = callback.frames

    val isConnected: Boolean get() = !callback.connectionLost.isCompleted

    private val opLock = Mutex()

    /**
     * Write one request frame and wait for the stack to confirm it. Serialized: concurrent
     * GATT writes are dropped by Android without an error.
     */
    @SuppressLint("MissingPermission")
    suspend fun write(data: ByteArray) = opLock.withLock {
        val done = CompletableDeferred<Int>()
        callback.pendingWrite = done

        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                writeChar,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                writeChar.value = data
                gatt.writeCharacteristic(writeChar)
            }
        }
        if (!accepted) {
            callback.pendingWrite = null
            throw BleException("the Bluetooth stack refused the write (link dropped?)")
        }

        val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
            ?: throw BleException("timed out waiting for the ring to acknowledge a write")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            throw BleException("write failed with GATT status $status")
        }
    }

    /** Suspends until the link drops. Lets a caller notice a mid-sync disconnect. */
    suspend fun awaitDisconnect() {
        callback.connectionLost.await()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        callback.finish()
    }

    companion object {
        /**
         * Scan for a ring, connect, negotiate the MTU, discover the Oura service and
         * subscribe to its notify characteristics. Returns a link ready to carry frames.
         *
         * `onStage` reports progress by name so a failure says *what* never happened —
         * "no advertisement seen" and "GATT connect stalled" have completely different
         * causes and fixes.
         */
        @SuppressLint("MissingPermission")
        suspend fun connect(
            ctx: Context,
            nameContains: String = "Oura",
            scanTimeoutMs: Long = 25_000,
            onStage: (String) -> Unit = {},
        ): BleTransport {
            if (!BlePermissions.granted(ctx)) {
                throw BleException(
                    "missing Bluetooth permission: ${BlePermissions.missing(ctx).joinToString()}",
                )
            }
            val manager = ctx.getSystemService(BluetoothManager::class.java)
                ?: throw BleException("no Bluetooth service on this device")
            val adapter = manager.adapter ?: throw BleException("no Bluetooth adapter")
            if (!adapter.isEnabled) throw BleException("Bluetooth is turned off")

            onStage("scanning")
            // Everything else the radio saw. Without this, "ring not found" cannot be told
            // apart from "the scan returned nothing at all" — one means the ring is away or
            // already connected elsewhere, the other means the scan itself is broken.
            val alsoSeen = linkedSetOf<String>()
            val found = scanForRing(ctx, nameContains, scanTimeoutMs, alsoSeen)
                ?: throw BleException(
                    buildString {
                        append("no ring advertisement in ${scanTimeoutMs / 1000}s")
                        if (alsoSeen.isEmpty()) {
                            append(" — and no other BLE device either, so the scan itself saw ")
                            append("nothing. Check that Bluetooth and Location are on.")
                        } else {
                            append(" — but ${alsoSeen.size} other device(s) were seen, so the ")
                            append("radio works. The ring is out of range, on a charger, or ")
                            append("already connected to another phone or the desktop client.")
                        }
                    },
                )
            val device = found.device
            val name = found.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: "ring"
            Log.i(TAG, "found $name rssi=${found.rssi}")

            // Let the scan actually stop before opening a link. Issuing connectGatt in the
            // same breath as stopScan is a well-documented source of status 133 — the stack
            // is still tearing the scan down and fails the connection outright.
            delay(SCAN_SETTLE_MS)

            // Bond first. `docs/sync-orchestration.md` opens the ring's handshake with
            // "CONNECT (BLE connect + bond)", and an unbonded central gets the link accepted
            // and then dropped by the ring ~100 ms later — which the stack reports as a bare
            // status 133, indistinguishable from a dozen unrelated failures. CoreBluetooth
            // bonds implicitly, which is why the desktop client never had to do this.
            ensureBonded(ctx, device, onStage)

            // 133 is famously transient: it means "generic GATT failure", commonly a losing
            // race inside the stack rather than anything about the ring, and a retry after a
            // clean close usually succeeds. Retrying beats surfacing a scary error the user
            // can do nothing about.
            var last: Throwable? = null
            repeat(CONNECT_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(RETRY_BACKOFF_MS * attempt)
                onStage(if (attempt == 0) "connecting" else "connecting (attempt ${attempt + 1})")
                // Last resort: autoConnect=true. It hands the request to the stack to
                // satisfy whenever the peripheral next becomes available instead of
                // demanding an immediate link, which is slower but succeeds on stacks and
                // peripherals where the direct connect keeps failing.
                val patient = attempt == CONNECT_ATTEMPTS - 1
                try {
                    return openLink(ctx, device, name, found.rssi, patient, onStage)
                } catch (t: Throwable) {
                    last = t
                    Log.w(TAG, "connect attempt ${attempt + 1} failed: ${t.message}")
                }
            }
            // Signal strength is a footnote, not the headline. It was briefly mistaken for
            // the cause of a 133 here, and the real answer was a missing bond — so report
            // the actual failure first and mention RSSI only as a contributing factor.
            val note = if (found.rssi < WEAK_RSSI_DBM) {
                " (signal was ${found.rssi} dBm, on the weak side, which can contribute)"
            } else {
                ""
            }
            throw BleException("${last?.message ?: "could not connect to the ring"}$note")
        }

        /**
         * Make sure the phone is bonded to the ring, pairing if it is not.
         *
         * A bond is *not* the ring's auth key — it is a link-layer pairing held by the
         * Bluetooth stack, entirely separate from the 16-byte key in [RingKeyStore]. Adding
         * one here cannot invalidate the desktop client's key; at worst the ring's bond table
         * is full and the desktop has to re-bond on its next connect.
         */
        @SuppressLint("MissingPermission")
        private suspend fun ensureBonded(
            ctx: Context,
            device: BluetoothDevice,
            onStage: (String) -> Unit,
        ) {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return

            onStage("pairing")
            val settled = CompletableDeferred<Int>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    @Suppress("DEPRECATION")
                    val subject = intent.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE,
                    )
                    if (subject?.address != device.address) return
                    when (val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_NONE -> {
                            Log.i(TAG, "bond state settled at $state")
                            settled.complete(state)
                        }
                        // BOND_BONDING is progress, not an outcome — keep waiting.
                    }
                }
            }
            ContextCompat.registerReceiver(
                ctx,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
                if (!device.createBond()) throw BleException("could not start pairing with the ring")
                val state = withTimeoutOrNull(BOND_TIMEOUT_MS) { settled.await() }
                    ?: throw BleException(
                        "timed out pairing with the ring — if a system pairing prompt " +
                            "appeared, accept it and try again",
                    )
                if (state != BluetoothDevice.BOND_BONDED) {
                    throw BleException("the ring refused pairing, or it was cancelled")
                }
                Log.i(TAG, "bonded")
            } finally {
                runCatching { ctx.unregisterReceiver(receiver) }
            }
        }

        /** One connect attempt: GATT open → MTU → discovery → subscriptions. */
        @SuppressLint("MissingPermission")
        private suspend fun openLink(
            ctx: Context,
            device: BluetoothDevice,
            name: String,
            rssi: Int,
            autoConnect: Boolean,
            onStage: (String) -> Unit,
        ): BleTransport {
            val callback = RingGattCallback()
            // connectGatt from the main thread: issuing it from an arbitrary thread is a
            // well-known source of spurious status-133 failures on several stacks.
            val gatt = withContext(Dispatchers.Main) {
                // The transport-taking overload is marked deprecated by the newest SDK, but
                // passing TRANSPORT_LE explicitly is exactly what avoids the stack guessing
                // BR/EDR and failing with status 133. Keep it until targetSdk moves past 34
                // and the replacement can actually be tested on hardware.
                @Suppress("DEPRECATION")
                val g = device.connectGatt(ctx, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
                g
            } ?: throw BleException("connectGatt returned nothing")

            try {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { callback.connected.await() }
                    ?: throw BleException("timed out connecting to the ring (GATT never opened)")

                // Deliberately NOT calling requestConnectionPriority(CONNECTION_PRIORITY_HIGH)
                // here. It is the obvious reach for a bulk transfer — shortest connection
                // interval, ~11 ms against a ~50 ms default — but it was measured on this
                // ring and does nothing: two 64k-event drains from an identical database,
                // 1,237 vs 1,256 events/sec, +1.5% and inside the noise. The drain is bound
                // by per-batch request/response latency, not by the interval, and the stack
                // already sends several packets per connection event. Not worth the extra
                // radio duty cycle. Re-measure before adding it, don't assume.

                // MTU before discovery: the negotiated size applies to everything that
                // follows, and asking afterwards can invalidate a freshly-built cache.
                onStage("negotiating MTU")
                var mtu = 23
                if (gatt.requestMtu(REQUESTED_MTU)) {
                    mtu = withTimeoutOrNull(MTU_TIMEOUT_MS) { callback.mtuChanged.await() } ?: 23
                }
                Log.i(TAG, "mtu=$mtu")

                onStage("discovering services")
                if (!gatt.discoverServices()) throw BleException("could not start service discovery")
                withTimeoutOrNull(DISCOVER_TIMEOUT_MS) { callback.servicesDiscovered.await() }
                    ?: throw BleException("timed out discovering GATT services")

                val service = gatt.getService(SERVICE_UUID)
                    ?: throw BleException(
                        "connected, but this device has no Oura service ($SERVICE_UUID)",
                    )
                val writeChar = service.getCharacteristic(WRITE_UUID)
                    ?: throw BleException("no write characteristic ($WRITE_UUID)")

                onStage("subscribing")
                val notifyChars = service.characteristics.filter {
                    it.properties and (
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_INDICATE
                        ) != 0
                }
                if (notifyChars.isEmpty()) {
                    throw BleException("the Oura service exposes no notify characteristic")
                }
                var subscribed = 0
                for (c in notifyChars) {
                    if (subscribe(gatt, callback, c)) subscribed++
                }
                if (subscribed == 0) {
                    throw BleException("could not enable notifications on any characteristic")
                }
                Log.i(TAG, "subscribed to $subscribed/${notifyChars.size} characteristics")

                onStage("ready")
                return BleTransport(gatt, callback, writeChar, mtu, name, subscribed, rssi)
            } catch (t: Throwable) {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
                callback.finish()
                throw t
            }
        }

        /**
         * Enable notifications on one characteristic: the local flag *and* the CCCD write,
         * awaited before returning so the next subscription doesn't collide with this one.
         */
        @SuppressLint("MissingPermission")
        private suspend fun subscribe(
            gatt: BluetoothGatt,
            callback: RingGattCallback,
            c: BluetoothGattCharacteristic,
        ): Boolean {
            if (!gatt.setCharacteristicNotification(c, true)) {
                Log.w(TAG, "setCharacteristicNotification refused for ${c.uuid}")
                return false
            }
            val cccd = c.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                // Some characteristics are notify-capable without a CCCD; the local flag is
                // then all there is, so count it rather than failing the whole connect.
                Log.w(TAG, "no CCCD on ${c.uuid}; relying on the local flag alone")
                return true
            }
            val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }

            val done = CompletableDeferred<Int>()
            callback.pendingDescriptor = done
            val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = value
                    gatt.writeDescriptor(cccd)
                }
            }
            if (!accepted) {
                callback.pendingDescriptor = null
                Log.w(TAG, "CCCD write refused for ${c.uuid}")
                return false
            }
            val status = withTimeoutOrNull(DESCRIPTOR_TIMEOUT_MS) { done.await() }
            if (status == null || status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD write failed for ${c.uuid} (status $status)")
                return false
            }
            return true
        }

        @SuppressLint("MissingPermission")
        private suspend fun scanForRing(
            ctx: Context,
            nameContains: String,
            timeoutMs: Long,
            alsoSeen: MutableSet<String>,
        ): ScanResult? {
            val manager = ctx.getSystemService(BluetoothManager::class.java) ?: return null
            val scanner = manager.adapter?.bluetoothLeScanner ?: return null
            return withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : ScanCallback() {
                        override fun onScanResult(callbackType: Int, result: ScanResult) {
                            if (!matches(result, nameContains)) {
                                // allow-duplicates re-reports the same device many times a
                                // second; the set keeps one line per device.
                                val label = result.scanRecord?.deviceName
                                    ?: result.device.address
                                if (alsoSeen.add(label)) {
                                    Log.d(TAG, "saw $label rssi=${result.rssi}")
                                }
                                return
                            }
                            if (cont.isActive) {
                                runCatching { scanner.stopScan(this) }
                                cont.resume(result)
                            }
                        }

                        override fun onScanFailed(errorCode: Int) {
                            Log.e(TAG, "scan failed with code $errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                    // Unfiltered, and matched in the callback: the ring's name often arrives
                    // late in a scan response rather than the initial advertisement, so a
                    // ScanFilter can miss it entirely. This is fine while the app is in the
                    // foreground; a background scan would have to supply filters, because
                    // Android drops unfiltered results once the screen is off.
                    runCatching { scanner.startScan(null, settings, cb) }
                        .onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }

        private fun matches(result: ScanResult, nameContains: String): Boolean {
            val advertisesService = result.scanRecord?.serviceUuids
                ?.any { it.uuid == SERVICE_UUID } == true
            if (advertisesService) return true
            val name = result.scanRecord?.deviceName
                ?: runCatching { result.device.name }.getOrNull()
            return name != null && name.contains(nameContains, ignoreCase = true)
        }
    }
}

/**
 * The GATT callback. Every phase of the connect hands off through a [CompletableDeferred]
 * so the setup can read as straight-line suspending code instead of a callback ladder.
 */
class RingGattCallback : BluetoothGattCallback() {

    // UNLIMITED because these completions arrive on a binder thread that must never block:
    // during a drain the ring can deliver frames faster than they are consumed, and dropping
    // one would silently corrupt the event stream.
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    val frames: Flow<ByteArray> = inbound.receiveAsFlow()

    val connected = CompletableDeferred<Unit>()
    val servicesDiscovered = CompletableDeferred<Unit>()
    val mtuChanged = CompletableDeferred<Int>()
    val connectionLost = CompletableDeferred<Unit>()

    @Volatile var pendingWrite: CompletableDeferred<Int>? = null
    @Volatile var pendingDescriptor: CompletableDeferred<Int>? = null

    fun finish() {
        inbound.close()
        connectionLost.complete(Unit)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                Log.i(TAG, "connected (status $status)")
                connected.complete(Unit)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                Log.w(TAG, "disconnected (status $status)")
                // Status 133 before the link ever opened is the classic Android failure;
                // completing exceptionally makes the waiter fail fast instead of hanging
                // until the connect timeout.
                connected.completeExceptionally(
                    BleException("the ring disconnected during setup (GATT status $status)"),
                )
                servicesDiscovered.completeExceptionally(BleException("disconnected"))
                mtuChanged.completeExceptionally(BleException("disconnected"))
                pendingWrite?.completeExceptionally(BleException("disconnected"))
                pendingDescriptor?.completeExceptionally(BleException("disconnected"))
                finish()
            }
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            servicesDiscovered.complete(Unit)
        } else {
            servicesDiscovered.completeExceptionally(
                BleException("service discovery failed with status $status"),
            )
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        mtuChanged.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        pendingDescriptor?.complete(status)
        pendingDescriptor = null
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        pendingWrite?.complete(status)
        pendingWrite = null
    }

    // Both overloads exist because the value-carrying one is API 33+. Each guards on the
    // API level so a frame is delivered exactly once rather than twice on new devices.
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        deliver(value)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        characteristic.value?.let { deliver(it) }
    }

    private fun deliver(value: ByteArray) {
        if (value.isEmpty()) return
        inbound.trySend(value.copyOf())
    }
}
