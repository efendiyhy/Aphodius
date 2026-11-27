package id.sengkuni.aphodius

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Mengembalikan aksi tombol mikrofon: minta izin bila perlu, lalu nyalakan suara atau bisukan/bunyikan mik. */
@Composable
fun rememberMicAction(room: NobarRoom): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) room.enableVoice() }
    return {
        if (room.voiceOn) room.toggleMic()
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) room.enableVoice()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/** Aksi tombol kamera (video call): minta izin kamera + mik bila perlu, lalu nyalakan/matikan kamera. */
@Composable
fun rememberCamAction(room: NobarRoom): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.all { it }) room.enableCam() }
    return {
        if (room.camOn) room.disableCam()
        else {
            val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
            if (need.isEmpty()) room.enableCam() else launcher.launch(need.toTypedArray())
        }
    }
}

@Composable
private fun VideoTile(track: org.webrtc.VideoTrack, mirror: Boolean, label: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val view = remember(track) {
        org.webrtc.SurfaceViewRenderer(ctx).apply {
            init(Rtc.egl.eglBaseContext, null)
            setMirror(mirror)
            setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }
    }
    DisposableEffect(view) {
        track.addSink(view)
        onDispose { runCatching { track.removeSink(view) }; view.release() }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView({ view }, Modifier.fillMaxSize())
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Deretan video call: kamera sendiri + peserta yang menyalakan kamera. Tidak tampil bila tak ada video. */
@Composable
fun VideoStrip(room: NobarRoom, modifier: Modifier = Modifier) {
    val remote = room.members.filter { it.cam && it.uid != room.me && room.videos[it.uid] != null }
    val mine = room.localVideo
    if (remote.isEmpty() && mine == null) return
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        mine?.let { VideoTile(it, true, "Kamu", Modifier.size(120.dp, 90.dp)) }
        remote.forEach { m -> key(m.uid) { room.videos[m.uid]?.let { VideoTile(it, false, m.name, Modifier.size(120.dp, 90.dp)) } } }
    }
}

@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, caps: Boolean = false, focus: androidx.compose.ui.focus.FocusRequester? = null, onDone: () -> Unit = {}) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(C.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = C.Subtle, fontSize = 14.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(C.Aqua),
            keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onDone() }, onDone = { onDone() }),
            modifier = if (focus != null) Modifier.fillMaxWidth().focusRequester(focus) else Modifier.fillMaxWidth(),
        )
    }
}

/** Kotak chat: daftar pesan + kolom tulis. size = ukuran huruf pesan. */
@Composable
fun ChatBox(room: NobarRoom, modifier: Modifier = Modifier, size: Int = 15) {
    var draft by remember { mutableStateOf("") }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    // Setelah kirim, kolom tulis tetap fokus dan keyboard tetap terbuka
    var replyTo by remember { mutableStateOf<NbMsg?>(null) }
    val send: () -> Unit = {
        if (draft.isNotBlank()) { room.send(draft, replyTo); draft = ""; replyTo = null }
        runCatching { fr.requestFocus() }
        kb?.show()
    }
    val listState = rememberLazyListState()
    // reverseLayout: pesan terbaru selalu menempel di dasar; item 0 = terbaru
    LaunchedEffect(room.chat.lastOrNull()?.id) { listState.scrollToItem(0) }
    Column(modifier) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(horizontal = 10.dp),
            state = listState, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 10.dp),
        ) {
            items(room.chat.asReversed(), key = { it.id }) { m ->
                val mine = m.uid == room.me
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Column(
                        Modifier.widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(14.dp, 14.dp, if (mine) 4.dp else 14.dp, if (mine) 14.dp else 4.dp))
                            .background(if (mine) C.Aqua.copy(alpha = .22f) else C.SurfaceHigh)
                            .clickable { replyTo = m }.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        if (!mine) Text(m.name, color = C.Gold, fontWeight = FontWeight.Bold, fontSize = (size - 3).sp)
                        if (m.q.isNotEmpty()) Row(Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .25f)).height(IntrinsicSize.Min)) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(C.Aqua))
                            Column(Modifier.padding(6.dp)) {
                                Text(m.qn, color = C.Aqua, fontWeight = FontWeight.Bold, fontSize = (size - 4).sp, maxLines = 1)
                                Text(m.qm, color = C.Muted, fontSize = (size - 3).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(m.text, color = Color.White, fontSize = size.sp, lineHeight = (size + 5).sp)
                    }
                }
            }
            if (room.chat.isEmpty()) item { Text("Belum ada pesan. Sapa temanmu! Ketuk pesan untuk membalas.", color = C.Subtle, fontSize = size.sp) }
        }
        replyTo?.let { r ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.SurfaceHigh).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Membalas ${r.name}", color = C.Aqua, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(r.text, color = C.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconBtn(Ic.Close, "Batal membalas", { replyTo = null }, size = 32.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextInput(draft, { draft = it.take(300) }, "Tulis pesan…", Modifier.weight(1f), focus = fr, onDone = send)
            Spacer(Modifier.width(8.dp))
            IconBtn(Ic.Send, "Kirim", send, size = 44.dp, tint = C.Aqua)
        }
    }
}

