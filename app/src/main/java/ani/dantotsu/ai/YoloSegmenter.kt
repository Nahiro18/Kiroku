package ani.dantotsu.ai

import android.content.Context
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
    private val modelVersion: YoloModelManager.Model,
    private val delegate: YoloModelManager.Delegate
) {

    companion object {
        private var instance: YoloSegmenter? = null

        fun getInstance(
            context: Context,
            modelFile: File,
            modelVersion: YoloModelManager.Model,
            delegate: YoloModelManager.Delegate
        ): YoloSegmenter? {
            return try {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    when (delegate) {
                        YoloModelManager.Delegate.GPU -> {
                            try {
                                val gpuDelegate = GpuDelegate()
                                addDelegate(gpuDelegate)
                            } catch (e: Exception) {
                                Log.w("YoloSegmenter", "GPU delegate failed, falling back to CPU: ${e.message}")
                            }
                        }
                        YoloModelManager.Delegate.CPU -> {
                        }
                    }
                }
                val interpreter = Interpreter(modelFile, options)
                instance = YoloSegmenter(interpreter, modelVersion, delegate)
                instance
            } catch (e: Exception) {
                Log.e("YoloSegmenter", "Failed to initialize ${modelVersion}@${delegate}: ${e.message}")
                null
            }
        }

        fun release() {
            instance?.interpreter?.close()
            instance = null
        }

        fun getFallbackChain(
            context: Context,
            modelVersion: YoloModelManager.Model,
            delegate: YoloModelManager.Delegate
        ): List<Pair<YoloModelManager.Model, YoloModelManager.Delegate>> {
            return when {
                modelVersion == YoloModelManager.Model.V11 && delegate == YoloModelManager.Delegate.GPU -> listOf(
                    YoloModelManager.Model.V11 to YoloModelManager.Delegate.GPU,
                    YoloModelManager.Model.V11 to YoloModelManager.Delegate.CPU,
                    YoloModelManager.Model.V8 to YoloModelManager.Delegate.GPU,
                    YoloModelManager.Model.V8 to YoloModelManager.Delegate.CPU
                )
                modelVersion == YoloModelManager.Model.V11 && delegate == YoloModelManager.Delegate.CPU -> listOf(
                    YoloModelManager.Model.V11 to YoloModelManager.Delegate.CPU,
                    YoloModelManager.Model.V8 to YoloModelManager.Delegate.GPU,
                    YoloModelManager.Model.V8 to YoloModelManager.Delegate.CPU
                )
                modelVersion == YoloModelManager.Model.V8 && delegate == YoloModelManager.Delegate.GPU -> listOf(
                    YoloModelManager.Model.V8 to YoloModelManager.Delegate.GPU,
                    YoloModelManager.Model.V8 to YoloModelManager.Delegate.CPU
                )
                else -> listOf(
                    YoloModelManager.Model.V8 to YoloModelManager.Delegate.CPU
                )
            }
        }
    }

    fun segment(bitmap: Bitmap): List<SegmentationResult> {
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val inputBuffer = ByteBuffer.allocateDirect(640 * 640 * 3)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(640 * 640)
        resizedBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)

        for (pixel in pixels) {
            inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
            inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
            inputBuffer.put((pixel and 0xFF).toByte())
        }

        val outputTensors = arrayOf(
            ByteBuffer.allocateDirect(160 * 160 * 32).order(ByteOrder.nativeOrder()),
            ByteBuffer.allocateDirect(4 * 8400).order(ByteOrder.nativeOrder()),
            ByteBuffer.allocateDirect(80 * 8400).order(ByteOrder.nativeOrder()),
            ByteBuffer.allocateDirect(32 * 8400).order(ByteOrder.nativeOrder())
        )

        interpreter.runForMultipleInputsOutputs(
            arrayOf(inputBuffer),
            mapOf(
                0 to outputTensors[0],
                1 to outputTensors[1],
                2 to outputTensors[2],
                3 to outputTensors[3]
            )
        )

        return parseOutput(outputTensors, bitmap.width, bitmap.height)
    }

    private fun parseOutput(
        outputTensors: Array<ByteBuffer>,
        originalWidth: Int,
        originalHeight: Int
    ): List<SegmentationResult> {
        val results = mutableListOf<SegmentationResult>()

        val masksBuffer = outputTensors[0].apply { rewind() }
        val boxesBuffer = outputTensors[1].apply { rewind() }
        val scoresBuffer = outputTensors[2].apply { rewind() }
        val protosBuffer = outputTensors[3].apply { rewind() }

        val masks = IntArray(160 * 160 * 32)
        masksBuffer.asIntBuffer().get(masks)

        val boxes = IntArray(4 * 8400)
        boxesBuffer.asIntBuffer().get(boxes)

        val scores = IntArray(80 * 8400)
        scoresBuffer.asIntBuffer().get(scores)

        val protos = IntArray(32 * 8400)
        protosBuffer.asIntBuffer().get(protos)

        val maskScale = 0.03800723f
        val maskZeroPoint = -121
        val boxScale = 0.00415815f
        val boxZeroPoint = -119
        val scoreScale = 0.00390625f
        val scoreZeroPoint = -128

        val scaleX = originalWidth / 640f
        val scaleY = originalHeight / 640f

        for (i in 0 until 8400) {
            val scoreIdx = i
            var maxScore = 0f
            var maxClass = -1

            for (c in 0 until 80) {
                val idx = c * 8400 + i
                val dequantized = (scores[idx] - scoreZeroPoint) * scoreScale
                if (dequantized > maxScore) {
                    maxScore = dequantized
                    maxClass = c
                }
            }

            if (maxScore > 0.5f && maxClass != -1) {
                val boxIdx = i * 4
                val cx = ((boxes[boxIdx] - boxZeroPoint) * boxScale) * 640f
                val cy = ((boxes[boxIdx + 1] - boxZeroPoint) * boxScale) * 640f
                val w = ((boxes[boxIdx + 2] - boxZeroPoint) * boxScale) * 640f
                val h = ((boxes[boxIdx + 3] - boxZeroPoint) * boxScale) * 640f

                val left = (cx - w / 2) * scaleX
                val top = (cy - h / 2) * scaleY
                val right = (cx + w / 2) * scaleX
                val bottom = (cy + h / 2) * scaleY

                val maskVector = FloatArray(32)
                for (k in 0 until 32) {
                    val mIdx = k * 160 * 160 + (cy / 4).toInt().coerceIn(0, 159) * 160 + (cx / 4).toInt().coerceIn(0, 159)
                    maskVector[k] = (masks[mIdx] - maskZeroPoint) * maskScale
                }

                results.add(SegmentationResult(
                    classId = maxClass,
                    confidence = maxScore,
                    boundingBox = RectF(
                        left.coerceIn(0f, originalWidth.toFloat()),
                        top.coerceIn(0f, originalHeight.toFloat()),
                        right.coerceIn(0f, originalWidth.toFloat()),
                        bottom.coerceIn(0f, originalHeight.toFloat())
                    ),
                    maskVector = maskVector
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
    val boundingBox: RectF,
    val maskVector: FloatArray? = null
)