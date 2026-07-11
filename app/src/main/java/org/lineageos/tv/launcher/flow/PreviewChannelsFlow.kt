/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.flow

import android.content.Context
import android.provider.BaseColumns
import androidx.core.os.bundleOf
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.flow.map
import org.lineageos.tv.launcher.ext.mapEachRow
import org.lineageos.tv.launcher.ext.queryFlow
import org.lineageos.tv.launcher.model.Channel
import org.lineageos.tv.launcher.utils.Suggestions

@Suppress("RestrictedApi")
class PreviewChannelsFlow(private val context: Context) : QueryFlow<Channel> {
    private val projection = arrayOf(
        BaseColumns._ID,
        TvContractCompat.Channels.COLUMN_PACKAGE_NAME,
        TvContractCompat.Channels.COLUMN_DISPLAY_NAME,
    )

    override fun flowData() = context.contentResolver.queryFlow(
        TvContractCompat.Channels.CONTENT_URI,
        projection,
        bundleOf(),
    ).mapEachRow { it, _ ->
        val id = it.getLong(it.getColumnIndexOrThrow(BaseColumns._ID))
        val packageName = it.getString(
            it.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_PACKAGE_NAME)
        )
        val displayName = it.getString(
            it.getColumnIndexOrThrow(TvContractCompat.Channels.COLUMN_DISPLAY_NAME)
        ) ?: ""

        Channel(
            id = id,
            title = Suggestions.getChannelTitle(context, packageName, displayName),
            isExternalChannel = true,
        )
    }.map { it.filterNotNull() }
}
