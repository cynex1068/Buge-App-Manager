// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.data

import android.content.Context
import android.os.Build
import com.buge.appmanager.util.LogManager

class ActivitySyncManager(private val context: Context) {

    private val pm = context.packageManager
    private val sp = context.getSharedPreferences("activity_sync", Context.MODE_PRIVATE)

    fun changedPackages(): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        val lastSeq = sp.getInt(KEY_SEQ, -1)
        val lastBoot = sp.getInt(KEY_BOOT, -1)
        val bootCount = try {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT
            )
        } catch (_: Exception) {
            lastBoot
        }

        if (lastSeq == -1 || bootCount != lastBoot) {
            saveState(0, bootCount)
            return null
        }

        val changed = runCatching { pm.getChangedPackages(lastSeq) }.getOrNull() ?: return null
        saveState(changed.sequenceNumber, bootCount)
        LogManager.info(context, "ActivitySync: changed", changed.packageNames.joinToString())
        return changed.packageNames.toSet()
    }

    private fun saveState(seq: Int, boot: Int) =
        sp.edit().putInt(KEY_SEQ, seq).putInt(KEY_BOOT, boot).apply()

    private companion object {
        const val KEY_SEQ = "last_sequence"
        const val KEY_BOOT = "last_boot"
    }
}