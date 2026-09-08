package com.whispertype.android.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.util.Log
import com.whispertype.android.core.audio.AudioInputSelection
import com.whispertype.android.core.audio.AvailableInputDevice
import com.whispertype.android.core.audio.GemAudioFormat
import com.whispertype.android.core.audio.InputDeviceType
import com.whispertype.android.core.model.AudioSourcePreference

/**
 * Android-facing view of the available audio input devices, backed by
 * [AudioManager]. Enumerates devices as pure [AvailableInputDevice] descriptors
 * so the choice logic ([AudioInputSelection]) stays JVM-testable, and resolves a
 * chosen descriptor back to its real [AudioDeviceInfo] for [AudioRecord].
 */
class AudioInputDevices(private val audioManager: AudioManager) {

    /** Every connected input device as a pure descriptor. */
    fun listAvailable(): List<AvailableInputDevice> =
        inputDevices().map { it.toDescriptor() }

    /** Every connected device (inputs and outputs) as a pure descriptor. */
    fun listAll(): List<AvailableInputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.toDescriptor() }

    /** The best connected bluetooth input device for dictation, or null. */
    fun bluetoothInputDevice(): AudioDeviceInfo? {
        val chosen = AudioInputSelection.selectBluetoothInput(listAvailable()) ?: return null
        return inputDevices().firstOrNull { it.id == chosen.id }
    }

    /** True when any bluetooth audio device (input or output) is currently present.
     *  Used to skip the SCO bring-up entirely when no headset exists at all. */
    fun hasBluetoothAudioDevice(): Boolean =
        listAll().any {
            it.type == InputDeviceType.BLUETOOTH_SCO || it.type == InputDeviceType.BLUETOOTH_A2DP
        }

    private fun inputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    private fun AudioDeviceInfo.toDescriptor(): AvailableInputDevice = AvailableInputDevice(
        id = id,
        type = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDeviceType.BUILTIN_MIC
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputDeviceType.BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> InputDeviceType.BLUETOOTH_A2DP
            else -> InputDeviceType.OTHER
        },
        name = productName?.toString() ?: DEFAULT_DEVICE_LABEL,
        supportsFormat = supportsPipelineFormat(),
    )

    /**
     * Whether the device accepts the pipeline's mono PCM16 encoding. The sample
     * rate is deliberately not gated here: a bluetooth SCO headset reports its
     * codec rates (typically 8/16 kHz) and the actual 16 kHz [AudioRecord] init
     * is the real gate — over-filtering silently drops usable headsets.
     */
    private fun AudioDeviceInfo.supportsPipelineFormat(): Boolean {
        // A zero-length array means the device supports every value.
        fun IntArray.supports(value: Int): Boolean = isEmpty() || contains(value)
        val monoByMask = channelMasks.isEmpty() || channelMasks.contains(AudioFormat.CHANNEL_IN_MONO)
        val monoByCount = channelCounts.isEmpty() || channelCounts.contains(1)
        val encodingOk = encodings.supports(GemAudioFormat.AUDIO_FORMAT)
        return monoByMask && monoByCount && encodingOk
    }

    private companion object {
        const val DEFAULT_DEVICE_LABEL = "Audio input"
    }
}

/**
 * Builds the capture source factory for the selected [preference]. Bluetooth is
 * strict opt-in: it records from the connected headset only when explicitly
 * chosen and its mic becomes usable; otherwise (and for [AudioSourcePreference.DEFAULT])
 * it always falls back to the phone microphone, so a missing headset never fails
 * a dictation.
 */
fun audioSourceFactory(
    audioManager: AudioManager,
    preference: AudioSourcePreference,
): () -> PcmSource? {
    if (preference != AudioSourcePreference.BLUETOOTH) {
        return { AudioCapture.createDefaultSource() }
    }
    return {
        ScoCaptureSource(audioManager).build() ?: AudioCapture.createDefaultSource()
    }
}

/**
 * A headset-mic capture source (0.6.0). A bluetooth headset's microphone is only
 * exposed as an input device while a SCO connection is active, so this source
 * opens one: it enters [AudioManager.MODE_IN_COMMUNICATION], requests SCO, waits
 * briefly for the headset's input device to appear, and pins the recording to it
 * with [AudioRecord.setPreferredDevice]. [requestStop] only unblocks recording;
 * on [release] the SCO connection is closed and the normal audio mode is
 * restored. Returns null from [build] (and cleans up) when the headset mic cannot
 * be brought up, so the caller falls back to the phone mic.
 */
@Suppress("DEPRECATION")
private class ScoCaptureSource(
    private val audioManager: AudioManager,
) : InterruptiblePcmSource {

    private var delegate: PcmSource? = null

    /** Activates SCO and returns [this] when the headset mic is ready, else null. */
    fun build(): PcmSource? {
        // Tap-to-recording fix (0.6.x): with no bluetooth audio device present
        // there is nothing to connect to, so fall back to the phone mic
        // immediately instead of waiting out the SCO poll loop (which previously
        // blocked capture start for up to 2 s when no headset was connected).
        if (!AudioInputDevices(audioManager).hasBluetoothAudioDevice()) {
            Log.i(TAG, "Bluetooth source: no bluetooth audio device present; using phone mic")
            return null
        }
        val modeOk = runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }.isSuccess
        if (!modeOk) return null
        val scoRequested = runCatching { audioManager.startBluetoothSco() }.isSuccess
        if (!scoRequested) {
            teardown()
            return null
        }
        // SCO connects asynchronously; poll briefly for the headset input device.
        val devices = AudioInputDevices(audioManager)
        var device = devices.bluetoothInputDevice()
        var waited = 0L
        while (device == null && waited < SCO_MAX_WAIT_MS) {
            Thread.sleep(SCO_POLL_MS)
            waited += SCO_POLL_MS
            device = devices.bluetoothInputDevice()
        }
        if (device == null) {
            Log.i(TAG, "Bluetooth source: SCO active but no headset input device; using phone mic")
            teardown()
            return null
        }
        val source = AudioCapture.createDeviceSource(device)
        if (source == null) {
            Log.i(TAG, "Bluetooth source: headset init failed; using phone mic")
            teardown()
            return null
        }
        Log.i(TAG, "Bluetooth source: recording from headset mic")
        delegate = source
        return this
    }

    override fun read(out: ByteArray): Int = delegate?.read(out) ?: 0

    override fun requestStop() {
        (delegate as? InterruptiblePcmSource)?.requestStop()
    }

    override fun release() {
        try {
            delegate?.release()
        } finally {
            teardown()
        }
    }

    private fun teardown() {
        runCatching { audioManager.stopBluetoothSco() }
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
    }

    private companion object {
        const val TAG = "AudioInput"
        const val SCO_POLL_MS = 100L
        const val SCO_MAX_WAIT_MS = 400L
    }
}
