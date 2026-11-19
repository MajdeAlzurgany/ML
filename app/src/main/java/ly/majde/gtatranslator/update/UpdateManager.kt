package ly.majde.gtatranslator.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    data class ReadyToInstall(val release: AppRelease, val apk: Uri) : UpdateState
    data class Failed(val release: AppRelease?) : UpdateState
}

/**
 * Checks GitHub Releases for a newer build and installs it in-app:
 * download with the system DownloadManager, then hand the APK to the package installer.
 */
class UpdateManager(private val context: Context, private val http: OkHttpClient) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(K_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val latest = fetchLatest()
            prefs.edit().putLong(K_LAST_CHECK, now).apply()
            if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(latest) else UpdateState.UpToDate
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (force) UpdateState.Failed(null) else UpdateState.Idle
        }
    }

    suspend fun downloadAndInstall(release: AppRelease) {
        _state.value = UpdateState.Downloading(release, 0f)
        val uri = try {
            download(release)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (uri == null) {
            _state.value = UpdateState.Failed(release)
            return
        }
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
        http.newCall(request).execute().use { resp ->
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
                    val name = a.optString("name")
                    if (!name.endsWith(".apk")) continue
                    if (best == null || code > best.versionCode) {
                        best = AppRelease(
                            versionCode = code,
                            versionName = versionName,
                            apkUrl = a.optString("browser_download_url"),
                            pageUrl = r.optString("html_url"),
                            sizeBytes = a.optLong("size"),
                        )
                    }
                }
            }
            best
        }
    }

    private suspend fun download(release: AppRelease): Uri? {
        val dm = context.getSystemService(DownloadManager::class.java) ?: return null
        val fileName = "gta6-translator-${release.versionName}.apk"
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(fileName)?.delete()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("مترجم GTA VI ${release.versionName}")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = dm.enqueue(request)
        while (true) {
            delay(400)
            val cursor = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
            cursor.use { c ->
                if (!c.moveToFirst()) return null
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> return dm.getUriForDownloadedFile(id)
                    DownloadManager.STATUS_FAILED -> return null
                    else -> {
                        val size = if (total > 0) total else release.sizeBytes
                        val progress = if (size > 0) (done.toFloat() / size).coerceIn(0f, 1f) else 0f
                        _state.value = UpdateState.Downloading(release, progress)
                    }
                }
            }
        }
    }

    companion object {
        const val REPO = "MajdeAlzurgany/ML"
        private const val TAG_PREFIX = "gta6-v"
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val K_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L
    }
}
