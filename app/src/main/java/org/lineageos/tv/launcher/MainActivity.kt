/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher

import android.animation.ValueAnimator
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.transition.Slide
import android.transition.TransitionManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.tv.launcher.adapter.AllAppsAdapter
import org.lineageos.tv.launcher.adapter.FavoritesAdapter
import org.lineageos.tv.launcher.adapter.MainVerticalAdapter
import org.lineageos.tv.launcher.adapter.PreviewProgramsAdapter
import org.lineageos.tv.launcher.adapter.WatchNextAdapter
import org.lineageos.tv.launcher.ext.favoriteApps
import org.lineageos.tv.launcher.ext.homeRoleRequestDialogDismissed
import org.lineageos.tv.launcher.ext.roleCanBeRequested
import org.lineageos.tv.launcher.ext.wallpaperRedditIntervalMinutes
import org.lineageos.tv.launcher.ext.wallpaperRedditSubreddit
import org.lineageos.tv.launcher.ext.wallpaperSourceType
import org.lineageos.tv.launcher.ext.wallpaperUri
import org.lineageos.tv.launcher.model.AppInfo
import org.lineageos.tv.launcher.model.InternalChannel
import org.lineageos.tv.launcher.model.MainRowItem
import org.lineageos.tv.launcher.notification.NotificationUtils
import org.lineageos.tv.launcher.notification.ServiceConnectionState
import org.lineageos.tv.launcher.utils.AppManager
import org.lineageos.tv.launcher.utils.PermissionsGatedCallback
import org.lineageos.tv.launcher.utils.RedditWallpaperRepository
import org.lineageos.tv.launcher.ext.WallpaperSourceType
import org.lineageos.tv.launcher.viewmodels.LauncherViewModel
import org.lineageos.tv.launcher.viewmodels.NotificationViewModel
import java.util.Locale

class MainActivity : AppCompatActivity(R.layout.activity_main) {
    // View models
    private val model: LauncherViewModel by viewModels()
    private val notificationViewModel: NotificationViewModel by viewModels()

    // Views
    private val assistantButtonsContainer by lazy { findViewById<LinearLayout>(R.id.assistant_buttons)!! }
    private val assistantHintImageView by lazy { findViewById<ImageView>(R.id.assistantHintImageView)!! }
    private val keyboardAssistantButton by lazy { findViewById<ImageButton>(R.id.keyboard_assistant)!! }
    private val mainVerticalGridView by lazy { findViewById<VerticalGridView>(R.id.main_vertical_grid)!! }
    private val backgroundScrimView by lazy { findViewById<View>(R.id.backgroundScrimView)!! }
    private val wallpaperImageView by lazy { findViewById<ImageView>(R.id.wallpaperImageView)!! }
    private val settingButton by lazy { findViewById<ImageButton>(R.id.settingsMaterialButton)!! }
    private val notificationCountTextView by lazy { findViewById<TextView>(R.id.notificationCountTextView)!! }
    private val topBarContainer by lazy { findViewById<LinearLayout>(R.id.top_bar)!! }
    private val voiceAssistantButton by lazy { findViewById<ImageButton>(R.id.voice_assistant)!! }

