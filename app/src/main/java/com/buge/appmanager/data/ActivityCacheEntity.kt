// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.data

import androidx.room.Entity
import androidx.room.Index
import com.buge.appmanager.model.ActivityDetail

@Entity(
    tableName = "activity_cache",
    primaryKeys = ["packageName", "className"],
    indices = [Index("packageName")]
)
data class ActivityCacheEntity(
    val packageName: String,
    val className: String,
    val labelRes: Int,
    val nonLocalizedLabel: String?,
    val fallbackLabel: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String?,
    val intentFilterCount: Int,
    val launchMode: String,
    val parentActivityName: String,
    val versionCode: Long,
    val updatedAt: Long
) {
    fun toDetail() = ActivityDetail(
        name = fallbackLabel,
        className = className,
        isExported = exported,
        intentFilterCount = intentFilterCount,
        permission = permission ?: "None",
        launchMode = launchMode,
        parentActivityName = parentActivityName,
        labelRes = labelRes,
        nonLocalizedLabel = nonLocalizedLabel
    )
}