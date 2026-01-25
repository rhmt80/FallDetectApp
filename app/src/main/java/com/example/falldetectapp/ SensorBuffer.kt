package com.example.falldetectapp

object SensorBuffer {

    private const val WINDOW_SIZE = 100
    private const val AXES = 3

    private val buffer = ArrayDeque<FloatArray>()

    /**
     * Adds a new accelerometer sample.
     * Returns a [100][3] window when ready, else null.
     */
    fun add(values: FloatArray): Array<FloatArray>? {
        buffer.addLast(values.copyOf())

        if (buffer.size < WINDOW_SIZE) return null

        if (buffer.size > WINDOW_SIZE) {
            buffer.removeFirst()
        }

        return buffer.toTypedArray()
    }

    fun clear() {
        buffer.clear()
    }
}