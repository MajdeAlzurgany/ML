package ly.majde.gtatranslator.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ly.majde.gtatranslator.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val pageUrl: String,
    val sizeBytes: Long,
    /** "What's new" text from the GitHub release. */
    val notes: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(
        val release: AppRelease,
        val progress: Float,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
    ) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val apk: Uri) : UpdateState
    data class Failed(val release: AppRelease?) : UpdateState
    /** The new APK is signed with another key: Android would refuse it, a manual reinstall is needed. */
    data class SignatureMismatch(val release: AppRelease) : UpdateState
}

/**
 * Checks GitHub Releases for a newer build and installs it in-app.
 * Downloads with the app's own HTTP client (the system DownloadManager can silently wait
 * for Wi-Fi on mobile data), shows real progress, resumes after a dropped connection,
 * and checks the signing key before handing the APK to the installer.
 */
class UpdateManager(private val context: Context, http: OkHttpClient) {

    private val apiClient = http
    private val downloadClient = http.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /**
     * Looks for a newer release. [minIntervalMs] avoids hammering GitHub when the app is
     * opened repeatedly; [userInitiated] shows errors instead of failing silently.
     */
    suspend fun check(minIntervalMs: Long = 0L, userInitiated: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!userInitiated && now - prefs.getLong(K_LAST_CHECK, 0L) < minIntervalMs) return
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.ReadyToInstall) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val latest = fetchLatest()
            prefs.edit().putLong(K_LAST_CHECK, now).apply()
            if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(latest) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (userInitiated) UpdateState.Failed(null) else UpdateState.Idle
        }
    }

    suspend fun downloadAndInstall(release: AppRelease) {
        _state.value = UpdateState.Downloading(release, 0f, 0L, release.sizeBytes)
        val file = try {
            download(release)
        } catch (e: CancellationException) {
            _state.value = UpdateState.Available(release)
            throw e
        } catch (e: Exception) {
            null
        }
        if (file == null) {
            _state.value = UpdateState.Failed(release)
            return
        }
        if (sameSigner(file) == false) {
            _state.value = UpdateState.SignatureMismatch(release)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        _state.value = UpdateState.ReadyToInstall(release, uri)
        install(uri)
    }

    /** Opens the system installer; asks for "install unknown apps" permission first if needed. */
    fun install(apk: Uri): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apk, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Fallback: let the browser download the APK. */
    fun openInBrowser(release: AppRelease) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private suspend fun fetchLatest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=20")
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        apiClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val arr = JSONArray(resp.body?.string().orEmpty())
            var best: AppRelease? = null
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val tag = r.optString("tag_name")
                if (!tag.startsWith(TAG_PREFIX)) continue
                val versionName = tag.removePrefix(TAG_PREFIX)
                val code = versionName.substringAfterLast('.').toIntOrNull() ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val a = assets.optJSONObject(j) ?: continue
                    if (!a.optString("name").endsWith(".apk")) continue
                    if (best == null || code > best.versionCode) {
                        best = AppRelease(
                            versionCode = code,
                            versionName = versionName,
                            apkUrl = a.optString("browser_download_url"),
                            pageUrl = r.optString("html_url"),
                            sizeBytes = a.optLong("size"),
                            notes = r.optString("body").trim(),
                        )
                    }
                }
            }
            best
        }
    }

    /** Downloads to the app cache with progress; resumes with an HTTP Range request after drops. */
    private suspend fun download(release: AppRelease): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { if (!it.name.startsWith(release.versionName)) it.delete() }
        val part = File(dir, "${release.versionName}.apk.part")
        val done = File(dir, "${release.versionName}.apk")
        if (done.exists() && (release.sizeBytes <= 0L || done.length() == release.sizeBytes)) return@withContext done

        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                val existing = if (part.exists()) part.length() else 0L
                val request = Request.Builder().url(release.apkUrl).apply {
                    if (existing > 0L) header("Range", "bytes=$existing-")
                }.build()
                downloadClient.newCall(request).execute().use { resp ->
                    if (resp.code == 416) {
                        part.delete()
                        throw IOException("Range not satisfiable")
                    }
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("Empty body")
                    val append = resp.code == 206 && existing > 0L
                    val length = body.contentLength()
                    val total = when {
                        append && length > 0L -> existing + length
                        length > 0L -> length
                        else -> release.sizeBytes
                    }
                    var written = if (append) existing else 0L
                    var lastEmit = 0L
                    FileOutputStream(part, append).use { out ->
                        val input = body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastEmit > 250L) {
                                lastEmit = now
                                val progress = if (total > 0L) (written.toFloat() / total).coerceIn(0f, 1f) else 0f
                                _state.value = UpdateState.Downloading(release, progress, written, total)
                            }
                        }
                    }
                    if (total > 0L && written < total) throw IOException("Incomplete download")
                }
                if (!part.renameTo(done)) {
                    part.copyTo(done, overwrite = true)
                    part.delete()
                }
                return@withContext done
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Weak network: wait a little and continue from where it stopped.
                delay(1_500L * attempt)
            }
        }
        null
    }

    /** True if the downloaded APK is signed with the same key as this app (null = unknown). */
    private fun sameSigner(apk: File): Boolean? = try {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val a = archive?.let { signers(it) }.orEmpty()
        val b = signers(installed)
        if (a.isEmpty() || b.isEmpty()) null else a == b
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
        } else {
            info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        }

    companion object {
        const val REPO = "MajdeAlzurgany/ML"
        private const val TAG_PREFIX = "gta6-v"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val K_LAST_CHECK = "last_check"
        private const val MAX_ATTEMPTS = 6
    }
}
