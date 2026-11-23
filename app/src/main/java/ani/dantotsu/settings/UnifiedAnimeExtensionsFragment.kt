package ani.dantotsu.settings

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ani.dantotsu.R
import ani.dantotsu.cloudstream.CloudStreamExtensionManager
import ani.dantotsu.cloudstream.CloudStreamInstalled
import ani.dantotsu.cloudstream.CloudStreamPlugin
import ani.dantotsu.cloudstream.CloudStreamRepositoryManager
import ani.dantotsu.cloudstream.CloudStreamRuntime
import ani.dantotsu.databinding.FragmentExtensionsBinding
import ani.dantotsu.databinding.ItemExtensionAllBinding
import ani.dantotsu.databinding.ItemExtensionBinding
import ani.dantotsu.others.LanguageMapper.Companion.getLanguageName
import ani.dantotsu.settings.extensionprefs.AnimeSourcePreferencesFragment
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.util.customAlertDialog
import com.bumptech.glide.Glide
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import kotlinx.coroutines.launch
import rx.android.schedulers.AndroidSchedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * A single extension list that can show both Aniyomi and CloudStream extensions and lets
 * the user filter by provider. Used for the "Installed Anime" and "Available Anime" tabs,
 * so both extension systems live in one manager without sharing a runtime.
 */
class UnifiedAnimeExtensionsFragment : Fragment(), SearchQueryHandler {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!

    private val animeManager: AnimeExtensionManager = Injekt.get()
    private var installedMode: Boolean = true
    private var providerFilter: ProviderFilter = ProviderFilter.ALL
    private var searchQuery: String = ""
    private var entries: List<ExtensionEntry> = emptyList()

    private val adapter by lazy { ExtensionAdapter(installedMode) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        installedMode = requireArguments().getBoolean(ARG_INSTALLED, true)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.allExtensionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.allExtensionsRecyclerView.adapter = adapter
        binding.providerFilterScroll.isVisible = true
        setupProviderFilters()

        if (!installedMode && animeManager.availableExtensionsFlow.value.isEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch { animeManager.findAvailableExtensions() }
        }
        if (!installedMode && CloudStreamRepositoryManager.available.value.isEmpty()) {
            viewLifecycleOwner.lifecycleScope.launch { CloudStreamRepositoryManager.refresh() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            animeManager.installedExtensionsFlow.collect { render() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            animeManager.availableExtensionsFlow.collect { render() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            CloudStreamExtensionManager.installed.collect { render() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            CloudStreamRepositoryManager.available.collect { render() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            CloudStreamExtensionManager.installing.collect { render() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            CloudStreamRuntime.errors.collect { render() }
        }
        render()
    }

    private fun setupProviderFilters() {
        binding.providerChipAll.isChecked = true
        binding.providerChipAll.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                providerFilter = ProviderFilter.ALL
                render()
            }
        }
        binding.providerChipAniyomi.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                providerFilter = ProviderFilter.ANIYOMI
                render()
            }
        }
        binding.providerChipCloudStream.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                providerFilter = ProviderFilter.CLOUDSTREAM
                render()
            }
        }
    }

