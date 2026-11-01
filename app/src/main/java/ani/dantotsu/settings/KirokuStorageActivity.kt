package ani.dantotsu.settings

import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import ani.dantotsu.databinding.ActivityKirokuStorageBinding
import ani.dantotsu.initActivity
import ani.dantotsu.media.kiroku.KirokuChapterCache
import ani.dantotsu.media.kiroku.KirokuEpisodeCache
import ani.dantotsu.navBarHeight
import ani.dantotsu.statusBarHeight
import ani.dantotsu.R
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class KirokuStorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityKirokuStorageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)

        binding = ActivityKirokuStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.storageLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarHeight
            bottomMargin = navBarHeight
        }

        binding.storageBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.storageRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val sizes = withContext(Dispatchers.IO) {
                listOf(
                    KirokuChapterCache.usedBytes(),
                    KirokuEpisodeCache.usedBytes(),
                    imageCacheBytes(),
                )
            }
            val chapters = sizes[0]
            val episodes = sizes[1]
            val images = sizes[2]

            binding.storageRecycler.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(R.string.storage_chapters),
                        desc = readable(chapters),
                        icon = R.drawable.ic_round_import_contacts_24,
                        onClick = {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { KirokuChapterCache.clear() }
                                refresh()
                            }
                        },
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.storage_episodes),
                        desc = readable(episodes),
                        icon = R.drawable.ic_round_movie_filter_24,
                        onClick = {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { KirokuEpisodeCache.clear() }
                                refresh()
                            }
                        },
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.storage_images),
                        desc = readable(images),
                        icon = R.drawable.ic_round_image_search_24,
                        onClick = {
                            lifecycleScope.launch {
                                withContext(Dispatchers.IO) { clearImageCache() }
                                refresh()
                            }
                        },
                    ),
                )
            )
        }
    }

    private fun imageCacheBytes(): Long {
        val root = File(cacheDir, "image_manager_disk_cache")
        if (!root.exists()) return 0L
        return runCatching {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    private fun clearImageCache() {
        val root = File(cacheDir, "image_manager_disk_cache")
        runCatching { root.deleteRecursively() }
        toast(getString(R.string.storage_cleared))
    }

    private fun readable(bytes: Long): String {
        if (bytes <= 0L) return getString(R.string.storage_empty)
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return "%.1f %s".format(value, units[index])
    }
}
