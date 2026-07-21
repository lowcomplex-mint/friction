package com.friction.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.friction.app.databinding.ActivityMainBinding
import com.friction.app.databinding.ItemPermissionRowBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: AppListAdapter
    private var allApps: List<InstalledApp> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupPermissionRows()
        setupSettings()
        setupAppList()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionUi()
    }

    private fun setupPermissionRows() {
        setupRow(
            binding.rowAccessibility,
            title = getString(R.string.permission_accessibility),
            hint = getString(R.string.permission_accessibility_hint),
        ) { PermissionHelper.openAccessibilitySettings(this) }

        setupRow(
            binding.rowOverlay,
            title = getString(R.string.permission_overlay),
            hint = getString(R.string.permission_overlay_hint),
        ) { PermissionHelper.openOverlaySettings(this) }

        setupRow(
            binding.rowBattery,
            title = getString(R.string.permission_battery),
            hint = getString(R.string.permission_battery_hint),
        ) { PermissionHelper.openBatterySettings(this) }
    }

    private fun setupRow(
        row: ItemPermissionRowBinding,
        title: String,
        hint: String,
        onClick: () -> Unit,
    ) {
        row.permissionTitle.text = title
        row.permissionHint.text = hint
        row.root.setOnClickListener { onClick() }
    }

    private fun refreshPermissionUi() {
        val a11yListed = PermissionHelper.isAccessibilityEnabled(this)
        val a11yLive = PermissionHelper.isAccessibilityLive(this)
        val overlay = PermissionHelper.isOverlayAllowed(this)
        val battery = PermissionHelper.isBatteryOptimizationExempt(this)

        // Row shows On if user enabled us; banner warns if OEM left the service crashed
        setRowStatus(binding.rowAccessibility, a11yListed)
        setRowStatus(binding.rowOverlay, overlay)
        setRowStatus(binding.rowBattery, battery)

        when {
            a11yListed && !a11yLive -> {
                binding.statusBanner.text = getString(R.string.a11y_enabled_not_live)
                binding.statusBanner.setTextColor(ContextCompat.getColor(this, R.color.friction_danger))
            }
            a11yListed && overlay && battery -> {
                binding.statusBanner.text = getString(R.string.all_set)
                binding.statusBanner.setTextColor(ContextCompat.getColor(this, R.color.friction_accent))
            }
            else -> {
                binding.statusBanner.text = getString(R.string.missing_permissions)
                binding.statusBanner.setTextColor(ContextCompat.getColor(this, R.color.friction_text))
            }
        }
    }

    private fun setRowStatus(row: ItemPermissionRowBinding, enabled: Boolean) {
        val status: TextView = row.permissionStatus
        if (enabled) {
            status.text = getString(R.string.status_on)
            status.setTextColor(ContextCompat.getColor(this, R.color.friction_accent))
        } else {
            status.text = getString(R.string.status_off)
            status.setTextColor(ContextCompat.getColor(this, R.color.friction_danger))
        }
    }

    private fun setupSettings() {
        val delay = Prefs.getDelaySeconds(this).toFloat()
        binding.delaySlider.value = delay.coerceIn(3f, 30f)
        binding.delayValue.text = "${delay.toInt()}s"
        binding.delaySlider.addOnChangeListener { _, value, fromUser ->
            binding.delayValue.text = "${value.toInt()}s"
            if (fromUser) Prefs.setDelaySeconds(this, value.toInt())
        }
    }

    private fun setupAppList() {
        adapter = AppListAdapter { app, guarded ->
            Prefs.setGuarded(this, app.packageName, guarded)
            adapter.updateGuarded(Prefs.getGuardedPackages(this))
        }
        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        Thread {
            val apps = AppListLoader.load(this)
            runOnUiThread {
                allApps = apps
                applyFilter(binding.searchApps.text?.toString().orEmpty())
            }
        }.start()

        binding.searchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        adapter.submit(filtered, Prefs.getGuardedPackages(this))
    }
}
