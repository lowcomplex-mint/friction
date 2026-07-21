package com.friction.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object AppListLoader {

    /**
     * Launchable apps only (MAIN + LAUNCHER), excluding Friction itself.
     * Sorted by display label.
     */
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        return resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName) return@mapNotNull null
                val label = ri.loadLabel(pm)?.toString() ?: pkg
                val icon = ri.loadIcon(pm)
                InstalledApp(packageName = pkg, label = label, icon = icon)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
