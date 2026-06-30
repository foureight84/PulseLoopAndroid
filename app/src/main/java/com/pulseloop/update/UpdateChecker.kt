package com.pulseloop.update

import android.content.Context
import android.os.Build
import com.pulseloop.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
)

/** A newer release than what's installed, resolved to an installable APK asset. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val apkUrl: String,
    val apkSize: Long,
    val htmlUrl: String,
)

/**
 * Polls the GitHub "latest release" endpoint and compares it to the installed build.
 *
 * Versioning contract: releases are tagged `v{versionName}+{versionCode}` (e.g. `v1.0.0+5`).
 * The integer after `+` is the Android versionCode — the only value Android uses to decide
 * "is this newer" — so that's what we compare. versionName is display-only.
 *
 * Self-update only works for the signed release build under the real applicationId: a debug
 * build (different signing key / `.debug` id) can't install the release APK over itself.
 */
object UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    private const val PREFS = "self_update"
    private const val KEY_ETAG = "etag"
    private const val KEY_LAST_CHECK = "lastCheckAt"
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 3600_000L

    fun isSupported(): Boolean =
        !BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.pulseloop"

    fun versionCodeFromTag(tag: String): Int? = tag.substringAfter('+', "").toIntOrNull()
    fun versionNameFromTag(tag: String): String = tag.removePrefix("v").substringBefore('+')

    /**
     * Returns an [UpdateInfo] if a newer release exists, else null. [force] bypasses the
     * once-a-day throttle and the ETag cache (used by the Settings "Check for updates" button).
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return@withContext null
        }

        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .apply { if (!force) prefs.getString(KEY_ETAG, null)?.let { header("If-None-Match", it) } }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return@withContext null
        }

        response.use { resp ->
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (resp.code == 304) return@withContext null          // Not Modified (ETag)
            if (!resp.isSuccessful) return@withContext null
            resp.header("ETag")?.let { prefs.edit().putString(KEY_ETAG, it).apply() }

            val bodyText = resp.body?.string() ?: return@withContext null
            val release = try {
                json.decodeFromString(GithubRelease.serializer(), bodyText)
            } catch (_: Exception) {
                return@withContext null
            }
            if (release.draft || release.prerelease) return@withContext null

            val remoteCode = versionCodeFromTag(release.tagName) ?: return@withContext null
            if (remoteCode <= BuildConfig.VERSION_CODE) return@withContext null

            val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            val apk = apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
                ?: apks.firstOrNull { a -> Build.SUPPORTED_ABIS.any { a.name.contains(it, ignoreCase = true) } }
                ?: apks.firstOrNull()
                ?: return@withContext null

            UpdateInfo(
                versionName = versionNameFromTag(release.tagName),
                versionCode = remoteCode,
                changelog = release.body?.takeIf { it.isNotBlank() } ?: (release.name ?: "A new version is available."),
                apkUrl = apk.browserDownloadUrl,
                apkSize = apk.size,
                htmlUrl = release.htmlUrl ?: "",
            )
        }
    }

    /** Download the APK to cacheDir/updates, reporting progress in 0f..1f. Returns the file or null. */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val stream = resp.body?.byteStream() ?: return@withContext null
                val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: info.apkSize

                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }   // drop any stale download
                val out = File(dir, "pulseloop-${info.versionCode}.apk")

                out.outputStream().use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (stream.read(buf).also { read = it } != -1) {
                        fos.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
                out
            }
        } catch (_: Exception) {
            null
        }
    }
}
