// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ActivityCacheDao {

    @Query("SELECT COUNT(*) FROM activity_cache WHERE packageName = :pkg")
    suspend fun hasCache(pkg: String): Int

    @Query("SELECT * FROM activity_cache WHERE packageName = :pkg")
    suspend fun query(pkg: String): List<ActivityCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ActivityCacheEntity>)

    @Query("DELETE FROM activity_cache WHERE packageName = :pkg")
    suspend fun delete(pkg: String)

    @Transaction
    suspend fun replace(pkg: String, items: List<ActivityCacheEntity>) {
        delete(pkg)
        insertAll(items)
    }

    @Query(
        """SELECT COUNT(*) FROM activity_cache
           WHERE packageName = :pkg AND versionCode = :versionCode
             AND updatedAt > :minUpdatedAt"""
    )
    suspend fun countValid(pkg: String, versionCode: Long, minUpdatedAt: Long): Int

    suspend fun isValidFor(pkg: String, versionCode: Long, ttlMs: Long): Boolean {
        val minUpdatedAt = if (ttlMs <= 0L) 0L else System.currentTimeMillis() - ttlMs
        return countValid(pkg, versionCode, minUpdatedAt) > 0
    }

    @Query("DELETE FROM activity_cache")
    suspend fun clearAll()
}