    private fun render() {
        if (_binding == null) return
        val query = searchQuery.trim().lowercase(Locale.ROOT)
        val languageFilter = PrefManager.getVal<String>(PrefName.LangSort)
        val showNsfw = PrefManager.getVal<Boolean>(PrefName.NSFWExtension)
        val built = mutableListOf<ExtensionEntry>()

        if (installedMode) {
            if (providerFilter != ProviderFilter.CLOUDSTREAM) {
                animeManager.installedExtensionsFlow.value.forEach { extension ->
                    built.add(
                        ExtensionEntry(
                            key = "ani-installed:${extension.pkgName}",
                            name = extension.name,
                            version = extension.versionName,
                            lang = extension.lang,
                            isNsfw = extension.isNsfw,
                            provider = Provider.ANIYOMI,
                            installed = true,
                            hasUpdate = extension.hasUpdate,
                            iconUrl = null,
                            icon = extension.icon,
                            aniInstalled = extension,
                            error = null,
                        )
                    )
                }
            }
            if (providerFilter != ProviderFilter.ANIYOMI) {
                val available = CloudStreamRepositoryManager.available.value
                CloudStreamExtensionManager.installed.value.forEach { extension ->
                    val update = available.firstOrNull {
                        it.internalName.equals(extension.internalName, true)
                    }
                    built.add(
                        ExtensionEntry(
                            key = "cs-installed:${extension.internalName}",
                            name = extension.displayName,
                            version = "v${extension.version}",
                            lang = extension.language,
                            isNsfw = extension.isNsfw,
                            provider = Provider.CLOUDSTREAM,
                            installed = true,
                            hasUpdate = update != null &&
                                update.version > extension.version &&
                                update.url.isNotBlank(),
                            iconUrl = extension.iconUrl,
                            icon = null,
                            csInstalled = extension,
                            csAvailable = update,
                            error = CloudStreamRuntime.errors.value[extension.internalName],
                        )
                    )
                }
            }
        } else {
            if (providerFilter != ProviderFilter.CLOUDSTREAM) {
                val installedPkgs = animeManager.installedExtensionsFlow.value
                    .map { it.pkgName }
                    .toSet()
                animeManager.availableExtensionsFlow.value
                    .filterNot { it.pkgName in installedPkgs }
                    .forEach { extension ->
                        built.add(
                            ExtensionEntry(
                                key = "ani-available:${extension.pkgName}",
                                name = extension.name,
                                version = extension.versionName,
                                lang = extension.lang,
                                isNsfw = extension.isNsfw,
                                provider = Provider.ANIYOMI,
                                installed = false,
                                hasUpdate = false,
                                iconUrl = extension.iconUrl,
                                icon = null,
                                aniAvailable = extension,
                            )
                        )
                    }
            }
            if (providerFilter != ProviderFilter.ANIYOMI) {
                val installedNames = CloudStreamExtensionManager.installed.value
                    .map { it.internalName.lowercase() }
                    .toSet()
                CloudStreamRepositoryManager.available.value
                    .filterNot { it.internalName.lowercase() in installedNames }
                    .forEach { extension ->
                        built.add(
                            ExtensionEntry(
                                key = "cs-available:${extension.internalName}",
                                name = extension.displayName,
                                version = "v${extension.version}",
                                lang = extension.language,
                                isNsfw = extension.isNsfw,
                                provider = Provider.CLOUDSTREAM,
                                installed = false,
                                hasUpdate = false,
                                iconUrl = extension.iconUrl,
                                icon = null,
                                installing = CloudStreamExtensionManager.installing.value
                                    .contains(extension.internalName),
                                csAvailable = extension,
                            )
                        )
                    }
            }
        }

        entries = built
            .filter { !it.isNsfw || showNsfw }
            .filter { query.isEmpty() || it.name.lowercase(Locale.ROOT).contains(query) }
            .filter {
                languageFilter == "all" || it.lang.equals(languageFilter, true) ||
                    it.lang.startsWith(languageFilter, true)
            }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

        adapter.submitList(entries)
    }

    override fun updateContentBasedOnQuery(query: String?) {
        searchQuery = query.orEmpty()
        render()
    }

    override fun notifyDataChanged() {
        viewLifecycleOwner.lifecycleScope.launch {
            animeManager.findAvailableExtensions()
            CloudStreamRepositoryManager.refresh()
            render()
        }
    }