/** Deretan peserta: avatar + titik status (hijau siap, kuning buffering/belum siap). Host bisa mengeluarkan peserta. */
@Composable
fun MemberStrip(room: NobarRoom, modifier: Modifier = Modifier) {
    var target by remember { mutableStateOf<NbMember?>(null) }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        room.members.forEach { m ->
            val canKick = room.isHost && m.uid != room.me
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).then(if (canKick) Modifier.clickable { target = m } else Modifier).padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Avatar(m.name, 34)
                    Box(Modifier.align(Alignment.BottomEnd).size(11.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.Navy).padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (m.buf) C.Gold else Color(0xFF3DDC84)))
                }
                Text((if (m.uid == room.info?.host) "★ " else "") + m.name.take(9), color = Color.White, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                Text(if (m.buf) "buffering" else "siap", color = if (m.buf) C.Gold else C.Muted, fontSize = 12.sp)
            }
        }
    }
    target?.let { m ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { target = null },
            containerColor = C.Surface,
            title = { Text("Keluarkan ${m.name}?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text("Dia tidak bisa masuk lagi ke ruangan ini.", color = C.Muted, fontSize = 13.sp) },
            confirmButton = { Text("KELUARKAN", color = C.Aqua, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.clickable { room.kick(m.uid); target = null }.padding(12.dp)) },
            dismissButton = { Text("BATAL", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clickable { target = null }.padding(12.dp)) },
        )
    }
}

val REACTIONS = listOf("👍", "😂", "😮", "❤️", "🔥", "👏")

@Composable
fun ReactionBar(room: NobarRoom, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        REACTIONS.forEach { e ->
            Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(C.SurfaceHigh).clickable { room.react(e) }, contentAlignment = Alignment.Center) {
                Text(e, fontSize = 22.sp)
            }
        }
    }
}

/** Emoji reaksi melayang naik di atas video. */
@Composable
fun ReactionLayer(room: NobarRoom, modifier: Modifier = Modifier) {
    var items by remember { mutableStateOf(listOf<NbMsg>()) }
    var lastId by remember { mutableStateOf(room.reactions.lastOrNull()?.id ?: "") }
    val tail = room.reactions.lastOrNull()?.id
    LaunchedEffect(tail) {
        val rs = room.reactions
        val idx = rs.indexOfFirst { it.id == lastId }
        val fresh = if (idx >= 0) rs.drop(idx + 1) else rs.takeLast(3)
        lastId = tail ?: lastId
        if (fresh.isNotEmpty()) items = (items + fresh).takeLast(12)
    }
    Box(modifier) {
        items.forEach { m ->
            key(m.id) { FloatingEmoji(m, Modifier.align(Alignment.BottomEnd)) { items = items.filter { it.id != m.id } } }
        }
    }
}

