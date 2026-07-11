/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher

import android.Manifest
import android.content.ContentUris
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.tv.launcher.adapter.WallpaperAdapter
import org.lineageos.tv.launcher.ext.wallpaperCustomSubreddit
import org.lineageos.tv.launcher.ext.wallpaperRedditIntervalMinutes
import org.lineageos.tv.launcher.ext.wallpaperRedditSubreddit
import org.lineageos.tv.launcher.ext.wallpaperRedditOrientation
import org.lineageos.tv.launcher.ext.wallpaperSourceType
import org.lineageos.tv.launcher.ext.wallpaperUri
import org.lineageos.tv.launcher.ext.WallpaperSourceType
import org.lineageos.tv.launcher.ext.WallpaperRedditOrientation
import org.lineageos.tv.launcher.utils.RedditWallpaperRepository
import org.lineageos.tv.launcher.utils.RedditWallpaperRepository.WallpaperImageOrientation
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.random.Random

class WallpaperPickerActivity : ModalActivity(R.layout.activity_wallpaper_picker) {
    private val wallpaperGridView by lazy { findViewById<RecyclerView>(R.id.wallpaper_grid)!! }
    private val wallpaperLoadingProgress by lazy { findViewById<android.widget.ProgressBar>(R.id.wallpaper_loading_progress)!! }
    private val emptyStateTextView by lazy { findViewById<TextView>(R.id.wallpaper_empty_text)!! }
    private val sourceGroup by lazy { findViewById<MaterialButtonToggleGroup>(R.id.wallpaper_source_group)!! }
    private val localSourceButton by lazy { findViewById<MaterialButton>(R.id.source_local_button)!! }
    private val projectivyLauncherButton by lazy { findViewById<MaterialButton>(R.id.source_reddit_projectivy_launcher_button)!! }
    private val projectivyTmdbButton by lazy { findViewById<MaterialButton>(R.id.source_reddit_projectivy_tmdb_button)!! }
    private val customSubredditButton by lazy { findViewById<MaterialButton>(R.id.custom_subreddit_button)!! }
    private val intervalButton by lazy { findViewById<MaterialButton>(R.id.interval_button)!! }
    private val orientationButton by lazy { findViewById<MaterialButton>(R.id.orientation_button)!! }
    private val refreshNowButton by lazy { findViewById<MaterialButton>(R.id.refresh_now_button)!! }

    private val wallpaperAdapter by lazy {
        WallpaperAdapter { uri ->
            PreferenceManager.getDefaultSharedPreferences(this).wallpaperUri = uri.toString()
            finish()
        }
    }

    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    private var loadJob: Job? = null
    private var currentWallpapers: List<Uri> = emptyList()

    private sealed class WallpaperSource {
        data object Local : WallpaperSource()
        data class Reddit(val subreddit: String, val title: String) : WallpaperSource()
    }

