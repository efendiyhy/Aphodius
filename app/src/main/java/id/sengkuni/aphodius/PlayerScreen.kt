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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun PlayerScreen(film: Film, next: Film?, s: AppState, onBack: () -> Unit, onNext: (Film) -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

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
    var phase by remember { mutableIntStateOf(0) } // 0 memuat, 1 siap, 2 galat
    var error by remember { mutableStateOf("") }
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
    var resize by remember { mutableIntStateOf(0) }
    var hud by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var muted by remember { mutableStateOf(false) }

    fun startPlay(v: VideoVariant, seek: Long) {
        player.setMediaItem(MediaItem.fromUri(Uri.parse(v.url)))
        player.prepare()
        if (seek > 0) player.seekTo(seek)
        player.setPlaybackSpeed(s.speed)
        player.playWhenReady = true
    }

    fun seekBy(ms: Long) {
        val d = player.duration
        val max = if (d > 0) d else Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0L, max))
        hud = if (ms > 0) "+${ms / 1000} dtk" else "−${-ms / 1000} dtk"
    }

    fun togglePlay() {
        if (ended) { player.seekTo(0); player.play() }
        else if (player.isPlaying) player.pause() else player.play()
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
            }
            override fun onPlayerError(e: PlaybackException) {
                error = "Video tidak bisa diputar (${e.errorCodeName})."
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
        val r = Extractor.extract(film.link)
        r.onSuccess { v ->
            variants = v
            val pick = chooseVariant(v, s.quality)
            curVar = pick
            val pr = s.progress[film.id]
            val seek = if (pr != null && pr.frac in 0.02f..0.95f) (pr.pos - 2000).coerceAtLeast(0) else 0L
            if (pick != null) { startPlay(pick, seek); phase = 1 } else { error = "Video tidak ditemukan."; phase = 2 }
        }.onFailure { error = it.message ?: "Gagal memuat video."; phase = 2 }
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

    BackHandler { if (panel != null) panel = null else onBack() }

    var volAcc by remember { mutableFloatStateOf(0f) }
    var dragLeft by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = player
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.resizeMode = resizeModes[resize].first },
            modifier = Modifier.fillMaxSize(),
        )

        // Lapisan gestur: ketuk = tampil/sembunyi kontrol, ketuk dua kali = ±10 dtk, geser vertikal = kecerahan/suara
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (panel != null) panel = null else controls = !controls },
                        onDoubleTap = { o ->
                            if (phase == 1) when {
                                o.x < size.width / 3f -> seekBy(-10_000)
                                o.x > size.width * 2f / 3f -> seekBy(10_000)
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
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (phase == 0) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = C.Aqua, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(12.dp))
                Text("Menyiapkan video…", color = Color.White)
                Text(film.name, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Ic.Back, "Kembali", tint = Color.White, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).size(28.dp).clickable { onBack() })
        }

        if (phase == 2) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AButton("COBA LAGI", Ic.Refresh, true) { attempt++ }
                    AButton("BUKA DI X", Ic.External, false) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(film.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    AButton("KEMBALI", null, false) { onBack() }
                }
            }
        }

        // Kontrol pemutar
        AnimatedVisibility(visible = controls && phase == 1, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f))) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Back, "Kembali", tint = Color.White, modifier = Modifier.size(28.dp).clickable { onBack() })
                    Text(film.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Icon(Ic.Aspect, "Rasio layar", tint = Color.White, modifier = Modifier.size(26.dp).clickable { resize = (resize + 1) % resizeModes.size; hud = resizeModes[resize].second })
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    SeekIcon(Ic.Rewind) { seekBy(-10_000) }
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f)).clickable { togglePlay() }, contentAlignment = Alignment.Center) {
                        Icon(if (isPlaying) Ic.PauseFill else Ic.PlayFill, if (isPlaying) "Jeda" else "Putar", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    SeekIcon(Ic.Forward) { seekBy(10_000) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val shown = if (dragging) (dragFrac * dur).toLong() else pos
                        Text(fmtTime(shown), color = Color.White, fontSize = 12.sp)
                        Text("-" + fmtTime((dur - shown).coerceAtLeast(0)), color = Color.White, fontSize = 12.sp)
                    }
                    Slider(
                        value = if (dragging) dragFrac else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = true; dragFrac = it },
                        onValueChangeFinished = { if (dur > 0) player.seekTo((dragFrac * dur).toLong()); dragging = false },
                        colors = SliderDefaults.colors(thumbColor = C.Aqua, activeTrackColor = C.Aqua, inactiveTrackColor = Color.White.copy(alpha = .3f)),
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BarButton(if (muted) Ic.Mute else Ic.Volume, "Suara") { panel = "volume" }
                        BarButton(Ic.Quality, "Kualitas · " + (if (s.quality == 0) "Otomatis" else "${s.quality}p")) { panel = "quality" }
                        BarButton(Ic.Speed, "Kecepatan · ${fmtSpeed(s.speed)}") { panel = "speed" }
                        if (next != null) BarButton(Ic.Next, "Berikutnya") { onNext(next) }
                    }
                }
            }
        }

        // Panel pengaturan (kanan)
        if (panel != null && phase == 1) {
            Column(
                Modifier.align(Alignment.CenterEnd).statusBarsPadding().navigationBarsPadding().padding(end = 24.dp)
                    .width(270.dp).clip(RoundedCornerShape(12.dp)).background(C.Surface.copy(alpha = .97f)).padding(vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (panel) {
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
    }
}

private fun applyQuality(list: List<VideoVariant>, q: Int, cur: VideoVariant?, player: ExoPlayer, change: (VideoVariant) -> Unit) {
    val v = chooseVariant(list, q) ?: return
    if (v.url != cur?.url) change(v)
}

private fun fmtSpeed(v: Float): String = (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + "x"

@Composable
private fun SeekIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(44.dp))
        Text("10", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PanelTitle(t: String) =
    Text(t, color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

@Composable
private fun PanelItem(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = if (selected) C.Aqua else Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Ic.Check, null, tint = C.Aqua, modifier = Modifier.size(20.dp))
    }
}
