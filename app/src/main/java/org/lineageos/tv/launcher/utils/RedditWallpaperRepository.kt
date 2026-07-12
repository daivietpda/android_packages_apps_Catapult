/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.tv.launcher.utils

import android.net.Uri
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object RedditWallpaperRepository {
    suspend fun fetchWallpaperUris(
        subreddit: String,
        orientation: WallpaperImageOrientation = WallpaperImageOrientation.LANDSCAPE,
    ): List<Uri> {
        val normalizedSubreddit = normalizeSubreddit(subreddit)
        if (normalizedSubreddit.isBlank()) {
            return emptyList()
        }

        val connection = openConnection(normalizedSubreddit)

        return runCatching {
            if (connection.responseCode !in 200..299) {
                return emptyList()
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()

            val imageUris = mutableListOf<Uri>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                extractImageUrls(item)
                    .firstOrNull { matchesOrientation(it, orientation) }
                    ?.let { imageUris.add(Uri.parse(it)) }
            }

            if (imageUris.isNotEmpty()) {
                imageUris.distinct()
            } else {
                // If the requested orientation doesn't yield anything, fall back to all images.
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    extractImageUrls(item)
                        .firstOrNull()
                        ?.let { imageUris.add(Uri.parse(it)) }
                }
                imageUris.distinct()
            }
        }.getOrDefault(emptyList()).also {
            connection.disconnect()
        }
    }

    suspend fun fetchRandomWallpaperUri(
        subreddit: String,
        orientation: WallpaperImageOrientation = WallpaperImageOrientation.LANDSCAPE,
    ): Uri? {
        val wallpapers = fetchWallpaperUris(subreddit, orientation)
        if (wallpapers.isEmpty()) {
            return null
        }

        return wallpapers[Random.nextInt(wallpapers.size)]
    }

    private fun openConnection(subreddit: String): HttpURLConnection {
        val rssUrl = "https://www.reddit.com/r/$subreddit/.rss"
        val rss2JsonUrl = "https://api.rss2json.com/v1/api.json?rss_url=$rssUrl"

        return (URL(rss2JsonUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
            )
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            instanceFollowRedirects = true
        }
    }

    private fun extractImageUrls(item: JSONObject): List<String> {
        val urls = mutableListOf<String>()

        // RSS thumbnail fields are often low-resolution preview assets.
        item.optString("link").takeIf { it.isNotBlank() }?.let(urls::add)

        listOf("description", "content").forEach { field ->
            val html = item.optString(field)
            urls += extractImageUrlsFromHtml(html)
        }

        return urls
            .mapNotNull { normalizeImageUrl(it) }
            .filter { isImageUrl(it) }
            .sortedByDescending { qualityScore(it) }
            .distinctBy { canonicalImageKey(it) }
            .distinct()
    }

    private fun extractImageUrlsFromHtml(html: String): List<String> {
        if (html.isBlank()) {
            return emptyList()
        }

        val regex = Regex("https?://[^\"'\\s>]+")
        return regex.findAll(html)
            .map { it.value }
            .filter { isImageUrl(it) }
            .toList()
    }

    private fun matchesOrientation(url: String, orientation: WallpaperImageOrientation): Boolean {
        if (orientation == WallpaperImageOrientation.ANY) {
            return true
        }

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        return runCatching {
            URL(url).openStream().use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@runCatching true
            }

            when (orientation) {
                WallpaperImageOrientation.LANDSCAPE -> bounds.outWidth >= bounds.outHeight
                WallpaperImageOrientation.PORTRAIT -> bounds.outHeight > bounds.outWidth
                WallpaperImageOrientation.ANY -> true
            }
        }.getOrDefault(true)
    }

    private fun isImageUrl(url: String): Boolean {
        val lowerCaseUrl = url.lowercase()
        return lowerCaseUrl.startsWith("http") && (
            lowerCaseUrl.contains("i.redd.it") ||
                lowerCaseUrl.contains("preview.redd.it") ||
                lowerCaseUrl.endsWith(".jpg") ||
                lowerCaseUrl.endsWith(".jpeg") ||
                lowerCaseUrl.endsWith(".png") ||
                lowerCaseUrl.endsWith(".webp") ||
                lowerCaseUrl.contains(".jpg?") ||
                lowerCaseUrl.contains(".jpeg?") ||
                lowerCaseUrl.contains(".png?") ||
                lowerCaseUrl.contains(".webp?")
            )
    }

    private fun normalizeImageUrl(url: String): String? {
        val decoded = url.replace("&amp;", "&")
        val uri = runCatching { URI(decoded) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null

        if (host.contains("thumbs.redditmedia.com") || host.contains("external-preview.redd.it")) {
            return null
        }

        val sanitized = URI(
            uri.scheme,
            uri.userInfo,
            when {
                host == "preview.redd.it" -> "i.redd.it"
                else -> uri.host
            },
            uri.port,
            uri.path,
            null,
            null
        ).toString()

        return sanitized
    }

    private fun qualityScore(url: String): Int {
        val lower = url.lowercase()
        var score = 0
        if (lower.contains("i.redd.it")) {
            score += 200
        }
        if (lower.endsWith(".png") || lower.contains(".png?")) {
            score += 20
        }
        if (lower.endsWith(".webp") || lower.contains(".webp?")) {
            score += 10
        }
        return score
    }

    private fun canonicalImageKey(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase() ?: ""
        val path = uri.path?.lowercase()?.substringBeforeLast('.') ?: ""
        return "$host$path"
    }

    private fun normalizeSubreddit(value: String): String {
        return value.trim()
            .removePrefix("r/")
            .removePrefix("/r/")
            .trim('/')
    }

    enum class WallpaperImageOrientation {
        ANY,
        LANDSCAPE,
        PORTRAIT,
    }
}