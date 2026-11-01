package ani.dantotsu.others.imagesearch

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.App.Companion.context
import ani.dantotsu.R
import ani.dantotsu.ai.YoloModelManager
import ani.dantotsu.ai.YoloSegmenter
import ani.dantotsu.ai.SegmentationResult
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.databinding.ActivityImageSearchBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImageSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImageSearchBinding
    private val viewModel: ImageSearchViewModel by viewModels()
    private var useYolo = false
    private var yoloSegmenter: YoloSegmenter? = null
    private var originalBitmap: Bitmap? = null
    private var imageBytes: ByteArray? = null
    private var searchButton: Button? = null
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
        val contentResolver = applicationContext.contentResolver
        lifecycleScope.launch(Dispatchers.IO) {
            val inputStream = contentResolver.openInputStream(imageUri)
            if (inputStream != null) {
                val bytes = inputStream.readBytes()
                inputStream.close()
                if (bytes.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        imageBytes = bytes
                        withContext(Dispatchers.Main) {
                            originalBitmap = bitmap
                            displayImage(bitmap)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            toast(getString(R.string.error_loading_image))
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.error_loading_image))
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.error_loading_image))
                }
            }
        }
    }

    private fun runYoloSegmentation(bitmap: Bitmap): Boolean {
        val selectedModel = YoloModelManager.getSelectedModel()
        val selectedDelegate = YoloModelManager.getSelectedDelegate()

        val fallbackChain = YoloSegmenter.getFallbackChain(this, selectedModel, selectedDelegate)

        for ((model, delegate) in fallbackChain) {
            val modelPath = YoloModelManager.getModelPath(model)
            if (modelPath == null) continue

            val modelFile = File(modelPath)
            if (!modelFile.exists()) continue

            val segmenter = YoloSegmenter.getInstance(this, modelFile, model, delegate)
            if (segmenter == null) continue

            val results = segmenter.segment(bitmap)

            if (model != selectedModel || delegate != selectedDelegate) {
                val modelName = if (model == YoloModelManager.Model.V11) "YOLOv11" else "YOLOv8"
                val delegateName = if (delegate == YoloModelManager.Delegate.GPU) "GPU" else "CPU"
                runOnUiThread { toast("Using fallback: $modelName @ $delegateName") }
            }
            yoloSegmenter = segmenter
            return true
        }
        return false
    }

    private fun drawSegmentationOverlay(bitmap: Bitmap, results: List<SegmentationResult>) {
        val overlayBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlayBitmap)
        val paint = android.graphics.Paint()

        yoloSegmenter?.drawSegmentation(canvas, bitmap, results, paint)

        runOnUiThread {
            binding.imagePreview.setImageBitmap(overlayBitmap)
        }
    }

    private fun performSearch() {
        if (isSearching) return
        isSearching = true
        searchButton?.isEnabled = false

        if (useYolo) {
            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = originalBitmap ?: return@launch
                val success = runYoloSegmentation(bitmap)
                if (success) {
                    withContext(Dispatchers.Main) {
                        drawSegmentationOverlay(originalBitmap!!, yoloSegmenter!!.segment(originalBitmap!!))
                    }
                }
                withContext(Dispatchers.Main) {
                    performTraceMoeSearch()
                }
            }
        } else {
            performTraceMoeSearch()
        }
    }

    private fun performTraceMoeSearch() {
        if (imageBytes == null) {
            isSearching = false
            searchButton?.isEnabled = true
            return
        }

        val stream = java.io.ByteArrayInputStream(imageBytes!!)
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.analyzeImage(stream)
            withContext(Dispatchers.Main) {
                isSearching = false
                searchButton?.isEnabled = true
            }
        }
    }

    private fun displayImage(bitmap: Bitmap) {
        binding.imagePreview.setImageBitmap(bitmap)
        binding.imagePreview.visibility = View.VISIBLE
        binding.uploadImage.text = getString(R.string.search_again)
        binding.yoloContainer.visibility = View.VISIBLE
        binding.searchButtonContainer.visibility = View.VISIBLE

        binding.yoloToggle.setOnCheckedChangeListener(null)
        binding.yoloToggle.isChecked = useYolo
        binding.yoloToggle.setOnCheckedChangeListener { _, isChecked ->
            useYolo = isChecked
            if (isChecked) {
                if (YoloModelManager.isModelDownloaded(YoloModelManager.getSelectedModel())) {
                    runOnUiThread { toast("AI enabled. Press Search to run segmentation.") }
                } else {
                    binding.yoloToggle.isChecked = false
                    useYolo = false
                    toast("Model not downloaded. Go to Addons to download it.")
                }
            }
        }

        setupSearchButton()
        binding.progressBar.visibility = View.GONE
    }

    private fun setupSearchButton() {
        if (searchButton == null) {
            searchButton = Button(this).apply {
                text = getString(R.string.search)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(getColor(android.R.color.holo_blue_dark))
                setTextColor(getColor(android.R.color.white))
                textSize = 16f
                setOnClickListener { performSearch() }
            }
            binding.searchButtonContainer.addView(searchButton!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initActivity(this)
        ThemeManager(this).applyTheme()
        binding = ActivityImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.uploadImage.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = navBarHeight
        }
        binding.uploadImage.setOnClickListener {
            viewModel.clearResults()
            binding.imagePreview.setImageBitmap(null)
            binding.imagePreview.visibility = View.GONE
            binding.yoloContainer.visibility = View.GONE
            binding.searchButtonContainer.visibility = View.GONE
            binding.yoloToggle.isChecked = false
            useYolo = false
            imageSelectionLauncher.launch("image/*")
        }
        binding.imageSearchTitle.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        viewModel.searchResultLiveData.observe(this) { result ->
            result?.let { displayResult(it) }
        }

        binding.yoloToggle.isChecked = false
        useYolo = false
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

                    withContext(Dispatchers.Main) {
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
        yoloSegmenter?.let {
            YoloSegmenter.release()
            yoloSegmenter = null
        }
        originalBitmap = null
        imageBytes = null
    }
}