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
    private val gate = Semaphore(3)
    private val urlRe = Regex("\"thumbnail_url\"\\s*:\\s*\"([^\"]+)\"")
    private val tailRe = Regex("(https://pbs\\.twimg\\.com/(?:ext_tw_video_thumb|amplify_video_thumb|tweet_video_thumb)/[^\"'\\s\\\\<>)]+)")

    suspend fun get(ctx: Context, link: String): String? {
        val id = Extractor.tweetId(link) ?: return null
        mem[id]?.let { return it.ifEmpty { null } }
        val sp = ctx.applicationContext.getSharedPreferences("thumbs", Context.MODE_PRIVATE)
        sp.getString(id, null)?.let { mem[id] = it; return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                var found: String? = null
                for (src in listOf("https://api.fxtwitter.com/status/$id", "https://api.vxtwitter.com/Twitter/status/$id")) {
                    try {
                        val body = httpGet(src, 12000).replace("\\/", "/")
                        found = urlRe.find(body)?.groupValues?.get(1) ?: tailRe.find(body)?.groupValues?.get(1)
                        if (found != null) break
                    } catch (_: Exception) { }
                }
                if (found != null) { mem[id] = found; sp.edit().putString(id, found).apply() }
                found
            }
        }
    }
}
