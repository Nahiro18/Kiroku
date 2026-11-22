package ani.dantotsu.download

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityDownloadsBinding
import ani.dantotsu.databinding.ItemDownloadQueueBinding
import ani.dantotsu.download.anime.AnimeDownloaderService
import ani.dantotsu.download.anime.OfflineAnimeFragment
import ani.dantotsu.download.manga.OfflineMangaFragment
import ani.dantotsu.getThemeColor
import ani.dantotsu.initActivity
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager

/**
 * Everything download related in one place: the downloaded library (anime/manga) and the live
 * queue of what the in-app downloader is working on right now.
 */
class DownloadsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var queueAdapter: QueueAdapter
    private var showingQueue = false
    private var showingManga = false
    private var shownFragmentClass: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.downloadsHeader.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
        }

        binding.downloadsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.downloadsQueueButton.setOnClickListener { showQueue(true) }
        binding.downloadsModeDownloaded.setOnClickListener { showQueue(false) }
        binding.downloadsModeQueue.setOnClickListener { showQueue(true) }
        binding.downloadsTypeAnime.setOnClickListener { setType(manga = false) }
        binding.downloadsTypeManga.setOnClickListener { setType(manga = true) }

        queueAdapter = QueueAdapter()
        binding.downloadsQueueRecycler.layoutManager = LinearLayoutManager(this)
        binding.downloadsQueueRecycler.adapter = queueAdapter

        setType(intent.getStringExtra(EXTRA_TYPE) == "manga")
    }

    private fun setType(manga: Boolean) {
        showingManga = manga
        styleTab(binding.downloadsTypeAnime, !manga)
        styleTab(binding.downloadsTypeManga, manga)
        showLibrary()
    }

    private fun showLibrary() {
        styleTab(binding.downloadsModeDownloaded, true)
        styleTab(binding.downloadsModeQueue, false)
        showingQueue = false
        binding.downloadsFragmentContainer.isVisible = true
        binding.downloadsQueueRecycler.isVisible = false
        binding.downloadsQueueEmpty.isVisible = false
        binding.downloadsTypeContainer.isVisible = true

        val fragmentClass = if (showingManga) {
            OfflineMangaFragment::class.java
        } else {
            OfflineAnimeFragment::class.java
        }
        if (shownFragmentClass == fragmentClass) return
        shownFragmentClass = fragmentClass
        val fragment = if (showingManga) OfflineMangaFragment() else OfflineAnimeFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.downloadsFragmentContainer, fragment)
            .commitAllowingStateLoss()
    }

    private fun showQueue(queue: Boolean) {
        if (!queue) {
            showLibrary()
            return
        }
        showingQueue = true
        styleTab(binding.downloadsModeDownloaded, false)
        styleTab(binding.downloadsModeQueue, true)
        binding.downloadsFragmentContainer.isVisible = false
        binding.downloadsTypeContainer.isVisible = false
        binding.downloadsQueueRecycler.isVisible = true
        refreshQueue()
    }

    private fun refreshQueue() {
        val items = DownloadQueueTracker.snapshot()
        queueAdapter.submit(items)
        binding.downloadsQueueEmpty.isVisible = items.isEmpty()
        binding.downloadsQueueRecycler.isVisible = items.isNotEmpty()
    }

    private fun styleTab(view: TextView, selected: Boolean) {
        view.setBackgroundResource(
            if (selected) R.drawable.bg_downloads_pill_selected
            else R.drawable.bg_downloads_pill_unselected
        )
        val color = if (selected) {
            getThemeColor(com.google.android.material.R.attr.colorOnPrimary)
        } else {
            ColorUtils.setAlphaComponent(
                getThemeColor(com.google.android.material.R.attr.colorOnBackground),
                160
            )
        }
        view.setTextColor(color)
    }

    override fun onStart() {
        super.onStart()
        DownloadQueueTracker.listener = {
            runOnUiThread { if (showingQueue) refreshQueue() }
        }
        if (showingQueue) refreshQueue()
    }

    override fun onStop() {
        super.onStop()
        DownloadQueueTracker.listener = null
    }

    private inner class QueueAdapter : RecyclerView.Adapter<QueueAdapter.Holder>() {
        private var items: List<DownloadQueueTracker.Entry> = emptyList()

        fun submit(newItems: List<DownloadQueueTracker.Entry>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                ItemDownloadQueueBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val binding = holder.binding
            binding.itemDownloadQueueTitle.text = entry.title
            binding.itemDownloadQueueEpisode.text =
                this@DownloadsActivity.getString(R.string.episode) + entry.episode
            binding.itemDownloadQueueProgress.progress = entry.percent
            binding.itemDownloadQueueStatus.text = when (entry.status) {
                "Downloading" -> "Downloading ${entry.percent}%"
                "Queued" -> "Queued"
                "Completed" -> "Completed"
                else -> entry.status
            }
            val running = entry.status == "Downloading" || entry.status == "Queued"
            binding.itemDownloadQueueCancel.isVisible = running
            binding.itemDownloadQueueCancel.setOnClickListener {
                sendBroadcast(
                    Intent(AnimeDownloaderService.ACTION_CANCEL_DOWNLOAD)
                        .setPackage(packageName)
                        .putExtra(AnimeDownloaderService.EXTRA_TASK_NAME, entry.taskName)
                )
                DownloadQueueTracker.removeByTaskName(entry.taskName)
            }
        }

        inner class Holder(val binding: ItemDownloadQueueBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        const val EXTRA_TYPE = "type"
    }
}
