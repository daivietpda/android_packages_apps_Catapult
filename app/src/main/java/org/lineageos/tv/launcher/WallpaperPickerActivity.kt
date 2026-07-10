/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.tv.launcher.adapter.WallpaperAdapter
import org.lineageos.tv.launcher.ext.wallpaperUri

class WallpaperPickerActivity : ModalActivity(R.layout.activity_wallpaper_picker) {
    private val wallpaperGridView by lazy { findViewById<RecyclerView>(R.id.wallpaper_grid)!! }
    private val emptyStateTextView by lazy { findViewById<TextView>(R.id.wallpaper_empty_text)!! }

    private val wallpaperAdapter by lazy {
        WallpaperAdapter { uri ->
            PreferenceManager.getDefaultSharedPreferences(this).wallpaperUri = uri.toString()
            finish()
        }
    }

    private val sharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(this)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            loadWallpapers()
        } else {
            Toast.makeText(this, R.string.wallpaper_permission_denied, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wallpaperGridView.layoutManager = GridLayoutManager(this, 3)
        wallpaperGridView.adapter = wallpaperAdapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.reset_wallpaper_button)!!.setOnClickListener {
            sharedPreferences.wallpaperUri = null
            finish()
        }

        if (hasMediaPermission()) {
            loadWallpapers()
        } else {
            permissionLauncher.launch(requiredPermission())
        }
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

    private fun loadWallpapers() {
        lifecycleScope.launch {
            val wallpapers = withContext(Dispatchers.IO) { queryWallpapers() }
            wallpaperAdapter.submitList(wallpapers)
            emptyStateTextView.isVisible = wallpapers.isEmpty()
        }
    }

    private fun queryWallpapers(): List<Uri> {
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
}