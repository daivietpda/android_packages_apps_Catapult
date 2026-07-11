/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.ext

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

fun <T> SharedPreferences.valueFlow(
    key: String,
    valueGetter: SharedPreferences.(key: String) -> T,
) = callbackFlow {
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
        changedKey?.takeIf { it == key }?.let {
            trySend(valueGetter(it))
        }
    }

    registerOnSharedPreferenceChangeListener(listener)

    // Emit the latest value
    trySend(valueGetter(key))

    awaitClose {
        unregisterOnSharedPreferenceChangeListener(listener)
    }
}

const val FAVORITE_APPS_KEY = "favorite_apps"

/**
 * The list of apps the user added to favorites.
 */
var SharedPreferences.favoriteApps: List<String>
    get() = getString(FAVORITE_APPS_KEY, null)?.split(",") ?: listOf()
    set(value) = edit {
        putString(FAVORITE_APPS_KEY, value.joinToString(","))
    }

const val KNOWN_CHANNELS_KEY = "known_channels"

/**
 * The list of known channels, used for ordering.
 */
var SharedPreferences.knownChannels: List<Long>
    get() = getString(KNOWN_CHANNELS_KEY, null)?.split(",")?.map {
        it.toLong()
    } ?: listOf()
    set(value) = edit {
        putString(KNOWN_CHANNELS_KEY, value.joinToString(","))
    }

const val HIDDEN_CHANNELS_KEY = "hidden_channels"

/**
 * The list of channels' IDs hidden by the user.
 */
var SharedPreferences.hiddenChannels: Set<Long>
    get() = getStringSet(HIDDEN_CHANNELS_KEY, setOf())?.map {
        it.toLong()
    }?.toSet() ?: setOf()
    set(value) = edit {
        putStringSet(HIDDEN_CHANNELS_KEY, value.map { it.toString() }.toSet())
    }

const val HOME_ROLE_REQUEST_DIALOG_DISMISSED = "home_role_request_dialog_dismissed"

/**
 * Whether the user asked to never show again the home role request dialog.
 */
var SharedPreferences.homeRoleRequestDialogDismissed: Boolean
    get() = getBoolean(HOME_ROLE_REQUEST_DIALOG_DISMISSED, false)
    set(value) = edit {
        putBoolean(HOME_ROLE_REQUEST_DIALOG_DISMISSED, value)
    }

const val WALLPAPER_URI = "wallpaper_uri"

const val WALLPAPER_CUSTOM_SUBREDDIT = "wallpaper_custom_subreddit"
const val WALLPAPER_SOURCE = "wallpaper_source"
const val WALLPAPER_REDDIT_SUBREDDIT = "wallpaper_reddit_subreddit"
const val WALLPAPER_REDDIT_INTERVAL_MINUTES = "wallpaper_reddit_interval_minutes"
const val WALLPAPER_REDDIT_ORIENTATION = "wallpaper_reddit_orientation"

enum class WallpaperSourceType {
    LOCAL,
    REDDIT,
}

enum class WallpaperRedditOrientation {
    ANY,
    LANDSCAPE,
    PORTRAIT,
}

var SharedPreferences.wallpaperUri: String?
    get() = getString(WALLPAPER_URI, null)
    set(value) = edit {
        if (value == null) {
            remove(WALLPAPER_URI)
        } else {
            putString(WALLPAPER_URI, value)
        }
    }

var SharedPreferences.wallpaperCustomSubreddit: String
    get() = getString(WALLPAPER_CUSTOM_SUBREDDIT, "") ?: ""
    set(value) = edit {
        putString(WALLPAPER_CUSTOM_SUBREDDIT, value)
    }

var SharedPreferences.wallpaperSourceType: WallpaperSourceType
    get() = runCatching {
        WallpaperSourceType.valueOf(getString(WALLPAPER_SOURCE, WallpaperSourceType.LOCAL.name)!!)
    }.getOrDefault(WallpaperSourceType.LOCAL)
    set(value) = edit {
        putString(WALLPAPER_SOURCE, value.name)
    }

var SharedPreferences.wallpaperRedditSubreddit: String
    get() = getString(WALLPAPER_REDDIT_SUBREDDIT, "tmdbwallpapers") ?: "tmdbwallpapers"
    set(value) = edit {
        putString(WALLPAPER_REDDIT_SUBREDDIT, value)
    }

var SharedPreferences.wallpaperRedditIntervalMinutes: Int
    get() = getString(WALLPAPER_REDDIT_INTERVAL_MINUTES, "5")?.toIntOrNull()?.coerceAtLeast(1) ?: 5
    set(value) = edit {
        putString(WALLPAPER_REDDIT_INTERVAL_MINUTES, value.coerceAtLeast(1).toString())
    }

var SharedPreferences.wallpaperRedditOrientation: WallpaperRedditOrientation
    get() = runCatching {
        WallpaperRedditOrientation.valueOf(
            getString(WALLPAPER_REDDIT_ORIENTATION, WallpaperRedditOrientation.LANDSCAPE.name)!!
        )
    }.getOrDefault(WallpaperRedditOrientation.LANDSCAPE)
    set(value) = edit {
        putString(WALLPAPER_REDDIT_ORIENTATION, value.name)
    }
