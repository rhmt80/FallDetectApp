package com.example.falldetectapp

class WakeDetector {

    private val movementThreshold = 13.5f
    private val calmThreshold = 11.0f

    fun isMovementDetected(magnitude: Float): Boolean {
        return magnitude > movementThreshold
    }

    fun isCalm(magnitude: Float): Boolean {
        return magnitude < calmThreshold
    }
}