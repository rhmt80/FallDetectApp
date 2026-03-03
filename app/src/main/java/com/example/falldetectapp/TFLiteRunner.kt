package com.example.falldetectapp

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class TFLiteRunner(private val context: Context) {

    private val interpreter: Interpreter

    init {
        interpreter = Interpreter(loadModelFile())
    }

    private fun loadModelFile(): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd("model.tflite")
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun runInference(input: FloatArray): Float {

        // Model expects shape: [1, 100, 6]
        val inputBuffer = ByteBuffer.allocateDirect(1 * 100 * 6 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        for (value in input) {
            inputBuffer.putFloat(value)
        }

        inputBuffer.rewind()

        val output = Array(1) { FloatArray(1) }

        interpreter.run(inputBuffer, output)

        return output[0][0]
    }
}