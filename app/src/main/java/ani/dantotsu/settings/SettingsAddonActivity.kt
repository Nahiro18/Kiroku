package ani.dantotsu.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.ai.YoloModelManager
import ani.dantotsu.addons.AddonDownloader
import ani.dantotsu.addons.download.DownloadAddonManager
import ani.dantotsu.addons.torrent.TorrentAddonManager
import ani.dantotsu.addons.torrent.TorrentServerService
import ani.dantotsu.databinding.ActivitySettingsAddonsBinding
import ani.dantotsu.databinding.ItemSettingsBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.restartApp
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.statusBarHeight
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SettingsAddonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAddonsBinding
    private val downloadAddonManager: DownloadAddonManager = Injekt.get()
    private val torrentAddonManager: TorrentAddonManager = Injekt.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        initActivity(this)
        val context = this
        binding = ActivitySettingsAddonsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            settingsAddonsLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = statusBarHeight
                bottomMargin = navBarHeight
            }

            addonSettingsBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

            settingsRecyclerView.adapter = SettingsAdapter(
                arrayListOf(
                    Settings(
                        type = 1,
                        name = getString(R.string.anime_downloader_addon),
                        desc = getString(R.string.not_installed),
                        icon = R.drawable.ic_download_24,
                        isActivity = true,
                        attach = {
                            setStatus(
                                view = it,
                                context = context,
                                status = downloadAddonManager.hadError(context),
                                hasUpdate = downloadAddonManager.hasUpdate
                            )
                            var job = Job()
                            downloadAddonManager.addListenerAction { _ ->
                                job.cancel()
                                it.settingsIconRight.animate().cancel()
                                it.settingsIconRight.rotation = 0f
                                setStatus(
                                    view = it,
                                    context = context,
                                    status = downloadAddonManager.hadError(context),
                                    hasUpdate = false
                                )
                            }
                            it.settingsIconRight.setOnClickListener { _ ->
                                if (it.settingsDesc.text == getString(R.string.installed)) {
                                    downloadAddonManager.uninstall()
                                    return@setOnClickListener
                                } else {
                                    job = Job()
                                    val scope = CoroutineScope(Dispatchers.Main + job)
                                    it.settingsIconRight.setImageResource(R.drawable.ic_sync)
                                    scope.launch {
                                        while (isActive) {
                                            withContext(Dispatchers.Main) {
                                                it.settingsIconRight.animate()
                                                    .rotationBy(360f)
                                                    .setDuration(1000)
                                                    .setInterpolator(LinearInterpolator())
                                                    .start()
                                            }
                                            delay(1000)
                                        }
                                    }
                                    snackString(getString(R.string.downloading))
                                    lifecycleScope.launchIO {
                                        AddonDownloader.update(
                                            activity = context,
                                            downloadAddonManager,
                                            repo = DownloadAddonManager.REPO,
                                            currentVersion = downloadAddonManager.getVersion() ?: ""
                                        )
                                    }
                                }
                            }
                        },
                    ), Settings(
                        type = 1,
                        name = getString(R.string.torrent_addon),
                        desc = getString(R.string.not_installed),
                        icon = R.drawable.ic_round_magnet_24,
                        isActivity = true,
                        attach = {
                            setStatus(
                                view = it,
                                context = context,
                                status = torrentAddonManager.hadError(context),
                                hasUpdate = torrentAddonManager.hasUpdate
                            )
                            var job = Job()
                            torrentAddonManager.addListenerAction { _ ->
                                job.cancel()
                                it.settingsIconRight.animate().cancel()
                                it.settingsIconRight.rotation = 0f
                                setStatus(
                                    view = it,
                                    context = context,
                                    status = torrentAddonManager.hadError(context),
                                    hasUpdate = false
                                )
                            }
                            it.settingsIconRight.setOnClickListener { _ ->
                                if (it.settingsDesc.text == getString(R.string.installed)) {
                                    TorrentServerService.stop()
                                    torrentAddonManager.uninstall()
                                    return@setOnClickListener
                                } else {
                                    job = Job()
                                    val scope = CoroutineScope(Dispatchers.Main + job)
                                    it.settingsIconRight.setImageResource(R.drawable.ic_sync)
                                    scope.launch {
                                        while (isActive) {
                                            withContext(Dispatchers.Main) {
                                                it.settingsIconRight.animate()
                                                    .rotationBy(360f)
                                                    .setDuration(1000)
                                                    .setInterpolator(LinearInterpolator())
                                                    .start()
                                            }
                                            delay(1000)
                                        }
                                    }
                                    snackString(getString(R.string.downloading))
                                    lifecycleScope.launchIO {
                                        AddonDownloader.update(
                                            activity = context,
                                            torrentAddonManager,
                                            repo = TorrentAddonManager.REPO,
                                            currentVersion = torrentAddonManager.getVersion() ?: "",
                                        )
                                    }
                                }
                            }
                        },
                    ),
                    Settings(
                        type = 2,
                        name = getString(R.string.enable_torrent),
                        desc = getString(R.string.enable_torrent_desc),
                        icon = R.drawable.ic_round_dns_24,
                        isChecked = PrefManager.getVal(PrefName.TorrentEnabled),
                        switch = { isChecked, it ->
                            if (isChecked && !torrentAddonManager.isAvailable(false)) {
                                snackString(getString(R.string.install_torrent_addon))
                                it.settingsButton.isChecked = false
                                PrefManager.setVal(PrefName.TorrentEnabled, false)
                                return@Settings
                            }
                            PrefManager.setVal(PrefName.TorrentEnabled, isChecked)
                            Injekt.get<TorrentAddonManager>().extension?.let {
                                if (isChecked) {
                                    lifecycleScope.launchIO {
                                        if (!TorrentServerService.isRunning()) {
                                            TorrentServerService.start()
                                        }
                                    }
                                } else {
                                    lifecycleScope.launchIO {
                                        if (TorrentServerService.isRunning()) {
                                            TorrentServerService.stop()
                                        }
                                    }
                                }
                            }
                        },
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.yolo_model_title),
                        desc = getString(R.string.yolo_model_desc),
                        icon = R.drawable.ic_round_auto_awesome_24,
                        isActivity = false,
                        attach = { itemView ->
                            setupYoloModelItem(itemView)
                            itemView.settingsIconRight.setOnClickListener {
                                val isDownloaded = YoloModelManager.isModelDownloaded(YoloModelManager.Model.V11)
                                val isCompatible = YoloModelManager.isCompatible()
                                if (!isCompatible) return@setOnClickListener
                                if (isDownloaded) {
                                    YoloModelManager.deleteModel(YoloModelManager.Model.V11)
                                    setupYoloModelItem(itemView)
                                } else {
                                    downloadYoloModel(itemView, YoloModelManager.Model.V11)
                                }
                            }
                        },
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.yolo_model_v8_title),
                        desc = getString(R.string.yolo_model_v8_desc),
                        icon = R.drawable.ic_round_auto_awesome_24,
                        isActivity = false,
                        attach = { itemView ->
                            setupYoloModelItem(itemView, YoloModelManager.Model.V8)
                            itemView.settingsIconRight.setOnClickListener {
                                val isDownloaded = YoloModelManager.isModelDownloaded(YoloModelManager.Model.V8)
                                val isCompatible = YoloModelManager.isCompatible()
                                if (!isCompatible) return@setOnClickListener
                                if (isDownloaded) {
                                    YoloModelManager.deleteModel(YoloModelManager.Model.V8)
                                    setupYoloModelItem(itemView, YoloModelManager.Model.V8)
                                } else {
                                    downloadYoloModel(itemView, YoloModelManager.Model.V8)
                                }
                            }
                        },
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.yolo_selected_model),
                        desc = getString(R.string.yolo_selected_model_desc),
                        icon = R.drawable.ic_round_swap_horiz_24,
                        isActivity = false,
                        attach = { itemView ->
                            setupYoloModelSpinner(itemView)
                        },
                    ),
                    Settings(
                        type = 1,
                        name = getString(R.string.yolo_delegate),
                        desc = getString(R.string.yolo_delegate_desc),
                        icon = R.drawable.ic_round_memory_24,
                        isActivity = false,
                        attach = { itemView ->
                            setupYoloDelegateSpinner(itemView)
                        },
                    )
                )
            )
            settingsRecyclerView.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)

        }
    }

    override fun onResume() {
        super.onResume()
        (binding.settingsRecyclerView.adapter as? SettingsAdapter)?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        torrentAddonManager.removeListenerAction()
        downloadAddonManager.removeListenerAction()
    }

    private fun setupYoloModelItem(itemView: ItemSettingsBinding, model: YoloModelManager.Model = YoloModelManager.Model.V11) {
        val isDownloaded = YoloModelManager.isModelDownloaded(model)
        val isCompatible = YoloModelManager.isCompatible()
        val sizeMB = YoloModelManager.getModelSizeMB(model)

        itemView.settingsDesc.text = if (!isCompatible) {
            getString(R.string.yolo_not_compatible)
        } else if (isDownloaded) {
            getString(R.string.yolo_downloaded)
        } else {
            getString(R.string.yolo_model_desc).replace("~3.2", String.format("%.1f", sizeMB))
        }

        itemView.settingsIconRight.apply {
            if (!isCompatible) {
                visibility = View.GONE
            } else if (isDownloaded) {
                setImageResource(R.drawable.ic_round_delete_24)
                setOnClickListener {
                    val modelToDelete = if (model == YoloModelManager.Model.V11) YoloModelManager.Model.V11 else YoloModelManager.Model.V8
                    YoloModelManager.deleteModel(modelToDelete)
                    (settingsRecyclerView.adapter as? SettingsAdapter)?.notifyDataSetChanged()
                }
            } else {
                setImageResource(R.drawable.ic_download_24)
                setOnClickListener {
                    downloadYoloModel(itemView, model)
                }
            }
        }
    }

    private fun setupYoloModelSpinner(itemView: ItemSettingsBinding) {
        val currentModel = YoloModelManager.getSelectedModel()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsAddonActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("YOLOv11n-seg", "YOLOv8n-seg"))
            setSelection(currentModel.ordinal)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val newModel = YoloModelManager.Model.values()[position]
                    if (newModel != YoloModelManager.getSelectedModel()) {
                        YoloModelManager.setSelectedModel(newModel)
                        showRestartDialog()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        itemView.attachView.addView(spinner)
        itemView.settingsIconRight.visibility = View.GONE
    }

    private fun setupYoloDelegateSpinner(itemView: ItemSettingsBinding) {
        val currentDelegate = YoloModelManager.getSelectedDelegate()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsAddonActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("GPU (recommended)", "CPU (fallback)"))
            setSelection(currentDelegate.ordinal)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val newDelegate = YoloModelManager.Delegate.values()[position]
                    if (newDelegate != YoloModelManager.getSelectedDelegate()) {
                        YoloModelManager.setSelectedDelegate(newDelegate)
                        showRestartDialog()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }
        itemView.attachView.addView(spinner)
        itemView.settingsIconRight.visibility = View.GONE
    }

    private fun showRestartDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restart_required)
            .setMessage(R.string.restart_required_msg)
            .setPositiveButton(R.string.restart_now) { _, _ ->
                restartApp()
            }
            .setNegativeButton(R.string.later) { _, _ -> }
            .show()
    }

    private fun downloadYoloModel(itemView: ItemSettingsBinding, model: YoloModelManager.Model) {
        if (YoloModelManager.isDownloadingNow()) return

        val job = Job()
        val scope = CoroutineScope(Dispatchers.Main + job)
        itemView.settingsIconRight.setImageResource(R.drawable.ic_sync)
        scope.launch {
            while (isActive && YoloModelManager.isDownloadingNow()) {
                itemView.settingsIconRight.animate()
                    .rotationBy(360f)
                    .setDuration(1000)
                    .setInterpolator(LinearInterpolator())
                    .start()
                delay(1000)
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val success = YoloModelManager.downloadModel(model) { progress ->
                if (isActive) {
                    itemView.settingsDesc.text = getString(R.string.yolo_downloading, progress)
                }
            }
            job.cancel()
            itemView.settingsIconRight.animate().cancel()
            itemView.settingsIconRight.rotation = 0f
            if (success) {
                itemView.settingsIconRight.setImageResource(R.drawable.ic_round_delete_24)
                itemView.settingsDesc.text = getString(R.string.yolo_downloaded)
            } else {
                itemView.settingsIconRight.setImageResource(R.drawable.ic_download_24)
                itemView.settingsDesc.text = getString(R.string.yolo_download_failed)
            }
        }
    }

    private fun setStatus(
        view: ItemSettingsBinding,
        context: Context,
        status: String?,
        hasUpdate: Boolean
    ) {
        try {
            when (status) {
                context.getString(R.string.loaded_successfully) -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_delete_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.installed)
                }

                null -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_download_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.not_installed)
                }

                else -> {
                    view.settingsIconRight.setImageResource(R.drawable.ic_round_new_releases_24)
                    view.settingsIconRight.rotation = 0f
                    view.settingsDesc.text = context.getString(R.string.error_msg, status)
                }
            }
            if (hasUpdate) {
                view.settingsIconRight.setImageResource(R.drawable.ic_round_sync_24)
                view.settingsDesc.text = context.getString(R.string.update_addon)
            }
        } catch (e: Exception) {
            Logger.log(e)
        }
    }
}