@Composable
private fun FloatingEmoji(m: NbMsg, modifier: Modifier, onDone: () -> Unit) {
    val prog = remember { androidx.compose.animation.core.Animatable(0f) }
    val seed = (m.id.hashCode() and 0x7fffffff) % 6
    LaunchedEffect(Unit) {
        prog.animateTo(1f, androidx.compose.animation.core.tween(2800, easing = androidx.compose.animation.core.LinearEasing))
        onDone()
    }
    Column(
        modifier.padding(end = (24 + seed * 26).dp, bottom = 96.dp).graphicsLayer {
            translationY = -prog.value * 380.dp.toPx()
            alpha = (1f - prog.value * prog.value).coerceIn(0f, 1f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(m.text, fontSize = 38.sp)
        Text(m.name.take(10), color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
    }
}

/** Panel chat di sisi kanan pemutar: hanya chat dan jumlah yang bergabung. */
@Composable
fun ChatDock(room: NobarRoom, onClose: () -> Unit, onGather: () -> Unit = {}, modifier: Modifier = Modifier) {
    Column(modifier.background(C.Navy).statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nobar · ${room.code}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${room.members.size} dari $MAX_MEMBERS bergabung", color = C.Muted, fontSize = 12.sp)
            }
            IconBtn(Ic.Close, "Tutup chat", onClose, size = 34.dp)
        }
        Spacer(Modifier.height(8.dp))
        MemberStrip(room)
        if (room.camOn || room.videos.isNotEmpty()) { Spacer(Modifier.height(8.dp)); VideoStrip(room) }
        if (room.isHost) {
            Spacer(Modifier.height(8.dp))
            val waiting = (room.info?.gather ?: 0L) > 0
            AButton(if (waiting) "MENUNGGU SEMUA SIAP…" else "TUNGGU SEMUA SIAP", null, !waiting, Modifier.fillMaxWidth().height(40.dp)) { if (!waiting) onGather() }
        }
        Spacer(Modifier.height(8.dp))
        ReactionBar(room)
        Spacer(Modifier.height(8.dp))
        ChatBox(room, Modifier.weight(1f), size = 15)
    }
}

/** Pesan baru muncul sebentar di atas video (transparan) saat panel chat tertutup. */
@Composable
fun ChatOverlay(room: NobarRoom, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(listOf<Pair<NbMsg, Long>>()) }
    var lastId by remember { mutableStateOf(room.chat.lastOrNull()?.id ?: "") }
    val tail = room.chat.lastOrNull()?.id
    LaunchedEffect(tail) {
        val cs = room.chat
        val idx = cs.indexOfFirst { it.id == lastId }
        val fresh = if (idx >= 0) cs.drop(idx + 1) else cs.takeLast(3)
        lastId = tail ?: lastId
        if (fresh.isNotEmpty()) {
            val now = System.currentTimeMillis()
            shown = (shown + fresh.map { it to now }).takeLast(4)
        }
    }
    LaunchedEffect(shown.size) {
        while (shown.isNotEmpty()) {
            delay(1000)
            val now = System.currentTimeMillis()
            shown = shown.filter { now - it.second < 8000 }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { (m, _) ->
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    withStyle(androidx.compose.ui.text.SpanStyle(color = if (m.uid == room.me) C.Aqua else C.Gold, fontWeight = FontWeight.Bold)) { append(m.name + "  ") }
                    withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White)) { append(m.text) }
                },
                fontSize = 18.sp, lineHeight = 23.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .38f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SmallChip(text: String, active: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (active) C.Aqua else C.SurfaceHigh).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (active) C.OnAqua else Color.White
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Isi lobi: anggota dan pengaturan dibuat ringkas, chat mengisi sisa layar. */
@Composable
fun LobbyPanel(room: NobarRoom, modifier: Modifier = Modifier) {
    val mic = rememberMicAction(room)
    val cam = rememberCamAction(room)
    Column(modifier) {
        Text("${room.members.size}/$MAX_MEMBERS bergabung", color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        MemberStrip(room)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallChip(if (!room.voiceOn) "Nyalakan suara" else if (room.micOn) "Mik aktif" else "Mik mati", room.voiceOn && room.micOn, if (room.voiceOn && room.micOn) Ic.Mic else Ic.MicOff) { mic() }
            SmallChip(if (room.camOn) "Kamera aktif" else "Nyalakan kamera", room.camOn, if (room.camOn) Ic.Cam else Ic.CamOff) { cam() }
            if (room.voiceOn) SmallChip("Matikan suara", false) { room.disableVoice() }
            if (room.isHost) SmallChip(if (room.info?.ctl == "all") "Semua boleh kendali: ya" else "Semua boleh kendali: tidak", room.info?.ctl == "all") { room.setControl(room.info?.ctl != "all") }
            else Text(if (room.info?.ctl == "all") "Semua boleh mengendalikan" else "Hanya host yang mengendalikan", color = C.Subtle, fontSize = 12.sp)
        }
        if (room.voiceError.isNotEmpty()) Text(room.voiceError, color = Color(0xFFFF8A80), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        if (room.camOn || room.videos.isNotEmpty()) { Spacer(Modifier.height(8.dp)); VideoStrip(room) }
        Spacer(Modifier.height(10.dp))
        ChatBox(room, Modifier.weight(1f), size = 16)
    }
}

/** Layar nobar: gabung dengan kode, lanjutkan ruangan lama, atau (dari film) buat ruangan; lalu lobi. */
@Composable
fun NobarScreen(s: AppState, filmId: String?, onBack: () -> Unit, onWatch: (Film) -> Unit) {
    val nb = s.nobar
    val room = nb.room
    val scope = rememberCoroutineScope()
    val film = filmId?.let { s.find(it) }
    var code by remember { mutableStateOf("") }
    var confirmClose by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Link undangan: gabung otomatis ke kode yang dikirim teman
    LaunchedEffect(s.pendingJoin, room == null) {
        val c = s.pendingJoin
        if (c != null && room == null && !nb.busy) { s.pendingJoin = null; code = c; nb.join(c) { s.nickname } }
        else if (c != null && room != null) s.pendingJoin = null
    }

    BackHandler { nb.leave(); onBack() }
    // Ruangan ditutup host
    LaunchedEffect(room?.status) { if (room != null && room.status == "closed") { nb.forget(room.code); nb.leave(); onBack() } }

    Column(Modifier.fillMaxSize().background(C.Navy).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Kembali", { nb.leave(); onBack() })
            Spacer(Modifier.width(12.dp))
            Text("Nonton Bareng", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(16.dp))

        if (room == null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (film != null) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(16.dp)) {
                        Text("Buat ruangan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(film.name, color = C.Aqua, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Kamu jadi host. Ruangan tetap aktif sampai kamu menutupnya. Maks $MAX_MEMBERS orang termasuk kamu.", color = C.Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        AButton(if (nb.busy) "MEMBUAT…" else "BUAT RUANGAN", Ic.People, true, Modifier.fillMaxWidth()) {
                            if (!nb.busy) scope.launch { nb.create(film) { s.nickname } }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (nb.history.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(16.dp)) {
                        Text("Ruangan sebelumnya", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(6.dp))
                        nb.history.forEach { h ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(h.filmName.ifBlank { "Film" }, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${h.code}${if (h.host) " · kamu host" else ""}", color = C.Muted, fontSize = 12.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.clip(RoundedCornerShape(50)).background(C.Aqua).clickable { if (!nb.busy) scope.launch { nb.join(h.code) { s.nickname } } }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("LANJUTKAN", color = C.OnAqua, fontSize = 12.sp, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text("Gabung dengan kode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Punya kode dari teman? Masukkan di sini.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    Spacer(Modifier.height(10.dp))
                    TextInput(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, "Kode 6 karakter", caps = true,
                        onDone = { if (!nb.busy) scope.launch { nb.join(code) { s.nickname } } })
                    Spacer(Modifier.height(10.dp))
                    AButton(if (nb.busy) "MENYAMBUNG…" else "GABUNG", null, false, Modifier.fillMaxWidth()) {
                        if (!nb.busy) scope.launch { nb.join(code) { s.nickname } }
                    }
                }
                if (nb.busy) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Aqua, modifier = Modifier.size(24.dp)) }
                if (nb.error.isNotEmpty()) Text(nb.error, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
            return@Column
        }

        // ---- Lobi ----
        val info = room.info
        val f = info?.let { s.find(it.filmId) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(room.code, color = C.Aqua, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Text(info?.filmName ?: "Memuat…", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Ic.Copy, "Salin kode", { clip.setText(AnnotatedString(room.code)) })
            Spacer(Modifier.width(6.dp))
            IconBtn(Ic.Share, "Bagikan undangan", {
                val msg = "Ayo nobar${info?.filmName?.let { " \"$it\"" } ?: ""} bareng di Aphodius!\nKode ruangan: ${room.code}\nBuka link: aphodius://join/${room.code}\n(atau Aphodius > Nobar > Gabung dengan kode)"
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, msg) }
                runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Bagikan undangan")) }
            })
        }
        if (room.status == "offline") Text("Koneksi terputus, mencoba menyambung lagi…", color = C.Gold, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        LobbyPanel(room, Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        if (info != null && f == null) {
            Text("Film ini perlu fitur Adults/Extraordinary yang aktif di profilmu. Aktifkan dulu di Profil untuk ikut menonton; chat dan suara tetap jalan.", color = C.Gold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (confirmClose) {
            Text("Tutup ruangan untuk semua orang? Ruangan tidak bisa dibuka lagi.", color = C.Gold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AButton("BATAL", null, false, Modifier.weight(1f)) { confirmClose = false }
                AButton("YA, TUTUP", null, true, Modifier.weight(1f)) { nb.close(); onBack() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AButton("KELUAR", null, false, Modifier.weight(1f)) { nb.leave(); onBack() }
                AButton(if (room.isHost) "TONTON" else "IKUT TONTON", Ic.PlayFill, f != null, Modifier.weight(1.5f)) { if (f != null) onWatch(f) }
            }
            if (room.isHost) Text("Tutup ruangan", color = C.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally).clickable { confirmClose = true }.padding(top = 10.dp, bottom = 2.dp, start = 12.dp, end = 12.dp))
            else Text("Keluar tidak menutup ruangan. Kamu bisa lanjut lagi dari Nobar di Beranda.", color = C.Subtle, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
