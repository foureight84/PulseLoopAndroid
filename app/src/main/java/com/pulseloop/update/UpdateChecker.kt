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
 * Outcome of an update check. Failures are distinct from "no update" so the UI never
 * tells an offline or rate-limited user they're on the latest version.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    /** Unsupported build (debug / non-release id), or inside the auto-check throttle window. */
    data object Skipped : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

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
    private const val KEY_CACHED_RELEASE = "cachedReleaseJson"
    private const val KEY_LAST_CHECK = "lastCheckAt"
    private const val AUTO_CHECK_INTERVAL_MS = 24 * 3600_000L

    fun isSupported(): Boolean =
        !BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.pulseloop"

    fun versionCodeFromTag(tag: String): Int? = tag.substringAfter('+', "").toIntOrNull()
    fun versionNameFromTag(tag: String): String = tag.removePrefix("v").substringBefore('+')

    /**
     * Checks the GitHub latest release against the installed build. [force] bypasses the
     * once-a-day throttle and the ETag cache (used by the Settings "Check for updates" button).
     *
     * A 304 Not Modified still re-evaluates the release cached alongside the ETag — the
     * throttle is the only thing that limits how often the result surfaces. Otherwise a
     * pending update the user dismissed once would never be offered again while that
     * release's ETag stays current.
     */
    suspend fun check(context: Context, force: Boolean = false): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!isSupported()) return@withContext UpdateCheckResult.Skipped
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return@withContext UpdateCheckResult.Skipped
        }
        // Only send If-None-Match when a cached release body exists to evaluate on 304.
        val cached = prefs.getString(KEY_CACHED_RELEASE, null)
        val etag = if (!force && cached != null) prefs.getString(KEY_ETAG, null) else null

        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: Exception) {
            return@withContext UpdateCheckResult.Failed("network error")
        }

        response.use { resp ->
            val edit = prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            val bodyText = when {
                resp.code == 304 -> cached          // Not Modified — re-evaluate the cached release
                resp.isSuccessful -> resp.body?.string()?.also {
                    edit.putString(KEY_ETAG, resp.header("ETag")).putString(KEY_CACHED_RELEASE, it)
                }
                else -> {
                    edit.apply()
                    return@withContext UpdateCheckResult.Failed("GitHub returned ${resp.code}")
                }
            }
            edit.apply()
            if (bodyText == null) return@withContext UpdateCheckResult.Failed("empty response")
            evaluate(bodyText)
        }
    }

    /** Compare a releases-API response body against the installed build. */
    private fun evaluate(bodyText: String): UpdateCheckResult {
        val release = try {
            json.decodeFromString(GithubRelease.serializer(), bodyText)
        } catch (_: Exception) {
            return UpdateCheckResult.Failed("unexpected response from GitHub")
        }
        if (release.draft || release.prerelease) return UpdateCheckResult.UpToDate

        val remoteCode = versionCodeFromTag(release.tagName)
            ?: return UpdateCheckResult.UpToDate    // tag not in the v<name>+<code> scheme
        if (remoteCode <= BuildConfig.VERSION_CODE) return UpdateCheckResult.UpToDate

        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val apk = apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: apks.firstOrNull { a -> Build.SUPPORTED_ABIS.any { a.name.contains(it, ignoreCase = true) } }
            ?: apks.firstOrNull()
            ?: return UpdateCheckResult.Failed("release has no APK asset")

        return UpdateCheckResult.UpdateAvailable(UpdateInfo(
            versionName = versionNameFromTag(release.tagName),
            versionCode = remoteCode,
            changelog = release.body?.takeIf { it.isNotBlank() } ?: (release.name ?: "A new version is available."),
            apkUrl = apk.browserDownloadUrl,
            apkSize = apk.size,
            htmlUrl = release.htmlUrl ?: "",
        ))
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
