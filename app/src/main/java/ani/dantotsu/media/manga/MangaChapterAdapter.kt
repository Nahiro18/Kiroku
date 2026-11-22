package ani.dantotsu.media.manga

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.NumberPicker
import androidx.core.view.isVisible
import androidx.lifecycle.coroutineScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.setAnimation
import ani.dantotsu.util.customAlertDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaChapterAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: MangaReadFragment,
    var arr: ArrayList<MangaChapter> = arrayListOf(),
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> ChapterCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            0 -> ChapterListViewHolder(
                ItemChapterListBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return type
    }

    override fun getItemCount(): Int = arr.size

    inner class ChapterCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val chapter = arr[bindingAdapterPosition]
                    if (batchMode) toggleBatchSelection(chapter)
                    else fragment.onMangaChapterClick(chapter)
                }
            }
            itemView.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size && !batchMode) {
                    itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    enterBatchMode(arr[bindingAdapterPosition])
                    true
                } else false
            }
        }
    }

    private val activeDownloads = mutableSetOf<String>()
    private val downloadedChapters = mutableSetOf<String>()

    var batchMode = false
        private set
    val selectedForBatch = mutableSetOf<String>()
    var onBatchSelectionChanged: ((Int) -> Unit)? = null

    fun toggleBatchSelection(chapter: MangaChapter) {
        val key = chapter.uniqueNumber()
        if (!selectedForBatch.remove(key)) selectedForBatch.add(key)
        notifyChapter(key)
        onBatchSelectionChanged?.invoke(selectedForBatch.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun enterBatchMode(chapter: MangaChapter) {
        batchMode = true
        selectedForBatch.clear()
        selectedForBatch.add(chapter.uniqueNumber())
        notifyDataSetChanged()
        onBatchSelectionChanged?.invoke(selectedForBatch.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun exitBatchMode() {
        batchMode = false
        selectedForBatch.clear()
        notifyDataSetChanged()
        onBatchSelectionChanged?.invoke(0)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAllBatch() {
        selectedForBatch.clear()
        selectedForBatch.addAll(
            arr.filterNot {
                activeDownloads.contains(it.uniqueNumber()) ||
                        downloadedChapters.contains(it.uniqueNumber())
            }.map { it.uniqueNumber() }
        )
        notifyDataSetChanged()
        onBatchSelectionChanged?.invoke(selectedForBatch.size)
    }

    /** Chapters currently selected and still downloadable, in list order. */
    fun selectedChapters(): List<MangaChapter> = arr.filter {
        val key = it.uniqueNumber()
        selectedForBatch.contains(key) &&
                !activeDownloads.contains(key) &&
                !downloadedChapters.contains(key)
    }

    private fun notifyChapter(key: String) {
        val position = arr.indexOfFirst { it.uniqueNumber() == key }
        if (position != -1) notifyItemChanged(position)
    }

    fun startDownload(chapterNumber: String) {
        activeDownloads.add(chapterNumber)
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    fun stopDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.add(chapterNumber)
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber }
        if (position != -1) {
            arr[position].progress = "Downloaded"
            notifyItemChanged(position)
        }
    }

    fun deleteDownload(chapterNumber: MangaChapter) {
        downloadedChapters.remove(chapterNumber.uniqueNumber())
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber.uniqueNumber() }
        if (position != -1) {
            arr[position].progress = ""
            notifyItemChanged(position)
        }
    }

    fun purgeDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.remove(chapterNumber)
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber }
        if (position != -1) {
            arr[position].progress = ""
            notifyItemChanged(position)
        }
    }

    fun updateDownloadProgress(chapterNumber: String, progress: Int) {
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber }
        if (position != -1) {
            arr[position].progress = "Downloading: ${progress}%"

            notifyItemChanged(position)
        }
    }

    private fun showNextNDownloadDialog(position: Int) {
        val context = currContext() ?: return
        val max = (arr.size - position).coerceAtLeast(1)
        context.customAlertDialog().apply {
            setTitle("Multi Chapter Downloader")
            setMessage("Enter the number of chapters to download")
            val input = NumberPicker(context)
            input.minValue = 1
            input.maxValue = max
            input.value = 1
            setCustomView(input)
            setPosButton("OK") {
                downloadNChaptersFrom(position, input.value)
            }
            setNegButton("Cancel")
            show()
        }
    }

    fun downloadNChaptersFrom(position: Int, n: Int) {
        //download next n chapters
        if (position < 0 || position >= arr.size) return
        for (i in 0..<n) {
            if (position + i < arr.size) {
                val chapter = arr[position + i]
                val chapterNumber = chapter.uniqueNumber()
                if (activeDownloads.contains(chapterNumber)) {
                    //do nothing
                    continue
                } else if (downloadedChapters.contains(chapterNumber)) {
                    //do nothing
                    continue
                } else {
                    fragment.onMangaChapterDownloadClick(chapter)
                    startDownload(chapter.uniqueNumber())
                }
            }
        }
    }

    inner class ChapterListViewHolder(val binding: ItemChapterListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val activeCoroutines = mutableSetOf<String>()
        fun bind(chapterNumber: String, progress: String?) {
            if (progress != null) {
                binding.itemChapterTitle.visibility = View.VISIBLE
                binding.itemChapterTitle.text = "$progress"
            } else {
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
            }
            if (activeDownloads.contains(chapterNumber)) {
                // Show spinner
                binding.itemDownload.setImageResource(R.drawable.ic_sync)
                startOrContinueRotation(chapterNumber) {
                    binding.itemDownload.rotation = 0f
                }
            } else if (downloadedChapters.contains(chapterNumber)) {
                // Show checkmark
                binding.itemDownload.setImageResource(R.drawable.ic_circle_check)
                binding.itemDownload.postDelayed({
                    binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                    binding.itemDownload.rotation = 0f
                }, 1000)
            } else {
                // Show download icon
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                binding.itemDownload.rotation = 0f
            }

        }

        private fun startOrContinueRotation(chapterNumber: String, resetRotation: () -> Unit) {
            if (!isRotationCoroutineRunningFor(chapterNumber)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    // Add chapter number to active coroutines set
                    activeCoroutines.add(chapterNumber)
                    while (activeDownloads.contains(chapterNumber)) {
                        binding.itemDownload.animate().rotationBy(360f).setDuration(1000)
                            .setInterpolator(
                                LinearInterpolator()
                            ).start()
                        delay(1000)
                    }
                    // Remove chapter number from active coroutines set
                    activeCoroutines.remove(chapterNumber)
                    resetRotation()
                }
            }
        }

        private fun isRotationCoroutineRunningFor(chapterNumber: String): Boolean {
            return chapterNumber in activeCoroutines
        }

        init {
            itemView.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val chapter = arr[bindingAdapterPosition]
                    if (batchMode) toggleBatchSelection(chapter)
                    else fragment.onMangaChapterClick(chapter)
                }
            }
            binding.itemDownload.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val chapter = arr[bindingAdapterPosition]
                    val chapterNumber = chapter.uniqueNumber()
                    if (activeDownloads.contains(chapterNumber)) {
                        fragment.onMangaChapterStopDownloadClick(chapter)
                        return@setOnClickListener
                    } else if (downloadedChapters.contains(chapterNumber)) {
                        it.context.customAlertDialog().apply {
                            setTitle("Delete Chapter")
                            setMessage("Are you sure you want to delete ${chapterNumber}?")
                            setPosButton(R.string.delete) {
                                fragment.onMangaChapterRemoveDownloadClick(chapter)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                        return@setOnClickListener
                    } else {
                        fragment.onMangaChapterDownloadClick(chapter)
                        startDownload(chapter.uniqueNumber())
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val position = bindingAdapterPosition
                    it.context.customAlertDialog().apply {
                        setTitle("Batch Download")
                        setMessage("Choose chapters to download, or download the next few in a row")
                        setPosButton("Select chapters") { enterBatchMode(arr[position]) }
                        setNeutralButton("Next N chapters…") { showNextNDownloadDialog(position) }
                        setNegButton("Cancel")
                        show()
                    }
                }
                true
            }

        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChapterCompactViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)
                val ep = arr[position]
                val parsedNumber = MediaNameAdapter.findChapterNumber(ep.number)?.toInt()
                binding.itemEpisodeNumber.text = parsedNumber?.toString() ?: ep.number
                binding.itemBatchCheck.isVisible = batchMode
                binding.itemBatchCheck.isChecked = selectedForBatch.contains(ep.uniqueNumber())
                binding.itemBatchCheck.setOnClickListener { toggleBatchSelection(ep) }
                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number)
                            ?: 9999f) <= media.userProgress!!.toFloat()
                    )
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                    else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(
                                media,
                                MediaNameAdapter.findChapterNumber(ep.number).toString()
                            )
                            true
                        }
                    }
                }
            }

            is ChapterListViewHolder -> {
                val binding = holder.binding
                val ep = arr[position]
                holder.bind(ep.uniqueNumber(), ep.progress)
                setAnimation(fragment.requireContext(), holder.binding.root)
                binding.itemChapterNumber.text = ep.number
                binding.itemBatchCheck.isVisible = batchMode
                binding.itemBatchCheck.isChecked = selectedForBatch.contains(ep.uniqueNumber())
                binding.itemBatchCheck.setOnClickListener { toggleBatchSelection(ep) }

                if (ep.date != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterDate.text = formatDate(ep.date)
                }
                if (ep.scanlator != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterScan.text = ep.scanlator.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(
                            Locale.ROOT
                        ) else it.toString()
                    }
                }
                if (formatDate(ep.date) == "" || ep.scanlator == null) {
                    binding.itemChapterDateDivider.visibility = View.GONE
                } else binding.itemChapterDateDivider.visibility = View.VISIBLE

                if (ep.progress.isNullOrEmpty()) {
                    binding.itemChapterTitle.visibility = View.GONE
                } else binding.itemChapterTitle.visibility = View.VISIBLE

                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number)
                            ?: 9999f) <= media.userProgress!!.toFloat()
                    ) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        binding.root.setOnLongClickListener {
                            updateProgress(
                                media,
                                MediaNameAdapter.findChapterNumber(ep.number).toString()
                            )
                            true
                        }
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }
            }
        }
    }

    fun updateType(t: Int) {
        type = t
    }

    private fun formatDate(timestamp: Long?): String {
        timestamp ?: return "" // Return empty string if timestamp is null

        val targetDate = Date(timestamp)

        if (targetDate < Date(946684800000L)) { // January 1, 2000 (who want dates before that?)
            return ""
        }

        val currentDate = Date()
        val difference = currentDate.time - targetDate.time

        return when (val daysDifference = difference / (1000 * 60 * 60 * 24)) {
            0L -> {
                val hoursDifference = difference / (1000 * 60 * 60)
                val minutesDifference = (difference / (1000 * 60)) % 60

                when {
                    hoursDifference > 0 -> "$hoursDifference hour${if (hoursDifference > 1) "s" else ""} ago"
                    minutesDifference > 0 -> "$minutesDifference minute${if (minutesDifference > 1) "s" else ""} ago"
                    else -> "Just now"
                }
            }

            1L -> "1 day ago"
            in 2..6 -> "$daysDifference days ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(targetDate)
        }
    }

}