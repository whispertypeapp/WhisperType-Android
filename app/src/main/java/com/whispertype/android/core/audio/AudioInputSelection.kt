package com.whispertype.android.core.audio

/**
 * Pure, framework-free view of an available audio input device, so device-choice
 * logic stays JVM-testable. Platform code ([com.whispertype.android.audio.AudioInputDevices])
 * maps Android's [android.media.AudioDeviceInfo] onto this model.
 */
data class AvailableInputDevice(
    /** Opaque id used by platform code to map the descriptor back to the real device. */
    val id: Int,
    val type: InputDeviceType,
    /** Human-readable device label (e.g. the headset product name). */
    val name: String,
    /** True when the device accepts the app's 16 kHz mono PCM16 format. */
    val supportsFormat: Boolean,
) {
    /** A bluetooth input device; SCO (headset profile) is preferred over A2DP. */
    val isBluetooth: Boolean
        get() = type == InputDeviceType.BLUETOOTH_SCO || type == InputDeviceType.BLUETOOTH_A2DP
}

/** Input device categories relevant to dictation capture. */
enum class InputDeviceType {
    BUILTIN_MIC,
    BLUETOOTH_SCO,
    BLUETOOTH_A2DP,
    OTHER,
}

/** Pure device-selection rules for [com.whispertype.android.core.model.AudioSourcePreference]. */
object AudioInputSelection {

    /**
     * Picks the bluetooth input device to record from, or null when none is
     * usable. SCO (the headset profile's mic) wins over A2DP; only devices that
     * support the pipeline format are candidates. Falls back to null (and the
     * caller uses the phone mic) when no bluetooth device is connected or none
     * supports 16 kHz mono PCM16.
     */
    fun selectBluetoothInput(devices: List<AvailableInputDevice>): AvailableInputDevice? {
        val usable = devices.filter { it.isBluetooth && it.supportsFormat }
        if (usable.isEmpty()) return null
        return usable.firstOrNull { it.type == InputDeviceType.BLUETOOTH_SCO } ?: usable.first()
    }

    /**
     * Picks any connected bluetooth headset (input or output, format not
     * checked) for status display — e.g. the Settings caption, which should show
     * the headset even before SCO is active.
     */
    fun selectBluetoothHeadset(devices: List<AvailableInputDevice>): AvailableInputDevice? {
        val headsets = devices.filter { it.isBluetooth }
        if (headsets.isEmpty()) return null
        return headsets.firstOrNull { it.type == InputDeviceType.BLUETOOTH_SCO } ?: headsets.first()
    }
}
