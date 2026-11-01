package ani.dantotsu.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class YoloSegmenter private constructor(
    private val interpreter: Interpreter,
    private val inputSize: Int
) {

    companion object {
        private var instance: YoloSegmenter? = null

        fun getInstance(context: android.content.Context, modelFile: File): YoloSegmenter? {
            if (instance != null) return instance
            return try {
                val gpuDelegate = GpuDelegate()
                val options = Interpreter.Options().apply {
                    addDelegate(gpuDelegate)
                    setNumThreads(4)
                }
                val interpreter = Interpreter(modelFile, options)
                val inputTensor = interpreter.getInputTensor(0)
                val inputSize = inputTensor.shape[1]
                instance = YoloSegmenter(interpreter, inputSize)
                instance
            } catch (e: Exception) {
                Log.e("YoloSegmenter", "Failed to initialize: ${e.message}")
                null
            }
        }

        fun release() {
            instance?.interpreter?.close()
            instance = null
        }
    }

    fun segment(bitmap: Bitmap): List<SegmentationResult> {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val inputBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }

        val outputTensor = interpreter.getOutputTensor(0)
        val outputShape = outputTensor.shape
        val outputBuffer = ByteBuffer.allocateDirect(4 * outputShape[1] * outputShape[2] * outputShape[3])
            .order(ByteOrder.nativeOrder())

        interpreter.run(inputBuffer, outputBuffer)

        return parseOutput(outputBuffer, bitmap.width, bitmap.height, outputShape)
    }

    private fun parseOutput(outputBuffer: ByteBuffer, originalWidth: Int, originalHeight: Int, outputShape: IntArray): List<SegmentationResult> {
        val results = mutableListOf<SegmentationResult>()
        val channels = outputShape[3]
        val height = outputShape[1]
        val width = outputShape[2]

        val outputArray = FloatArray(channels * height * width)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(outputArray)

        val scaleX = originalWidth.toFloat() / width
        val scaleY = originalHeight.toFloat() / height

        for (c in 0 until channels) {
            var maxVal = 0f
            var maxX = 0
            var maxY = 0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val idx = c * height * width + y * width + x
                    val value = outputArray[idx]
                    if (value > maxVal) {
                        maxVal = value
                        maxX = x
                        maxY = y
                    }
                }
            }

            if (maxVal > 0.5f) {
                val centerX = (maxX * scaleX).toInt()
                val centerY = (maxY * scaleY).toInt()
                val radius = (maxVal * 50).toInt().coerceIn(20, 200)

                results.add(SegmentationResult(
                    classId = c,
                    confidence = maxVal,
                    boundingBox = RectF(
                        (centerX - radius).toFloat().coerceAtLeast(0f),
                        (centerY - radius).toFloat().coerceAtLeast(0f),
                        (centerX + radius).toFloat().coerceAtMost(originalWidth.toFloat()),
                        (centerY + radius).toFloat().coerceAtMost(originalHeight.toFloat())
                    )
                ))
            }
        }

        return results
    }

    fun drawSegmentation(canvas: Canvas, bitmap: Bitmap, results: List<SegmentationResult>, paint: Paint) {
        val scaleX = canvas.width.toFloat() / bitmap.width
        val scaleY = canvas.height.toFloat() / bitmap.height

        for (result in results) {
            val box = result.boundingBox
            val left = box.left * scaleX
            val top = box.top * scaleY
            val right = box.right * scaleX
            val bottom = box.bottom * scaleY

            paint.color = Color.argb(100, 0, 255, 0)
            paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, right, bottom, paint)

            paint.color = Color.GREEN
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawRect(left, top, right, bottom, paint)

            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            paint.textSize = 30f
            canvas.drawText("Class ${result.classId} (${(result.confidence * 100).toInt()}%)", left, top - 10, paint)
        }
    }
}

data class SegmentationResult(
    val classId: Int,
    val confidence: Float,
    val boundingBox: RectF
)