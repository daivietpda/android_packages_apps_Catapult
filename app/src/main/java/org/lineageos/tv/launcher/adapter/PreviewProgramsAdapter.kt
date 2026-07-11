/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.adapter

import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.tvprovider.media.tv.PreviewProgram
import org.lineageos.tv.launcher.R
import org.lineageos.tv.launcher.view.WatchNextCard

class PreviewProgramsAdapter :
    ListAdapter<PreviewProgram, PreviewProgramsAdapter.ViewHolder>(diffCallback) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        WatchNextCard(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val watchNextCard: WatchNextCard
    ) : RecyclerView.ViewHolder(watchNextCard) {
        init {
            watchNextCard.setOnClickListener {
                val context = watchNextCard.context

                try {
                    context.startActivity(watchNextCard.launchIntent)
                } catch (e: Exception) {
                    Log.e("PreviewProgramsAdapter", "Failed to start activity", e)
                    Toast.makeText(context, R.string.error_activity_not_found, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        fun bind(previewProgram: PreviewProgram) {
            watchNextCard.setInfo(previewProgram)
        }
    }

    companion object {
        private val diffCallback = object : DiffUtil.ItemCallback<PreviewProgram>() {
            @Suppress("RestrictedApi")
            override fun areItemsTheSame(
                oldItem: PreviewProgram,
                newItem: PreviewProgram
            ) = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: PreviewProgram,
                newItem: PreviewProgram
            ) = oldItem.hasAnyUpdatedValues(newItem)
        }
    }
}
