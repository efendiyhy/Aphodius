package id.sengkuni.aphodius

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/*
 * Nobar (nonton bareng): ruangan berbasis kode, maks 4 orang.
 * Sinkron putar + chat lewat Firebase Realtime Database (REST + SSE, tanpa SDK, paket Spark),
 * suara lewat WebRTC peer-to-peer dengan STUN publik.
 */

const val MAX_MEMBERS = 4
private const val API_KEY = "AIzaSyBT2ilMvsaGdfGeWYzWOFzr8OWzHC-jqXY"
private val DB_CANDIDATES = listOf(
    "https://ekinstru-default-rtdb.firebaseio.com",
    "https://ekinstru-default-rtdb.asia-southeast1.firebasedatabase.app",
    "https://ekinstru-default-rtdb.europe-west1.firebasedatabase.app",
)
private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private val SV = JSONObject().put(".sv", "timestamp")

data class NbInfo(val host: String, val filmId: String, val filmName: String, val ctl: String, val created: Long)
data class NbPb(val playing: Boolean, val pos: Long, val at: Long, val seq: Long, val by: String) {
    val stamp: String get() = "${seq}_$by"
}
data class NbMember(val uid: String, val name: String, val seen: Long, val voice: Boolean, val mic: Boolean)
data class NbMsg(val id: String, val uid: String, val name: String, val text: String)

/** Akses REST Firebase: login anonim, pencarian URL database, request biasa. */
class Fb(ctx: Context) {
    private val sp = ctx.getSharedPreferences("nobar_fb", Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var idToken = ""
    @Volatile private var exp = 0L
    @Volatile var uid: String = sp.getString("uid", "") ?: ""
        private set
    @Volatile private var dbUrl: String = sp.getString("db", "") ?: ""

    private fun call(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap(), timeout: Int = 15000): Pair<Int, String> {
        var last: java.io.IOException? = null
        for (i in 0 until 3) {
            try { return callOnce(method, url, body, headers, timeout) }
            catch (e: java.io.IOException) { last = e; Thread.sleep(400L * (i + 1)) }
        }
        val host = runCatching { URL(url).host }.getOrDefault("server")
        throw java.io.IOException("Koneksi ke $host gagal: ${last?.message}")
    }

    private fun callOnce(method: String, url: String, body: String?, headers: Map<String, String>, timeout: Int): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.setRequestProperty("Connection", "close")
            c.connectTimeout = timeout; c.readTimeout = timeout
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                if (!headers.containsKey("Content-Type")) c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally { c.disconnect() }
    }

