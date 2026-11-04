// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 Buge Studio

package com.buge.appmanager

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.buge.appmanager.adapter.ActivityDetailAdapter
import com.buge.appmanager.data.ActivityRepository
import com.buge.appmanager.data.AppDatabase
import com.buge.appmanager.databinding.ActivityActivityDetailBinding
import com.buge.appmanager.model.ActivityDetail
import com.buge.appmanager.shizuku.ShizukuManager
import com.buge.appmanager.util.ActivityLabelResolver
import com.buge.appmanager.util.LogManager
import com.buge.appmanager.util.SnackbarHelper
import com.buge.appmanager.viewmodel.ActivityDetailViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ActivityDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_IS_SYSTEM = "extra_is_system"
        private const val SHORTCUT_REQUEST_CODE = 1001
    }

    private lateinit var binding: ActivityActivityDetailBinding
    private val viewModel: ActivityDetailViewModel by viewModels()
    private lateinit var activitiesAdapter: ActivityDetailAdapter
    private lateinit var repository: ActivityRepository
    private var allActivities: List<ActivityDetail> = emptyList()
    private var packageName: String = ""
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }

        repository = ActivityRepository(this, AppDatabase.get(this).activityCacheDao())
        LogManager.info(this, "ActivityDetailActivity created", "Package: $packageName")

        setupBackPressedCallback()
        setupToolbar()
        setupAppInfo()
        setupRecyclerView()
        setupSearch()
        observeViewModel()
        viewModel.load(packageName)
    }

    private fun setupBackPressedCallback() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val searchText = binding.searchEditText.text.toString()
                if (searchText.isNotEmpty()) {
                    binding.searchEditText.setText("")
                    searchQuery = ""
                    filterActivities("")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = getString(R.string.title_activity_detail)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupAppInfo() {
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName
        val isSystem = intent.getBooleanExtra(EXTRA_IS_SYSTEM, false)

        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val icon = packageManager.getApplicationIcon(appInfo)
            binding.appIcon.setImageDrawable(icon)
        } catch (e: Exception) {
            binding.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        binding.appName.text = appName
        binding.packageName.text = packageName
        binding.systemBadge.visibility = if (isSystem) View.VISIBLE else View.GONE
    }

    private fun setupRecyclerView() {
        activitiesAdapter = ActivityDetailAdapter(
            onActivityClick = { activity ->
                handleActivityClick(activity)
            },
            onShortcutCreate = { activity, _ ->
                showShortcutDialog(activity)
            },
            canLaunchUnexported = {
                ShizukuManager.isAuthorized()
            },
            onRequestResolveLabel = { activity ->
                resolveLabelVisible(activity)
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = activitiesAdapter

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(rv, dx, dy)
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
                for (i in first..last) {
                    activitiesAdapter.currentList.getOrNull(i)?.let { resolveLabelVisible(it) }
                }
            }
        })
    }

    private fun resolveLabelVisible(activity: ActivityDetail) {
        val cached = ActivityLabelResolver.peek(packageName, activity.className)
        if (cached != null) {
            if (cached != activity.name) {
                activitiesAdapter.updateNameByClassName(activity.className, cached)
                val idx = allActivities.indexOfFirst { it.className == activity.className }
                if (idx >= 0) {
                    allActivities = allActivities.toMutableList().also { it[idx] = it[idx].copy(name = cached) }
                }
            }
            return
        }
        ActivityLabelResolver.preloadVisible(
            this,
            repository,
            lifecycleScope,
            listOf(Triple(packageName, activity.className, Pair(activity.labelRes, activity.nonLocalizedLabel))),
            onResolved = { pkg, cls, label ->
                if (pkg == packageName) {
                    activitiesAdapter.updateNameByClassName(cls, label)
                    val idx = allActivities.indexOfFirst { it.className == cls }
                    if (idx >= 0) {
                        allActivities = allActivities.toMutableList().also { it[idx] = it[idx].copy(name = label) }
                    }
                }
            }
        )
    }

    private fun setupSearch() {
        val searchEditText = binding.searchEditText
        val btnClear = binding.btnClearSearch

        searchEditText?.addTextChangedListener { text ->
            searchQuery = text?.toString()?.trim() ?: ""
            btnClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
            filterActivities(searchQuery)
        }

        btnClear?.setOnClickListener {
            searchEditText?.setText("")
            searchQuery = ""
            searchEditText?.requestFocus()
            filterActivities("")
        }

        searchEditText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)
                searchEditText.clearFocus()
                true
            } else {
                false
            }
        }
    }

    private fun filterActivities(query: String) {
        if (query.isEmpty()) {
            activitiesAdapter.submitList(allActivities)
        } else {
            val filtered = allActivities.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.className.contains(query, ignoreCase = true)
            }
            activitiesAdapter.submitList(filtered)
        }
    }

    private fun handleActivityClick(activity: ActivityDetail) {
        if (activity.isExported) {
            try {
                val intent = Intent().apply {
                    setClassName(packageName, activity.className)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                startActivity(intent)
                SnackbarHelper.showSnackbar(binding.root, "Launching ${activity.name}")
            } catch (e: Exception) {
                SnackbarHelper.showSnackbar(binding.root, "Failed to launch: ${e.message}")
            }
        } else if (ShizukuManager.isRootMode() && ShizukuManager.isRootAvailable()) {
            launchUnexportedViaRoot(activity)
        } else if (!ShizukuManager.isRootMode() &&
            ShizukuManager.isShizukuAvailable() && ShizukuManager.hasShizukuPermission()
        ) {
            launchUnexportedViaShizuku(activity)
        } else {
            SnackbarHelper.showSnackbar(binding.root, "This activity is not exported and cannot be launched")
        }
    }

    private fun launchUnexportedViaRoot(activity: ActivityDetail) {
        val component = "$packageName/${activity.className}"
        lifecycleScope.launch {
            val result = ShizukuManager.executeCommand("am start -n '$component'")
            if (result.success) {
                SnackbarHelper.showSnackbar(binding.root, "Launching ${activity.name}")
            } else {
                SnackbarHelper.showSnackbar(binding.root, "Failed to launch: ${result.error.ifBlank { "unknown error" }}")
            }
        }
    }

    private fun launchUnexportedViaShizuku(activity: ActivityDetail) {
        lifecycleScope.launch {
            val result = ShizukuManager.launchUnexportedViaShizuku(packageName, activity.className)
            if (result.success) {
                SnackbarHelper.showSnackbar(binding.root, "Launching ${activity.name}")
            } else {
                SnackbarHelper.showSnackbar(binding.root, "Failed to launch: ${result.error.ifBlank { "unknown error" }}")
            }
        }
    }

    private fun showShortcutDialog(activity: ActivityDetail) {
        if (!activity.isExported) {
            SnackbarHelper.showSnackbar(binding.root, "This activity is not exported, cannot create shortcut")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Create Shortcut")
            .setMessage("Create shortcut for ${activity.name} on home screen?")
            .setPositiveButton(R.string.confirm) { _, _ ->
                createShortcut(activity)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createShortcut(activity: ActivityDetail) {
        try {
            val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: packageName
            val appIcon = getAppIcon()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                createShortcutModern(activity, appName, appIcon)
            } else {
                createShortcutLegacy(activity, appName, appIcon)
            }
        } catch (e: Exception) {
            SnackbarHelper.showSnackbar(binding.root, "Failed to create shortcut: ${e.message}")
        }
    }

    private fun getAppIcon(): Drawable? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        return try {
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
                    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bitmap
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun createShortcutLegacy(activity: ActivityDetail, appName: String, appIcon: Drawable?) {
        val intent = Intent().apply {
            setClassName(packageName, activity.className)
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val shortcutIntent = Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent)
            putExtra(Intent.EXTRA_SHORTCUT_NAME, activity.name)

            val iconBitmap = drawableToBitmap(appIcon)
            if (iconBitmap != null) {
                putExtra(Intent.EXTRA_SHORTCUT_ICON, iconBitmap)
            } else {
                putExtra(
                    Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this@ActivityDetailActivity, android.R.drawable.ic_menu_edit)
                )
            }
        }

        startActivityForResult(shortcutIntent, SHORTCUT_REQUEST_CODE)
    }

    private fun createShortcutModern(activity: ActivityDetail, appName: String, appIcon: Drawable?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val shortcutManager = getSystemService(ShortcutManager::class.java)

        val intent = Intent().apply {
            setClassName(packageName, activity.className)
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val shortcutBuilder = ShortcutInfo.Builder(this, "activity_${System.currentTimeMillis()}")
            .setShortLabel(activity.name)
            .setLongLabel("$appName - ${activity.name}")
            .setIntent(intent)

        val iconBitmap = drawableToBitmap(appIcon)
        if (iconBitmap != null) {
            try {
                shortcutBuilder.setIcon(Icon.createWithBitmap(iconBitmap))
            } catch (e: Exception) {
                shortcutBuilder.setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_edit))
            }
        } else {
            shortcutBuilder.setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_edit))
        }

        try {
            shortcutManager.requestPinShortcut(shortcutBuilder.build(), null)
            SnackbarHelper.showSnackbar(binding.root, "Shortcut created")
        } catch (e: Exception) {
            SnackbarHelper.showSnackbar(binding.root, "Failed to create shortcut: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SHORTCUT_REQUEST_CODE && resultCode == RESULT_OK) {
            SnackbarHelper.showSnackbar(binding.root, "Shortcut created")
        }
    }

    private fun observeViewModel() {
        viewModel.activities.observe(this) { activities ->
            allActivities = activities
            filterActivities(searchQuery)
            val isEmpty = activities.isEmpty()
            binding.emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            if (error != null) {
                SnackbarHelper.showSnackbar(binding.root, "Failed to load activities: $error")
            }
        }
    }
}