    // System services
    private val roleManager by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)
        } else {
            null
        }
    }

    // Activity request launchers
    private val homeRoleActivityRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Do nothing
    }

    // Adapters
    private val allAppsAdapter by lazy { AllAppsAdapter() }
    private val favoritesAdapter by lazy { FavoritesAdapter() }
    private val mainVerticalAdapter by lazy { MainVerticalAdapter() }
    private val watchNextAdapter by lazy { WatchNextAdapter() }
    private val previewChannelAdapters = mutableMapOf<Long, PreviewProgramsAdapter>()
    private var topBarHidden = false
    private var wallpaperRotationJob: Job? = null
    private var scrimDefaultColor = Color.argb(96, 0, 0, 0)
    private var scrimExpandedColor = Color.argb(70, 0, 0, 0)

    companion object {
        private const val TOP_BAR_ANIMATION_DURATION_MS = 220L
        private const val CONTENT_SHIFT_Y = -28f
        private const val SCRIM_ALPHA_EXPANDED = 0.90f
        private const val SCRIM_ALPHA_DEFAULT = 1f
        private const val WALLPAPER_SCALE_EXPANDED = 1.08f
    }

    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    private val permissionsGatedCallback = PermissionsGatedCallback(this) {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    model.watchNextPrograms,
                    model.channelsToPrograms
                ) { watchNextPrograms, channels ->
                    channels.mapNotNull { channel ->
                        // Check if "Watch Next" should be skipped
                        if (channel.first.id == InternalChannel.WATCH_NEXT.id && watchNextPrograms.isEmpty()) {
                            null
                        } else {
                            channel.first.id to MainRowItem(
                                channel.first.title,
                                when (channel.first.id) {
                                    InternalChannel.FAVORITE_APPS.id -> favoritesAdapter
                                    InternalChannel.WATCH_NEXT.id -> watchNextAdapter
                                    InternalChannel.ALL_APPS.id -> allAppsAdapter
                                    else -> previewChannelAdapters.getOrPut(channel.first.id) {
                                        PreviewProgramsAdapter()
                                    }.apply {
                                        channel.second?.let { previewPrograms ->
                                            submitList(previewPrograms)
                                        }
                                    }
                                }
                            )
                        }
                    } to watchNextPrograms
                }.collectLatest { (updatedList, watchNextPrograms) ->
                    mainVerticalAdapter.submitList(updatedList)
                    watchNextAdapter.submitList(watchNextPrograms)
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.installedApps.collectLatest {
                    allAppsAdapter.submitList(it)

                    if (it.isNotEmpty()) {
                        AppManager.updateFavoriteApps(this@MainActivity, it)
                    }
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.favoriteApps.collectLatest {
                    favoritesAdapter.submitList(
                        it.mapNotNull {
                            runCatching {
                                AppInfo.create(this@MainActivity, it)
                            }.getOrNull()
                        } + listOf(
                            FavoritesAdapter.createAddFavoriteEntry(this@MainActivity),
                            FavoritesAdapter.createModifyChannelsEntry(this@MainActivity),
                        )
                    )
                }
            }
        }

        askForHomeRoleIfNeeded()
    }

    @Suppress("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundScrimView.setBackgroundColor(scrimDefaultColor)

        settingButton.setOnClickListener {
            safeStartActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }

        notificationCountTextView.setOnClickListener {
            startActivity(Intent(this@MainActivity, SystemOptionsActivity::class.java))
        }

        val assistIntent = Intent(Intent.ACTION_ASSIST)
        assistIntent.resolveActivity(packageManager)?.also {
            setupAssistantButtons(assistIntent)
        } ?: run {
            assistantHintImageView.isInvisible = true
            assistantButtonsContainer.isInvisible = true
        }

        mainVerticalGridView.adapter = mainVerticalAdapter
        setupTopBarAutoHideOnScroll()

        favoritesAdapter.onFavoritesChangedCallback = {
            sharedPreferences.favoriteApps = it
        }

        syncWallpaperForCurrentMode()

        settingButton.requestFocus()

        permissionsGatedCallback.runAfterPermissionsCheck()

        onBackPressedDispatcher.addCallback(object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                notificationViewModel.state.collect { state ->
                    when (state) {
                        ServiceConnectionState.Connected -> {}
                        ServiceConnectionState.Disconnected -> {
                            notificationCountTextView.text = ""
                        }
                        is ServiceConnectionState.Notifications -> {
                            if (state.notifications.isNotEmpty()) {
                                notificationCountTextView.text = String.format(
                                    Locale.getDefault(),
                                    "%d",
                                    state.notifications.count()
                                )
                            } else {
                                notificationCountTextView.text = ""
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (NotificationUtils.notificationPermissionGranted(this)) {
            notificationViewModel.bindService(this)
        }

        syncWallpaperRotation()
    }

    override fun onResume() {
        super.onResume()
        syncWallpaperForCurrentMode()
    }

    override fun onDestroy() {
        super.onDestroy()

        if (NotificationUtils.notificationPermissionGranted(this)) {
            notificationViewModel.unbindService(this)
        }

        wallpaperRotationJob?.cancel()
    }

    private fun setupAssistantButtons(assistIntent: Intent) {
        voiceAssistantButton.setOnClickListener {
            safeStartActivity(assistIntent)
        }

        val keyboardAssistantIntent = Intent(assistIntent).apply {
            putExtra(Intent.EXTRA_ASSIST_INPUT_HINT_KEYBOARD, true)
        }
        keyboardAssistantButton.setOnClickListener {
            safeStartActivity(keyboardAssistantIntent)
        }

        val transition = Slide().apply {
            slideEdge = Gravity.START
            duration = 400
        }
        assistantHintImageView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                transition.removeTarget(assistantHintImageView)
                transition.addTarget(assistantButtonsContainer)
                TransitionManager.beginDelayedTransition(topBarContainer, transition)
                assistantHintImageView.isVisible = false
                assistantButtonsContainer.isVisible = true
            }
        }

        val assistantButtonFocusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                if (!keyboardAssistantButton.hasFocus() && !voiceAssistantButton.hasFocus()) {
                    transition.removeTarget(assistantButtonsContainer)
                    transition.addTarget(assistantHintImageView)
                    TransitionManager.beginDelayedTransition(topBarContainer, transition)
                    assistantButtonsContainer.isVisible = false
                    assistantHintImageView.isVisible = true
                }
            }
        }

        keyboardAssistantButton.onFocusChangeListener = assistantButtonFocusListener
        voiceAssistantButton.onFocusChangeListener = assistantButtonFocusListener
    }

    private fun setupTopBarAutoHideOnScroll() {
        mainVerticalGridView.setOnChildViewHolderSelectedListener(
            object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int
                ) {
                    if (position > 0) {
                        hideTopBar()
                    } else {
                        showTopBar()
                    }
                }
            }
        )
    }

    private fun hideTopBar() {
        if (topBarHidden || !topBarContainer.isVisible) {
            return
        }

        topBarHidden = true
        topBarContainer.clearAnimation()
        mainVerticalGridView.clearAnimation()
        backgroundScrimView.animate().cancel()
        wallpaperImageView.animate().cancel()

        topBarContainer.animate()
            .alpha(0f)
            .translationY(-topBarContainer.height.toFloat())
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                topBarContainer.isVisible = false
                topBarContainer.alpha = 1f
                topBarContainer.translationY = 0f
            }
            .start()

        mainVerticalGridView.animate()
            .translationY(CONTENT_SHIFT_Y)
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        backgroundScrimView.animate()
            .alpha(SCRIM_ALPHA_EXPANDED)
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        animateScrimColorTo(scrimExpandedColor)

        wallpaperImageView.animate()
            .scaleX(WALLPAPER_SCALE_EXPANDED)
            .scaleY(WALLPAPER_SCALE_EXPANDED)
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        mainVerticalGridView.updatePadding(top = 8)
    }

    private fun showTopBar() {
        if (!topBarHidden) {
            return
        }

        topBarHidden = false
        topBarContainer.clearAnimation()
        mainVerticalGridView.clearAnimation()
        backgroundScrimView.animate().cancel()
        wallpaperImageView.animate().cancel()

        topBarContainer.isVisible = true
        topBarContainer.alpha = 0f
        topBarContainer.translationY = -topBarContainer.height.toFloat() / 2f
        topBarContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        mainVerticalGridView.animate()
            .translationY(0f)
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        backgroundScrimView.animate()
            .alpha(SCRIM_ALPHA_DEFAULT)
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        animateScrimColorTo(scrimDefaultColor)

        wallpaperImageView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(TOP_BAR_ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        mainVerticalGridView.updatePadding(top = 24)
    }

    private fun askForHomeRoleIfNeeded() {
        val roleMgr = roleManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !AppManager.isSystemApp(this) && roleMgr.roleCanBeRequested(RoleManager.ROLE_HOME)
            && !sharedPreferences.homeRoleRequestDialogDismissed
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.home_role_request_dialog_title)
                .setMessage(R.string.home_role_request_dialog_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    homeRoleActivityRequestLauncher.launch(
                        roleMgr.createRequestRoleIntent(RoleManager.ROLE_HOME)
                    )
                }
                .setNeutralButton(R.string.home_role_request_dialog_neutral) { _, _ ->
                    // Do nothing
                }
                .setNegativeButton(R.string.home_role_request_dialog_negative) { _, _ ->
                    sharedPreferences.homeRoleRequestDialogDismissed = true
                }
                .show().also {
                    it.getButton(DialogInterface.BUTTON_NEUTRAL).requestFocus()
                }
        }
    }

    private fun applyWallpaper() {
        val wallpaper = sharedPreferences.wallpaperUri
        if (wallpaper.isNullOrBlank()) {
            wallpaperImageView.isVisible = true
            wallpaperImageView.load(R.drawable.default_wallpaper) {
                allowHardware(false)
                crossfade(true)
                listener(
                    onSuccess = { _, result ->
                        applyDynamicScrimFromDrawable(result.drawable)
                    }
                )
            }
            return
        }

        wallpaperImageView.isVisible = true
        wallpaperImageView.load(wallpaper.toUri()) {
            allowHardware(false)
            crossfade(true)
            listener(
                onSuccess = { _, result ->
                    applyDynamicScrimFromDrawable(result.drawable)
                },
                onError = { _, _ ->
                    wallpaperImageView.load(R.drawable.default_wallpaper) {
                        allowHardware(false)
                        crossfade(true)
                        listener(
                            onSuccess = { _, result ->
                                applyDynamicScrimFromDrawable(result.drawable)
                            }
                        )
                    }
                }
            )
        }
    }

    private fun applyDynamicScrimFromDrawable(drawable: Drawable?) {
        if (drawable == null) {
            scrimDefaultColor = Color.argb(96, 0, 0, 0)
            scrimExpandedColor = Color.argb(70, 0, 0, 0)
            animateScrimColorTo(if (topBarHidden) scrimExpandedColor else scrimDefaultColor)
            return
        }

        lifecycleScope.launch {
            val (defaultColor, expandedColor) = withContext(Dispatchers.Default) {
                val bitmap = drawable.toBitmap(
                    width = 240,
                    height = 135,
                    config = android.graphics.Bitmap.Config.ARGB_8888
                )
                val palette = Palette.from(bitmap)
                    .clearFilters()
                    .maximumColorCount(16)
                    .generate()
                val seedColor = palette.getVibrantColor(
                    palette.getDominantColor(
                        palette.getMutedColor(Color.rgb(52, 78, 114))
                    )
                )
                val tonedSeed = if (ColorUtils.calculateLuminance(seedColor) > 0.6) {
                    ColorUtils.blendARGB(seedColor, Color.BLACK, 0.35f)
                } else {
                    seedColor
                }

                val defaultScrim = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(Color.BLACK, tonedSeed, 0.08f),
                    94
                )
                val expandedScrim = ColorUtils.setAlphaComponent(
                    ColorUtils.blendARGB(Color.BLACK, tonedSeed, 0.14f),
                    66
                )
                defaultScrim to expandedScrim
            }

            scrimDefaultColor = defaultColor
            scrimExpandedColor = expandedColor
            animateScrimColorTo(if (topBarHidden) scrimExpandedColor else scrimDefaultColor)
        }
    }

    private fun animateScrimColorTo(targetColor: Int) {
        val currentColor = (backgroundScrimView.background as? ColorDrawable)?.color
            ?: scrimDefaultColor
        ValueAnimator.ofArgb(currentColor, targetColor).apply {
            duration = 360L
            addUpdateListener { animator ->
                backgroundScrimView.setBackgroundColor(animator.animatedValue as Int)
            }
            start()
        }
    }

    private fun syncWallpaperForCurrentMode() {
        when (sharedPreferences.wallpaperSourceType) {
            WallpaperSourceType.LOCAL -> {
                wallpaperRotationJob?.cancel()
                applyWallpaper()
            }

            WallpaperSourceType.REDDIT -> {
                wallpaperRotationJob?.cancel()
                syncWallpaperRotation()
            }
        }
    }

    private fun syncWallpaperRotation() {
        if (sharedPreferences.wallpaperSourceType != WallpaperSourceType.REDDIT) {
            wallpaperRotationJob?.cancel()
            return
        }

        wallpaperRotationJob = lifecycleScope.launch {
            while (isActive && sharedPreferences.wallpaperSourceType == WallpaperSourceType.REDDIT) {
                refreshRedditWallpaper()
                val intervalMinutes = sharedPreferences.wallpaperRedditIntervalMinutes.coerceAtLeast(1)
                delay(intervalMinutes * 60_000L)
            }
        }
    }

    private suspend fun refreshRedditWallpaper() {
        val subreddit = sharedPreferences.wallpaperRedditSubreddit
        if (subreddit.isBlank()) {
            return
        }

        val wallpaper = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            RedditWallpaperRepository.fetchRandomWallpaperUri(subreddit)
        } ?: return
        sharedPreferences.wallpaperUri = wallpaper.toString()
        applyWallpaper()
    }

    private fun safeStartActivity(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e("MainActivity", "Failed to start activity for intent: $intent", e)
            Toast.makeText(this, R.string.error_activity_not_found, Toast.LENGTH_SHORT).show()
        }
    }
}
