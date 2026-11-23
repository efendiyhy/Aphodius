package id.sengkuni.aphodius

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Poster pengganti: thumbnail video dari tweet X, dipakai bila film tidak punya poster. */
object Thumbs {
    var enabled by mutableStateOf(true)
    private val mem = ConcurrentHashMap<String, String>()
    private val gate = Semaphore(2)
    private val urlRe = Regex("\"thumbnail_url\"\\s*:\\s*\"([^\"]+)\"")
    private val tailRe = Regex("(https://pbs\\.twimg\\.com/(?:ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb)/[^\"'\\s\\\\<>)]+)")

    /** Satu frame dari tengah video (bukan awal), disimpan sebagai berkas kecil di cache. */
    private fun midFrame(ctx: Context, id: String, url: String): String? {
        val dir = java.io.File(ctx.cacheDir, "thumbs").apply { mkdirs() }
        val out = java.io.File(dir, "${id}_mid.jpg")
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(url, HashMap())
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (dur < 4000) return null
            val bmp = r.getFrameAtTime(dur / 2 * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val w = 360
            val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            out.outputStream().use { small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            "file://${out.absolutePath}"
        } catch (_: Throwable) { null } finally { runCatching { r.release() } }
    }

    suspend fun get(ctx: Context, link: String): String? {
        val id = Extractor.tweetId(link) ?: return null
        mem[id]?.let { return it.ifEmpty { null } }
        val cached = java.io.File(ctx.cacheDir, "thumbs/${id}_mid.jpg")
        if (cached.exists() && cached.length() > 0) { val u = "file://${cached.absolutePath}"; mem[id] = u; return u }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                // 1) frame tengah dari varian mp4 terkecil (hemat data)
                val variants = Extractor.extract(link).getOrNull().orEmpty()
                val mp4 = variants.filter { !it.hls }.minByOrNull { it.pixels }
                var found: String? = mp4?.let { midFrame(ctx, id, it.url) }
                // 2) cadangan: thumbnail bawaan X (frame awal)
                if (found == null) {
                    for (src in listOf("https://api.fxtwitter.com/status/$id", "https://api.vxtwitter.com/Twitter/status/$id")) {
                        try {
                            val body = httpGet(src, 12000).replace("\\/", "/")
                            found = urlRe.find(body)?.groupValues?.get(1) ?: tailRe.find(body)?.groupValues?.get(1)
                            if (found != null) break
                        } catch (_: Exception) { }
                    }
                }
                if (found != null) mem[id] = found
                found
            }
        }
    }
}
