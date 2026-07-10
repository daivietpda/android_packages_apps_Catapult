/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.adapter

import android.net.Uri
import android.widget.ImageView
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.card.MaterialCardView

class WallpaperAdapter(
    private val onWallpaperSelected: (Uri) -> Unit,
) : ListAdapter<Uri, WallpaperAdapter.ViewHolder>(diffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        MaterialCardView(parent.context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                val margin = (parent.resources.displayMetrics.density * 6).toInt()
                setMargins(margin, margin, margin, margin)
            }
            radius = 24f
            isFocusable = true
            isClickable = true
            cardElevation = 0f
        }
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
        private val imageView = ImageView(card.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (card.resources.displayMetrics.density * 160).toInt(),
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        init {
            card.addView(imageView)
            card.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onWallpaperSelected(getItem(position))
                }
            }
        }

        fun bind(uri: Uri) {
            imageView.load(uri) {
                crossfade(true)
            }
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<Uri>() {
            override fun areItemsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem

            override fun areContentsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem
        }
    }
}