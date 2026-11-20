// Ported from BitChord's AudioOutputPolicy, AudioRouting and AudioOutputStatus
// (https://github.com/kushagrasinghx/BitChord, GPLv3), cut down to what a
// local player without an output picker or Bluetooth codec tracking uses.
package com.ghostmikz.ghostly.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.ghostmikz.ghostly.data.OutputPcmMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * Float output is not a quality switch that is safe on every Android route.
 * Some OEM speaker paths accept the AudioTrack and then convert it to PCM16 in
 * AudioFlinger; on affected Samsung FLAC decoders that combination can also
 * produce timestamp discontinuities and severely distorted output. Only an
 * external route that advertises PCM float is allowed.
 */
internal object AudioOutputPolicy {
    /**
     * - Built-in phone speaker (PHONE) stays at 16-bit, to keep clear of OEM
     *   mixer issues and distortion on affected device paths.
     * - External routes (USB, Bluetooth, wired, HDMI) use float if it was asked
     *   for and the route says it takes it.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRoute.Kind,
        advertisesPcmFloat: Boolean,
    ): Boolean {
        if (routeKind == AudioRoute.Kind.PHONE) return false
        if (requestedMode != OutputPcmMode.FLOAT_32) return false
        return advertisesPcmFloat
    }

    /**
     * Samsung's FLAC decoder produces one extra 232.2ms timestamp advance per
     * decoded buffer when Media3 asks it for float, so FLAC goes through the
     * platform software codec instead.
     */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "c2.sec.flac.decoder" || (lower.startsWith("omx.sec.") && lower.contains("flac"))
    }
}

/** What kind of output a device is, and which one the music goes to (BitChord's AudioRouting). */
internal object AudioRoute {
    enum class Kind { PHONE, WIRED, USB, BLUETOOTH, HDMI }

    /**
     * Null for the sinks that are not somewhere music is listened to: the
     * telephony earpiece, a remote submix, the virtual sink a screen recorder opens.
     */
    fun kindOf(type: Int): Kind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.PHONE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> Kind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> Kind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        -> Kind.HDMI
        else -> null
    }

    /** The first attached USB audio output, if any. */
    fun usbOutput(manager: AudioManager): AudioDeviceInfo? = outputs(manager).firstOrNull { kindOf(it.type) == Kind.USB }

    /**
     * Where Android sends media with nothing chosen, in its order of preference:
     * reproduced rather than asked for, since there is no API that answers it
     * for a sink this app has not opened yet.
     */
    fun systemDefault(manager: AudioManager): AudioDeviceInfo? {
        val infos = outputs(manager)
        return infos.firstOrNull { kindOf(it.type) == Kind.BLUETOOTH }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.WIRED }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.USB }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.HDMI }
            ?: infos.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /** Whether [device] (or the default route) takes PCM float directly. */
    fun takesFloat(device: AudioDeviceInfo?): Boolean {
        if (device?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true) return true
        if (Build.VERSION.SDK_INT < 29) return false
        // A route that lists no encodings at all can still play float directly.
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(48_000)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return runCatching { AudioTrack.isDirectPlaybackSupported(format, attributes) }.getOrDefault(false)
    }

    fun label(device: AudioDeviceInfo?): String = when (device?.let { kindOf(it.type) }) {
        null, Kind.PHONE -> "Phone speaker"
        Kind.WIRED -> "Wired headphones"
        Kind.USB -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB DAC"
        Kind.BLUETOOTH -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
        Kind.HDMI -> "HDMI"
    }

    private fun outputs(manager: AudioManager): Array<AudioDeviceInfo> =
        runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray())
}

/**
 * What the normal output actually opened, for the "Output precision" row
 * (BitChord's AudioOutputStatus, just the fields that row shows).
 */
object OutputStatus {
    data class Snapshot(
        val sink: String = "AudioTrack",
        val deviceName: String = "Phone speaker",
        val isUsb: Boolean = false,
        val sampleRateHz: Int? = null,
        /** The AudioTrack's encoding once one has opened; null before. */
        val encoding: Int? = null,
        val requested: OutputPcmMode = OutputPcmMode.PCM_16,
        /** Float was asked for but this route was given 16-bit. */
        val floatFallback: Boolean = false,
    )

    private val _current = MutableStateFlow(Snapshot())
    val current: StateFlow<Snapshot> = _current.asStateFlow()

    internal fun update(transform: (Snapshot) -> Snapshot) {
        _current.value = transform(_current.value)
    }

    fun encodingLabel(snapshot: Snapshot): String = when (snapshot.encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> "32-bit float"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit PCM"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit PCM"
        AudioFormat.ENCODING_PCM_16BIT -> if (snapshot.floatFallback) "16-bit fallback" else "16-bit PCM"
        null -> if (snapshot.floatFallback) "16-bit fallback" else snapshot.requested.label
        else -> "PCM (${snapshot.encoding})"
    }
}
