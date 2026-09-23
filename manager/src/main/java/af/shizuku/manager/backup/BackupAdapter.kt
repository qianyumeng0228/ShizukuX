package af.shizuku.manager.backup

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import af.shizuku.manager.databinding.ItemBackupAppBinding

class BackupAdapter : ListAdapter<BackupViewModel.AppEntry, BackupAppViewHolder>(DIFF) {

    var onBackupClick: ((BackupViewModel.AppEntry) -> Unit)? = null
    var onRestoreClick: ((BackupViewModel.AppEntry) -> Unit)? = null
    var onFreezeClick: ((BackupViewModel.AppEntry) -> Unit)? = null
    var onItemClick: ((BackupViewModel.AppEntry) -> Unit)? = null
    var onItemLongClick: ((BackupViewModel.AppEntry) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null

    private var busyPackages: Set<String> = emptySet()
    private var selectionMode = false
    private val selectedPackages = mutableSetOf<String>()

    fun setBusy(packages: Set<String>) {
        val old = busyPackages
        busyPackages = packages
        // Notify only items that changed busy state to avoid full rebind.
        for (i in 0 until itemCount) {
            val pkg = getItem(i).packageName
            if ((pkg in old) != (pkg in packages)) notifyItemChanged(i)
        }
    }

    fun isInSelectionMode() = selectionMode

    fun getSelectedCount() = selectedPackages.size

    fun getSelectedPackages() = selectedPackages.toSet()

    fun enterSelectionMode(pkg: String) {
        selectionMode = true
        selectedPackages.clear()
        selectedPackages.add(pkg)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPackages.size)
    }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPackages.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun toggleSelection(pkg: String) {
        if (selectedPackages.contains(pkg)) {
            selectedPackages.remove(pkg)
        } else {
            selectedPackages.add(pkg)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPackages.size)
    }

    fun selectAll() {
        for (i in 0 until itemCount) {
            selectedPackages.add(getItem(i).packageName)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedPackages.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BackupAppViewHolder {
        val binding = ItemBackupAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BackupAppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BackupAppViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(
            entry,
            entry.packageName in busyPackages,
            entry.packageName in selectedPackages,
            selectionMode,
            onBackupClick,
            onRestoreClick,
            onFreezeClick,
            onItemClick,
            onItemLongClick
        )
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<BackupViewModel.AppEntry>() {
            override fun areItemsTheSame(
                oldItem: BackupViewModel.AppEntry,
                newItem: BackupViewModel.AppEntry
            ) = oldItem.packageName == newItem.packageName

            override fun areContentsTheSame(
                oldItem: BackupViewModel.AppEntry,
                newItem: BackupViewModel.AppEntry
            ) = oldItem == newItem
        }
    }
}
