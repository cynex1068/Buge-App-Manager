// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.util

import android.content.Context
import com.buge.appmanager.data.ActivityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object ActivityLabelResolver {

    private val resolved = ConcurrentHashMap<String, String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun key(packageName: String, className: String) = "$packageName/$className"

    fun peek(packageName: String, className: String): String? = resolved[key(packageName, className)]

    fun preloadVisible(
        context: Context,
        repository: ActivityRepository,
        scope: CoroutineScope,
        items: List<Triple<String, String, Pair<Int, String?>>>,
        onResolved: (packageName: String, className: String, label: String) -> Unit
    ) {
        for ((packageName, className, meta) in items) {
            val k = key(packageName, className)
            val cached = resolved[k]
            if (cached != null) {
                onResolved(packageName, className, cached)
                continue
            }
            if (!inFlight.add(k)) continue

            scope.launch {
                val label = withContext(Dispatchers.IO) {
                    repository.resolveLabel(packageName, className, meta.first, meta.second, className.substringAfterLast('.'))
                }
                resolved[k] = label
                inFlight.remove(k)
                onResolved(packageName, className, label)
            }
        }
    }

    fun clear() {
        resolved.clear()
        inFlight.clear()
    }
}