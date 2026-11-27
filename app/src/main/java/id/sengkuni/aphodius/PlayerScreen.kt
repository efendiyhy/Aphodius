package id.sengkuni.aphodius

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun chooseVariant(list: List<VideoVariant>, q: Int): VideoVariant? {
    val mp4 = list.filter { !it.hls }.sortedByDescending { it.pixels }
    if (mp4.isEmpty()) return list.firstOrNull()
    if (q == 0) return mp4.first()
    return mp4.firstOrNull { it.height <= q } ?: mp4.last()
}

private val resizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "Sesuai layar",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Isi layar",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "Regangkan",
)
private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
fun PlayerScreen(film: Film, next: Film?, s: AppState, onBack: () -> Unit, onNext: (Film) -> Unit, room: NobarRoom? = null) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()

    // Landscape + layar penuh selama memutar
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            activity?.window?.let { w ->
                val a = w.attributes
                a.screenBrightness = -1f
                w.attributes = a
            }
        }
    }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    val fx = remember { AudioFx() }
    var pvRef by remember { mutableStateOf<PlayerView?>(null) }
    var cropMsg by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        fx.attach(player.audioSessionId)
        val al = object : Player.Listener { override fun onAudioSessionIdChanged(audioSessionId: Int) { fx.attach(audioSessionId) } }
        player.addListener(al)
        onDispose { player.removeListener(al); fx.release() }
    }
    LaunchedEffect(s.surround, s.eq, room?.voiceOn) { fx.apply(s.surround && room?.voiceOn != true, s.eq) }
    // Redam suara film saat mik nobar hidup agar suara teman lebih jelas (volume ExoPlayer, bukan volume sistem), transisi halus
    LaunchedEffect(s.duck, room?.voiceOn, room?.micOn) {
        val target = if (s.duck && room?.let { it.voiceOn && it.micOn } == true) 0.3f else 1f
        val from = player.volume
        for (i in 1..10) { player.volume = from + (target - from) * i / 10f; kotlinx.coroutines.delay(50) }
    }
    var phase by remember { mutableIntStateOf(0) } // 0 memuat, 1 siap, 2 galat
    var error by remember { mutableStateOf("") }
    var errorDetail by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var variants by remember { mutableStateOf<List<VideoVariant>>(emptyList()) }
    var curVar by remember { mutableStateOf<VideoVariant?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<String?>(null) }
    var chatOpen by remember { mutableStateOf(false) }
    var rotLocked by remember { mutableStateOf(false) }
    var sleepEnd by remember { mutableLongStateOf(0L) }
    var stageText by remember { mutableStateOf("") }
    var showHint by remember { mutableStateOf(!s.hintSeen) }
    val skipMs = s.skipSec * 1000L
    var locked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var unlockTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(unlockTick) { if (unlockTick > 0) { showUnlock = true; delay(2500); showUnlock = false } }
    val micAct = if (room != null) rememberMicAction(room) else null
    val camAct = if (room != null) rememberCamAction(room) else null
    var editing by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var muted by remember { mutableStateOf(false) }

    fun startPlay(v: VideoVariant, seek: Long) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(v.url)))
        player.prepare()
        if (room != null) {
            val e = room.expected()
            if (e > 0) player.seekTo(e)
            player.setPlaybackSpeed(1f)
            player.playWhenReady = room.pb.playing
        } else {
            if (seek > 0) player.seekTo(seek)
            player.setPlaybackSpeed(s.speed)
            player.playWhenReady = true
        }
    }

    /** Di mode nobar: hanya yang berhak mengendalikan; hasilnya disiarkan ke semua anggota. */
    fun allowed(): Boolean {
        if (room == null || room.canControl()) return true
        hud = "Hanya host yang bisa mengatur"
        return false
    }

    fun seekBy(ms: Long) {
        if (!allowed()) return
        val d = player.duration
        val max = if (d > 0) d else Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0L, max))
        room?.publish(player.playWhenReady, player.currentPosition)
        hud = if (ms > 0) "+${ms / 1000} dtk" else "−${-ms / 1000} dtk"
    }

    fun togglePlay() {
        if (!allowed()) return
        if (ended) { player.seekTo(0); player.play() }
        else if (player.playWhenReady) player.pause() else player.play()
        room?.publish(player.playWhenReady, player.currentPosition)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
            }
            override fun onPlayerError(e: PlaybackException) {
                errorDetail = "${e.errorCodeName} (${e.errorCode})"
                error = friendlyError(e.errorCodeName)
                phase = 2
            }
        }
        player.addListener(l)
        onDispose {
            val d = player.duration
            if (d > 0) s.saveProgress(film.id, if (player.playbackState == Player.STATE_ENDED) d else player.currentPosition, d)
            player.removeListener(l)
            player.release()
        }
    }

    // Ekstrak link X -> URL video langsung
    LaunchedEffect(film.id, attempt) {
        phase = 0
        stageText = "Mencari sumber 1 dari 3…"
        val r = Extractor.extract(film.link) { i, n -> stageText = "Mencari sumber $i dari $n…" }
        r.onSuccess { v ->
            variants = v
            val mobile = isMobileData(ctx)
            val q = if (mobile && s.mobileQuality > 0 && s.quality == 0) s.mobileQuality else s.quality
            val pick = chooseVariant(v, q)
            stageText = "Memulai pemutaran…"
            if (mobile && s.mobileQuality > 0 && s.quality == 0 && pick != null && !pick.hls) hud = "Data seluler: ${pick.height}p"
            curVar = pick
            val pr = s.progress[film.id]
            val seek = if (room == null && pr != null && pr.frac in 0.02f..0.95f) (pr.pos - 2000).coerceAtLeast(0) else 0L
            if (pick != null) { startPlay(pick, seek); phase = 1 } else { error = "Video tidak ditemukan."; phase = 2 }
        }.onFailure { errorDetail = it.message ?: ""; error = friendlyError(it.message ?: ""); phase = 2 }
    }

    // Pembaruan posisi + simpan progres berkala
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            pos = player.currentPosition.coerceAtLeast(0)
            dur = player.duration.coerceAtLeast(0)
            tick++
            if (tick % 12 == 0 && player.isPlaying && dur > 0) s.saveProgress(film.id, pos, dur)
            delay(400)
        }
    }

    LaunchedEffect(controls, isPlaying, panel, dragging, phase) {
        if (controls && isPlaying && panel == null && !dragging && phase == 1) { delay(3500); controls = false }
    }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }

    // Timer tidur: jeda video saat waktunya habis
    LaunchedEffect(sleepEnd) {
        if (sleepEnd <= 0L) return@LaunchedEffect
        while (System.currentTimeMillis() < sleepEnd) delay(1000)
        player.pause()
        if (room != null && room.canControl()) room.publish(false, player.currentPosition)
        sleepEnd = 0L
        hud = "Timer tidur: video dihentikan"
    }
    // Petunjuk gestur: tampil sekali, hilang sendiri
    LaunchedEffect(showHint, phase) {
        if (showHint && phase == 1) { delay(7000); showHint = false; s.markHintSeen() }
    }

    // Nobar: lapor siap/buffering, dan host memulai bersamaan setelah semua siap
    if (room != null) {
        LaunchedEffect(phase, buffering, ended, isPlaying) { room.setReady(phase == 1 && !buffering) }
        DisposableEffect(room) { onDispose { room.setReady(false) } }
        val gatherAt = room.info?.gather ?: 0L
        LaunchedEffect(gatherAt) {
            if (!room.isHost || gatherAt <= 0L) return@LaunchedEffect
            var okSince = 0L
            val t0 = System.currentTimeMillis()
            while (true) {
                delay(400)
                val allReady = room.members.isNotEmpty() && room.members.none { it.buf } && !room.myBuf
                val now = System.currentTimeMillis()
                if (allReady) { if (okSince == 0L) okSince = now } else okSince = 0L
                if ((okSince != 0L && now - okSince > 1200) || now - t0 > 90_000) { room.endGather(player.currentPosition); break }
            }
        }
    }

    // Sinkron nobar: ikuti keadaan ruangan + koreksi selisih waktu berkala
    if (room != null) {
        val lc = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(room.pb.stamp, room.pb.playing, phase) {
            if (phase != 1 || room.pb.by == room.me) return@LaunchedEffect
            val e = room.expected()
            if (kotlin.math.abs(player.currentPosition - e) > 1200) player.seekTo(e)
            player.playWhenReady = room.pb.playing
        }
        LaunchedEffect(phase) {
            while (phase == 1) {
                delay(2500)
                if (!lc.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
                if (room.pb.playing) {
                    val e = room.expected()
                    if (!player.playWhenReady) player.playWhenReady = true
                    if (player.playbackState == Player.STATE_READY && kotlin.math.abs(player.currentPosition - e) > 2500) player.seekTo(e)
                } else if (player.playWhenReady) {
                    player.playWhenReady = false
                    if (kotlin.math.abs(player.currentPosition - room.pb.pos) > 800) player.seekTo(room.pb.pos)
                }
            }
        }
        LaunchedEffect(room.status) { if (room.status == "closed") onBack() }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) {
                player.pause()
                if (player.duration > 0) s.saveProgress(film.id, player.currentPosition, player.duration)
            }
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }

    BackHandler { if (locked) unlockTick++ else if (editing) editing = false else if (chatOpen) chatOpen = false else if (panel != null) panel = null else onBack() }

    var volAcc by remember { mutableFloatStateOf(0f) }
    var dragLeft by remember { mutableStateOf(true) }

    Row(Modifier.fillMaxSize().background(Color.Black)) {
    Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black)) {
        // Crop global yang disimpan pengguna. Nonaktif = video tampil apa adanya.
        val cr = s.crop
        val cropActive = s.cropOn && s.hasCrop && !editing
        val cl = if (cropActive) cr[0] else 0f
        val ct = if (cropActive) cr[1] else 0f
        val crr = if (cropActive) cr[2] else 0f
        val cb = if (cropActive) cr[3] else 0f
        val rw = 1f - cl - crr
        val rh = 1f - ct - cb
        val zoom = if (cropActive) minOf(1f / rw, 1f / rh) else 1f
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AndroidView(
                factory = { c ->
                    (android.view.LayoutInflater.from(c).inflate(R.layout.player_view, null) as PlayerView).apply {
                        useController = false
                        this.player = player
                        pvRef = this
                        keepScreenOn = true
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.resizeMode = resizeModes[resize].first },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = zoom * (0.5f - (cl + 1f - crr) / 2f) * size.width
                    translationY = zoom * (0.5f - (ct + 1f - cb) / 2f) * size.height
                },
            )
            if (cropActive) Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val vw = rw * zoom * w; val vh = rh * zoom * h
                val x0 = (w - vw) / 2f; val y0 = (h - vh) / 2f
                drawRect(Color.Black, Offset(0f, 0f), Size(w, y0))
                drawRect(Color.Black, Offset(0f, y0 + vh), Size(w, h - y0 - vh))
                drawRect(Color.Black, Offset(0f, y0), Size(x0, vh))
                drawRect(Color.Black, Offset(x0 + vw, y0), Size(w - x0 - vw, vh))
            }
        }

        // Lapisan gestur: ketuk = tampil/sembunyi kontrol, ketuk dua kali = ±10 dtk, geser vertikal = kecerahan/suara
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (panel != null) panel = null else controls = !controls },
                        onDoubleTap = { o ->
                            if (phase == 1) when {
                                o.x < size.width / 3f -> seekBy(-skipMs)
                                o.x > size.width * 2f / 3f -> seekBy(skipMs)
                                else -> togglePlay()
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { o ->
                            dragLeft = o.x < size.width / 2f
                            volAcc = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                        },
                        onVerticalDrag = { _, dy ->
                            val delta = -dy / size.height.toFloat() * 1.3f
                            if (dragLeft) {
                                val w = activity?.window
                                if (w != null) {
                                    val a = w.attributes
                                    val cur = if (a.screenBrightness < 0f) 0.5f else a.screenBrightness
                                    val nv = (cur + delta).coerceIn(0.02f, 1f)
                                    a.screenBrightness = nv
                                    w.attributes = a
                                    hud = "Kecerahan ${(nv * 100).roundToInt()}%"
                                }
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                volAcc = (volAcc + delta * max).coerceIn(0f, max.toFloat())
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, volAcc.roundToInt(), 0)
                                hud = "Suara ${(volAcc / max * 100).roundToInt()}%"
                            }
                        },
                    )
                },
        )

        if (phase == 1 && buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = C.Aqua, strokeWidth = 3.dp)
        }

        hud?.let {
            Text(
                it, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp).clip(RoundedCornerShape(50)).background(C.Surface.copy(alpha = .92f)).padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }

        if (showHint && phase == 1 && !locked) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = .72f))
                    .clickable { showHint = false; s.markHintSeen() }.padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text("Gestur pemutar", color = C.Aqua, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text("• Ketuk dua kali kiri / kanan: mundur / maju ${s.skipSec} detik", color = Color.White, fontSize = 13.sp)
                Text("• Ketuk dua kali di tengah: jeda / putar", color = Color.White, fontSize = 13.sp)
                Text("• Geser atas-bawah di kiri: kecerahan", color = Color.White, fontSize = 13.sp)
                Text("• Geser atas-bawah di kanan: suara", color = Color.White, fontSize = 13.sp)
                Text("Ketuk untuk menutup", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        if (phase == 0) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = C.Aqua, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("Menyiapkan video…", color = Color.White)
                if (stageText.isNotEmpty()) Text(stageText, color = C.Aqua, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Text(film.name, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            IconBtn(Ic.Back, "Kembali", onBack, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp), glass = true)
        }

        if (phase == 2) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = Color.White, textAlign = TextAlign.Center)
                if (errorDetail.isNotBlank()) Text("Detail teknis: $errorDetail", color = C.Subtle, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AButton("COBA LAGI", Ic.Refresh, true) { attempt++ }
                    AButton("KEMBALI", null, false) { onBack() }
                }
            }
        }

        // Kontrol pemutar
        androidx.compose.animation.AnimatedVisibility(visible = controls && phase == 1, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .70f), 0.30f to Color.Black.copy(alpha = .25f), 0.65f to Color.Black.copy(alpha = .25f), 1f to Color.Black.copy(alpha = .80f)))) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.Back, "Kembali", onBack, glass = true)
                    Text(film.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    if (room != null) {
                        IconBtn(Ic.People, "Chat nobar", { chatOpen = !chatOpen }, glass = true)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconBtn(Ic.Rotate, if (rotLocked) "Buka kunci rotasi" else "Kunci rotasi", {
                        rotLocked = !rotLocked
                        activity?.requestedOrientation = if (rotLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        hud = if (rotLocked) "Rotasi terkunci" else "Rotasi otomatis"
                    }, glass = true, tint = if (rotLocked) C.Aqua else Color.White)
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Ic.Lock, "Kunci layar", { locked = true; controls = false; panel = null; hud = "Layar terkunci"; unlockTick++ }, glass = true)
                    Spacer(Modifier.width(8.dp))
                    IconBtn(Ic.Aspect, "Rasio layar", { resize = (resize + 1) % resizeModes.size; hud = resizeModes[resize].second }, glass = true)
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeekIcon(Ic.Rewind, s.skipSec.toString()) { seekBy(-skipMs) }
                    Box(Modifier.size(72.dp).clip(CircleShape).background(C.Aqua).clickable { togglePlay() }, contentAlignment = Alignment.Center) {
                        Icon(if (isPlaying) Ic.PauseFill else Ic.PlayFill, if (isPlaying) "Jeda" else "Putar", tint = C.OnAqua, modifier = Modifier.size(32.dp))
                    }
                    SeekIcon(Ic.Forward, s.skipSec.toString()) { seekBy(skipMs) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val shown = if (dragging) (dragFrac * dur).toLong() else pos
                        Text(fmtTime(shown), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text("-" + fmtTime((dur - shown).coerceAtLeast(0)), color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Slider(
                        value = if (dragging) dragFrac else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = true; dragFrac = it },
                        onValueChangeFinished = {
                            if (dur > 0 && allowed()) { player.seekTo((dragFrac * dur).toLong()); room?.publish(player.playWhenReady, player.currentPosition) }
                            dragging = false
                        },
                        colors = SliderDefaults.colors(thumbColor = C.Aqua, activeTrackColor = C.Aqua, inactiveTrackColor = Color.White.copy(alpha = .28f)),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BarButton(if (muted) Ic.Mute else Ic.Volume, "Suara") { panel = "volume" }
                        BarButton(Ic.Quality, "Kualitas · " + (if (s.quality == 0) "Otomatis" else "${s.quality}p")) { panel = "quality" }
                        BarButton(Ic.Speed, "Kecepatan · ${fmtSpeed(s.speed)}") { panel = "speed" }
                        BarButton(Ic.Skip, "Lompat · ${s.skipSec} dtk") { panel = "skip" }
                        BarButton(Ic.Moon, "Timer tidur" + if (sleepEnd > 0) " · ${((sleepEnd - System.currentTimeMillis()) / 60000 + 1)} mnt" else "") { panel = "sleep" }
                        BarButton(Ic.Crop, "Crop · " + (if (s.cropOn && s.hasCrop) "Aktif" else "Mati")) { panel = "crop" }
                        if (room != null) BarButton(Ic.Star, "Reaksi") { panel = "react" }
                        if (room != null) BarButton(if (room.camOn) Ic.Cam else Ic.CamOff, if (room.camOn) "Kamera aktif" else "Kamera") { camAct?.invoke() }
                        if (room != null) BarButton(if (room.voiceOn && room.micOn) Ic.Mic else Ic.MicOff, if (!room.voiceOn) "Suara" else if (room.micOn) "Mik aktif" else "Mik mati") { micAct?.invoke() }
                        else if (next != null) BarButton(Ic.Next, "Berikutnya") { panel = "next" }
                    }
                }
            }
        }

        // Panel pengaturan (kanan)
        if (panel != null && phase == 1) {
            Column(
                Modifier.align(Alignment.CenterEnd).statusBarsPadding().navigationBarsPadding().padding(end = 24.dp)
                    .width(280.dp).clip(RoundedCornerShape(20.dp)).background(C.Surface.copy(alpha = .97f)).border(1.dp, C.Outline, RoundedCornerShape(20.dp)).padding(vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (panel) {
                    "next" -> if (next != null) {
                        PanelTitle("Berikutnya")
                        Text(next.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 18.dp))
                        Text(meta(next), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AButton("BATAL", null, false, Modifier.weight(1f)) { panel = null }
                            AButton("PUTAR", Ic.PlayFill, true, Modifier.weight(1f)) { panel = null; onNext(next) }
                        }
                    }
                    "skip" -> {
                        PanelTitle("Durasi lompat")
                        listOf(5, 10, 30).forEach { v -> PanelItem("$v detik", s.skipSec == v) { s.updateSkip(v); panel = null } }
                    }
                    "sleep" -> {
                        PanelTitle("Timer tidur")
                        PanelItem("Mati", sleepEnd == 0L) { sleepEnd = 0L; panel = null }
                        listOf(15, 30, 45, 60, 90).forEach { mnt ->
                            PanelItem("$mnt menit", false) { sleepEnd = System.currentTimeMillis() + mnt * 60_000L; panel = null; hud = "Video berhenti dalam $mnt menit" }
                        }
                    }
                    "react" -> if (room != null) {
                        PanelTitle("Reaksi")
                        Box(Modifier.padding(horizontal = 12.dp)) { ReactionBar(room) }
                        Spacer(Modifier.height(10.dp))
                        PanelItem(if (s.floatChat) "Chat & reaksi melayang: tampil" else "Chat & reaksi melayang: sembunyi", s.floatChat) { s.updateFloatChat(!s.floatChat) }
                        PanelItem(if (s.duck) "Redam suara film saat mik hidup: ya" else "Redam suara film saat mik hidup: tidak", s.duck) { s.updateDuck(!s.duck) }
                    }
                    "quality" -> {
                        PanelTitle("Kualitas video")
                        PanelItem("Otomatis (terbaik)", s.quality == 0) { s.setQualityPref(0); applyQuality(variants, 0, curVar, player) { curVar = it; startPlay(it, player.currentPosition) }; panel = null }
                        variants.filter { !it.hls }.map { it.height }.distinct().forEach { h ->
                            PanelItem("${h}p", s.quality == h) { s.setQualityPref(h); applyQuality(variants, h, curVar, player) { curVar = it; startPlay(it, player.currentPosition) }; panel = null }
                        }
                    }
                    "speed" -> {
                        PanelTitle("Kecepatan putar")
                        speeds.forEach { sp ->
                            PanelItem(if (sp == 1f) "Normal" else fmtSpeed(sp), s.speed == sp) { s.setSpeedPref(sp); player.setPlaybackSpeed(sp); panel = null }
                        }
                    }
                    "crop" -> {
                        PanelTitle("Crop video")
                        PanelItem("Atur crop manual…", false) { panel = null; controls = false; editing = true }
                        PanelItem(if (s.hasCrop) "Pakai crop tersimpan" else "Pakai crop tersimpan (belum ada)", s.cropOn && s.hasCrop) {
                            if (s.hasCrop) { s.useCrop(!s.cropOn); panel = null } else { panel = null; controls = false; editing = true }
                        }
                        PanelItem("Matikan & hapus crop tersimpan", false) { s.clearCrop(); panel = null }
                    }
                    "volume" -> {
                        PanelTitle("Suara")
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        var v by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (v == 0f) Ic.Mute else Ic.Volume, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Slider(
                                value = v,
                                onValueChange = { v = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0); muted = it == 0f },
                                colors = SliderDefaults.colors(thumbColor = C.Aqua, activeTrackColor = C.Aqua, inactiveTrackColor = Color.White.copy(alpha = .3f)),
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        if (editing) CropEditor(
            initial = if (s.hasCrop) s.crop else listOf(0f, 0f, 0f, 0f),
            onAuto = { pvRef?.let { detectCrop(it) } },
            onSave = { s.saveCrop(it); editing = false },
            onCancel = { editing = false },
        )

        // Pesan chat melayang di atas video saat panel chat tertutup
        if (room != null && !chatOpen && phase == 1) {
            if (s.floatChat) ChatOverlay(room, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 20.dp, top = 72.dp).fillMaxWidth(0.6f))
        }
        if (room != null && s.floatChat && phase == 1) ReactionLayer(room, Modifier.fillMaxSize())
        if (room != null && (room.info?.gather ?: 0L) > 0) {
            val waitN = room.members.count { it.buf }
            Text("Menunggu semua siap… ($waitN belum siap)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 16.dp, vertical = 8.dp))
        }

        // Kunci layar: semua sentuhan (kecerahan, volume, geser, ketuk) diblokir, hanya tombol buka kunci yang aktif
        if (locked) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { unlockTick++ }) }) {
                if (showUnlock) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).navigationBarsPadding().padding(end = 28.dp).size(60.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = .6f)).clickable { locked = false; showUnlock = false; controls = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.LockOpen, "Buka kunci", tint = Color.White, modifier = Modifier.size(28.dp)) }
                }
            }
        }
    }
    if (chatOpen && room != null) ChatDock(room, { chatOpen = false }, onGather = { room.gather(player.currentPosition) }, modifier = Modifier.width(300.dp).fillMaxHeight())
    }
}

