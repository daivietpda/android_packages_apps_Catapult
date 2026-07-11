/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.utils

import android.content.Context
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager
import org.lineageos.tv.launcher.R
import org.lineageos.tv.launcher.ext.hiddenChannels
import org.lineageos.tv.launcher.model.InternalChannel

@Suppress("RestrictedApi")
object Suggestions {
    fun toggleChannel(context: Context, channelId: Long, enabled: Boolean) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        sharedPreferences.hiddenChannels = sharedPreferences.hiddenChannels.toMutableSet().apply {
            if (enabled) {
                remove(channelId)
            } else {
                if (!contains(channelId)) {
                    add(channelId)
                }
            }
        }
    }

    fun getChannelTitle(context: Context, packageName: String?, displayName: String): String {
        val appName = getAppName(context, packageName)
        if (appName.isEmpty()) {
            return displayName
        }

        // Avoid having "appName: appName" in the list title
        if (appName == displayName) {
            return appName
        }

        return context.resources.getString(
            R.string.channel_title, appName, displayName
        )
    }

    fun <T, K> List<T>.orderSuggestions(orderIds: List<K>, idSelector: (T) -> K?): List<T> {
        if (orderIds.isEmpty()) {
            val (presentItems, remainingItems) = this.partition {
                idSelector(it) == InternalChannel.ALL_APPS.id
            }
            return remainingItems + presentItems
        }

        val (presentItems, remainingItems) = this.partition { idSelector(it) in orderIds }
        val sortedPresentItems = presentItems.sortedBy { orderIds.indexOf(idSelector(it)) }
        return sortedPresentItems + remainingItems
    }

    private fun getAppName(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) {
            return ""
        }

        val packageManager: PackageManager = context.packageManager
        return try {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }
}
