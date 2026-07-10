/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.utils

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import androidx.preference.PreferenceManager
import org.lineageos.tv.launcher.ext.favoriteApps
import org.lineageos.tv.launcher.model.LeanbackAppInfo

object AppManager {
    fun updateFavoriteApps(context: Context, installedApps: List<LeanbackAppInfo>) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val favoriteApps = sharedPreferences.favoriteApps.toMutableList()

        // Remove apps from favorite if they got uninstalled
        installedApps.map { it.packageName }.let { installedPackageNames ->
            favoriteApps.filter { favoriteApp ->
                !installedPackageNames.contains(favoriteApp)
            }
        }.forEach { uninstalledFavoriteApp ->
            favoriteApps.remove(uninstalledFavoriteApp)
        }

        sharedPreferences.favoriteApps = favoriteApps
    }

    fun toggleFavoriteApp(context: Context, packageName: String, favorite: Boolean) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val favoriteApps = sharedPreferences.favoriteApps.toMutableList()

        if (favorite) {
            if (!favoriteApps.contains(packageName)) {
                favoriteApps.add(packageName)
            }
        } else {
            favoriteApps.remove(packageName)
        }

        sharedPreferences.favoriteApps = favoriteApps
    }

    fun getFavoriteApps(context: Context): List<String> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        return sharedPreferences.favoriteApps
    }

    fun uninstallApp(context: Context, packageName: String) {
        val packageUri = Uri.parse("package:$packageName")
        val uninstallIntent = Intent(Intent.ACTION_DELETE, packageUri)
        context.startActivity(uninstallIntent, null)
    }

    fun uninstallable(app: ApplicationInfo, context: Context): Boolean {
        val isSignedWithPlatformKey = try {
            val method = app.javaClass.getMethod("isSignedWithPlatformKey")
            method.invoke(app) as Boolean
        } catch (e: Exception) {
            false
        }

        val isEssentialPackage = try {
            val clazz = Class.forName("com.android.settingslib.Utils")
            val method = clazz.getMethod(
                "isEssentialPackage",
                Resources::class.java,
                PackageManager::class.java,
                String::class.java
            )
            method.invoke(null, context.resources, context.packageManager, app.packageName) as Boolean
        } catch (e: Exception) {
            false
        }

        return !isSystemApp(context) && !isSignedWithPlatformKey && !isEssentialPackage
    }

    fun isSystemApp(context: Context): Boolean {
        return context.applicationInfo.flags and FLAG_SYSTEM != 0
    }
}
