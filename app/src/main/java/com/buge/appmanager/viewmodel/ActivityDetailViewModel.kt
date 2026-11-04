// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.buge.appmanager.data.ActivityRepository
import com.buge.appmanager.data.AppDatabase
import com.buge.appmanager.model.ActivityDetail
import com.buge.appmanager.util.LogManager
import com.buge.appmanager.util.PreferencesManager
import kotlinx.coroutines.launch

class ActivityDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.get(application).activityCacheDao()
    private val repository = ActivityRepository(application, dao)

    private val _activities = MutableLiveData<List<ActivityDetail>>()
    val activities: LiveData<List<ActivityDetail>> = _activities

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun load(packageName: String, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val showNotExported = PreferencesManager.getShowNotExportedActivities(getApplication())
                LogManager.info(getApplication(), "ActivityDetailViewModel loading", "pkg=$packageName, showNotExported=$showNotExported, refresh=$forceRefresh")
                val activities = repository.getAppActivities(packageName, showNotExported, forceRefresh)
                _activities.value = activities
                LogManager.info(getApplication(), "ActivityDetailViewModel loaded", "pkg=$packageName, count=${activities.size}")
            } catch (e: Exception) {
                _error.value = e.message
                LogManager.error(getApplication(), "ActivityDetailViewModel load error", "pkg=$packageName, err=${e.message}")
                _activities.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh(packageName: String) {
        load(packageName, forceRefresh = true)
    }
}