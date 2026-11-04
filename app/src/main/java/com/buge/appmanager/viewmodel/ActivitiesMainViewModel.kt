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
import com.buge.appmanager.model.AppInfo
import com.buge.appmanager.util.PreferencesManager
import kotlinx.coroutines.launch

class ActivitiesMainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.get(application).activityCacheDao()
    private val repository = ActivityRepository(application, dao)

    private val _apps = MutableLiveData<List<AppInfo>>()
    val apps: LiveData<List<AppInfo>> = _apps

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun loadApps(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val showSystemApps = PreferencesManager.getShowSystemApps(getApplication())
                val apps = repository.getInstalledAppsWithActivities(showSystemApps, forceRefresh)
                _apps.value = apps
            } catch (e: Exception) {
                _apps.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        loadApps(forceRefresh = true)
    }
}