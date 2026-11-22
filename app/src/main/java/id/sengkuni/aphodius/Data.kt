package id.sengkuni.aphodius

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Data film disimpan web Master Stream di repo master-stream-data (harus berstatus public agar bisa dibaca aplikasi).
val DATA_URLS = listOf(
    "https://raw.githubusercontent.com/efendiyhy/master-stream-data/main/data.json",
)
const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

data class Film(
    val id: String,
    val link: String,
    val name: String,
    val year: Int?,
    val genres: List<String>,
    val poster: String,
    val added: Long,
)

data class Prog(val pos: Long, val dur: Long, val ts: Long) {
    val frac: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
}

fun httpGet(url: String, timeoutMs: Int = 15000): String {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json,text/plain,*/*")
        c.setRequestProperty("Cache-Control", "no-cache")
        val code = c.responseCode
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally {
        c.disconnect()
    }
}

fun parseFilms(text: String): Pair<List<Film>, List<String>> {
    val o = JSONObject(text)
    val arr = o.optJSONArray("films") ?: JSONArray()
    val films = ArrayList<Film>()
    for (i in 0 until arr.length()) {
        val f = arr.optJSONObject(i) ?: continue
        val link = f.optString("link", "")
        val name = f.optString("name", "")
        if (link.isBlank() || name.isBlank()) continue
        val g = ArrayList<String>()
        f.optJSONArray("genres")?.let { ga -> for (j in 0 until ga.length()) g.add(ga.optString(j)) }
        val poster = if (f.isNull("poster")) "" else f.optString("poster", "")
        films.add(
            Film(
                id = f.optString("id").ifBlank { link },
                link = link,
                name = name,
                year = if (f.isNull("year") || !f.has("year")) null else f.optInt("year").takeIf { it > 0 },
                genres = g,
                poster = poster,
                added = f.optLong("added", 0L),
            )
        )
    }
    val genres = ArrayList<String>()
    o.optJSONArray("genres")?.let { ga -> for (j in 0 until ga.length()) genres.add(ga.optString(j)) }
    return films to genres
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("aphodius", Context.MODE_PRIVATE)
    var cachedJson: String?
        get() = sp.getString("cache", null)
        set(v) { sp.edit().putString("cache", v).apply() }
    var quality: Int
        get() = sp.getInt("quality", 0)
        set(v) { sp.edit().putInt("quality", v).apply() }
    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) { sp.edit().putFloat("speed", v).apply() }
    /** Persen bagian atas video yang dipotong (menyembunyikan banner/iklan di atas video). */
    var cropTop: Int
        get() = sp.getInt("cropTop", 8)
        set(v) { sp.edit().putInt("cropTop", v).apply() }
    var nickname: String
        get() = sp.getString("nickname", "") ?: ""
        set(v) { sp.edit().putString("nickname", v).apply() }
    var pinSalt: String
        get() = sp.getString("pinSalt", "") ?: ""
        set(v) { sp.edit().putString("pinSalt", v).apply() }
    var pinHash: String
        get() = sp.getString("pinHash", "") ?: ""
        set(v) { sp.edit().putString("pinHash", v).apply() }
    var biometric: Boolean
        get() = sp.getBoolean("biometric", false)
        set(v) { sp.edit().putBoolean("biometric", v).apply() }
    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }
    fun favs(): Set<String> = sp.getStringSet("favs", emptySet())!!.toSet()
    fun saveFavs(s: Set<String>) { sp.edit().putStringSet("favs", s).apply() }
    fun progress(): Map<String, Prog> {
        val o = JSONObject(sp.getString("progress", "{}") ?: "{}")
        val m = HashMap<String, Prog>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            m[k] = Prog(a.optLong(0), a.optLong(1), a.optLong(2))
        }
        return m
    }
    fun saveProgress(m: Map<String, Prog>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONArray().put(v.pos).put(v.dur).put(v.ts))
        sp.edit().putString("progress", o.toString()).apply()
    }
}

class AppState(ctx: Context) {
    val store = Store(ctx)
    var films by mutableStateOf<List<Film>>(emptyList())
    var genres by mutableStateOf<List<String>>(emptyList())
    var loaded by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var netError by mutableStateOf(false)
    var lastError by mutableStateOf("")
    var lastRefresh = 0L
    var favs by mutableStateOf(store.favs())
    val progress = mutableStateMapOf<String, Prog>().also { it.putAll(store.progress()) }
    var quality by mutableStateOf(store.quality)
    var speed by mutableStateOf(store.speed)
    var cropTop by mutableStateOf(store.cropTop)
    var nickname by mutableStateOf(store.nickname)
    var lockEnabled by mutableStateOf(store.pinHash.isNotEmpty())
    var biometric by mutableStateOf(store.biometric)
    var update by mutableStateOf<UpdateInfo?>(null)

    fun find(id: String): Film? = films.firstOrNull { it.id == id }

    private fun apply(text: String): Boolean = try {
        val (f, g) = parseFilms(text)
        films = f.sortedByDescending { it.added }
        genres = g
        loaded = true
        true
    } catch (e: Exception) {
        false
    }

    fun loadCache() { store.cachedJson?.let { apply(it) } }

    /** Ambil data terbaru dari web Master Stream. Mencoba beberapa alamat; hasil terakhir yang valid dipakai. */
    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        var err = ""
        try {
            val t = System.currentTimeMillis()
            for (base in DATA_URLS) {
                try {
                    val text = withContext(Dispatchers.IO) { httpGet("$base?t=$t") }.trim()
                    if (!text.startsWith("{")) { err = "Respons bukan JSON dari ${base.substringAfter("//").substringBefore("/")}"; continue }
                    if (apply(text)) {
                        store.cachedJson = text
                        netError = false
                        lastError = ""
                        lastRefresh = System.currentTimeMillis()
                        return
                    } else err = "Format data tidak dikenali"
                } catch (e: Exception) {
                    err = "${base.substringAfter("//").substringBefore("/")}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            netError = true
            lastError = err
        } finally {
            refreshing = false
        }
    }

    fun toggleFav(id: String) {
        favs = if (id in favs) favs - id else favs + id
        store.saveFavs(favs)
    }

    fun saveProgress(id: String, pos: Long, dur: Long) {
        if (dur <= 0) return
        progress[id] = Prog(pos, dur, System.currentTimeMillis())
        store.saveProgress(progress.toMap())
    }

    fun setQualityPref(q: Int) { quality = q; store.quality = q }
    fun setSpeedPref(s: Float) { speed = s; store.speed = s }
    fun setCropPref(c: Int) { cropTop = c; store.cropTop = c }
    fun setNickname(n: String) { nickname = n.trim(); store.nickname = nickname }
    fun setBiometricPref(b: Boolean) { biometric = b; store.biometric = b }

    fun setPin(pin: String) {
        val salt = java.util.UUID.randomUUID().toString()
        store.pinSalt = salt
        store.pinHash = hashPin(salt, pin)
        lockEnabled = true
    }
    fun checkPin(pin: String): Boolean = store.pinHash.isNotEmpty() && hashPin(store.pinSalt, pin) == store.pinHash
    fun clearPin() { store.pinHash = ""; store.pinSalt = ""; lockEnabled = false; setBiometricPref(false) }
    private fun hashPin(salt: String, pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