private fun applyQuality(list: List<VideoVariant>, q: Int, cur: VideoVariant?, player: ExoPlayer, change: (VideoVariant) -> Unit) {
    val v = chooseVariant(list, q) ?: return
    if (v.url != cur?.url) change(v)
}

private fun fmtSpeed(v: Float): String = (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + "x"

@Composable
private fun SeekIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).heightIn(min = 48.dp).clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PanelTitle(t: String) =
    Text(t.uppercase(), color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))

@Composable
private fun PanelItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = if (selected) C.Aqua else Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Ic.Check, null, tint = C.Aqua, modifier = Modifier.size(20.dp))
    }
}

/** Atur garis crop dengan menggeser garis di layar (kiri, atas, kanan, bawah). Hasil disimpan global. */
@Composable
private fun CropEditor(initial: List<Float>, onAuto: suspend () -> List<Float>?, onSave: (List<Float>) -> Unit, onCancel: () -> Unit) {
    var ins by remember { mutableStateOf(initial) } // kiri, atas, kanan, bawah
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(1, 1)) }
    var edge by remember { mutableIntStateOf(-1) }
    val aqua = C.Aqua
    val scope = rememberCoroutineScope()
    var autoMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val w = box.width.toFloat(); val h = box.height.toFloat()
                        val d = floatArrayOf(
                            kotlin.math.abs(o.x - ins[0] * w), kotlin.math.abs(o.y - ins[1] * h),
                            kotlin.math.abs(o.x - (1 - ins[2]) * w), kotlin.math.abs(o.y - (1 - ins[3]) * h),
                        )
                        val m = d.indices.minByOrNull { d[it] } ?: 0
                        edge = if (d[m] < 90.dp.toPx()) m else -1
                    },
                    onDragEnd = { edge = -1 },
                    onDragCancel = { edge = -1 },
                ) { change, drag ->
                    change.consume()
                    if (edge < 0) return@detectDragGestures
                    val w = box.width.toFloat(); val h = box.height.toFloat()
                    val n = ins.toMutableList()
                    val delta = when (edge) { 0 -> drag.x / w; 1 -> drag.y / h; 2 -> -drag.x / w; else -> -drag.y / h }
                    val limit = if (edge % 2 == 0) 0.9f - ins[if (edge == 0) 2 else 0] else 0.9f - ins[if (edge == 1) 3 else 1]
                    n[edge] = (n[edge] + delta).coerceIn(0f, limit.coerceAtLeast(0f))
                    ins = n
                }
            },
        ) {
            val w = size.width; val h = size.height
            val l = ins[0] * w; val t = ins[1] * h; val r = w - ins[2] * w; val b = h - ins[3] * h
            val dim = Color.Black.copy(alpha = .65f)
            drawRect(dim, Offset(0f, 0f), Size(w, t))
            drawRect(dim, Offset(0f, b), Size(w, h - b))
            drawRect(dim, Offset(0f, t), Size(l, b - t))
            drawRect(dim, Offset(r, t), Size(w - r, b - t))
            val sw = 3.dp.toPx()
            drawLine(aqua, Offset(l, 0f), Offset(l, h), sw)
            drawLine(aqua, Offset(r, 0f), Offset(r, h), sw)
            drawLine(aqua, Offset(0f, t), Offset(w, t), sw)
            drawLine(aqua, Offset(0f, b), Offset(w, b), sw)
            val hr = 9.dp.toPx()
            drawCircle(aqua, hr, Offset(l, h / 2)); drawCircle(aqua, hr, Offset(r, h / 2))
            drawCircle(aqua, hr, Offset(w / 2, t)); drawCircle(aqua, hr, Offset(w / 2, b))
        }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .7f)).padding(horizontal = 16.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (autoMsg.isNotEmpty()) autoMsg else "Geser garis untuk mengatur area video", color = Color.White, fontSize = 13.sp)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AButton("SIMPAN", Ic.Check, true) { onSave(ins) }
                AButton(if (busy) "…" else "AUTO", null, false) {
                    if (!busy) scope.launch {
                        busy = true; autoMsg = "Mendeteksi bingkai hitam…"
                        val r = onAuto()
                        if (r == null) autoMsg = "Gagal membaca frame video"
                        else if (r.all { it < 0.003f }) { ins = listOf(0f, 0f, 0f, 0f); autoMsg = "Tidak ada bingkai hitam" }
                        else { ins = r; autoMsg = "Selesai — geser untuk menyesuaikan" }
                        busy = false
                    }
                }
                AButton("RESET", null, false) { ins = listOf(0f, 0f, 0f, 0f) }
                AButton("BATAL", null, false) { onCancel() }
            }
        }
    }
}

