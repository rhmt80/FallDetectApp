// old version - no adaptive
//package com.example.falldetectapp
//
//class WakeDetector(private val thresh: Float = 18f) {
//    fun check(magnitude: Float): Boolean = magnitude >= thresh
//}


// adaptive
package com.example.falldetectapp

import kotlin.math.abs
import kotlin.math.sqrt

class WakeDetector {

    private var triggerCount = 0

    private val WAKE_ACCEL_DELTA = 1.2f   // m/s² deviation from gravity
    private val REQUIRED_TRIGGERS = 5     // consecutive samples

    fun update(ax: Float, ay: Float, az: Float): Boolean {
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        val delta = abs(magnitude - 9.81f)

        if (delta > WAKE_ACCEL_DELTA) {
            triggerCount++
        } else {
            triggerCount = 0
        }

        return triggerCount >= REQUIRED_TRIGGERS
    }

    fun reset() {
        triggerCount = 0
    }
}