package com.example.falldetectapp

import android.content.Context
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

class TFLiteRunner(context: Context) {

    private val interpreter: Interpreter

    init {
        val modelBuffer = FileUtil.loadMappedFile(context, "model.tflite")
        interpreter = Interpreter(modelBuffer)
    }

    /**
     * Input shape: [1][100][6]
     * Output shape: [1][1]
     */
    fun runInference(input: Array<Array<FloatArray>>): Float {
        val output = Array(1) { FloatArray(1) }
        interpreter.run(input, output)
        return output[0][0]
    }
}