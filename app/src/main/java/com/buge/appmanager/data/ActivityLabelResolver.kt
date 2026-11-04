// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build

class ActivityLabelResolver(context: Context) {

    private val appContext = context.applicationContext
    private val pm: PackageManager = appContext.packageManager
    private val labelCache = HashMap<String, String>()

    fun resolve(packageName: String, entity: ActivityCacheEntity): String {
        val key = cacheKey(packageName, entity.className)
        labelCache[key]?.let { return it }
        val label = resolveInternal(packageName, entity)
        labelCache[key] = label
        return label
    }

    fun resolveAll(packageName: String, entities: List<ActivityCacheEntity>): List<String> {
        if (entities.isEmpty()) return emptyList()
        val resIds = entities
            .filter { labelCache[cacheKey(packageName, it.className)] == null }
            .filter { it.nonLocalizedLabel.isNullOrBlank() && it.labelRes != 0 }
            .map { it.labelRes }
            .distinct()

        if (resIds.isNotEmpty()) {
            prefetch(packageName, resIds)
        }

        return entities.map { entity ->
            val key = cacheKey(packageName, entity.className)
            labelCache[key] ?: resolveInternal(packageName, entity).also { labelCache[key] = it }
        }
    }

    fun clear() = labelCache.clear()

    private fun resolveInternal(packageName: String, entity: ActivityCacheEntity): String =
        entity.nonLocalizedLabel?.takeIf { it.isNotBlank() }
            ?: loadText(packageName, entity.labelRes)
            ?: entity.className.substringAfterLast('.')

    private fun loadText(packageName: String, resId: Int): String? {
        if (resId == 0) return null
        return runCatching {
            resourcesOf(packageName)?.getText(resId, appContext.packageName)?.toString()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun prefetch(packageName: String, resIds: List<Int>) {
        val res = runCatching { resourcesOf(packageName) }.getOrNull() ?: return
        resIds.forEach { id ->
            runCatching {
                res.getText(id, appContext.packageName)?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { text ->
                labelCache[resourceKey(packageName, id)] = text
            }
        }
    }

    private fun resourcesOf(packageName: String): Resources? {
        if (packageName == appContext.packageName) return appContext.resources
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getResourcesForApplication(
                    pm.getApplicationInfo(
                        packageName,
                        PackageManager.ApplicationInfoFlags.of(0)
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getResourcesForApplication(pm.getApplicationInfo(packageName, 0))
            }
        }.getOrNull()
    }

    private fun cacheKey(pkg: String, className: String) = "$pkg|$className"

    private fun resourceKey(pkg: String, resId: Int) = "$pkg|res:$resId"
}
