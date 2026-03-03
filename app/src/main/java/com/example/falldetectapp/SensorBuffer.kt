package com.example.falldetectapp

class SensorBuffer(private val windowSize: Int) {

    private val accelData = ArrayList<FloatArray>()
    private val gyroData = ArrayList<FloatArray>()

    fun addAccelerometer(values: FloatArray) {
        if (accelData.size >= windowSize) accelData.removeAt(0)
        accelData.add(values)
    }

    fun addGyroscope(values: FloatArray) {
        if (gyroData.size >= windowSize) gyroData.removeAt(0)
        gyroData.add(values)
    }

    fun isFull(): Boolean {
        return accelData.size == windowSize && gyroData.size == windowSize
    }

    fun getFlattenedWindow(): FloatArray {
        val result = FloatArray(windowSize * 6)
        for (i in 0 until windowSize) {
            val acc = accelData[i]
            val gyro = gyroData[i]

            result[i * 6 + 0] = acc[0]
            result[i * 6 + 1] = acc[1]
            result[i * 6 + 2] = acc[2]
            result[i * 6 + 3] = gyro[0]
            result[i * 6 + 4] = gyro[1]
            result[i * 6 + 5] = gyro[2]
        }
        return result
    }

    fun clear() {
        accelData.clear()
        gyroData.clear()
    }
}