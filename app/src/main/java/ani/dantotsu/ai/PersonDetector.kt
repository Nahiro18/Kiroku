package ani.dantotsu.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ani.dantotsu.util.Logger
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.min

class PersonDetector private constructor(
    private val interpreter: Interpreter
) : Closeable {

    companion object {
        private const val INPUT_SIZE = 640
        private const val CHANNELS = 5
        private const val CELL_COUNT = 8400
        private const val PADDING_COLOR = 114
        private const val CONFIDENCE_THRESHOLD = 0.327f
        private const val NMS_IOU_THRESHOLD = 0.45f
        private const val MIN_AREA_RATIO = 0.004f
        private const val MIN_SIDE_PX = 8f
        private const val CENTER_WEIGHT = 0.4f

        fun create(modelFile: File): PersonDetector? {
            return try {
                val options = Interpreter.Options().apply { setNumThreads(4) }
                PersonDetector(Interpreter(modelFile, options))
            } catch (t: Throwable) {
                Logger.log("PersonDetector init failed: ${t.message}")
                null
            }
        }
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    fun detect(bitmap: Bitmap): List<PersonDetection> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val scale = min(INPUT_SIZE.toFloat() / bitmap.width, INPUT_SIZE.toFloat() / bitmap.height)
        val scaledWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - scaledWidth) / 2f
        val padY = (INPUT_SIZE - scaledHeight) / 2f

        val input = buildInput(bitmap, padX, padY, scaledWidth.toFloat(), scaledHeight.toFloat())
        if (input == null) return emptyList()

        val output = ByteBuffer.allocateDirect(CHANNELS * CELL_COUNT).order(ByteOrder.nativeOrder())
        try {
            input.rewind()
            interpreter.run(input, output)
        } catch (t: Throwable) {
            Logger.log("PersonDetector inference failed: ${t.message}")
            return emptyList()
        }

        val raw = ByteArray(CHANNELS * CELL_COUNT)
        output.rewind()
        output.get(raw)

        val params = interpreter.getOutputTensor(0).quantizationParams()
        val scales = params.scale
        val zeroPoints = params.zeroPoint
        if (scales == null || zeroPoints == null || scales.isEmpty() || zeroPoints.isEmpty()) return emptyList()
        val outScale = scales[0]
        val outZero = zeroPoints[0]
        if (outScale <= 0f) return emptyList()

        val shape = interpreter.getOutputTensor(0).shape()
        val channelFirst = shape.size < 3 || shape[1] == CHANNELS

        fun value(row: Int, cell: Int): Float {
            val index = if (channelFirst) row * CELL_COUNT + cell else cell * CHANNELS + row
            return (raw[index].toInt() - outZero) * outScale
        }

        val candidates = ArrayList<PersonDetection>()
        for (cell in 0 until CELL_COUNT) {
            val confidence = value(4, cell)
            if (confidence < CONFIDENCE_THRESHOLD) continue

            val centerX = value(0, cell) * INPUT_SIZE
            val centerY = value(1, cell) * INPUT_SIZE
            val width = value(2, cell) * INPUT_SIZE
            val height = value(3, cell) * INPUT_SIZE
            if (width <= 0f || height <= 0f) continue

            val left = ((centerX - width / 2f - padX) / scale).coerceIn(0f, bitmap.width.toFloat())
            val top = ((centerY - height / 2f - padY) / scale).coerceIn(0f, bitmap.height.toFloat())
            val right = ((centerX + width / 2f - padX) / scale).coerceIn(0f, bitmap.width.toFloat())
            val bottom = ((centerY + height / 2f - padY) / scale).coerceIn(0f, bitmap.height.toFloat())

            val box = RectF(left, top, right, bottom)
            if (box.width() < MIN_SIDE_PX || box.height() < MIN_SIDE_PX) continue
            if (box.width() * box.height() < bitmap.width * bitmap.height * MIN_AREA_RATIO) continue

            candidates.add(PersonDetection(confidence, box))
        }

        return suppressOverlaps(candidates, bitmap.width, bitmap.height)
            .sortedByDescending { rank(it, bitmap.width, bitmap.height) }
    }

    private fun buildInput(
        bitmap: Bitmap,
        padX: Float,
        padY: Float,
        scaledWidth: Float,
        scaledHeight: Float
    ): ByteBuffer? {
        return runCatching {
            val frame = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frame)
            canvas.drawColor(Color.rgb(PADDING_COLOR, PADDING_COLOR, PADDING_COLOR))
            val paint = Paint().apply { isFilterBitmap = true }
            canvas.drawBitmap(bitmap, null, RectF(padX, padY, padX + scaledWidth, padY + scaledHeight), paint)

            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            frame.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            frame.recycle()

            val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
                .order(ByteOrder.nativeOrder())
            for (pixel in pixels) {
                buffer.put(((pixel shr 16) and 0xFF).toByte())
                buffer.put(((pixel shr 8) and 0xFF).toByte())
                buffer.put((pixel and 0xFF).toByte())
            }
            buffer
        }.getOrNull()
    }

    private fun suppressOverlaps(
        detections: List<PersonDetection>,
        imageWidth: Int,
        imageHeight: Int
    ): List<PersonDetection> {
        val ordered = detections.sortedWith(
            compareByDescending<PersonDetection> { rank(it, imageWidth, imageHeight) }
                .thenByDescending { it.confidence }
        )
        val kept = ArrayList<PersonDetection>()
        for (candidate in ordered) {
            val overlaps = kept.any { intersectionOverUnion(candidate.box, it.box) > NMS_IOU_THRESHOLD }
            if (!overlaps) kept.add(candidate)
        }
        return kept
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun rank(detection: PersonDetection, imageWidth: Int, imageHeight: Int): Float {
        val box = detection.box
        val area = (box.width() * box.height()) / (imageWidth.toFloat() * imageHeight.toFloat())
        val offsetX = box.centerX() / imageWidth - 0.5f
        val offsetY = box.centerY() / imageHeight - 0.5f
        val distance = (hypot(offsetX, offsetY) / 0.7071f).coerceIn(0f, 1f)
        return area * (1f - CENTER_WEIGHT * distance) + detection.confidence * 0.05f
    }
}

data class PersonDetection(
    val confidence: Float,
    val box: RectF
)