/** Deteksi tepi hitam: pindai beberapa frame, ambil crop terkecil (aman untuk adegan gelap). Hasil = inset kiri, atas, kanan, bawah (pecahan layar). */
private suspend fun detectCrop(pv: PlayerView): List<Float>? {
    val tv = pv.videoSurfaceView as? android.view.TextureView ?: return null
    if (tv.width < 2 || tv.height < 2 || pv.width < 2 || pv.height < 2) return null
    val a = IntArray(2); val b = IntArray(2)
    tv.getLocationInWindow(a); pv.getLocationInWindow(b)
    val x0 = (a[0] - b[0]).toFloat(); val y0 = (a[1] - b[1]).toFloat()
    val tw = tv.width.toFloat(); val th = tv.height.toFloat()
    val bw = 200; val bh = (bw * th / tw).toInt().coerceIn(40, 600)
    var best: FloatArray? = null
    repeat(4) {
        val bmp = tv.getBitmap(bw, bh)
        if (bmp != null) {
            val px = IntArray(bw * bh)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
            bmp.recycle()
            fun lit(x: Int, y: Int): Boolean {
                val c = px[y * bw + x]
                return maxOf((c shr 16) and 255, (c shr 8) and 255, c and 255) > 30
            }
            fun rowLit(y: Int) = (0 until bw).count { lit(it, y) } > bw * 0.015
            fun colLit(x: Int) = (0 until bh).count { lit(x, it) } > bh * 0.015
            val total = px.count { c -> maxOf((c shr 16) and 255, (c shr 8) and 255, c and 255) > 30 }
            if (total > px.size * 0.02) {
                var t = 0; while (t < bh - 1 && !rowLit(t)) t++
                var bt = 0; while (bt < bh - 1 - t && !rowLit(bh - 1 - bt)) bt++
                var l = 0; while (l < bw - 1 && !colLit(l)) l++
                var r = 0; while (r < bw - 1 - l && !colLit(bw - 1 - r)) r++
                val cur = floatArrayOf(l.toFloat() / bw, t.toFloat() / bh, r.toFloat() / bw, bt.toFloat() / bh)
                best = best?.let { o -> FloatArray(4) { minOf(o[it], cur[it]) } } ?: cur
            }
        }
        kotlinx.coroutines.delay(300)
    }
    val m = best ?: return null
    val pw = pv.width.toFloat(); val ph = pv.height.toFloat()
    val out = listOf(
        (x0 + m[0] * tw) / pw,
        (y0 + m[1] * th) / ph,
        ((pw - (x0 + tw)) + m[2] * tw) / pw,
        ((ph - (y0 + th)) + m[3] * th) / ph,
    ).map { if (it < 0.004f) 0f else (it + 0.003f).coerceIn(0f, 0.45f) }
    return out
}


private fun isMobileData(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
}.getOrDefault(false)


/** Ubah galat teknis menjadi kalimat sederhana. Detail aslinya ditampilkan kecil di bawahnya. */
fun friendlyError(raw: String): String {
    val r = raw.lowercase()
    return when {
        "404" in r || "tidak berisi video" in r || "not found" in r -> "Video tidak ditemukan. Mungkin sudah dihapus dari X."
        "403" in r || "401" in r || "privat" in r -> "Video ini tidak bisa dibuka karena dibatasi pemiliknya."
        "429" in r -> "Terlalu banyak permintaan. Tunggu sebentar lalu coba lagi."
        "network" in r || "unknownhost" in r || "timeout" in r || "menghubungi server" in r || "koneksi" in r || "unable to resolve" in r -> "Koneksi internet bermasalah. Periksa jaringanmu lalu coba lagi."
        "decod" in r || "format" in r || "unsupported" in r -> "Format video ini tidak didukung di HP kamu."
        "5" == r.take(1) && r.length >= 3 && r.take(3).all { it.isDigit() } -> "Server sedang bermasalah. Coba lagi nanti."
        else -> "Video tidak bisa diputar saat ini. Coba lagi sebentar."
    }
}