    private fun installAniyomi(extension: AnimeExtension.Available) {
        animeManager.installExtension(extension)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { },
                { error -> snackString("Install failed: ${error.message}") },
                { snackString("Extension installed"); render() }
            )
    }

    private fun updateAniyomi(extension: AnimeExtension.Installed) {
        animeManager.updateExtension(extension)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { },
                { error -> snackString("Update failed: ${error.message}") },
                { snackString("Extension updated"); render() }
            )
    }

    private fun installCloudStream(plugin: CloudStreamPlugin) {
        viewLifecycleOwner.lifecycleScope.launch {
            snackString("Installing ${plugin.displayName}…")
            CloudStreamExtensionManager.install(plugin)
                .onSuccess { snackString("${plugin.displayName} installed"); render() }
                .onFailure { snackString("Install failed: ${it.message}") }
        }
    }

    private fun openAniyomiSettings(extension: AnimeExtension.Installed) {
        val allSettings = extension.sources.filterIsInstance<ConfigurableAnimeSource>()
        if (allSettings.isEmpty()) {
            snackString("Source is not configurable")
            return
        }
        val openSettings = { source: ConfigurableAnimeSource ->
            hideExtensionChrome(extension.name)
            val fragment = AnimeSourcePreferencesFragment().getInstance(source.id) {
                showExtensionChrome()
            }
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.slide_up, R.anim.slide_down)
                .replace(R.id.fragmentExtensionsContainer, fragment)
                .addToBackStack(null)
                .commit()
        }
        if (allSettings.size == 1) {
            openSettings(allSettings[0])
        } else {
            val names = allSettings.map { getLanguageName(it.lang) }.toTypedArray()
            requireContext().customAlertDialog().apply {
                setTitle("Select a Source")
                singleChoiceItems(names, 0) { which -> openSettings(allSettings[which]) }
                show()
            }
        }
    }

    private fun openCloudStreamSettings(extension: CloudStreamInstalled) {
        val providers = CloudStreamRuntime.providers.value
            .filter { it.extension.internalName == extension.internalName }
        val details = buildString {
            append(extension.description.ifBlank { "CloudStream extension" })
            append("\n\nRepository: ").append(
                extension.repositoryUrl.ifBlank { extension.repository }.ifBlank { "Unknown" }
            )
            append("\nProviders: ").append(
                providers.joinToString { it.name }.ifBlank { "Not loaded" }
            )
            append("\nLanguage: ").append(extension.language)
            append("\nVersion: ").append(extension.version)
            CloudStreamRuntime.errors.value[extension.internalName]?.let {
                append("\n\nLast error: ").append(it)
            }
        }
        requireContext().customAlertDialog().apply {
            setTitle(extension.displayName)
            setMessage(details)
            setPosButton("Remove") {
                viewLifecycleOwner.lifecycleScope.launch {
                    CloudStreamExtensionManager.uninstall(extension.internalName)
                    snackString("Extension uninstalled")
                    render()
                }
            }
            setNeutralButton("Update") {
                viewLifecycleOwner.lifecycleScope.launch {
                    val update = CloudStreamRepositoryManager.available.value.firstOrNull {
                        it.internalName.equals(extension.internalName, true)
                    }
                    if (update == null) {
                        snackString("No update available")
                    } else {
                        CloudStreamExtensionManager.install(update)
                            .onSuccess { snackString("Extension updated") }
                            .onFailure { snackString("Update failed: ${it.message}") }
                        render()
                    }
                }
            }
            setNegButton("Close")
            show()
        }
    }

    private fun hideExtensionChrome(title: String) {
        val activity = activity as? ExtensionsActivity ?: return
        activity.findViewById<ViewPager2>(R.id.viewPager).isVisible = false
        activity.findViewById<TabLayout>(R.id.tabLayout).isVisible = false
        activity.findViewById<TextInputLayout>(R.id.searchView).isVisible = false
        activity.findViewById<ImageView>(R.id.languageselect).isVisible = false
        activity.findViewById<TextView>(R.id.extensions).text = title
        activity.findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).isVisible = true
    }

    private fun showExtensionChrome() {
        val activity = activity as? ExtensionsActivity ?: return
        activity.findViewById<ViewPager2>(R.id.viewPager).isVisible = true
        activity.findViewById<TabLayout>(R.id.tabLayout).isVisible = true
        activity.findViewById<TextInputLayout>(R.id.searchView).isVisible = true
        activity.findViewById<ImageView>(R.id.languageselect).isVisible = true
        activity.findViewById<TextView>(R.id.extensions).text = getString(R.string.extensions)
        activity.findViewById<FrameLayout>(R.id.fragmentExtensionsContainer).isVisible = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private enum class ProviderFilter { ALL, ANIYOMI, CLOUDSTREAM }

    private enum class Provider { ANIYOMI, CLOUDSTREAM }

    private data class ExtensionEntry(
        val key: String,
        val name: String,
        val version: String,
        val lang: String,
        val isNsfw: Boolean,
        val provider: Provider,
        val installed: Boolean,
        val hasUpdate: Boolean,
        val iconUrl: String?,
        val icon: Drawable?,
        val installing: Boolean = false,
        val aniInstalled: AnimeExtension.Installed? = null,
        val aniAvailable: AnimeExtension.Available? = null,
        val csInstalled: CloudStreamInstalled? = null,
        val csAvailable: CloudStreamPlugin? = null,
        val error: String? = null,
    )

    private inner class ExtensionAdapter(
        private val installedMode: Boolean,
    ) : ListAdapter<ExtensionEntry, RecyclerView.ViewHolder>(DIFF_CALLBACK) {
        override fun getItemViewType(position: Int): Int = if (installedMode) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                InstalledHolder(ItemExtensionBinding.inflate(inflater, parent, false))
            } else {
                AvailableHolder(ItemExtensionAllBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = getItem(position)
            if (holder is InstalledHolder) bindInstalled(holder, entry)
            else if (holder is AvailableHolder) bindAvailable(holder, entry)
        }

        private fun bindInstalled(holder: InstalledHolder, entry: ExtensionEntry) {
            val binding = holder.binding
            binding.extensionNameTextView.text = entry.name
            binding.extensionVersionTextView.text = "${displayLanguage(entry.lang)} • ${entry.version}"
            binding.extensionProviderTextView.isVisible = true
            binding.extensionProviderTextView.text = providerLabel(entry.provider)
            binding.updateTextView.isVisible = entry.hasUpdate
            binding.extensionPinImageView.isVisible = entry.provider == Provider.ANIYOMI
            loadIcon(binding.extensionIconImageView, entry)
            if (!entry.error.isNullOrBlank()) {
                binding.extensionVersionTextView.text =
                    "${binding.extensionVersionTextView.text} • Load error"
            }
            binding.deleteTextView.setOnClickListener {
                when {
                    entry.aniInstalled != null -> {
                        animeManager.uninstallExtension(entry.aniInstalled.pkgName)
                        snackString("Extension uninstalled")
                        render()
                    }
                    entry.csInstalled != null -> viewLifecycleOwner.lifecycleScope.launch {
                        CloudStreamExtensionManager.uninstall(entry.csInstalled.internalName)
                        snackString("Extension uninstalled")
                        render()
                    }
                }
            }
            binding.updateTextView.setOnClickListener {
                when {
                    entry.aniInstalled != null -> updateAniyomi(entry.aniInstalled)
                    entry.csAvailable != null -> installCloudStream(entry.csAvailable)
                }
            }
            binding.settingsImageView.setOnClickListener {
                when {
                    entry.aniInstalled != null -> openAniyomiSettings(entry.aniInstalled)
                    entry.csInstalled != null -> openCloudStreamSettings(entry.csInstalled)
                }
            }
        }

        private fun bindAvailable(holder: AvailableHolder, entry: ExtensionEntry) {
            val binding = holder.binding
            binding.extensionNameTextView.text = entry.name
            binding.extensionVersionTextView.text = "${displayLanguage(entry.lang)} • ${entry.version}"
            binding.extensionProviderTextView.isVisible = true
            binding.extensionProviderTextView.text = providerLabel(entry.provider)
            loadIcon(binding.extensionIconImageView, entry)
            binding.closeTextView.alpha = if (entry.installing) 0.4f else 1f
            binding.closeTextView.setOnClickListener {
                if (entry.installing) return@setOnClickListener
                when {
                    entry.aniAvailable != null -> installAniyomi(entry.aniAvailable)
                    entry.csAvailable != null -> installCloudStream(entry.csAvailable)
                }
            }
        }

        private fun loadIcon(imageView: ImageView, entry: ExtensionEntry) {
            if (PrefManager.getVal<Boolean>(PrefName.SkipExtensionIcons)) {
                imageView.setImageDrawable(null)
                return
            }
            if (entry.icon != null) {
                imageView.setImageDrawable(entry.icon)
            } else if (!entry.iconUrl.isNullOrBlank()) {
                Glide.with(imageView).load(entry.iconUrl).into(imageView)
            } else {
                imageView.setImageResource(R.drawable.ic_extension)
            }
        }

        inner class InstalledHolder(val binding: ItemExtensionBinding) :
            RecyclerView.ViewHolder(binding.root)

        inner class AvailableHolder(val binding: ItemExtensionAllBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    private fun displayLanguage(code: String): String {
        if (code.isBlank()) return "Unknown"
        return runCatching { Locale(code).displayLanguage }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: code.uppercase(Locale.ROOT)
    }

    private fun providerLabel(provider: Provider): String = when (provider) {
        Provider.ANIYOMI -> getString(R.string.provider_aniyomi)
        Provider.CLOUDSTREAM -> getString(R.string.provider_cloudstream)
    }

    companion object {
        private const val ARG_INSTALLED = "installed"

        fun newInstance(installed: Boolean): UnifiedAnimeExtensionsFragment =
            UnifiedAnimeExtensionsFragment().apply {
                arguments = Bundle().apply { putBoolean(ARG_INSTALLED, installed) }
            }

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ExtensionEntry>() {
            override fun areItemsTheSame(oldItem: ExtensionEntry, newItem: ExtensionEntry) =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: ExtensionEntry, newItem: ExtensionEntry) =
                oldItem == newItem
        }
    }
}
