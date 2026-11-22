package id.sengkuni.aphodius

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val pageUrl: String, val size: Long)

/** Cek rilis terbaru di GitHub (repo Aphodius public), unduh APK, lalu pasang. */
object Updater {
    private const val API = "https://api.github.com/repos/efendiyhy/Aphodius/releases/latest"

    private fun parts(v: String): List<Int> =
        v.removePrefix("v").substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Mengembalikan info update bila ada versi lebih baru, null bila sudah terbaru atau gagal. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(httpGet(API))
            val tag = o.optString("tag_name").removePrefix("v")
            if (tag.isBlank() || !isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null
            val assets = o.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return@withContext UpdateInfo(
                        version = tag,
                        notes = o.optString("body", "").trim(),
                        apkUrl = a.optString("browser_download_url"),
                        pageUrl = o.optString("html_url"),
                        size = a.optLong("size", 0L),
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Aphodius-${info.version}.apk")
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 30000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
            c.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var n = input.read(buf)
                    while (n >= 0) {
                        os.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        n = input.read(buf)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        out
    }

    /** true = penginstal sistem dibuka; false = perlu izin "pasang dari sumber ini" dulu. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
