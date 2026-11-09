package com.nchat.app.core.update

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nchat.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    /** Release certificate fingerprint pinned by the repository's release-verification step. */
    val signingCertSha256: String,
    /** Optional alternate release URLs supplied by the version manifest. */
    val fallbackApkUrls: List<String> = emptyList()
)

/** Outcome of handing a downloaded APK to the system installer. */
sealed interface InstallResult {
    /** The installer UI was opened; Android takes over from here. */
    data object Launched : InstallResult

    /** "Install unknown apps" is still off for NChat; settings were opened. */
    data object NeedsPermission : InstallResult

    /** Everything else, with a message safe to show in the UI. */
    data class Failed(val message: String) : InstallResult
}

/**
 * In-app updater.
 *
 * Android only installs an update over an existing app when its signing key
 * is compatible with the installed version. All disk, network and PackageManager work happens on
 * [Dispatchers.IO]; only the final `startActivity` touches the main thread, and it is
 * always wrapped, because a bounced update must never crash or freeze the app.
 */
object AutoUpdater {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/cybereagle472/NChat-kotlin/main/version.json"
    private const val RELEASE_PAGE = "https://github.com/cybereagle472/NChat-kotlin/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val MIN_APK_BYTES = 100_000L
    private val SIGNER_FINGERPRINT = Regex("^[0-9a-f]{64}$")
    private val downloadMutex = Mutex()

    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (attempt in 0 until 3) {
            try {
                val connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("Cache-Control", "no-cache, no-store")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("User-Agent", "NChat-Updater")
                }
                try {
                    val status = connection.responseCode
                    if (status != HttpURLConnection.HTTP_OK) throw UpdateHttpException(status)
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val remoteCode = json.getInt("versionCode")
                    if (remoteCode <= BuildConfig.VERSION_CODE) return@withContext null
                    val signer = json.getString("signingCertSha256")
                        .replace(":", "")
                        .lowercase()
                    if (!SIGNER_FINGERPRINT.matches(signer)) {
                        throw IOException("The update manifest has an invalid signing-certificate fingerprint.")
                    }
                    val mirrors = json.optJSONArray("apkUrls")
                    val alternatives = if (mirrors == null) emptyList() else
                        (0 until minOf(mirrors.length(), 3)).mapNotNull { index ->
                            mirrors.optString(index).takeIf { it.isNotBlank() }
                        }
                    return@withContext UpdateInfo(
                        versionCode = remoteCode,
                        versionName = json.getString("versionName"),
                        apkUrl = json.getString("apkUrl"),
                        notes = json.optString("releaseNotes", "Performance and design improvements"),
                        signingCertSha256 = signer,
                        fallbackApkUrls = alternatives
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (error: IOException) {
                lastError = error
                if (!isTransientUpdateFailure(error) || attempt == 2) break
                delay(600L * (attempt + 1))
            }
        }
        throw IOException("Couldn't check for updates. Try another network or open the release page in your browser.", lastError)
    }

    /**
     * Downloads the APK and reports real byte progress through [onProgress]: a value in
     * `0f..1f`, or `null` when the server does not send a Content-Length (the UI then
     * shows an indeterminate bar for that stretch).
     */
    suspend fun downloadUpdate(
        context: Context,
        info: UpdateInfo,
        onProgress: (Float?) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
      downloadMutex.withLock {
        val targetDir = File(context.filesDir, "updates").apply {
            if (!exists() && !mkdirs()) error("Could not create the update folder.")
        }
        val apk = File(targetDir, "nchat-${info.versionCode}.apk")
        val temp = File(targetDir, "nchat-${info.versionCode}.part.apk")
        // Returning from Android's install-permission screen should not download 30 MB again.
        if (apk.isFile && apk.length() >= MIN_APK_BYTES && hasZipHeader(apk) &&
            validateArchive(context, apk, info.versionCode, info.signingCertSha256) == null) {
            onProgress(1f)
            return@withLock apk
        }
        // Never let a corrupt or wrongly signed cached file block a clean retry at rename time.
        if (apk.exists() && !apk.delete()) {
            throw IOException("Could not remove an invalid cached update. Free device storage and retry.")
        }
        val sources = listOf(info.apkUrl) + info.fallbackApkUrls
        val validate: (File) -> String? = { file ->
            if (!hasZipHeader(file)) "The downloaded file is not a valid APK."
            else validateArchive(context, file, info.versionCode, info.signingCertSha256)
        }
        try {
            try {
                ApkDownloader().download(sources, temp, onProgress, validate)
            } catch (directError: IOException) {
                if (!shouldUseAndroidDownloadFallback(directError)) throw directError
                try {
                    AndroidDownloadManagerFallback(context).download(sources, temp, onProgress)
                    validate(temp)?.let { throw InvalidUpdateDownload(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (invalid: InvalidUpdateDownload) {
                    // Do not hide signature/package/version diagnostics behind a generic transport error.
                    throw invalid
                } catch (systemError: Exception) {
                    val directMessage = directError.message ?: "The direct APK download failed."
                    val systemMessage = systemError.message ?: "Android's download service failed."
                    throw IOException(
                        "$directMessage Android's download service also failed: $systemMessage Try another network or use the official release page.",
                        systemError
                    )
                }
            }
            // Same-directory rename; keep the previous file if replacement fails.
            check(temp.renameTo(apk)) { "Could not prepare the downloaded update." }
            targetDir.listFiles()?.forEach { stale ->
                if (stale.name != apk.name && (stale.name.endsWith(".apk") || stale.name.endsWith(".part"))) stale.delete()
            }
            onProgress(1f)
            apk
        } finally {
            temp.delete()
        }
      }
    }

    /**
     * Shares the APK with the system installer.
     *
     * Never throws: every failure comes back as [InstallResult.Failed] so the caller can
     * show a dialog instead of letting the app die.
     */
    suspend fun installUpdate(context: Context, apk: File): InstallResult {
        if (!apk.exists() || apk.length() < MIN_APK_BYTES) {
            return InstallResult.Failed("The update file is missing. Please download it again.")
        }

        val canInstall = withContext(Dispatchers.IO) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                context.packageManager.canRequestPackageInstalls()
        }
        if (!canInstall) {
            withContext(Dispatchers.Main) { openUnknownSourcesSettings(context) }
            return InstallResult.NeedsPermission
        }

        val uri: Uri = try {
            withContext(Dispatchers.IO) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            }
        } catch (error: IllegalArgumentException) {
            return InstallResult.Failed(
                "Could not share the update file (${error.message ?: "provider error"})."
            )
        }

        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                clipData = ClipData.newRawUri("NChat update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, APK_MIME)
                clipData = ClipData.newRawUri("NChat update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        val launched = withContext(Dispatchers.Main) {
            candidates.any { intent ->
                try {
                    context.startActivity(intent)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                } catch (_: SecurityException) {
                    false
                } catch (_: Exception) {
                    false
                }
            }
        }

        return if (launched) {
            InstallResult.Launched
        } else {
            InstallResult.Failed("No package installer is available on this device.")
        }
    }

    /** A manual route when GitHub's download endpoint is unavailable to the app. */
    fun openReleasePage(context: Context): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    private fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            // Some OEMs rename or restrict this screen; the user can still enable it in Settings.
        }
    }

    private fun hasZipHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            input.read() == 0x50 && input.read() == 0x4B
        }
    } catch (_: Exception) {
        false
    }

    /** Check package, exact advertised version, and signing compatibility before invoking Android. */
    private fun validateArchive(
        context: Context,
        file: File,
        expectedVersion: Int,
        expectedSigner: String
    ): String? {
      return try {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "The downloaded file is not a valid Android package."
        if (archive.packageName != context.packageName) return "The downloaded file is not an NChat update."
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        if (version != expectedVersion.toLong() || version <= BuildConfig.VERSION_CODE.toLong()) {
            return "The downloaded APK does not match the advertised update. Please try again later."
        }
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val current = currentSigners(installed)
        val incoming = currentSigners(archive)
        val expected = setOf(expectedSigner)
        if (incoming != expected) {
            return "The downloaded APK is not signed by NChat's verified release key. Expected SHA-256: ${expectedSigner}. Received: ${describeSigners(incoming)}. Please retry or use the official release page."
        }
        val history = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            currentSigners(archive.signingInfo?.signingCertificateHistory)
        } else emptySet()
        if (!compatibleUpdateSigners(current, incoming, history)) {
            return "This installed copy has a different signing key, so Android cannot update it in place. Installed SHA-256: ${describeSigners(current)}. Official update SHA-256: ${describeSigners(incoming)}. Do not uninstall; send these fingerprints to NChat support."
        }
        null
      } catch (_: Exception) {
          "Could not verify the downloaded update. Please try again."
      }
    }

    @Suppress("DEPRECATION")
    private fun currentSigners(info: PackageInfo): Set<String> {
        val legacy = currentSigners(info.signatures)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return legacy
        val modern = currentSigners(info.signingInfo?.apkContentsSigners)
        // Some vendor PackageManager implementations return a null/empty SigningInfo for
        // archives or installed packages even when the legacy field is populated.
        return preferredSignerSet(modern, legacy)
    }

    private fun currentSigners(signatures: Array<Signature>?): Set<String> = signatures.orEmpty()
        .map { certificateSha256(it.toByteArray()) }.toSet()

    private fun describeSigners(signers: Set<String>): String =
        signers.sorted().takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "unavailable"
}
