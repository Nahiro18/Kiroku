package ani.dantotsu.media.anime

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TrackType
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.databinding.BottomSheetSubtitlesBinding
import ani.dantotsu.databinding.ItemSubtitleTextBinding
import java.util.Locale

@OptIn(UnstableApi::class)
class TrackGroupDialogFragment(
    private var instance: ExoplayerView,
    private var trackGroups: ArrayList<Tracks.Group>,
    private var type: @TrackType Int,
    private var overrideTrackNames: List<Pair<String, String>>? = null
) : BottomSheetDialogFragment() {
    private var _binding: BottomSheetSubtitlesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetSubtitlesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.selectionTitle.text = if (type == TRACK_TYPE_AUDIO) {
            getString(R.string.audio_and_language)
        } else {
            getString(R.string.subtitles)
        }
        binding.subtitlesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.subtitlesRecycler.adapter = TrackGroupAdapter()
    }

    private fun overrideName(position: Int): Pair<String, String>? {
        val names = overrideTrackNames ?: return null
        val index = position - (trackGroups.size - names.size)
        return names.getOrNull(index)
    }

    @SuppressLint("SetTextI18n")
    private fun audioLabel(trackGroup: Tracks.Group, position: Int): String {
        val format = trackGroup.getTrackFormat(0)
        val override = overrideName(position)
        val language = format.language
        val languageName = when {
            override != null -> "[${override.second}] ${override.first}"
            language.isNullOrBlank() || language == "und" ->
                format.label ?: getString(R.string.unknown_track, "Track ${position + 1}")
            language == "none" -> getString(R.string.disabled_track)
            else -> {
                val display = Locale.forLanguageTag(language).displayName
                    .ifBlank { language.uppercase(Locale.ROOT) }
                "[$language] $display"
            }
        }
        val codec = format.codecs
            ?: format.sampleMimeType?.substringAfterLast('/')?.uppercase(Locale.ROOT)
        val channels = when {
            format.channelCount <= 0 -> null
            format.channelCount == 1 -> "Mono"
            format.channelCount == 2 -> "Stereo"
            else -> "${format.channelCount} channels"
        }
        return listOfNotNull(languageName, codec, channels)
            .filter { it.isNotBlank() }
            .joinToString(" • ")
    }

    inner class TrackGroupAdapter : RecyclerView.Adapter<TrackGroupAdapter.StreamViewHolder>() {
        inner class StreamViewHolder(val binding: ItemSubtitleTextBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder =
            StreamViewHolder(
                ItemSubtitleTextBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

        @SuppressLint("SetTextI18n")
        @OptIn(UnstableApi::class)
        override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
            val binding = holder.binding
            val trackGroup = trackGroups[position]

            if (type == TRACK_TYPE_AUDIO) {
                binding.subtitleTitle.text = audioLabel(trackGroup, position)
            } else {
                val override = overrideName(position)
                if (override != null) {
                    binding.subtitleTitle.text = "[${override.second}] ${override.first}"
                } else when (val language = trackGroup.getTrackFormat(0).language?.lowercase()) {
                    null -> {
                        binding.subtitleTitle.text =
                            getString(R.string.unknown_track, "Track $position")
                    }

                    "none" -> {
                        binding.subtitleTitle.text = getString(R.string.disabled_track)
                    }

                    else -> {
                        val locale = if (language.contains("-")) {
                            val parts = language.split("-")
                            try {
                                Locale(parts[0], parts[1])
                            } catch (ignored: Exception) {
                                null
                            }
                        } else {
                            try {
                                Locale(language)
                            } catch (ignored: Exception) {
                                null
                            }
                        }
                        binding.subtitleTitle.text = locale?.let {
                            "[${it.language}] ${it.displayName}"
                        } ?: getString(R.string.unknown_track, language)
                    }
                }
            }

            if (trackGroup.isSelected) {
                binding.subtitleTitle.text = "✔ ${binding.subtitleTitle.text}"
            }
            binding.root.setOnClickListener {
                dismiss()
                instance.onSetTrackGroupOverride(trackGroup, type)
            }
        }

        override fun getItemCount(): Int = trackGroups.size
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}
