// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.buge.appmanager.model.ActivityDetail
import com.buge.appmanager.model.AppInfo
import com.buge.appmanager.util.LogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ActivityRepository(
    private val context: Context,
    private val cacheDao: ActivityCacheDao
) {
    private val pm: PackageManager = context.packageManager
    private val labelResolver = ActivityLabelResolver(context)

    companion object {
        private const val TAG = "ActivityRepository"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }

    suspend fun getInstalledAppsWithActivities(
        showSystemApps: Boolean,
        forceRefresh: Boolean = false
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        try {
            LogManager.info(context, "ActivityRepository: getting apps", "showSystem=$showSystemApps, refresh=$forceRefresh")

            val installed: List<PackageInfo> = installedPackages()

            val apps = installed.mapNotNull { pkg ->
                try {
                    val appInfo = pkg.applicationInfo ?: return@mapNotNull null
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (!showSystemApps && isSystem) return@mapNotNull null

                    val versionCode = versionCodeOf(pkg)
                    val hasActivities = if (!forceRefresh && cacheDao.isValidFor(pkg.packageName, versionCode, CACHE_TTL_MS)) {
                        true
                    } else {
                        pkg.activities?.isNotEmpty() == true || resolvePackageInfo(pkg.packageName)?.activities?.isNotEmpty() == true
                    }
                    if (!hasActivities) return@mapNotNull null

                    AppInfo(
                        packageName = pkg.packageName,
                        appName = appInfo.loadLabel(pm).toString(),
                        versionName = pkg.versionName ?: "",
                        versionCode = versionCode,
                        icon = runCatching { appInfo.loadIcon(pm) }.getOrNull(),
                        isSystemApp = isSystem,
                        isEnabled = appInfo.enabled,
                        installTime = pkg.firstInstallTime,
                        updateTime = pkg.lastUpdateTime,
                        targetSdkVersion = appInfo.targetSdkVersion,
                        minSdkVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                            appInfo.minSdkVersion else 0,
                        apkPath = appInfo.sourceDir ?: ""
                    )
                } catch (e: Exception) {
                    LogManager.warning(context, "ActivityRepository: app info failed", "${pkg.packageName}: ${e.message}")
                    null
                }
            }

            val sorted = apps.sortedBy { it.appName.lowercase() }
            LogManager.info(context, "ActivityRepository: apps loaded", "count=${sorted.size}")
            sorted
        } catch (e: Exception) {
            LogManager.error(context, "ActivityRepository: get apps failed", e.message)
            emptyList()
        }
    }

    suspend fun getAppActivities(
        packageName: String,
        showNotExported: Boolean,
        forceRefresh: Boolean = false
    ): List<ActivityDetail> = withContext(Dispatchers.IO) {
        try {
            LogManager.info(context, "ActivityRepository: loading activities", "pkg=$packageName, showNotExported=$showNotExported, refresh=$forceRefresh")
            val pkgInfo = resolvePackageInfo(packageName) ?: return@withContext emptyList()
            val versionCode = versionCodeOf(pkgInfo)
            if (!forceRefresh && cacheDao.isValidFor(packageName, versionCode, CACHE_TTL_MS)) {
                val cached = cacheDao.query(packageName)
                val visible = if (showNotExported) cached else cached.filter { it.exported }
                val labels = labelResolver.resolveAll(packageName, visible)
                val sorted = visible
                    .mapIndexed { index, entity -> entity.toDetail(labels[index]) }
                    .sortedBy { it.name.lowercase() }
                LogManager.info(context, "ActivityRepository: cache hit", "pkg=$packageName, count=${sorted.size}")
                return@withContext sorted
            }


            val activities = pkgInfo.activities ?: return@withContext emptyList()

            val entities = activities.map { info -> info.toCache(packageName, versionCode) }
            cacheDao.replace(packageName, entities)

            val visible = if (showNotExported) entities else entities.filter { it.exported }
            val labels = labelResolver.resolveAll(packageName, visible)
            val sorted = visible
                .mapIndexed { index, entity -> entity.toDetail(labels[index]) }
                .sortedBy { it.name.lowercase() }
            LogManager.info(context, "ActivityRepository: activities loaded", "pkg=$packageName, count=${sorted.size}")
            sorted
        } catch (e: Exception) {
            LogManager.error(context, "ActivityRepository: activities failed", "pkg=$packageName, err=${e.message}")
            emptyList()
        }
    }

    private fun installedPackages(): List<PackageInfo> = if (isAtLeastT()) {
        pm.getInstalledPackages(
            PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
    }

    private fun versionCodeOf(pkg: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkg.versionCode.toLong()
        }

    private fun resolvePackageInfo(packageName: String): PackageInfo? {
        val info = queryPackageInfo(packageName, activityFlags())
        if (info != null && !info.activities.isNullOrEmpty()) return info

        val fallback = queryPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
        if (fallback != null) {
            val resolvedActivities = resolveActivitiesViaIntent(packageName)
            if (resolvedActivities.isNotEmpty()) {
                fallback.activities = resolvedActivities
                return fallback
            }
        }

        val archive = archiveInfo(packageName)
        if (archive != null && !archive.activities.isNullOrEmpty()) return archive

        return info ?: fallback
    }
    private fun archiveInfo(packageName: String): PackageInfo? = runCatching {
        @Suppress("DEPRECATION")
        val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        val path = appInfo.publicSourceDir ?: appInfo.sourceDir ?: return@runCatching null
        if (!File(path).exists()) return@runCatching null

        if (isAtLeastT()) {
            pm.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(activityFlags().toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, activityFlags())
        }
    }.getOrNull()


    private fun activityFlags(): Int = PackageManager.GET_ACTIVITIES or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_DISABLED_COMPONENTS
        } else {
            0
        }

    private fun queryPackageInfo(packageName: String, flags: Int): PackageInfo? = runCatching {
        if (isAtLeastT()) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    }.getOrNull()

    private fun resolveActivitiesViaIntent(packageName: String): Array<ActivityInfo> {
        val intent = Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val resolved = runCatching {
            if (isAtLeastT()) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(activityFlags().toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, activityFlags())
            }
        }.getOrNull() ?: return emptyArray()
        return resolved.mapNotNull { it.activityInfo }.distinctBy { it.name }.toTypedArray()
    }

    private fun ActivityInfo.toCache(pkg: String, versionCode: Long) = ActivityCacheEntity(
        packageName = pkg,
        className = name,
        labelRes = labelRes,
        nonLocalizedLabel = nonLocalizedLabel?.toString()?.takeIf { it.isNotBlank() },
        exported = exported,
        enabled = enabled,
        permission = permission?.takeIf { it.isNotBlank() },
        intentFilterCount = countIntentFilters(this),
        launchMode = when (launchMode) {
            ActivityInfo.LAUNCH_MULTIPLE -> "standard"
            ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop"
            ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask"
            ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance"
            else -> "unknown"
        },
        parentActivityName = parentActivityName ?: "None",
        versionCode = versionCode,
        updatedAt = System.currentTimeMillis()
    )

    private fun countIntentFilters(info: ActivityInfo): Int = 0

    fun launchActivity(packageName: String, className: String): Boolean = try {
        val intent = Intent().apply {
            setComponent(ComponentName(packageName, className))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        LogManager.info(context, "Activity launched", "pkg=$packageName, cls=$className")
        true
    } catch (e: Exception) {
        LogManager.error(context, "launch failed", "pkg=$packageName, cls=$className, err=${e.message}")
        false
    }

    private fun isAtLeastT() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}
