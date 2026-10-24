package com.aniplay.core.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import android.content.pm.ApplicationInfo
import com.aniplay.core.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * App 內更新：讀 GitHub 最新 Release，tag 為 v1.0.<versionCode>（CI 的 run_number）。
 */
object Updater {
    private const val LATEST_API = "https://api.github.com/repos/DLIN131/AniPlay/releases/latest"
    private const val RELEASES_API = "https://api.github.com/repos/DLIN131/AniPlay/releases?per_page=50"

    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    /** 更新紀錄的一筆（一個版本） */
    data class Note(val versionCode: Int, val version: String, val date: String, val notes: String)

    /** 剛更新完第一次開啟時設定，主畫面會跳出「這次更新了什麼」 */
    var whatsNew by mutableStateOf<Note?>(null)

    /** 有新版本時設定，主畫面會跳出更新視窗 */
    var pending by mutableStateOf<Release?>(null)

    /** 同一個 Release 裡有手機版和電視版，各 App 啟動時設定自己要下載的檔名 */
    var apkName = "AniPlay.apk"

    @Suppress("DEPRECATION")
    private val packageInfo by lazy { App.instance.packageManager.getPackageInfo(App.instance.packageName, 0) }

    @Suppress("DEPRECATION")
    val currentVersionCode: Int get() = packageInfo.versionCode
    val currentVersionName: String get() = "v" + packageInfo.versionName

    /** debug 版的 versionCode 固定是 1，不自動檢查更新 */
    val isDebugBuild: Boolean
        get() = App.instance.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(LATEST_API).header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            val tag = o.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@use null
            val assets = o.optJSONArray("assets")
            var url: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == apkName) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
            url?.let { Release(code, o.optString("name").ifEmpty { tag }, cleanNotes(o.optString("body")), it) }
        }
    }

    /** 所有版本的更新內容，新的在前面（就是每個 GitHub Release 的說明） */
    suspend fun fetchChangelog(): List<Note> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(RELEASES_API).header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val arr = org.json.JSONArray(r.body!!.string())
            List(arr.length()) { arr.getJSONObject(it) }.mapNotNull { o ->
                val tag = o.optString("tag_name")
                val code = tag.substringAfterLast('.').toIntOrNull() ?: return@mapNotNull null
                Note(code, tag, o.optString("published_at").take(10), cleanNotes(o.optString("body")))
            }.sortedByDescending { it.versionCode }
        }
    }

    /**
     * 開啟時呼叫：版本比上次開啟時新，就把這個版本的更新內容放進 [whatsNew]。
     * 第一次安裝不顯示（沒有「上次」可以比較）。
     */
    suspend fun checkWhatsNew() {
        if (isDebugBuild) return
        val last = com.aniplay.core.Prefs.lastSeenVersion
        val current = currentVersionCode
        if (last == current) return
        com.aniplay.core.Prefs.lastSeenVersion = current
        if (last == 0) return
        whatsNew = runCatching { fetchChangelog() }.getOrNull()?.firstOrNull { it.versionCode == current }
    }

    /** 發布說明裡 `---` 以下是固定的下載資訊，Co-Authored-By 也不用給使用者看 */
    private fun cleanNotes(body: String) = body
        .substringBefore("\n---")
        .lines()
        .filterNot { it.startsWith("Co-Authored-By") }
        .joinToString("\n")
        .trim()

    /** 有比目前新的版本才回傳 */
    suspend fun check(): Release? = fetchLatest()?.takeIf { it.versionCode > currentVersionCode }

    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, apkName.removeSuffix(".apk") + "-${release.versionCode}.apk")
            client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val body = r.body!!
                val total = body.contentLength()
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPercent = -1
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = (read * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
            }
            file
        }

    /** Android 8 以上要使用者允許本 App「安裝不明應用程式」 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
