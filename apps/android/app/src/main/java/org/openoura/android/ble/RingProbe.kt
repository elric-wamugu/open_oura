package org.openoura.android.ble

import android.content.Context
import android.util.Log
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The one request frame this file knows: `GetFirmware` (`0x08`), which needs no auth and is
 * answered with a `0x09` packet.
 *
 * Kotlin is not supposed to know the protocol — that all lives in Rust, and the real sync
 * path drives `RingSession` rather than anything here. This constant exists only so the
 * transport can be proved end to end (write reaches the ring, notification comes back)
 * before there is a sync to run, which is otherwise untestable: a link that connects and
 * subscribes perfectly but delivers nothing looks identical to a working one.
 */
private val REQ_FIRMWARE = byteArrayOf(0x08, 0x03, 0x00, 0x00, 0x00)

/** Ring's reply tag for [REQ_FIRMWARE]. */
private const val FIRMWARE_REPLY_TAG: Byte = 0x09

private const val TAG = "OpenOuraBle"

/** What a probe managed to do, in the order it tried to do it. */
data class RingProbeResult(
    val ok: Boolean,
    /** The furthest stage reached: scanning / connecting / … / ready. */
    val stage: String,
    val deviceName: String? = null,
    val mtu: Int? = null,
    val subscribed: Int? = null,
    val framesSeen: Int = 0,
    val firstFrameHex: String? = null,
    val sawFirmwareReply: Boolean = false,
    val error: String? = null,
) {
    /** One-line summary for the UI. */
    fun summary(): String = when {
        !ok -> "$stage — ${error ?: "failed"}"
        sawFirmwareReply ->
            "$deviceName · MTU $mtu · $subscribed notify · the ring replied ($framesSeen frames)"
        framesSeen > 0 ->
            "$deviceName · MTU $mtu · $subscribed notify · $framesSeen frames, no 0x09 reply"
        else ->
            "$deviceName · MTU $mtu · $subscribed notify · connected but SILENT — check the CCCD write"
    }
}

/**
 * Connect to the ring, ask it for its firmware, and report what came back.
 *
 * This is a diagnostic, not part of the sync path: it proves scan → connect → MTU → CCCD →
 * write → notification works before any of the Rust protocol code is wired in, so a later
 * sync failure can be attributed to the protocol rather than the pipe.
 */
suspend fun probeRing(ctx: Context, nameContains: String = "Oura"): RingProbeResult {
    var stage = "starting"
    var transport: BleTransport? = null
    return try {
        val t = BleTransport.connect(ctx, nameContains) { stage = it }
        transport = t

        val frames = mutableListOf<ByteArray>()
        coroutineScope {
            val collector = launch {
                t.frames.collect { frame ->
                    frames.add(frame)
                    Log.i(TAG, "frame: ${frame.toHex()}")
                }
            }
            stage = "asking for firmware"
            t.write(REQ_FIRMWARE)
            // The reply is a single packet, but wait a beat longer than it needs so a slow
            // or chatty ring still lands inside the window.
            delay(3_000)
            collector.cancel()
        }

        RingProbeResult(
            ok = true,
            stage = "ready",
            deviceName = t.deviceName,
            mtu = t.mtu,
            subscribed = t.subscribedCount,
            framesSeen = frames.size,
            firstFrameHex = frames.firstOrNull()?.toHex(),
            sawFirmwareReply = frames.any { it.isNotEmpty() && it[0] == FIRMWARE_REPLY_TAG },
        )
    } catch (t: Throwable) {
        Log.e(TAG, "probe failed at $stage", t)
        RingProbeResult(
            ok = false,
            stage = stage,
            error = t.message ?: t::class.java.simpleName,
        )
    } finally {
        transport?.close()
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
