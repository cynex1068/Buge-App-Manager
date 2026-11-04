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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class ActivityRepository(
    private val context: Context,
    private val cacheDao: ActivityCacheDao
) {
    private val pm: PackageManager = context.packageManager

    companion object {
        private const val TAG = "ActivityRepository"
        private const val PARALLELISM = 4
        private const val CACHE_TTL_MS = 0L
    }

    suspend fun getInstalledAppsWithActivities(
        showSystemApps: Boolean,
        forceRefresh: Boolean = false
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        try {
            LogManager.info(context, "ActivityRepository: getting apps", "showSystem=$showSystemApps, refresh=$forceRefresh")

            val installed: List<PackageInfo> = if (isAtLeastT()) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }

            val semaphore = Semaphore(PARALLELISM)
            val apps = coroutineScope {
                installed.map { pkg ->
                    async {
                        semaphore.withPermit {
                            try {
                                val appInfo = pm.getApplicationInfo(pkg.packageName, 0)
                                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                                if (!showSystemApps && isSystem) return@withPermit null

                                val hasActivities = if (!forceRefresh && cacheDao.hasCache(pkg.packageName) > 0) {
                                    true
                                } else {
                                    resolvePackageInfo(pkg.packageName)?.activities?.isNotEmpty() == true
                                }
                                if (!hasActivities) return@withPermit null

                                AppInfo(
                                    packageName = pkg.packageName,
                                    appName = pm.getApplicationLabel(appInfo).toString(),
                                    versionName = pkg.versionName ?: "",
                                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                                        pkg.longVersionCode else {
                                        @Suppress("DEPRECATION") pkg.versionCode.toLong()
                                    },
                                    icon = runCatching { pm.getApplicationIcon(pkg.packageName) }.getOrNull(),
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
                    }
                }.awaitAll().filterNotNull()
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

            val versionCode = runCatching {
                if (isAtLeastT()) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0).versionCode.toLong()
                }
            }.getOrDefault(-1L)

            if (!forceRefresh && cacheDao.isValidFor(packageName, versionCode, CACHE_TTL_MS)) {
                val cached = cacheDao.query(packageName)
                val filtered = cached.filter { it.exported || showNotExported }
                val sorted = filtered.map { it.toDetail() }.sortedBy { it.name.lowercase() }
                LogManager.info(context, "ActivityRepository: cache hit", "pkg=$packageName, count=${sorted.size}")
                return@withContext sorted
            }

            val pkgInfo = resolvePackageInfo(packageName) ?: return@withContext emptyList()
            val activities = pkgInfo.activities ?: return@withContext emptyList()

            val semaphore = Semaphore(PARALLELISM)
            val entities = coroutineScope {
                activities.map { info ->
                    async {
                        semaphore.withPermit { info.toCache(packageName, versionCode) }
                    }
                }.awaitAll()
            }

            cacheDao.replace(packageName, entities)

            val filtered = entities.filter { it.exported || showNotExported }
            val sorted = filtered.map { it.toDetail() }.sortedBy { it.name.lowercase() }
            LogManager.info(context, "ActivityRepository: activities loaded", "pkg=$packageName, count=${sorted.size}")
            sorted
        } catch (e: Exception) {
            LogManager.error(context, "ActivityRepository: activities failed", "pkg=$packageName, err=${e.message}")
            emptyList()
        }
    }

    private fun resolvePackageInfo(packageName: String): PackageInfo? {
        val info = runCatching {
            if (isAtLeastT()) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            }
        }.getOrNull()

        val enabled = info?.applicationInfo?.enabled ?: false
        if (info == null || !enabled) {
            return archiveInfo(packageName) ?: info
        }
        return info
    }

    private fun archiveInfo(packageName: String): PackageInfo? = runCatching {
        val appInfo = if (isAtLeastT()) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }

        val apk = File(appInfo.publicSourceDir ?: appInfo.sourceDir ?: return@runCatching null)
        if (!apk.exists()) return@runCatching null

        if (isAtLeastT()) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
        }
    }.getOrNull()

    private fun ActivityInfo.toCache(pkg: String, versionCode: Long): ActivityCacheEntity {
        val label = runCatching { loadLabel(pm)?.toString() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: name.substringAfterLast('.')

        return ActivityCacheEntity(
            packageName = pkg,
            className = name,
            label = label,
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
    }

    private fun countIntentFilters(info: ActivityInfo): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info.intentFilters?.size ?: 0
        } else {
            if (info.exported) 1 else 0
        }
    } catch (_: Throwable) {
        if (info.exported) 1 else 0
    }

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