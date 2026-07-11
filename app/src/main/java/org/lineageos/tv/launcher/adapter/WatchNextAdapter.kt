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
import androidx.tvprovider.media.tv.WatchNextProgram
import org.lineageos.tv.launcher.R
import org.lineageos.tv.launcher.view.WatchNextCard

class WatchNextAdapter : ListAdapter<WatchNextProgram, WatchNextAdapter.ViewHolder>(DIFF_UTIL) {
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

    inner class ViewHolder(private val card: WatchNextCard) : RecyclerView.ViewHolder(card) {
        init {
            card.setOnClickListener {
                val context = card.context

                try {
                    context.startActivity(card.launchIntent)
                } catch (e: Exception) {
                    Log.e("WatchNextAdapter", "Failed to start activity", e)
                    Toast.makeText(context, R.string.error_activity_not_found, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        fun bind(watchNextProgram: WatchNextProgram) {
            card.setInfo(watchNextProgram)
        }
    }

    companion object {
        @Suppress("RestrictedApi")
        private val DIFF_UTIL = object : DiffUtil.ItemCallback<WatchNextProgram>() {
            override fun areItemsTheSame(
                oldItem: WatchNextProgram,
                newItem: WatchNextProgram
            ) = oldItem.id == oldItem.id

            override fun areContentsTheSame(
                oldItem: WatchNextProgram,
                newItem: WatchNextProgram
            ) = oldItem.hasAnyUpdatedValues(newItem)
        }
    }
}
