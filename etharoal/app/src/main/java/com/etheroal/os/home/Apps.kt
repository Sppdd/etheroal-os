package com.etheroal.os.home

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.os.Process
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.Collator

/** One launchable app. */
data class AppEntry(val label: String, val component: ComponentName) {
    val key: String get() = component.flattenToString()
}

/**
 * The apps on the phone, for the drawer and dock. Uses LauncherApps (the API launchers are meant
 * to use) and keeps the list fresh when apps are installed, removed or updated.
 */
class AppCatalog(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val icons = LruCache<String, Bitmap>(200)

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val _dock = MutableStateFlow(loadDock())
    val dock: StateFlow<List<String>> = _dock

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: android.os.UserHandle?) = refreshAsync()
        override fun onPackageAdded(packageName: String?, user: android.os.UserHandle?) = refreshAsync()
        override fun onPackageChanged(packageName: String?, user: android.os.UserHandle?) = refreshAsync()
        override fun onPackagesAvailable(p: Array<out String>?, u: android.os.UserHandle?, r: Boolean) = refreshAsync()
        override fun onPackagesUnavailable(p: Array<out String>?, u: android.os.UserHandle?, r: Boolean) = refreshAsync()
    }

    init {
        launcherApps.registerCallback(callback)
    }

    private fun refreshAsync() {
        Thread { _apps.value = load() }.start()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) { _apps.value = load() }

    private fun load(): List<AppEntry> {
        val collator = Collator.getInstance()
        return launcherApps.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != context.packageName }
            .map { AppEntry(it.label.toString(), it.componentName) }
            .distinctBy { it.key }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .also { list -> pruneDock(list.map { it.key }.toSet()) }
    }

    fun icon(app: AppEntry, sizePx: Int): Bitmap? {
        icons[app.key]?.let { return it }
        val info = runCatching {
            launcherApps.getActivityList(app.component.packageName, Process.myUserHandle())
                .firstOrNull { it.componentName == app.component }
        }.getOrNull() ?: return null
        return runCatching { info.getBadgedIcon(0).toBitmap(sizePx, sizePx) }.getOrNull()?.also { icons.put(app.key, it) }
    }

    /** Told about every launch from Etheroal, for habit learning. */
    var onLaunch: ((AppEntry) -> Unit)? = null

    fun launch(app: AppEntry) {
        onLaunch?.invoke(app)
        launcherApps.startMainActivity(app.component, Process.myUserHandle(), null, null)
    }

    fun openAppInfo(app: AppEntry) {
        launcherApps.startAppDetailsActivity(app.component, Process.myUserHandle(), null, null)
    }

    // ---- dock ---------------------------------------------------------

    fun isDocked(app: AppEntry) = app.key in _dock.value

    fun toggleDock(app: AppEntry) {
        val now = _dock.value.toMutableList()
        if (!now.remove(app.key)) {
            if (now.size >= DOCK_SIZE) now.removeAt(0)
            now += app.key
        }
        saveDock(now)
    }

    private fun pruneDock(installed: Set<String>) {
        val kept = _dock.value.filter { it in installed }
        if (kept.size != _dock.value.size) saveDock(kept)
    }

    private fun loadDock() = prefs.getString("dock", "").orEmpty().split('\n').filter { it.isNotBlank() }

    private fun saveDock(keys: List<String>) {
        prefs.edit().putString("dock", keys.joinToString("\n")).apply()
        _dock.value = keys
    }

    companion object {
        const val DOCK_SIZE = 5

        /** True when Etheroal is the phone's home screen. */
        fun isDefaultHome(context: Context): Boolean =
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

        /** The system dialog that asks the user to make Etheroal the home app. */
        fun requestHomeIntent(context: Context): Intent? {
            val roles = context.getSystemService(RoleManager::class.java) ?: return null
            return if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME))
                roles.createRequestRoleIntent(RoleManager.ROLE_HOME) else null
        }

        /** Fallback: the Default apps screen, where Home app can be changed back at any time. */
        fun defaultAppsSettings(): Intent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }
}