    private var currentSource: WallpaperSource = WallpaperSource.Local

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadSource(WallpaperSource.Local)
        } else {
            Toast.makeText(this, R.string.wallpaper_permission_denied, Toast.LENGTH_SHORT).show()
            showLocalPermissionRequiredState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wallpaperGridView.layoutManager = GridLayoutManager(this, 3)
        wallpaperGridView.adapter = wallpaperAdapter

        setupSourceButtons()

        findViewById<com.google.android.material.button.MaterialButton>(R.id.reset_wallpaper_button)!!.setOnClickListener {
            sharedPreferences.wallpaperSourceType = WallpaperSourceType.LOCAL
            sharedPreferences.wallpaperUri = null
            finish()
        }

        if (sharedPreferences.wallpaperSourceType == WallpaperSourceType.REDDIT) {
            loadSource(savedRedditSource())
        } else if (hasMediaPermission()) {
            loadSource(WallpaperSource.Local)
        } else {
            showLocalPermissionRequiredState()
        }
    }

    override fun onResume() {
        super.onResume()
        updateCustomButtonText()
        updateOrientationButtonText()
        updateIntervalButtonText()
    }

    private fun setupSourceButtons() {
        localSourceButton.setOnClickListener {
            sharedPreferences.wallpaperSourceType = WallpaperSourceType.LOCAL
            if (hasMediaPermission()) {
                loadSource(WallpaperSource.Local)
            } else {
                permissionLauncher.launch(requiredPermission())
            }
        }

        projectivyLauncherButton.setOnClickListener {
            sharedPreferences.wallpaperSourceType = WallpaperSourceType.REDDIT
            sharedPreferences.wallpaperRedditSubreddit = "tmdbwallpapers"
            loadSource(WallpaperSource.Reddit("tmdbwallpapers", getString(R.string.wallpaper_source_projectivy_wallpapers)))
        }

        projectivyTmdbButton.setOnClickListener {
            sharedPreferences.wallpaperSourceType = WallpaperSourceType.REDDIT
            sharedPreferences.wallpaperRedditSubreddit = "Projectivy_TMDB"
            loadSource(WallpaperSource.Reddit("Projectivy_TMDB", getString(R.string.wallpaper_source_projectivy_tmdb)))
        }

        customSubredditButton.setOnClickListener {
            showCustomSubredditDialog()
        }

        intervalButton.setOnClickListener {
            showIntervalDialog()
        }

        orientationButton.setOnClickListener {
            showOrientationDialog()
        }

        refreshNowButton.setOnClickListener {
            refreshNow()
        }

        updateSourceButtonsFromPreferences()
        updateIntervalButtonText()
        updateOrientationButtonText()
    }

    private fun showCustomSubredditDialog() {
        val currentSubreddit = sharedPreferences.wallpaperCustomSubreddit
        val input = EditText(this).apply {
            hint = getString(R.string.wallpaper_source_custom_dialog_hint)
            setText(currentSubreddit)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSelectAllOnFocus(true)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wallpaper_source_custom_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.wallpaper_source_custom_dialog_positive) { _, _ ->
                val subreddit = normalizeSubreddit(input.text?.toString().orEmpty())
                if (subreddit.isBlank()) {
                    Toast.makeText(this, R.string.wallpaper_source_custom_empty, Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }

                sharedPreferences.wallpaperCustomSubreddit = subreddit
                sharedPreferences.wallpaperSourceType = WallpaperSourceType.REDDIT
                sharedPreferences.wallpaperRedditSubreddit = subreddit
                loadSource(WallpaperSource.Reddit(subreddit, "r/$subreddit"))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showOrientationDialog() {
        val labels = arrayOf(
            getString(R.string.wallpaper_orientation_landscape),
            getString(R.string.wallpaper_orientation_portrait),
            getString(R.string.wallpaper_orientation_any),
        )
        val currentIndex = when (sharedPreferences.wallpaperRedditOrientation) {
            WallpaperRedditOrientation.LANDSCAPE -> 0
            WallpaperRedditOrientation.PORTRAIT -> 1
            WallpaperRedditOrientation.ANY -> 2
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wallpaper_orientation)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                sharedPreferences.wallpaperRedditOrientation = when (which) {
                    0 -> WallpaperRedditOrientation.LANDSCAPE
                    1 -> WallpaperRedditOrientation.PORTRAIT
                    else -> WallpaperRedditOrientation.ANY
                }
                updateOrientationButtonText()
                dialog.dismiss()

                if (sharedPreferences.wallpaperSourceType == WallpaperSourceType.REDDIT) {
                    loadSource(currentSource)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadSource(source: WallpaperSource) {
        currentSource = source
        if (source is WallpaperSource.Reddit) {
            sharedPreferences.wallpaperSourceType = WallpaperSourceType.REDDIT
            sharedPreferences.wallpaperRedditSubreddit = source.subreddit
        } else {
            sharedPreferences.wallpaperSourceType = WallpaperSourceType.LOCAL
        }
        updateRedditControlsVisibility(source is WallpaperSource.Reddit)
        updateSourceButtonsFromPreferences()
        updateCustomButtonText()

        when (source) {
            WallpaperSource.Local -> {
                loadLocalWallpapers()
            }

            is WallpaperSource.Reddit -> {
                loadRedditWallpapers(source)
            }
        }
    }

    private fun updateRedditControlsVisibility(showRedditControls: Boolean) {
        customSubredditButton.isVisible = showRedditControls
        intervalButton.isVisible = showRedditControls
        orientationButton.isVisible = showRedditControls
        refreshNowButton.isVisible = showRedditControls
    }

    private fun loadLocalWallpapers() {
        wallpaperLoadingProgress.isVisible = true
        emptyStateTextView.isVisible = false

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val wallpapers = withContext(Dispatchers.IO) { queryLocalWallpapers() }
            currentWallpapers = wallpapers
            wallpaperAdapter.submitList(wallpapers)
            wallpaperLoadingProgress.isVisible = false
            emptyStateTextView.isVisible = wallpapers.isEmpty()
            emptyStateTextView.text = getString(R.string.wallpaper_empty)
        }
    }

    private fun showLocalPermissionRequiredState() {
        wallpaperLoadingProgress.isVisible = false
        emptyStateTextView.isVisible = true
        emptyStateTextView.text = getString(R.string.wallpaper_local_permission_needed)
    }

    private fun loadRedditWallpapers(source: WallpaperSource.Reddit) {
        wallpaperLoadingProgress.isVisible = true
        emptyStateTextView.isVisible = false

        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val wallpapers = withContext(Dispatchers.IO) {
                RedditWallpaperRepository.fetchWallpaperUris(
                    source.subreddit,
                    sharedPreferences.wallpaperRedditOrientation.toRepositoryOrientation()
                )
            }
            currentWallpapers = wallpapers
            wallpaperAdapter.submitList(wallpapers)
            wallpaperLoadingProgress.isVisible = false
            emptyStateTextView.isVisible = wallpapers.isEmpty()
            emptyStateTextView.text = getString(R.string.wallpaper_empty)
        }
    }

    private fun showIntervalDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.wallpaper_interval_dialog_hint)
            setText(sharedPreferences.wallpaperRedditIntervalMinutes.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wallpaper_interval_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.wallpaper_interval_dialog_positive) { _, _ ->
                val minutes = input.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 5
                sharedPreferences.wallpaperRedditIntervalMinutes = minutes
                updateIntervalButtonText()
                if (sharedPreferences.wallpaperSourceType == WallpaperSourceType.REDDIT) {
                    loadSource(currentSource)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun hasMediaPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun queryLocalWallpapers(): List<Uri> {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val images = mutableListOf<Uri>()

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                images.add(
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                )
            }
        }

        return images
    }

    private fun updateCustomButtonText() {
        val selectedSubreddit = when (sharedPreferences.wallpaperSourceType) {
            WallpaperSourceType.REDDIT -> sharedPreferences.wallpaperRedditSubreddit
            WallpaperSourceType.LOCAL -> sharedPreferences.wallpaperCustomSubreddit
        }.trim()
        customSubredditButton.text = if (selectedSubreddit.isBlank()) {
            getString(R.string.wallpaper_source_custom_subreddit)
        } else {
            "${getString(R.string.wallpaper_source_custom_subreddit)}: r/$selectedSubreddit"
        }
    }

    private fun updateOrientationButtonText() {
        orientationButton.text = when (sharedPreferences.wallpaperRedditOrientation) {
            WallpaperRedditOrientation.LANDSCAPE -> getString(R.string.wallpaper_orientation_landscape)
            WallpaperRedditOrientation.PORTRAIT -> getString(R.string.wallpaper_orientation_portrait)
            WallpaperRedditOrientation.ANY -> getString(R.string.wallpaper_orientation_any)
        }
    }

    private fun normalizeSubreddit(value: String): String {
        return value.trim()
            .removePrefix("r/")
            .removePrefix("/r/")
            .trim('/')
    }

    private fun refreshNow() {
        val selectedWallpaper = currentWallpapers.randomOrNull()
        if (selectedWallpaper == null) {
            if (sharedPreferences.wallpaperSourceType == WallpaperSourceType.REDDIT) {
                loadSource(currentSource)
            } else if (hasMediaPermission()) {
                loadSource(WallpaperSource.Local)
            } else {
                showLocalPermissionRequiredState()
            }
            return
        }

        sharedPreferences.wallpaperUri = selectedWallpaper.toString()
        wallpaperAdapter.submitList(currentWallpapers)
        Toast.makeText(this, R.string.wallpaper_refresh_now, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun updateIntervalButtonText() {
        intervalButton.text = getString(
            R.string.wallpaper_interval_custom,
            sharedPreferences.wallpaperRedditIntervalMinutes
        )
    }

    private fun updateSourceButtonsFromPreferences() {
        projectivyLauncherButton.text = getString(R.string.wallpaper_source_projectivy_wallpapers)
        projectivyTmdbButton.text = getString(R.string.wallpaper_source_projectivy_tmdb)

        when (sharedPreferences.wallpaperSourceType) {
            WallpaperSourceType.LOCAL -> sourceGroup.check(localSourceButton.id)
            WallpaperSourceType.REDDIT -> {
                val subreddit = sharedPreferences.wallpaperRedditSubreddit.lowercase()
                when (subreddit) {
                    "tmdbwallpapers" -> sourceGroup.check(projectivyLauncherButton.id)
                    "projectivy_tmdb" -> sourceGroup.check(projectivyTmdbButton.id)
                    else -> {
                        projectivyLauncherButton.text = "r/${sharedPreferences.wallpaperRedditSubreddit}"
                        sourceGroup.check(projectivyLauncherButton.id)
                    }
                }
            }
        }
    }

    private fun WallpaperRedditOrientation.toRepositoryOrientation(): WallpaperImageOrientation {
        return when (this) {
            WallpaperRedditOrientation.ANY -> WallpaperImageOrientation.ANY
            WallpaperRedditOrientation.LANDSCAPE -> WallpaperImageOrientation.LANDSCAPE
            WallpaperRedditOrientation.PORTRAIT -> WallpaperImageOrientation.PORTRAIT
        }
    }

    private fun savedRedditSource(): WallpaperSource.Reddit {
        val subreddit = sharedPreferences.wallpaperRedditSubreddit
        return WallpaperSource.Reddit(subreddit, "r/$subreddit")
    }

}