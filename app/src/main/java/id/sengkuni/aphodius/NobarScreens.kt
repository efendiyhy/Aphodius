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
private fun TextInput(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, caps: Boolean = false, onDone: () -> Unit = {}) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(C.SurfaceHigh).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, color = C.Subtle, fontSize = 14.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(C.Aqua),
            keyboardOptions = KeyboardOptions(capitalization = if (caps) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onDone() }, onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Daftar anggota + pengaturan host + mikrofon + chat. Dipakai di lobi dan di panel pemutar. */
@Composable
fun RoomPanel(room: NobarRoom, modifier: Modifier = Modifier, showChatBox: Boolean = true) {
    val mic = rememberMicAction(room)
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(room.chat.size) { if (room.chat.isNotEmpty()) listState.animateScrollToItem(room.chat.size - 1) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Anggota ${room.members.size}/$MAX_MEMBERS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            val on = room.voiceOn
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (on && room.micOn) C.Aqua else C.SurfaceHigh).clickable { mic() }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (on && room.micOn) C.OnAqua else Color.White
                Icon(if (on && room.micOn) Ic.Mic else Ic.MicOff, null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (!on) "Nyalakan suara" else if (room.micOn) "Mik aktif" else "Mik mati", color = fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (room.voiceOn) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(C.SurfaceHigh).clickable { room.disableVoice() }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("Matikan", color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        room.members.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name, 28)
                Spacer(Modifier.width(10.dp))
                Text(
                    m.name + if (m.uid == room.me) " (kamu)" else "", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (m.uid == room.info?.host) {
                    Spacer(Modifier.width(6.dp))
                    Text("HOST", color = C.Gold, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.weight(1f))
                if (m.voice) {
                    val st = room.peerState[m.uid]
                    val hint = if (m.uid == room.me) "" else when (st) { "ok" -> ""; "gagal" -> " gagal tersambung"; null -> ""; else -> " menyambung…" }
                    if (hint.isNotEmpty()) Text(hint, color = C.Subtle, fontSize = 11.sp)
                    Icon(if (m.mic) Ic.Mic else Ic.MicOff, null, tint = if (m.mic) C.Aqua else C.Subtle, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (room.isHost) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.SurfaceHigh).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Semua boleh mengendalikan", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Anggota bisa jeda, putar, dan geser waktu", color = C.Muted, fontSize = 11.sp)
                }
                Switch(
                    checked = room.info?.ctl == "all", onCheckedChange = { room.setControl(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Aqua, checkedThumbColor = C.OnAqua),
                )
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(if (room.info?.ctl == "all") "Semua anggota boleh mengendalikan pemutaran." else "Hanya host yang mengendalikan pemutaran.", color = C.Muted, fontSize = 11.sp)
        }
        if (showChatBox) {
            Spacer(Modifier.height(10.dp))
            Text("Chat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = true).clip(RoundedCornerShape(12.dp)).background(C.Surface).padding(10.dp), state = listState) {
                if (room.chat.isEmpty()) item { Text("Belum ada pesan.", color = C.Subtle, fontSize = 12.sp) }
                items(room.chat, key = { it.id }) { m ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(m.name + ": ", color = if (m.uid == room.me) C.Aqua else C.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(m.text, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextInput(draft, { draft = it.take(300) }, "Tulis pesan…", Modifier.weight(1f), onDone = { room.send(draft); draft = "" })
                Spacer(Modifier.width(8.dp))
                IconBtn(Ic.Send, "Kirim", { room.send(draft); draft = "" }, size = 44.dp, tint = C.Aqua)
            }
        }
    }
}

/** Layar nobar: masuk dengan kode / buat ruangan, lalu lobi. */
@Composable
fun NobarScreen(s: AppState, filmId: String?, onBack: () -> Unit, onWatch: (Film) -> Unit) {
    val nb = s.nobar
    val room = nb.room
    val scope = rememberCoroutineScope()
    val film = filmId?.let { s.find(it) }
    var code by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current

    BackHandler { nb.leave(); onBack() }
    // Ruangan ditutup host
    LaunchedEffect(room?.status) { if (room != null && room.status == "closed") { nb.leave(); onBack() } }

    Column(Modifier.fillMaxSize().background(C.Navy).statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Kembali", { nb.leave(); onBack() })
            Spacer(Modifier.width(12.dp))
            Text("Nonton Bareng", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(16.dp))

        if (room == null) {
            if (film != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(16.dp)) {
                    Text("Buat ruangan", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(film.name, color = C.Aqua, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Kamu jadi host. Bagikan kode ke teman (maks $MAX_MEMBERS orang termasuk kamu).", color = C.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    AButton(if (nb.busy) "MEMBUAT…" else "BUAT RUANGAN", Ic.People, true, Modifier.fillMaxWidth()) {
                        if (!nb.busy) scope.launch { nb.create(film) { s.nickname } }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(16.dp)) {
                Text("Gabung dengan kode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (film == null) Text("Mau membuat ruangan? Buka film lalu pilih Nobar.", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                TextInput(code, { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) }, "Kode 6 karakter", caps = true,
                    onDone = { if (!nb.busy) scope.launch { nb.join(code) { s.nickname } } })
                Spacer(Modifier.height(10.dp))
                AButton(if (nb.busy) "MENYAMBUNG…" else "GABUNG", null, film == null, Modifier.fillMaxWidth()) {
                    if (!nb.busy) scope.launch { nb.join(code) { s.nickname } }
                }
            }
            if (nb.busy) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Aqua, modifier = Modifier.size(24.dp)) }
            if (nb.error.isNotEmpty()) Text(nb.error, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            return@Column
        }

        // ---- Lobi ----
        val info = room.info
        val f = info?.let { s.find(it.filmId) }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("KODE RUANGAN", color = C.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(room.code, color = C.Aqua, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Text(info?.filmName ?: "Memuat…", color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Ic.Copy, "Salin kode", { clip.setText(AnnotatedString(room.code)) })
        }
        if (room.status == "offline") Text("Koneksi terputus, mencoba menyambung lagi…", color = C.Gold, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        RoomPanel(room, Modifier.weight(1f))
        Spacer(Modifier.height(10.dp))
        if (info != null && f == null) {
            Text("Film ini perlu fitur Adults/Extraordinary yang aktif di profilmu. Aktifkan dulu di Profil untuk ikut menonton, tetap bisa chat dan bersuara di sini.", color = C.Gold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AButton("KELUAR", null, false, Modifier.weight(1f)) { nb.leave(); onBack() }
            AButton(if (room.isHost) "TONTON" else "IKUT TONTON", Ic.PlayFill, f != null, Modifier.weight(1.5f)) { if (f != null) onWatch(f) }
        }
    }
}
