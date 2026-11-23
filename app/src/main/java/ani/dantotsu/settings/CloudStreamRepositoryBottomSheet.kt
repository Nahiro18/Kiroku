package ani.dantotsu.settings

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.BottomSheetDialogFragment
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.cloudstream.CloudStreamExtensionManager
import ani.dantotsu.cloudstream.CloudStreamRepositoryManager
import ani.dantotsu.databinding.BottomSheetAddRepositoryBinding
import ani.dantotsu.databinding.ItemRepoBinding
import kotlinx.coroutines.launch

/**
 * Repository manager for CloudStream. It only reads and writes
 * [CloudStreamRepositoryManager], so CloudStream indexes are never registered as Aniyomi
 * repositories (and vice versa).
 */
class CloudStreamRepositoryBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetAddRepositoryBinding? = null
    private val binding get() = _binding!!
    private var repositories: MutableList<String> = mutableListOf()
    private var adapter: RepoAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddRepositoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.addRepositoryText.text = getString(R.string.cloudstream_add_repository)
        binding.addRepositoryDescription.text = getString(R.string.cloudstream_repository_desc)
        binding.repositoryInput.hint = "https://raw.githubusercontent.com/user/repo/builds/plugins.json"

        repositories = CloudStreamRepositoryManager.repositories.toMutableList()
        adapter = RepoAdapter()
        binding.repositoriesRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.repositoriesRecyclerView.adapter = adapter
        updateStatus()

        binding.addButton.setOnClickListener {
            val input = binding.repositoryInput.text.toString().trim()
            if (input.isBlank()) {
                binding.repositoryInput.error = getString(R.string.cloudstream_repository_invalid)
                return@setOnClickListener
            }
            addRepository(input)
        }
        binding.cancelButton.setOnClickListener { dismiss() }
        binding.repositoryInput.setOnEditorActionListener { textView, action, keyEvent ->
            if (action == EditorInfo.IME_ACTION_DONE ||
                (keyEvent?.action == KeyEvent.ACTION_UP && keyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                val value = textView.text.toString().trim()
                if (value.isNotBlank()) {
                    addRepository(value)
                    return@setOnEditorActionListener true
                }
            }
            false
        }
    }

    private fun addRepository(input: String) {
        val normalized = CloudStreamRepositoryManager.normalizeRepositoryUrl(input)
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            binding.repositoryInput.error = getString(R.string.cloudstream_repository_invalid)
            return
        }
        binding.repositoryInput.error = null
        viewLifecycleOwner.lifecycleScope.launch {
            CloudStreamRepositoryManager.addRepository(normalized)
            repositories = CloudStreamRepositoryManager.repositories.toMutableList()
            adapter?.notifyDataSetChanged()
            updateStatus()
            binding.repositoryInput.setText("")
        }
    }

    private fun removeRepository(url: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            CloudStreamRepositoryManager.removeRepository(url)
            repositories = CloudStreamRepositoryManager.repositories.toMutableList()
            adapter?.notifyDataSetChanged()
            updateStatus()
        }
    }

    private fun updateStatus() {
        val count = CloudStreamExtensionManager.installed.value.size
        val available = CloudStreamRepositoryManager.available.value.size
        val error = CloudStreamRepositoryManager.lastError.value
        binding.addRepositoryDescription.text = buildString {
            append(getString(R.string.cloudstream_repository_desc))
            append("\n")
            append("$available available • $count installed")
            if (!error.isNullOrBlank()) append("\n").append(error)
        }
    }

    private inner class RepoAdapter : RecyclerView.Adapter<RepoAdapter.Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemRepoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = repositories.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val url = repositories[position]
            holder.binding.repoNameTextView.text = url
                .removePrefix("https://raw.githubusercontent.com/")
                .removeSuffix("/plugins.json")
                .removeSuffix("/plugins.min.json")
            holder.binding.repoDeleteImageView.setOnClickListener {
                removeRepository(url)
            }
            holder.binding.repoCopyImageView.setOnClickListener { image ->
                image.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                copyToClipboard(url, true)
                Toast.makeText(requireContext(), "Repository copied", Toast.LENGTH_SHORT).show()
            }
        }

        inner class Holder(val binding: ItemRepoBinding) : RecyclerView.ViewHolder(binding.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
