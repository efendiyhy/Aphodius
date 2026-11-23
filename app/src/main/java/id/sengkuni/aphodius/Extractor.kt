package id.sengkuni.aphodius

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoVariant(val url: String, val height: Int, val pixels: Int, val hls: Boolean) {
    val label: String get() = if (hls) "Otomatis (HLS)" else "${height}p"
}

/**
 * Mengambil URL video langsung (video.twimg.com) dari link tweet X/Twitter.
 * Mencoba beberapa layanan publik berurutan; yang pertama menghasilkan video dipakai.
 */
object Extractor {
    private val idRe = Regex("status(?:es)?/(\\d+)")
    private val videoRe = Regex("https://video\\.twimg\\.com/[^\"'\\s\\\\<>)]+")
    private val resRe = Regex("/(\\d{2,5})x(\\d{2,5})/")

    fun tweetId(link: String): String? = idRe.find(link)?.groupValues?.get(1)

    fun parseVariants(raw: String): List<VideoVariant> {
        val text = raw.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&")
        val urls = videoRe.findAll(text).map { it.value.trimEnd(',', ';', '.') }.toList()
        val out = LinkedHashMap<String, VideoVariant>()
        for (u in urls) {
            val path = u.substringBefore('?')
            val hls = path.endsWith(".m3u8")
            val mp4 = path.endsWith(".mp4")
            if (!hls && !mp4) continue
            val m = resRe.find(path)
            val w = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val v = VideoVariant(u, minOf(w, h), w * h, hls)
            // satu entri per path (abaikan duplikat dengan query berbeda)
            if (path !in out) out[path] = v
        }
        val list = out.values.toList()
        val mp4s = list.filter { !it.hls }.sortedByDescending { it.pixels }
        val hls = list.filter { it.hls }
        return if (mp4s.isNotEmpty()) mp4s else hls
    }

    private fun syndicationToken(id: String): String {
        val x = id.toDouble() / 1e15 * Math.PI
        var ip = x.toLong()
        var fp = x - ip
        val sb = StringBuilder(java.lang.Long.toString(ip, 36))
        var n = 0
        while (fp > 0 && n < 12) {
            fp *= 36
            val d = fp.toInt()
            sb.append(Character.forDigit(d, 36))
            fp -= d
            n++
        }
        return sb.toString().replace("0", "").replace(".", "")
    }

    suspend fun extract(link: String, onStage: (Int, Int) -> Unit = { _, _ -> }): Result<List<VideoVariant>> = withContext(Dispatchers.IO) {
        val id = tweetId(link) ?: return@withContext Result.failure(IllegalArgumentException("Link X tidak valid"))
        val sources = listOf(
            "https://api.fxtwitter.com/status/$id",
            "https://api.vxtwitter.com/Twitter/status/$id",
            "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${syndicationToken(id)}",
        )
        var reached = false
        val merged = LinkedHashMap<String, VideoVariant>()
        for ((si, src) in sources.withIndex()) {
            onStage(si + 1, sources.size)
            try {
                val body = httpGet(src, 12000)
                reached = true
                for (v in parseVariants(body)) merged.putIfAbsent(v.url.substringBefore('?'), v)
                // cukup bila sudah ada >= 2 pilihan kualitas; jika tidak, coba sumber berikutnya untuk melengkapi
                if (merged.values.count { !it.hls } >= 2) break
            } catch (e: Exception) {
                if (e.message?.startsWith("HTTP") == true) reached = true
            }
        }
        if (merged.isNotEmpty()) {
            val all = merged.values.toList()
            val mp4 = all.filter { !it.hls }.sortedByDescending { it.pixels }
            return@withContext Result.success(if (mp4.isNotEmpty()) mp4 else all)
        }
        Result.failure(
            Exception(if (reached) "Tweet ini tidak berisi video (atau sudah dihapus/privat)." else "Tidak bisa menghubungi server. Periksa koneksi internet.")
        )
    }
}
