package com.friction.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.friction.app.databinding.ItemAppRowBinding

class AppListAdapter(
    private val onToggle: (InstalledApp, Boolean) -> Unit,
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    private var items: List<InstalledApp> = emptyList()
    private var guarded: Set<String> = emptySet()

    fun submit(apps: List<InstalledApp>, guardedPackages: Set<String>) {
        items = apps
        guarded = guardedPackages
        notifyDataSetChanged()
    }

    fun updateGuarded(guardedPackages: Set<String>) {
        guarded = guardedPackages
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemAppRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(app: InstalledApp) {
            binding.appIcon.setImageDrawable(app.icon)
            binding.appLabel.text = app.label

            // Avoid feedback loops while rebinding
            binding.appGuarded.setOnCheckedChangeListener(null)
            binding.appGuarded.isChecked = guarded.contains(app.packageName)
            binding.appGuarded.setOnCheckedChangeListener { _, isChecked ->
                onToggle(app, isChecked)
            }

            binding.root.setOnClickListener {
                binding.appGuarded.isChecked = !binding.appGuarded.isChecked
            }
        }
    }
}
