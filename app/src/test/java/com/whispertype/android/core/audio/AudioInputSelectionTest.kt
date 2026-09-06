package com.whispertype.android.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Pure device-choice rules for the recording-source setting (0.6.0). */
class AudioInputSelectionTest {

    private fun device(
        id: Int,
        type: InputDeviceType,
        supportsFormat: Boolean = true,
        name: String = "device-$id",
    ) = AvailableInputDevice(id = id, type = type, name = name, supportsFormat = supportsFormat)

    private val builtIn = device(1, InputDeviceType.BUILTIN_MIC)
    private val sco = device(2, InputDeviceType.BLUETOOTH_SCO, name = "Headset (SCO)")
    private val a2dp = device(3, InputDeviceType.BLUETOOTH_A2DP, name = "Headset (A2DP)")
    private val other = device(4, InputDeviceType.OTHER)

    @Test
    fun `no bluetooth devices selects nothing`() {
        assertNull(AudioInputSelection.selectBluetoothInput(listOf(builtIn, other)))
        assertNull(AudioInputSelection.selectBluetoothInput(emptyList()))
    }

    @Test
    fun `SCO headset is preferred over A2DP`() {
        val chosen = AudioInputSelection.selectBluetoothInput(listOf(builtIn, a2dp, sco))
        assertEquals("Headset (SCO)", chosen?.name)
    }

    @Test
    fun `A2DP headset is used when it is the only bluetooth input`() {
        val chosen = AudioInputSelection.selectBluetoothInput(listOf(builtIn, a2dp))
        assertEquals("Headset (A2DP)", chosen?.name)
    }

    @Test
    fun `bluetooth device that lacks the pipeline format is ignored`() {
        val unusableSco = device(5, InputDeviceType.BLUETOOTH_SCO, supportsFormat = false)
        assertNull(AudioInputSelection.selectBluetoothInput(listOf(builtIn, unusableSco)))
    }

    @Test
    fun `a usable A2DP wins over an unusable SCO`() {
        val unusableSco = device(5, InputDeviceType.BLUETOOTH_SCO, supportsFormat = false)
        val chosen = AudioInputSelection.selectBluetoothInput(listOf(builtIn, unusableSco, a2dp))
        assertSame(a2dp, chosen)
    }

    @Test
    fun `isBluetooth covers SCO and A2DP only`() {
        assert(sco.isBluetooth)
        assert(a2dp.isBluetooth)
        assert(!builtIn.isBluetooth)
        assert(!other.isBluetooth)
    }
}