    private fun signUp() {
        val (code, text) = call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY", """{"returnSecureToken":true}""")
        if (code != 200) throw IllegalStateException(if (text.contains("ADMIN_ONLY_OPERATION") || text.contains("OPERATION_NOT_ALLOWED")) "Login anonim Firebase belum diaktifkan" else "Gagal masuk ke server nobar")
        val j = JSONObject(text)
        idToken = j.getString("idToken"); uid = j.getString("localId")
        exp = System.currentTimeMillis() + j.optString("expiresIn", "3600").toLong() * 1000
        sp.edit().putString("refresh", j.getString("refreshToken")).putString("uid", uid).apply()
    }

    private fun refresh(rt: String): Boolean {
        val (code, text) = call(
            "POST", "https://securetoken.googleapis.com/v1/token?key=$API_KEY", "grant_type=refresh_token&refresh_token=$rt",
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        )
        if (code != 200) return false
        val j = JSONObject(text)
        idToken = j.getString("id_token"); uid = j.getString("user_id")
        exp = System.currentTimeMillis() + j.optString("expires_in", "3600").toLong() * 1000
        sp.edit().putString("refresh", j.optString("refresh_token", rt)).putString("uid", uid).apply()
        return true
    }

    suspend fun token(): String = lock.withLock {
        withContext(Dispatchers.IO) {
            if (idToken.isEmpty() || exp - System.currentTimeMillis() < 300_000) {
                val rt = sp.getString("refresh", "") ?: ""
                if (rt.isEmpty() || !refresh(rt)) signUp()
            }
            idToken
        }
    }

    private suspend fun db(): String {
        if (dbUrl.isNotEmpty()) return dbUrl
        return withContext(Dispatchers.IO) {
            for (c in DB_CANDIDATES) {
                val code = runCatching { call("GET", "$c/.json?shallow=true", null, timeout = 8000).first }.getOrDefault(-1)
                if (code == 200 || code == 401 || code == 403) { dbUrl = c; sp.edit().putString("db", c).apply(); return@withContext c }
            }
            throw IllegalStateException("Database nobar belum dibuat di Firebase")
        }
    }

    /** Request biasa ke Realtime Database. */
    suspend fun req(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), query: String = ""): Pair<Int, String> {
        val t = token(); val base = db()
        return withContext(Dispatchers.IO) { call(method, "$base/$path.json?auth=$t$query", body, headers) }
    }

    /** Buka aliran SSE (dipakai untuk mendengarkan perubahan ruangan). */
    suspend fun openStream(path: String): HttpURLConnection {
        val t = token(); val base = db()
        return withContext(Dispatchers.IO) {
            val c = URL("$base/$path.json?auth=$t").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "text/event-stream")
            c.connectTimeout = 15000; c.readTimeout = 75000
            c
        }
    }
}

/** Satu ruangan nobar yang sedang diikuti. */
class NobarRoom(private val fb: Fb, val code: String, private val nick: () -> String, private val ctx: Context, private val onEnd: (NobarRoom) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    private var beatJob: Job? = null
    private var root: JSONObject? = null
    private var offset = 0L
    private val handledSig = HashSet<String>()
    val me: String get() = fb.uid

    var info by mutableStateOf<NbInfo?>(null)
    var pb by mutableStateOf(NbPb(false, 0, 0, 0, ""))
    var members by mutableStateOf<List<NbMember>>(emptyList())
    var chat by mutableStateOf<List<NbMsg>>(emptyList())
    /** "connecting", "online", "offline", "closed" */
    var status by mutableStateOf("connecting")
    var voiceOn by mutableStateOf(false)
    var micOn by mutableStateOf(false)
    val peerState = mutableStateMapOf<String, String>()
    private var voice: VoiceEngine? = null
    private var joined = false

    val isHost: Boolean get() = info?.host == me
    fun canControl(): Boolean = isHost || info?.ctl == "all"
    fun serverNow(): Long = System.currentTimeMillis() + offset
    fun expected(): Long = (if (pb.playing) pb.pos + (serverNow() - pb.at) else pb.pos).coerceAtLeast(0)

    // ---------- siklus hidup ----------
    fun start() {
        streamJob = scope.launch { streamLoop() }
        beatJob = scope.launch { while (isActive) { heartbeat(); delay(10_000) } }
    }

    private fun memberJson(): JSONObject = JSONObject()
        .put("name", nick().ifBlank { "Tamu" }).put("seen", SV).put("voice", voiceOn).put("mic", voiceOn && micOn)

    private suspend fun heartbeat() {
        runCatching {
            val t0 = System.currentTimeMillis()
            val r = fb.req("PUT", "rooms/$code/members/$me", memberJson().toString())
            val t1 = System.currentTimeMillis()
            if (r.first == 200) {
                val sv = JSONObject(r.second).optLong("seen", 0L)
                if (sv > 0) offset = sv - (t0 + t1) / 2
                joined = true
            }
        }
    }

    /** Dipanggil saat bergabung: tulis data anggota pertama kali. Mengembalikan pesan galat atau null. */
    suspend fun enter(): String? {
        val t0 = System.currentTimeMillis()
        val r = fb.req("PUT", "rooms/$code/members/$me", memberJson().toString())
        val t1 = System.currentTimeMillis()
        if (r.first == 200) {
            val sv = JSONObject(r.second).optLong("seen", 0L)
            if (sv > 0) offset = sv - (t0 + t1) / 2
            joined = true
            return null
        }
        return if (r.first == 401 || r.first == 403) "Tidak bisa masuk ruangan (periksa aturan database)" else "Gagal bergabung (kode ${r.first})"
    }

    fun leave() {
        val v = voice; voice = null
        v?.stop()
        voiceOn = false
        streamJob?.cancel(); beatJob?.cancel()
        val host = isHost; val c = code; val u = me
        // pembersihan di thread terpisah supaya selesai walau scope dibatalkan
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    if (host) { fb.req("DELETE", "rooms/$c"); fb.req("DELETE", "roomIdx/$c") }
                    else fb.req("DELETE", "rooms/$c/members/$u")
                }
            }
        }.start()
        scope.coroutineContext[Job]?.cancel()
        status = "closed"
        onEnd(this)
    }

    // ---------- aliran data (SSE) ----------
    private suspend fun streamLoop() {
        while (scope.isActive) {
            try {
                val c = fb.openStream("rooms/$code")
                withContext(Dispatchers.IO) {
                    BufferedReader(InputStreamReader(c.inputStream)).use { rd ->
                        status = "online"
                        var ev = ""
                        while (true) {
                            val line = rd.readLine() ?: break
                            if (line.startsWith("event:")) ev = line.substring(6).trim()
                            else if (line.startsWith("data:")) handleEvent(ev, line.substring(5).trim())
                        }
                    }
                }
            } catch (e: Exception) { /* sambung ulang */ }
            if (status == "closed") return
            status = "offline"
            delay(1500)
        }
    }

    private fun handleEvent(ev: String, data: String) {
        if (ev == "keep-alive") return
        if (ev == "cancel" || ev == "auth_revoked") throw java.io.IOException(ev)
        if (ev != "put" && ev != "patch") return
        val j = runCatching { JSONObject(data) }.getOrNull() ?: return
        val path = j.optString("path", "/")
        val value = if (j.isNull("data")) null else j.get("data")
        if (ev == "put") {
            if (path == "/") {
                root = value as? JSONObject
                if (root == null && joined) { status = "closed"; onHostClosed(); throw java.io.IOException("closed") }
            } else setAt(path, value)
        } else {
            val obj = value as? JSONObject ?: return
            obj.keys().forEach { k -> setAt(("/" + path.trim('/') + "/" + k).replace("//", "/"), if (obj.isNull(k)) null else obj.get(k)) }
        }
        status = "online"
        parse()
    }

    private fun onHostClosed() {
        val v = voice; voice = null; v?.stop(); voiceOn = false
    }

    private fun setAt(path: String, value: Any?) {
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segs.isEmpty()) return
        var cur = root ?: JSONObject().also { root = it }
        for (i in 0 until segs.size - 1) {
            var n = cur.optJSONObject(segs[i])
            if (n == null) { n = JSONObject(); cur.put(segs[i], n) }
            cur = n
        }
        if (value == null) cur.remove(segs.last()) else cur.put(segs.last(), value)
    }

    private fun parse() {
        val r = root ?: return
        r.optJSONObject("info")?.let { i ->
            info = NbInfo(i.optString("host"), i.optString("film"), i.optString("filmName"), i.optString("ctl", "host"), i.optLong("created"))
        }
        r.optJSONObject("pb")?.let { p ->
            val n = NbPb(p.optBoolean("playing"), p.optLong("pos"), p.optLong("at"), p.optLong("seq"), p.optString("by"))
            if (n.stamp != pb.stamp || n.playing != pb.playing || n.pos != pb.pos) pb = n
        }
        val ms = ArrayList<NbMember>()
        r.optJSONObject("members")?.let { m ->
            m.keys().forEach { k ->
                m.optJSONObject(k)?.let { o -> ms.add(NbMember(k, o.optString("name", "Tamu"), o.optLong("seen"), o.optBoolean("voice"), o.optBoolean("mic"))) }
            }
        }
        val h = info?.host
        members = ms.sortedWith(compareBy({ it.uid != h }, { it.name.lowercase() }))
        val cs = ArrayList<NbMsg>()
        r.optJSONObject("chat")?.let { c ->
            c.keys().asSequence().sorted().toList().takeLast(120).forEach { k ->
                c.optJSONObject(k)?.let { o -> cs.add(NbMsg(k, o.optString("u"), o.optString("n"), o.optString("m"))) }
            }
        }
        if (cs.size != chat.size || cs.lastOrNull()?.id != chat.lastOrNull()?.id) chat = cs
        // sinyal suara untuk saya
        r.optJSONObject("sig")?.optJSONObject(me)?.let { sg ->
            sg.keys().asSequence().sorted().toList().forEach { k ->
                if (handledSig.add(k)) {
                    sg.optJSONObject(k)?.let { o -> voice?.onSignal(o.optString("from"), o.optString("type"), o.optString("data")) }
                    scope.launch { runCatching { fb.req("DELETE", "rooms/$code/sig/$me/$k") } }
                }
            }
        }
        voice?.sync(ms)
    }

    // ---------- aksi ----------
    fun publish(playing: Boolean, pos: Long) {
        val seq = pb.seq + 1
        pb = NbPb(playing, pos, serverNow(), seq, me)
        val body = JSONObject().put("playing", playing).put("pos", pos).put("at", SV).put("seq", seq).put("by", me).toString()
        scope.launch { runCatching { fb.req("PUT", "rooms/$code/pb", body) } }
    }

    fun setControl(all: Boolean) {
        if (!isHost) return
        scope.launch { runCatching { fb.req("PUT", "rooms/$code/info/ctl", "\"${if (all) "all" else "host"}\"") } }
        info = info?.copy(ctl = if (all) "all" else "host")
    }

    fun send(text: String) {
        val t = text.trim().take(300)
        if (t.isEmpty()) return
        val id = "${System.currentTimeMillis()}_${me.take(4)}"
        val body = JSONObject().put("u", me).put("n", nick().ifBlank { "Tamu" }).put("m", t).toString()
        scope.launch { runCatching { fb.req("PUT", "rooms/$code/chat/$id", body) } }
    }

    internal fun sendSig(to: String, type: String, data: String) {
        val id = "${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
        val body = JSONObject().put("from", me).put("type", type).put("data", data).toString()
        scope.launch { runCatching { fb.req("PUT", "rooms/$code/sig/$to/$id", body) } }
    }

    // ---------- suara ----------
    fun enableVoice() {
        if (voice != null) return
        voiceError = ""
        voiceOn = true; micOn = true
        try {
            val v = VoiceEngine(ctx, this)
            voice = v
            scope.launch { heartbeat(); v.start(members) }
        } catch (t: Throwable) { voiceFailed(t.message ?: t.javaClass.simpleName) }
    }

    fun toggleMic() {
        if (!voiceOn) return
        micOn = !micOn
        voice?.setMic(micOn)
        scope.launch { heartbeat() }
    }

    var voiceError by mutableStateOf("")
    internal fun voiceFailed(msg: String) {
        voiceError = "Suara gagal: $msg"
        voice = null; voiceOn = false; micOn = false
    }

    fun disableVoice() {
        val v = voice ?: return
        voice = null
        v.stop()
        voiceOn = false; micOn = false
        peerState.clear()
        scope.launch { heartbeat() }
    }
}

/** Mesh WebRTC audio untuk maks 4 orang. Semua operasi WebRTC berjalan di satu thread. */
class VoiceEngine(private val ctx: Context, private val room: NobarRoom) {
    private val rtc = Executors.newSingleThreadExecutor()
    private var factory: PeerConnectionFactory? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val peers = HashMap<String, PeerConnection>()
    private val remoteSet = HashSet<String>()
    private val pending = HashMap<String, MutableList<IceCandidate>>()
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    @Volatile private var stopped = false

    private fun post(block: () -> Unit) {
        if (rtc.isShutdown) return
        try { rtc.execute { try { block() } catch (_: Throwable) { } } } catch (_: Throwable) { }
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private val ice get() = listOf(
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )

    fun start(current: List<NbMember>) {
        post {
            runCatching {
                if (!inited) {
                    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
                    inited = true
                }
                val adm = JavaAudioDeviceModule.builder(ctx).createAudioDeviceModule()
                val f = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
                adm.release()
                factory = f
                val mc = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                }
                source = f.createAudioSource(mc)
                track = f.createAudioTrack("mic", source).also { it.setEnabled(room.micOn) }
                prevMode = am.mode; prevSpeaker = am.isSpeakerphoneOn
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = !hasHeadset()
                current.filter { it.uid != room.me && it.voice }.forEach { connect(it.uid) }
            }.onFailure { room.voiceFailed(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun hasHeadset(): Boolean = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }

    fun setMic(on: Boolean) { post { track?.setEnabled(on) } }

    private fun newPc(uid: String): PeerConnection? {
        val f = factory ?: return null
        val cfg = PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peers.remove(uid)?.close()
        remoteSet.remove(uid); pending.remove(uid)
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                try { room.sendSig(uid, "ice", JSONObject().put("mid", c.sdpMid).put("idx", c.sdpMLineIndex).put("c", c.sdp).toString()) } catch (_: Throwable) { }
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {
                room.peerState[uid] = when (s) {
                    PeerConnection.PeerConnectionState.CONNECTED -> "ok"
                    PeerConnection.PeerConnectionState.FAILED, PeerConnection.PeerConnectionState.DISCONNECTED -> "gagal"
                    PeerConnection.PeerConnectionState.CLOSED -> "tutup"
                    else -> "menyambung"
                }
            }
        }) ?: return null
        track?.let { pc.addTrack(it, listOf("s0")) }
        peers[uid] = pc
        room.peerState[uid] = "menyambung"
        return pc
    }

    /** Kita yang menawarkan koneksi (kita yang baru menyalakan suara). */
    private fun connect(uid: String) {
        val pc = newPc(uid) ?: return
        val mc = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) }
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                try { pc.setLocalDescription(Sdp(), d); room.sendSig(uid, "offer", d.description) } catch (_: Throwable) { }
            }
        }, mc)
    }

    fun onSignal(from: String, type: String, data: String) {
        post {
            if (stopped || track == null || from == room.me) return@execute
            runCatching {
                when (type) {
                    "offer" -> {
                        val ex = peers[from]
                        // tabrakan penawaran: uid yang lebih kecil menang sebagai penawar
                        if (ex != null && ex.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER && room.me < from) return@execute
                        val pc = newPc(from) ?: return@execute
                        pc.setRemoteDescription(object : Sdp() {
                            override fun onSetSuccess() {
                                post {
                                    remoteSet.add(from); flush(from, pc)
                                    pc.createAnswer(object : Sdp() {
                                        override fun onCreateSuccess(d: SessionDescription?) {
                                            if (d == null) return
                                            try { pc.setLocalDescription(Sdp(), d); room.sendSig(from, "answer", d.description) } catch (_: Throwable) { }
                                        }
                                    }, MediaConstraints())
                                }
                            }
                        }, SessionDescription(SessionDescription.Type.OFFER, data))
                    }
                    "answer" -> {
                        val pc = peers[from] ?: return@execute
                        pc.setRemoteDescription(object : Sdp() {
                            override fun onSetSuccess() { post { remoteSet.add(from); flush(from, pc) } }
                        }, SessionDescription(SessionDescription.Type.ANSWER, data))
                    }
                    "ice" -> {
                        val o = JSONObject(data)
                        val cand = IceCandidate(o.optString("mid"), o.optInt("idx"), o.optString("c"))
                        val pc = peers[from]
                        if (pc != null && remoteSet.contains(from)) pc.addIceCandidate(cand)
                        else pending.getOrPut(from) { ArrayList() }.add(cand)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun flush(uid: String, pc: PeerConnection) {
        pending.remove(uid)?.forEach { pc.addIceCandidate(it) }
    }

    /** Tutup koneksi ke anggota yang sudah keluar atau mematikan suara. */
    fun sync(members: List<NbMember>) {
        post {
            if (stopped) return@execute
            val live = members.filter { it.voice && it.uid != room.me }.map { it.uid }.toSet()
            peers.keys.filter { it !in live }.forEach { k ->
                peers.remove(k)?.close(); remoteSet.remove(k); pending.remove(k); room.peerState.remove(k)
            }
        }
    }

    fun stop() {
        stopped = true
        post {
            runCatching { peers.values.forEach { it.close() }; peers.clear() }
            runCatching { track?.dispose() }
            runCatching { source?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { am.mode = prevMode; am.isSpeakerphoneOn = prevSpeaker }
            rtc.shutdown()
        }
    }

    companion object { @Volatile private var inited = false }
}

/** Pintu masuk fitur nobar untuk UI. */
class Nobar(ctx: Context) {
    private val app = ctx.applicationContext
    val fb = Fb(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var room by mutableStateOf<NobarRoom?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf("")

    private fun gen(): String = (1..6).map { CODE_CHARS.random() }.joinToString("")

    private fun make(code: String, nick: () -> String): NobarRoom = NobarRoom(fb, code, nick, app) { r -> if (room === r) room = null }

    suspend fun create(film: Film, nick: () -> String) {
        if (busy) return
        busy = true; error = ""
        try {
            val me = fb.token().let { fb.uid }
            var made: String? = null
            for (i in 0 until 6) {
                val code = gen()
                val body = JSONObject()
                    .put("info", JSONObject().put("host", me).put("created", SV).put("film", film.id).put("filmName", film.name).put("ctl", "host"))
                    .put("pb", JSONObject().put("playing", false).put("pos", 0).put("at", SV).put("seq", 0).put("by", me))
                    .put("members", JSONObject().put(me, JSONObject().put("name", nick().ifBlank { "Host" }).put("seen", SV).put("voice", false).put("mic", false)))
                val r = fb.req("PUT", "rooms/$code", body.toString(), mapOf("If-Match" to "null_etag"))
                if (r.first == 200) { made = code; break }
                if (r.first != 412) { error = "Server nobar menolak (kode ${r.first}): ${r.second.take(160)}"; return }
            }
            if (made == null) { if (error.isEmpty()) error = "Gagal membuat ruangan, coba lagi"; return }
            runCatching { fb.req("PUT", "roomIdx/$made", System.currentTimeMillis().toString()) }
            val r = make(made, nick)
            room = r
            r.start()
            scope.launch { cleanup() }
        } catch (e: Exception) {
            error = e.message ?: "Gagal membuat ruangan"
        } finally { busy = false }
    }

    suspend fun join(codeRaw: String, nick: () -> String) {
        if (busy) return
        val code = codeRaw.trim().uppercase()
        if (code.length != 6) { error = "Kode terdiri dari 6 karakter"; return }
        busy = true; error = ""
        try {
            val info = fb.req("GET", "rooms/$code/info")
            if (info.first != 200 || info.second == "null") { error = "Kode ruangan tidak ditemukan"; return }
            val created = JSONObject(info.second).optLong("created")
            if (created > 0 && System.currentTimeMillis() - created > 24 * 3600_000L) { error = "Ruangan sudah kedaluwarsa"; return }
            // buang anggota basi supaya slot tidak terkunci
            val ms = fb.req("GET", "rooms/$code/members")
            if (ms.first == 200 && ms.second != "null") {
                val j = JSONObject(ms.second)
                val now = System.currentTimeMillis()
                val keys = j.keys().asSequence().toList()
                keys.filter { k -> now - (j.optJSONObject(k)?.optLong("seen") ?: 0L) > 90_000 && k != fb.uid }
                    .forEach { k -> runCatching { fb.req("DELETE", "rooms/$code/members/$k") } }
                val alive = keys.count { k -> now - (j.optJSONObject(k)?.optLong("seen") ?: 0L) <= 90_000 && k != fb.uid }
                if (alive >= MAX_MEMBERS) { error = "Ruangan penuh (maks $MAX_MEMBERS orang)"; return }
            }
            val r = make(code, nick)
            val err = r.enter()
            if (err != null) { error = err; return }
            room = r
            r.start()
        } catch (e: Exception) {
            error = e.message ?: "Gagal bergabung"
        } finally { busy = false }
    }

    fun leave() { room?.leave(); room = null }

    /** Hapus ruangan lama (>24 jam) yang tertinggal. */
    private suspend fun cleanup() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            val r = fb.req("GET", "roomIdx", null, emptyMap(), "&orderBy=%22%24value%22&endAt=$cutoff&limitToFirst=10")
            if (r.first == 200 && r.second != "null") {
                val j = JSONObject(r.second)
                j.keys().forEach { k -> fb.req("DELETE", "rooms/$k"); fb.req("DELETE", "roomIdx/$k") }
            }
        }
    }
}
