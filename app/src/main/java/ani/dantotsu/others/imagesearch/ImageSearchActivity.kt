package ani.dantotsu.others.imagesearch

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.App.Companion.context
import ani.dantotsu.R
import ani.dantotsu.ai.SegmentationResult
import ani.dantotsu.ai.YoloModelManager
import ani.dantotsu.ai.YoloSegmenter
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityImageSearchBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ImageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageSearchBinding
    private val viewModel: ImageSearchViewModel by viewModels()
    private var useYolo = false
    private var originalBitmap: Bitmap? = null
    private var imageBytes: ByteArray? = null
    private var isSearching = false

    private val cropImageLauncher =
        registerForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                result.uriContent?.let { analyzeUri(it) }
            } else {
                toast(result.error?.message ?: getString(R.string.error_loading_image))
            }
        }

    private val imageSelectionLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { cropImageLauncher.launch(CropImageContractOptions(it, CropImageOptions())) }
        }

    private fun analyzeUri(imageUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            }.getOrNull()
            val bitmap = if (bytes != null && bytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                null
            }
            runOnUiThread {
                if (bytes != null && bitmap != null) {
                    imageBytes = bytes
                    originalBitmap = bitmap
                    displayImage(bitmap)
                } else {
                    toast(getString(R.string.error_loading_image))
                }
            }
        }
    }

    private fun runYoloSegmentation(bitmap: Bitmap): List<SegmentationResult> {
        val selectedModel = YoloModelManager.getSelectedModel()
        val selectedDelegate = YoloModelManager.getSelectedDelegate()
        val fallbackChain = YoloSegmenter.getFallbackChain(this, selectedModel, selectedDelegate)

        for ((model, delegate) in fallbackChain) {
            val modelPath = YoloModelManager.getModelPath(model) ?: continue
            val modelFile = File(modelPath)
            if (!modelFile.exists()) continue

            val segmenter = YoloSegmenter.getInstance(this, modelFile, model, delegate) ?: continue
            val results = runCatching { segmenter.segment(bitmap) }.getOrNull()
            if (results == null) {
                YoloSegmenter.release()
                continue
            }

            if (model != selectedModel || delegate != selectedDelegate) {
                val modelName = if (model == YoloModelManager.Model.V11) "YOLOv11" else "YOLOv8"
                val delegateName = if (delegate == YoloModelManager.Delegate.GPU) "GPU" else "CPU"
                runOnUiThread { toast(getString(R.string.yolo_fallback, modelName, delegateName)) }
            }
            return results
        }
        return emptyList()
    }

    private fun bestCrop(bitmap: Bitmap, results: List<SegmentationResult>): Bitmap? {
        val best = results.maxByOrNull { it.confidence } ?: return null
        val box = best.boundingBox
        if (!box.left.isFinite() || !box.top.isFinite() || !box.right.isFinite() || !box.bottom.isFinite()) return null
        if (box.width() < 8f || box.height() < 8f) return null

        val padX = box.width() * 0.18f
        val padY = box.height() * 0.18f
        val left = (box.left - padX).coerceIn(0f, bitmap.width.toFloat()).toInt()
        val top = (box.top - padY).coerceIn(0f, bitmap.height.toFloat()).toInt()
        val right = (box.right + padX).coerceIn(0f, bitmap.width.toFloat()).toInt()
        val bottom = (box.bottom + padY).coerceIn(0f, bitmap.height.toFloat()).toInt()

        if (right - left < 32 || bottom - top < 32) return null
        return runCatching {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        }.getOrNull()
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val maxDim = 1600
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, 92, output)
        return output.toByteArray()
    }

    private fun onSearchPressed() {
        if (isSearching) return
        if (originalBitmap == null) {
            pickImage()
            return
        }
        performSearch()
    }

    private fun performSearch() {
        val bitmap = originalBitmap ?: return
        isSearching = true
        binding.searchButton.isEnabled = false
        binding.yoloToggle.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        if (!useYolo) {
            sendSearch(imageBytes)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val results = runYoloSegmentation(bitmap)
            val crop = if (results.isNotEmpty()) bestCrop(bitmap, results) else null
            val searchBytes = if (crop != null) bitmapToJpeg(crop) else imageBytes
            YoloSegmenter.release()

            runOnUiThread {
                if (crop != null) {
                    binding.imagePreview.setImageBitmap(crop)
                    toast(getString(R.string.yolo_found_regions, results.size))
                } else {
                    toast(getString(R.string.yolo_no_regions))
                }
            }
            sendSearch(searchBytes)
        }
    }

    private fun sendSearch(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            finishSearch()
            toast(getString(R.string.error_loading_image))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.analyzeImage(ByteArrayInputStream(bytes))
            runOnUiThread { finishSearch() }
        }
    }

    private fun finishSearch() {
        isSearching = false
        binding.searchButton.isEnabled = true
        binding.yoloToggle.isEnabled = true
        binding.progressBar.visibility = View.GONE
        if (useYolo) {
            useYolo = false
            binding.yoloToggle.isChecked = false
        }
    }

    private fun displayImage(bitmap: Bitmap) {
        binding.imagePreview.setImageBitmap(bitmap)
        binding.imagePreview.visibility = View.VISIBLE
        binding.yoloContainer.visibility = View.VISIBLE
        binding.searchButton.isEnabled = true
        binding.yoloToggle.isEnabled = true
        binding.progressBar.visibility = View.GONE

        updateYoloToggleLabel()

        binding.yoloToggle.setOnCheckedChangeListener(null)
        binding.yoloToggle.isChecked = useYolo
        binding.yoloToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !YoloModelManager.isModelDownloaded(YoloModelManager.getSelectedModel())) {
                binding.yoloToggle.isChecked = false
                useYolo = false
                toast(getString(R.string.yolo_model_missing))
            } else {
                useYolo = isChecked
            }
        }
    }

    private fun updateYoloToggleLabel() {
        val modelName = when (YoloModelManager.getSelectedModel()) {
            YoloModelManager.Model.V11 -> "YOLOv11n-seg"
            YoloModelManager.Model.V8 -> "YOLOv8n-seg"
        }
        binding.yoloToggle.text = getString(R.string.use_yolo_segmentation_with_model, modelName)
    }

    private fun pickImage() {
        viewModel.clearResults()
        binding.recyclerView.adapter = null
        binding.imagePreview.setImageBitmap(null)
        binding.imagePreview.visibility = View.GONE
        binding.yoloContainer.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.yoloToggle.setOnCheckedChangeListener(null)
        binding.yoloToggle.isChecked = false
        useYolo = false
        isSearching = false
        binding.searchButton.isEnabled = true
        binding.yoloToggle.isEnabled = true
        originalBitmap = null
        imageBytes = null
        YoloSegmenter.release()
        imageSelectionLauncher.launch("image/*")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initActivity(this)
        ThemeManager(this).applyTheme()
        binding = ActivityImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.searchButton.setOnClickListener { onSearchPressed() }
        binding.imagePreview.setOnClickListener { pickImage() }
        binding.imageSearchTitle.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        viewModel.searchResultLiveData.observe(this) { result ->
            result?.let { displayResult(it) }
        }

        binding.yoloToggle.isChecked = false
        useYolo = false
        binding.progressBar.visibility = View.GONE
        binding.searchButton.isEnabled = true
    }

    private fun displayResult(result: ImageSearchViewModel.SearchResult) {
        val recyclerView: RecyclerView = findViewById(R.id.recyclerView)
        val searchResults: List<ImageSearchViewModel.ImageResult> = result.result.orEmpty()
        val adapter = ImageSearchResultAdapter(searchResults)

        adapter.setOnItemClickListener(object : ImageSearchResultAdapter.OnItemClickListener {
            override fun onItemClick(searchResult: ImageSearchViewModel.ImageResult) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val id = searchResult.anilist?.id?.toInt()
                    if (id == null) {
                        toast(getString(R.string.no_anilist_id_found))
                        return@launch
                    }
                    val media = Anilist.query.getMedia(id, false)
                    runOnUiThread {
                        media?.let {
                            startActivity(
                                Intent(this@ImageSearchActivity, MediaDetailsActivity::class.java)
                                    .putExtra("media", it)
                            )
                        }
                    }
                }
            }
        })

        recyclerView.post {
            recyclerView.adapter = adapter
            recyclerView.layoutManager = LinearLayoutManager(context)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        YoloSegmenter.release()
        originalBitmap = null
        imageBytes = null
    }
}
