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
    val send: () -> Unit = {
        if (draft.isNotBlank()) { room.send(draft); draft = "" }
        runCatching { fr.requestFocus() }
        kb?.show()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(room.chat.lastOrNull()?.id) { if (room.chat.isNotEmpty()) listState.animateScrollToItem(room.chat.size - 1) }
    Column(modifier) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(12.dp), state = listState) {
            if (room.chat.isEmpty()) item { Text("Belum ada pesan. Sapa temanmu!", color = C.Subtle, fontSize = size.sp) }
            items(room.chat, key = { it.id }) { m ->
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = if (m.uid == room.me) C.Aqua else C.Gold, fontWeight = FontWeight.Bold)) { append(m.name + "  ") }
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White)) { append(m.text) }
                    },
                    fontSize = size.sp, lineHeight = (size + 5).sp, modifier = Modifier.padding(vertical = 3.dp),
                )
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

/** Panel chat di sisi kanan pemutar: hanya chat dan jumlah yang bergabung. */
@Composable
fun ChatDock(room: NobarRoom, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(C.Navy).statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Nobar · ${room.code}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("${room.members.size} dari $MAX_MEMBERS bergabung", color = C.Muted, fontSize = 12.sp)
            }
            IconBtn(Ic.Close, "Tutup chat", onClose, size = 34.dp)
        }
        Spacer(Modifier.height(10.dp))
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
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Isi lobi: anggota dan pengaturan dibuat ringkas, chat mengisi sisa layar. */
@Composable
fun LobbyPanel(room: NobarRoom, modifier: Modifier = Modifier) {
    val mic = rememberMicAction(room)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${room.members.size}/$MAX_MEMBERS", color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            room.members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(m.name, 22)
                    Spacer(Modifier.width(5.dp))
                    Text(m.name + if (m.uid == room.info?.host) " ★" else "", color = Color.White, fontSize = 12.sp, maxLines = 1)
                    if (m.voice) { Spacer(Modifier.width(3.dp)); Icon(if (m.mic) Ic.Mic else Ic.MicOff, null, tint = if (m.mic) C.Aqua else C.Subtle, modifier = Modifier.size(12.dp)) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallChip(if (!room.voiceOn) "Nyalakan suara" else if (room.micOn) "Mik aktif" else "Mik mati", room.voiceOn && room.micOn, if (room.voiceOn && room.micOn) Ic.Mic else Ic.MicOff) { mic() }
            if (room.voiceOn) SmallChip("Matikan suara", false) { room.disableVoice() }
            if (room.isHost) SmallChip(if (room.info?.ctl == "all") "Semua boleh kendali: ya" else "Semua boleh kendali: tidak", room.info?.ctl == "all") { room.setControl(room.info?.ctl != "all") }
            else Text(if (room.info?.ctl == "all") "Semua boleh mengendalikan" else "Hanya host yang mengendalikan", color = C.Subtle, fontSize = 11.sp)
        }
        if (room.voiceError.isNotEmpty()) Text(room.voiceError, color = Color(0xFFFF8A80), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
                                    Text("${h.code}${if (h.host) " · kamu host" else ""}", color = C.Muted, fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.clip(RoundedCornerShape(50)).background(C.Aqua).clickable { if (!nb.busy) scope.launch { nb.join(h.code) { s.nickname } } }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("LANJUTKAN", color = C.OnAqua, fontSize = 11.sp, fontWeight = FontWeight.Black)
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
            else Text("Keluar tidak menutup ruangan. Kamu bisa lanjut lagi dari Nobar di Beranda.", color = C.Subtle, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
