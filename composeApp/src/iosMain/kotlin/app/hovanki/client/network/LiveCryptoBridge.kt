@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.hovanki.client.network

import app.hovanki.client.network.live.LiveAeads
import app.hovanki.shared.live.LiveAead
import app.hovanki.shared.live.LiveAeadCheck
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * AES-128-GCM of the live channel v2's UDP (docs/live-v2/client.md §6): CryptoKit is Swift only, so the app implements
 * this in `iosApp/iosApp/LiveCrypto.swift` and hands it over through [installLiveCryptoBridge] before
 * `mainViewController()`. The key comes with every call; [sealed] and the result of [seal] are the ciphertext followed
 * by the 16-byte tag. Null when CryptoKit refuses, and from [open] when the datagram doesn't authenticate. Called from
 * any thread.
 */
interface LiveCryptoBridge {
    fun seal(key: NSData, nonce: NSData, aad: NSData, plaintext: NSData): NSData?

    fun open(key: NSData, nonce: NSData, aad: NSData, sealed: NSData): NSData?
}

/** The Swift bridge, once installed and found to seal and open as the spec does. */
internal object LiveCryptoBridges {
    var installed: LiveCryptoBridge? = null
}

/**
 * Swift: `LiveCryptoBridgeKt.installLiveCryptoBridge(bridge:)`, before `mainViewController()`. The bridge is checked
 * against the spec's known answer first ([LiveAeadCheck]); one that fails is not installed, and the phone keeps to the
 * WebSocket v2. True when installed.
 */
fun installLiveCryptoBridge(bridge: LiveCryptoBridge): Boolean {
    if (!LiveAeadCheck.passes { BridgedLiveAead(bridge, it) }) return false
    LiveCryptoBridges.installed = bridge
    return true
}

/** The [LiveAeads] of the UDP on iOS: the Swift bridge with the key kept on Kotlin's side. */
class IosLiveAeads(private val bridge: LiveCryptoBridge) : LiveAeads {
    override fun forKey(key: ByteArray): LiveAead = BridgedLiveAead(bridge, key)
}

private class BridgedLiveAead(private val bridge: LiveCryptoBridge, key: ByteArray) : LiveAead {
    private val key = key.toNSData()

    override fun seal(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        checkNotNull(bridge.seal(key, nonce.toNSData(), aad.toNSData(), plaintext.toNSData())) {
            "CryptoKit refused to seal"
        }.toByteArray()

    override fun open(nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? =
        bridge.open(key, nonce.toNSData(), aad.toNSData(), ciphertextAndTag.toNSData())?.toByteArray()
}

private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}

private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return bytes
}
