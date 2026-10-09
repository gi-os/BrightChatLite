package com.gios.brightchatlite.ui

import android.view.KeyEvent
import com.thelightphone.sdk.LightViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The Light Phone III's scroll wheel. LightOS labels its turns `WHEEL_CCW` (up) and
 * `WHEEL_CW` (down); when those labels are not in the key table the raw scancodes from the
 * optical sensor (19 and 20) are used, gated on the sensor's device name so a Bluetooth
 * keyboard's R and T keys are not mistaken for it. Mapping taken from BrightRecorder.
 */
object Wheel {
    private const val SENSOR = "Pixart pat9126ja"
    private const val SCAN_UP = 19
    private const val SCAN_DOWN = 20

    private val upCode: Int = KeyEvent.keyCodeFromString("WHEEL_CCW")
    private val downCode: Int = KeyEvent.keyCodeFromString("WHEEL_CW")

    /** -1 for a turn up, +1 for a turn down, 0 for any other key. */
    fun direction(keyCode: Int, event: KeyEvent): Int {
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            if (keyCode == upCode) return -1
            if (keyCode == downCode) return 1
        }
        if (event.device?.name == SENSOR) {
            if (event.scanCode == SCAN_UP) return -1
            if (event.scanCode == SCAN_DOWN) return 1
        }
        return 0
    }
}

/** A view model whose screen scrolls with the wheel. */
abstract class WheelViewModel<R> : LightViewModel<R>() {
    private val _wheel = MutableSharedFlow<Int>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val wheel: SharedFlow<Int> = _wheel

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val dir = Wheel.direction(keyCode, event)
        if (dir == 0) return false
        _wheel.tryEmit(dir)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = Wheel.direction(keyCode, event) != 0
}
