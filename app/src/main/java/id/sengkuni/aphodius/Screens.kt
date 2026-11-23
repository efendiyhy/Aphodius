package id.sengkuni.aphodius

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Beranda", Ic.Home), New("Baru", Ic.Eps), Movie("Movie", Ic.Play), Series("Series", Ic.Series), MyList("Daftarku", Ic.Bookmark), Extra("Extraordinary", Ic.Star)
}

// Gradien dasar poster pengganti (sama dengan mockup di folder portrait/ dan landscape/)
private val gradients = listOf(
    listOf(Color(0xFF0A84C6), Color(0xFF05263F)),
    listOf(Color(0xFF22C7EC), Color(0xFF0A3A6B)),
    listOf(Color(0xFF3A9BD9), Color(0xFF0B2A4A)),
    listOf(Color(0xFF1B6CA8), Color(0xFF061A2E)),
    listOf(Color(0xFF5FB8E6), Color(0xFF123C5E)),
    listOf(Color(0xFF0E94C4), Color(0xFF04121F)),
)

fun meta(f: Film): String = listOfNotNull(f.year?.toString(), f.country.takeIf { it.isNotBlank() }, f.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")).joinToString(" • ")

val Film.kind: String get() = if (isSeries) "Series" else "Film"


/* ---------------- Util tambahan ---------------- */
/** Jumlah kolom grid mengikuti lebar layar (tablet/layar lipat dapat lebih banyak kolom). */
@Composable
fun gridCols(): Int = (LocalConfiguration.current.screenWidthDp / 120).coerceIn(3, 8)

val LocalApp = compositionLocalOf<AppState?> { null }

/** Lencana "Selesai" untuk film yang sudah ditonton (95% atau lebih). */
@Composable
fun BoxScope.DoneBadge(f: Film) {
    val s = LocalApp.current ?: return
    if (f.isSeries) return
    if ((s.progress[f.id]?.frac ?: 0f) > 0.95f) {
        Text(
            "Selesai", color = C.OnAqua, fontSize = 12.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(50)).background(C.Aqua).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Kolom cari di bagian atas layar: ketuk untuk membuka pencarian. */
@Composable
fun SearchBarEntry(hint: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp).clip(RoundedCornerShape(24.dp)).background(C.SurfaceHigh)
            .clickable(onClickLabel = "Buka pencarian", onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Search, null, tint = C.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(hint, color = C.Muted, fontSize = 14.sp)
    }
}

/** Tarik ke bawah dari paling atas untuk memuat ulang. */
@Composable
fun rememberPullState(listState: androidx.compose.foundation.lazy.LazyListState, onRefresh: () -> Unit): Pair<androidx.compose.ui.input.nestedscroll.NestedScrollConnection, Float> {
    var pull by remember { mutableFloatStateOf(0f) }
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    val cb = rememberUpdatedState(onRefresh)
    val conn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (pull > 0f && available.y < 0f) { val d = minOf(pull, -available.y); pull -= d; return androidx.compose.ui.geometry.Offset(0f, -d) }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.Drag && available.y > 0f) { pull = (pull + available.y * 0.6f).coerceAtMost(threshold * 1.4f); return available }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (pull >= threshold) cb.value()
                pull = 0f
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    return conn to (pull / threshold).coerceIn(0f, 1f)
}

/** Kilau abu-abu berdenyut sebagai placeholder saat gambar belum siap. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "sk")
    val a by tr.animateFloat(0.04f, 0.16f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(modifier.background(Color.White.copy(alpha = a)))
}

/* ---------------- Poster ---------------- */
/** Poster + judul di bawahnya (maks 1 baris, dipotong dengan …). */
@Composable
fun PosterCard(f: Film, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Box { Poster(f, Modifier.fillMaxWidth().aspectRatio(2f / 3f)); DoneBadge(f) }
        Text(f.name, fontSize = 12.sp, color = Color.White.copy(alpha = .88f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
fun Poster(film: Film, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)) {
    val seed = film.id.hashCode() and 0x7fffffff
    val g = gradients[seed % gradients.size]
    Box(modifier.clip(shape).background(Brush.linearGradient(g)), contentAlignment = Alignment.Center) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val thumb by produceState<String?>(null, film.id, film.poster, Thumbs.enabled) {
            value = if (Thumbs.enabled && film.poster.isBlank() && !film.isSeries) Thumbs.get(ctx, film.link) else null
        }
        if (film.poster.isBlank() && thumb != null) {
            AsyncImage(thumb, film.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else if (film.poster.isNotBlank()) {
            // Poster base64 didecode di thread latar (lazy) agar daftar panjang tetap mulus
            val bytes by produceState<ByteArray?>(null, film.poster) {
                value = if (film.poster.startsWith("data:")) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    try { Base64.decode(film.poster.substringAfter("base64,"), Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) }
                } else null
            }
            val isData = film.poster.startsWith("data:")
            var ready by remember(film.poster) { mutableStateOf(false) }
            if (!isData || bytes != null) {
                val req = coil.request.ImageRequest.Builder(ctx).data(if (isData) bytes else film.poster)
                    .memoryCacheKey(if (isData) "p_${film.id}_${film.poster.length}" else film.poster).crossfade(true).build()
                AsyncImage(req, film.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onSuccess = { ready = true }, onError = { ready = true })
            }
            if (!ready) Skeleton(Modifier.fillMaxSize())
        } else {
            // Tanpa gambar: pola segitiga (low-poly) yang unik per film
            Facets(seed, Modifier.fillMaxSize())
            Icon(Ic.Play, null, tint = Color.White.copy(alpha = .55f), modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun Facets(seed: Int, modifier: Modifier) {
    Canvas(modifier) {
        val r = java.util.Random(seed.toLong())
        val cols = 3
        val rows = 4
        val cw = size.width / cols
        val ch = size.height / rows
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val x = i * cw
                val y = j * ch
                val a = Path()
                val b = Path()
                if (r.nextBoolean()) {
                    a.moveTo(x, y); a.lineTo(x + cw, y); a.lineTo(x, y + ch); a.close()
                    b.moveTo(x + cw, y); b.lineTo(x + cw, y + ch); b.lineTo(x, y + ch); b.close()
                } else {
                    a.moveTo(x, y); a.lineTo(x + cw, y); a.lineTo(x + cw, y + ch); a.close()
                    b.moveTo(x, y); b.lineTo(x + cw, y + ch); b.lineTo(x, y + ch); b.close()
                }
                drawPath(a, Color.White.copy(alpha = 0.02f + r.nextFloat() * 0.10f))
                drawPath(b, Color.Black.copy(alpha = 0.02f + r.nextFloat() * 0.16f))
            }
        }
    }
}

/* ---------------- Splash ---------------- */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B4A75), C.Navy))), contentAlignment = Alignment.Center) {
        LowPolyBackdrop(Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BeetleMark(132.dp)
            Spacer(Modifier.height(26.dp))
            Wordmark(22.dp)
            Spacer(Modifier.height(12.dp))
            Text("TONTON TANPA BATAS", color = C.Muted, fontSize = 12.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(30.dp))
            CircularProgressIndicator(color = C.Aqua, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("dari", color = C.Subtle, fontSize = 12.sp)
            Text("SengkuniDeveloper", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/* ---------------- Navigasi bawah ---------------- */
@Composable
fun BottomNav(tab: Tab, extra: Boolean, onTab: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Outline))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.values().filter { if (extra) it != Tab.MyList else it != Tab.Extra }.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onTab(t) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.width(56.dp).height(30.dp).clip(RoundedCornerShape(15.dp))
                            .background(if (on) C.Aqua.copy(alpha = .16f) else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(t.icon, t.label, tint = if (on) C.Aqua else C.Muted, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) C.Aqua else C.Muted)
                }
            }
        }
    }
}

/* ---------------- Komponen bersama ---------------- */
@Composable
fun SectionTitle(text: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).rotate(45f).background(C.Aqua))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterRow(films: List<Film>, s: AppState, onOpen: (Film) -> Unit, showProgress: Boolean = false) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(films, key = { it.id }) { f ->
            Column(Modifier.width(118.dp).combinedClickable(onClick = { onOpen(f) }, onLongClick = { if (showProgress) menuFor = f.id })) {
                Box {
                    Poster(f, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    if (!showProgress) DoneBadge(f)
                    androidx.compose.material3.DropdownMenu(expanded = menuFor == f.id, onDismissRequest = { menuFor = null }, modifier = Modifier.background(C.Surface)) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Hapus dari Lanjutkan", color = Color.White) },
                            onClick = { s.hideFromContinue(f); menuFor = null },
                        )
                    }
                    if (showProgress) {
                        val p = s.lastProg(f)?.frac ?: 0f
                        Box(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)
                                .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f))))
                        )
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 9.dp)
                                .fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .3f))
                        ) {
                            Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(C.Aqua))
                        }
                    }
                }
                Text(
                    f.name, fontSize = 12.sp, color = Color.White.copy(alpha = .85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
            }
        }
    }
}

@Composable
fun EmptyState(s: AppState, text: String) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BeetleMark(64.dp, Modifier.alpha(.55f))
        Spacer(Modifier.height(18.dp))
        val ctx = LocalContext.current
        if (s.netError && s.films.isEmpty()) {
            Text("Belum bisa memuat daftar film.", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("Periksa koneksi internetmu, lalu tekan Muat ulang.", color = C.Muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            Text("Detail teknis: ${s.lastError}", color = C.Subtle, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        } else if (s.films.isEmpty() && s.loaded) {
            Text("Belum ada film di daftar", color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("1. Buka Master Stream di browser\n2. Masuk, lalu tempel link video X dan simpan\n3. Kembali ke sini, tekan Muat ulang", color = C.Muted, textAlign = TextAlign.Start, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(14.dp))
            AButton("Buka Master Stream", Ic.Search, false) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ekinstru.web.app/master-stream/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                s.suppressLock()
            }
        } else Text(text, color = C.Muted, textAlign = TextAlign.Center)
        if (s.netError && s.films.isNotEmpty()) {
            Text("Gagal memuat data.", color = C.Subtle, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        } else if (s.loaded && s.lastRefresh != 0L) {
            val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.lastRefresh))
            Text("Data web terakhir dimuat: $t", color = C.Subtle, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        AButton(if (s.refreshing) "Memuat…" else "Muat ulang", Ic.Refresh, true) { scope.launch { s.refresh() } }
    }
}

/* ---------------- BERANDA ---------------- */
@Composable
fun HomeScreen(s: AppState, onOpen: (Film) -> Unit, onPlay: (Film) -> Unit, onSearch: () -> Unit, onProfile: () -> Unit, onNobar: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    val land = LocalConfiguration.current.screenWidthDp > 600
    val homeCols = gridCols()
    val used = remember(s.films) { s.films.flatMap { it.genres }.distinct().sorted() }
    val genre = picked?.takeIf { it in used }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val (pullConn, pullFrac) = rememberPullState(listState) {
        scope.launch { s.refresh(); if (s.refreshNote.isEmpty()) s.refreshNote = "Sudah yang terbaru" }
    }
    LaunchedEffect(s.refreshNote) { if (s.refreshNote.isNotEmpty()) { kotlinx.coroutines.delay(3000); s.refreshNote = "" } }
    Box(Modifier.fillMaxSize().nestedScroll(pullConn)) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BeetleMark(32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Wordmark(13.dp)
                }
                IconBtn(Ic.Refresh, "Muat ulang", { scope.launch { s.refresh() } }, tint = if (s.refreshing) C.Aqua else Color.White)
                Spacer(Modifier.width(8.dp))
                IconBtn(Ic.People, "Nobar", onNobar)
                Spacer(Modifier.width(8.dp))
                IconBtn(Ic.Search, "Cari", onSearch)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = "Buka profil") { onProfile() }, contentAlignment = Alignment.Center) { Avatar(s.nickname, 40) }
            }
        }
        if (s.nickname.isNotBlank()) item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 14.dp)) {
                Text("Halo,", color = Color(0xFFB4C7D9), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(s.nickname, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Mau nonton apa hari ini?", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (used.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip("Semua", genre == null) { picked = null } }
                items(used) { g -> Chip(g, genre == g) { picked = g } }
            }
        }
        if (s.films.isEmpty()) {
            item { EmptyState(s, if (s.loaded) "Belum ada film.\nTambahkan lewat web Master Stream, lalu tekan Sinkron." else "Memuat data…") }
        } else if (genre != null) {
            val cols = homeCols
            items(s.films.filter { genre in it.genres }.chunked(cols)) { rowFilms ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowFilms.forEach { f -> PosterCard(f, Modifier.weight(1f)) { onOpen(f) } }
                    repeat(cols - rowFilms.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            val hero = s.films.firstOrNull { it.recommended } ?: s.films.first()
            item {
                val h = if (land) 280.dp else 460.dp
                Box(Modifier.padding(top = 16.dp, start = 16.dp, end = 16.dp).fillMaxWidth().height(h).clip(RoundedCornerShape(24.dp)).clickable { onOpen(hero) }) {
                    Poster(hero, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to C.Navy.copy(alpha = .96f))))
                    Row(
                        Modifier.align(Alignment.TopStart).padding(14.dp).clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = .45f)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).rotate(45f).background(C.Gold))
                        Spacer(Modifier.width(8.dp))
                        Text("SOROTAN", color = C.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    }
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                        Text(hero.name, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(meta(hero), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AButton("PUTAR", Ic.PlayFill, true, Modifier.weight(1f)) { onPlay(hero) }
                            AButton(if (hero.id in s.favs) "DI DAFTARKU" else "DAFTARKU", if (hero.id in s.favs) Ic.Check else Ic.Plus, false, Modifier.weight(1f)) { s.toggleFav(hero.id) }
                        }
                    }
                }
            }
            val cont = s.films.filter { it.id !in s.hiddenCont && (s.lastProg(it)?.frac ?: 0f) in 0.02f..0.95f }.sortedByDescending { s.lastProg(it)?.ts ?: 0L }
            if (cont.isNotEmpty()) {
                item { SectionTitle("Lanjutkan Menonton") }
                item { PosterRow(cont, s, onPlay, showProgress = true) }
            }
            val rec = s.films.filter { it.recommended }
            if (rec.isNotEmpty()) {
                item { SectionTitle("Rekomendasi untukmu") }
                item { PosterRow(rec, s, onOpen) }
            }
            val pop = s.films.filter { it.popular }
            if (pop.isNotEmpty()) {
                item { SectionTitle("Populer") }
                item { PosterRow(pop, s, onOpen) }
            }
            item { SectionTitle("Baru Ditambah") }
            item { PosterRow(s.films.take(20), s, onOpen) }
            used.forEach { g ->
                val list = s.films.filter { g in it.genres }
                if (list.size >= 2) {
                    item { SectionTitle(g) }
                    item { PosterRow(list, s, onOpen) }
                }
            }
        }
    }
    if (pullFrac > 0f || s.refreshing) {
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp).size(36.dp).clip(CircleShape).background(C.Surface), contentAlignment = Alignment.Center) {
            if (s.refreshing) CircularProgressIndicator(color = C.Aqua, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else CircularProgressIndicator(progress = { pullFrac }, color = C.Aqua, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    }
    if (s.refreshNote.isNotEmpty()) {
        Text(s.refreshNote, color = C.OnAqua, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).clip(RoundedCornerShape(50)).background(C.Aqua).padding(horizontal = 16.dp, vertical = 8.dp))
    }
    }
}

/* ---------------- CARI ---------------- */
@Composable
fun SearchScreen(s: AppState, onBack: () -> Unit, onOpen: (Film) -> Unit, onPlay: (Film) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, s.films) {
        val k = q.trim().lowercase()
        if (k.isEmpty()) s.films else s.films.filter { (it.name + " " + (it.year ?: "") + " " + it.country + " " + it.genres.joinToString(" ")).lowercase().contains(k) }
    }
    val fieldShape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth().clip(fieldShape).background(C.Surface).border(1.dp, C.Outline, fieldShape)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Back, "Kembali", tint = Color.White, modifier = Modifier.size(22.dp).clickable { onBack() })
            Spacer(Modifier.width(12.dp))
            Icon(Ic.Search, null, tint = C.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Cari judul, negara, tahun, genre", color = C.Subtle, fontSize = 15.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AFont, color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth())
            }
            if (q.isNotEmpty()) Icon(Ic.Close, "Hapus", tint = C.Muted, modifier = Modifier.size(20.dp).clickable { q = "" })
        }
        Text(
            if (q.isBlank()) "PENCARIAN TERATAS" else "HASIL (${list.size})",
            fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = C.Muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(f) }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(f, Modifier.width(56.dp).aspectRatio(2f / 3f), RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(f.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(f.kind, f.year?.toString(), f.country.takeIf { it.isNotBlank() }).joinToString(" • "), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    IconBtn(Ic.PlayFill, "Putar", { onPlay(f) }, tint = C.Aqua)
                }
            }
            if (list.isEmpty()) item { Text("Tidak ada hasil.", color = C.Muted, modifier = Modifier.padding(24.dp)) }
        }
    }
}

/* ---------------- BARU ---------------- */
@Composable
fun NewScreen(s: AppState, onOpen: (Film) -> Unit, onPlay: (Film) -> Unit) {
    val df = remember { SimpleDateFormat("MMM", Locale("id", "ID")) }
    val dd = remember { SimpleDateFormat("d", Locale("id", "ID")) }
    val now = remember { System.currentTimeMillis() }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text("Baru dan Populer", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) }
        if (s.films.isEmpty()) item { EmptyState(s, "Belum ada film.") }
        items(s.films, key = { it.id }) { f ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column(Modifier.width(48.dp)) {
                    if (f.added > 0) {
                        Text(df.format(Date(f.added)).uppercase(), color = C.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(dd.format(Date(f.added)), fontSize = 26.sp, fontWeight = FontWeight.Black)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).clickable { onOpen(f) }, contentAlignment = Alignment.Center) {
                        Poster(f, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                        IconBtn(Ic.PlayFill, "Putar", { onPlay(f) }, glass = true, size = 48.dp)
                        if (f.added > 0 && now - f.added < 604_800_000L) {
                            Text(
                                "BARU", color = C.OnAqua, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(50)).background(C.Gold).padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    Text(f.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                    Text(meta(f), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/* ---------------- EXTRAORDINARY ---------------- */
@Composable
fun ExtraScreen(s: AppState, onOpen: (Film) -> Unit) {
    val land = LocalConfiguration.current.screenWidthDp > 600
    val cols = gridCols()
    val list = s.extraFilms
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Extraordinary", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.weight(1f))
                if (list.isNotEmpty()) Text("${list.size} judul", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(C.SurfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        if (list.isEmpty()) item { Text("Belum ada film bergenre Extraordinary.", color = C.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp)) }
        items(list.chunked(cols)) { rowFilms ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowFilms.forEach { f -> PosterCard(f, Modifier.weight(1f)) { onOpen(f) } }
                repeat(cols - rowFilms.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/* ---------------- DAFTARKU ---------------- */
@Composable
fun MyListScreen(s: AppState, onOpen: (Film) -> Unit) {
    val land = LocalConfiguration.current.screenWidthDp > 600
    val cols = gridCols()
    var sort by remember { mutableIntStateOf(0) }
    val list = s.films.filter { it.id in s.favs }.let { l ->
        when (sort) {
            1 -> l.sortedBy { it.name.lowercase() }
            2 -> l.sortedByDescending { s.lastProg(it)?.ts ?: 0L }
            else -> l
        }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Daftarku", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (list.isNotEmpty()) {
                    Text(
                        "${list.size} judul", color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(C.SurfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (list.size > 1) item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Terbaru", "Abjad", "Terakhir ditonton").withIndex().toList()) { (i, n) -> Chip(n, sort == i) { sort = i } }
            }
        }
        if (list.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BeetleMark(64.dp, Modifier.alpha(.55f))
                    Spacer(Modifier.height(18.dp))
                    Text("Daftarmu masih kosong.\nTekan “Daftarku” pada sebuah film untuk menyimpannya di sini.", color = C.Muted, textAlign = TextAlign.Center)
                }
            }
        }
        items(list.chunked(cols)) { rowFilms ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowFilms.forEach { f -> PosterCard(f, Modifier.weight(1f)) { onOpen(f) } }
                repeat(cols - rowFilms.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/* ---------------- DETAIL ---------------- */
@Composable
fun DetailScreen(f: Film, s: AppState, onBack: () -> Unit, onPlay: () -> Unit, onPlayEp: (Int) -> Unit = {}, onNobar: () -> Unit = {}) {
    val ctx = LocalContext.current
    val fav = f.id in s.favs
    val prog = s.lastProg(f)
    val resume = prog != null && prog.frac in 0.02f..0.95f
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val heroBox: @Composable (Modifier) -> Unit = { m ->
        Box(m, contentAlignment = Alignment.Center) {
            Poster(f, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent, C.Navy))))
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconBtn(Ic.Back, "Kembali", onBack, glass = true)
            }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).border(2.dp, Color.White, CircleShape).clickable { onPlay() }, contentAlignment = Alignment.Center) {
                Icon(Ic.PlayFill, "Putar", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
    val contentBox: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(f.name, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
            Text(
                listOfNotNull(f.kind, f.year?.toString(), f.country.takeIf { it.isNotBlank() }, f.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" • "),
                color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
            )
            if (f.genres.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    f.genres.forEach { g ->
                        Text(g, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = C.Muted, modifier = Modifier.clip(RoundedCornerShape(50)).background(C.SurfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            if (f.synopsis.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text("Sinopsis", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(f.synopsis, color = Color(0xFFD0DCE8), fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(20.dp))
            val finished = !f.isSeries && prog != null && prog.frac >= 0.95f
            if (finished) Text("Sudah selesai ditonton", color = C.Aqua, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            AButton(if (finished) "MULAI DARI AWAL" else if (resume) "LANJUTKAN ${fmtTime(prog!!.pos)}" else "PUTAR", if (finished) Ic.Refresh else Ic.PlayFill, true, Modifier.fillMaxWidth()) { onPlay() }
            if (resume) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth(prog!!.frac).fillMaxHeight().background(C.Aqua))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(if (fav) Ic.Check else Ic.Plus, if (fav) "Di Daftarku" else "Daftarku", Modifier.weight(1f)) { s.toggleFav(f.id) }
                if (!f.isSeries) ActionTile(Ic.People, "Nobar", Modifier.weight(1f)) { onNobar() }
            }
            if (f.isSeries && f.episodes.isNotEmpty()) {
                SectionTitle("Episode (${f.episodes.size})")
                f.episodes.forEachIndexed { i, e ->
                    val ep = s.progress[s.epFilm(f, i).id]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).clickable { onPlayEp(i) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${e.n}", fontSize = 18.sp, fontWeight = FontWeight.Black, color = C.Aqua, modifier = Modifier.width(34.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (ep != null && ep.frac > 0.02f) {
                                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.SurfaceHigh)) {
                                    Box(Modifier.fillMaxWidth(ep.frac).fillMaxHeight().background(C.Aqua))
                                }
                            }
                        }
                        Icon(Ic.PlayFill, "Putar", tint = C.Aqua, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            heroBox(Modifier.weight(0.4f).fillMaxHeight())
            Column(Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 24.dp, bottom = 24.dp)) { contentBox() }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            heroBox(Modifier.fillMaxWidth().height(360.dp))
            contentBox()
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(C.Surface).border(1.dp, C.Outline, shape).clickable(onClick = onClick).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 12.sp, color = C.Muted, modifier = Modifier.padding(top = 6.dp))
    }
}


fun fmtTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/* ---------------- ONBOARDING ---------------- */
@Composable
fun OnboardingScreen(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val fieldShape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B4A75), C.Navy))).statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        LowPolyBackdrop(Modifier.fillMaxSize())
        Column(Modifier.padding(28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BeetleMark(96.dp)
            Spacer(Modifier.height(22.dp))
            Text("Selamat datang di Aphodius", fontSize = 22.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text("Siapa nama sapaanmu?", color = C.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))
            Box(Modifier.fillMaxWidth().clip(fieldShape).background(C.Surface).border(1.dp, C.Outline, fieldShape).padding(horizontal = 16.dp, vertical = 15.dp)) {
                if (name.isEmpty()) Text("Nama sapaan", color = C.Subtle)
                BasicTextField(name, { if (it.length <= 24) name = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AFont, color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            val ok = name.isNotBlank()
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(if (ok) C.Aqua else Color.White.copy(alpha = .12f)).clickable(enabled = ok) { onDone(name.trim()) },
                contentAlignment = Alignment.Center,
            ) { Text("MULAI", color = if (ok) C.OnAqua else C.Muted, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }
            Text("Profil hanya tersimpan di perangkat ini.", color = C.Subtle, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/* ---------------- MOVIE / SERIES (genre, negara, tahun) ---------------- */
@Composable
fun BrowseScreen(s: AppState, series: Boolean, onOpen: (Film) -> Unit, onSearch: () -> Unit = {}) {
    var mode by remember { mutableStateOf("Genre") }
    val base = remember(s.films, series) { s.films.filter { it.isSeries == series } }
    val groups: List<Pair<String, List<Film>>> = remember(base, mode) {
        when (mode) {
            "Negara" -> base.groupBy { it.country.ifBlank { "Lainnya" } }.toList().sortedWith(compareBy({ it.first == "Lainnya" }, { -it.second.size }))
            "Tahun" -> base.groupBy { it.year?.toString() ?: "Tanpa tahun" }.toList().sortedWith(compareBy<Pair<String, List<Film>>>({ it.first == "Tanpa tahun" }, { -(it.first.toIntOrNull() ?: 0) }))
            else -> base.flatMap { f -> if (f.genres.isEmpty()) listOf("Lainnya" to f) else f.genres.map { it to f } }
                .groupBy({ it.first }, { it.second }).toList().sortedWith(compareBy({ it.first == "Lainnya" }, { it.first }))
        }
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text(if (series) "Series" else "Movie", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) }
        item { SearchBarEntry(if (series) "Cari series…" else "Cari film…", onSearch); Spacer(Modifier.height(12.dp)) }
        if (base.isEmpty()) item {
            if (series) Text("Belum ada series.\nTambahkan lewat web Master Stream dengan tipe Series.", color = C.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp))
            else EmptyState(s, "Belum ada film.")
        }
        if (base.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Genre", "Negara", "Tahun")) { m -> Chip(m, mode == m) { mode = m } }
                }
            }
            val rec = base.filter { it.recommended }
            if (rec.isNotEmpty()) {
                item { SectionTitle("Rekomendasi") }
                item { PosterRow(rec, s, onOpen) }
            }
            val pop = base.filter { it.popular }
            if (pop.isNotEmpty()) {
                item { SectionTitle("Populer") }
                item { PosterRow(pop, s, onOpen) }
            }
        }
        groups.forEach { (g, list) ->
            item(key = "g_${mode}_$g") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).rotate(45f).background(C.Aqua))
                    Spacer(Modifier.width(10.dp))
                    Text(g, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                    Text("${list.size}", color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(50)).background(C.SurfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp))
                }
                PosterRow(list, s, onOpen)
            }
        }
    }
}

/* ---------------- PROFIL & PENGATURAN ---------------- */
@Composable
fun ProfileScreen(s: AppState, onBack: () -> Unit, onHistory: () -> Unit = {}) {
    val ctx = LocalContext.current
    var editName by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(s.nickname) }
    var pinMode by remember { mutableStateOf<String?>(null) } // "set1","set2","off"
    var firstPin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var askPw by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pwErr by remember { mutableStateOf("") }
    var pwFor by remember { mutableStateOf("adults") }
    var wantAdults by remember { mutableStateOf(false) } // aktifkan Adults setelah PIN dibuat
    var info by remember { mutableStateOf("") }

    BackHandler { if (askPw) { askPw = false; pw = ""; pwErr = "" } else if (pinMode != null) { pinMode = null; wantAdults = false } else onBack() }

    if (askPw) {
        Box(Modifier.fillMaxSize().background(C.Navy).statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (pwFor == "extra") "Aktifkan Extraordinary" else "Aktifkan Adults", fontSize = 22.sp, fontWeight = FontWeight.Black)
                Text(if (pwFor == "extra") "Masukkan password untuk membuka menu Extraordinary" else "Masukkan password untuk menampilkan film 21+", color = C.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
                val shape = RoundedCornerShape(16.dp)
                Box(Modifier.fillMaxWidth().clip(shape).background(C.Surface).border(1.dp, C.Outline, shape).padding(horizontal = 16.dp, vertical = 15.dp)) {
                    if (pw.isEmpty()) Text("Password", color = C.Subtle)
                    BasicTextField(
                        pw, { pw = it; pwErr = "" }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AFont, color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(pwErr, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp).height(20.dp))
                Spacer(Modifier.height(8.dp))
                AButton("AKTIFKAN", null, true, Modifier.fillMaxWidth()) {
                    if (!(if (pwFor == "extra") s.checkExtraPassword(pw) else s.checkAdultsPassword(pw))) { pwErr = "Password salah" } else {
                        askPw = false; pw = ""; pwErr = ""
                        if (pwFor == "extra") s.enableExtra(true)
                        else if (s.lockEnabled) s.enableAdults(true) else { wantAdults = true; err = ""; pinMode = "set1" }
                    }
                }
                Spacer(Modifier.height(8.dp))
                AButton("BATAL", null, false, Modifier.fillMaxWidth()) { askPw = false; pw = ""; pwErr = "" }
            }
        }
        return
    }

    if (pinMode != null) {
        Box(Modifier.fillMaxSize().background(C.Navy).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val title = when (pinMode) { "set1" -> "Buat PIN"; "set2" -> "Ulangi PIN"; else -> "Masukkan PIN" }
                val sub = when (pinMode) { "set1" -> "$PIN_LEN digit untuk mengunci aplikasi"; "set2" -> "Masukkan PIN yang sama"; else -> "Untuk mematikan kunci aplikasi" }
                PinPad(title, sub, err, resetTick = tick) { pin ->
                    when (pinMode) {
                        "set1" -> { firstPin = pin; err = ""; pinMode = "set2"; tick++ }
                        "set2" -> if (pin == firstPin) { s.setPin(pin); if (wantAdults) { s.enableAdults(true); wantAdults = false }; pinMode = null; err = "" } else { err = "PIN tidak sama, ulangi"; pinMode = "set1"; tick++ }
                        else -> if (s.checkPin(pin)) { s.clearPin(); pinMode = null; err = "" } else { err = "PIN salah"; tick++ }
                    }
                }
                Text("Batal", color = C.Aqua, modifier = Modifier.padding(top = 18.dp).clickable { pinMode = null; err = ""; wantAdults = false })
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Kembali", onBack)
            Text("Profil", fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 14.dp))
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val pickCtx = LocalContext.current
            val pickScope = rememberCoroutineScope()
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) pickScope.launch(kotlinx.coroutines.Dispatchers.IO) { AvatarStore.save(pickCtx, uri) }
            }
            Box(Modifier.clickable { picker.launch("image/*") }) { Avatar(s.nickname, 96) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (AvatarStore.version >= 0 && AvatarStore.has(pickCtx)) "Ganti foto" else "Tambah foto", color = C.Aqua, fontSize = 13.sp, modifier = Modifier.clickable { picker.launch("image/*") })
                if (AvatarStore.version >= 0 && AvatarStore.has(pickCtx)) Text("Hapus foto", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clickable { AvatarStore.clear(pickCtx) })
            }
            Spacer(Modifier.height(10.dp))
            if (editName) {
                Box(Modifier.width(240.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    BasicTextField(name, { if (it.length <= 24) name = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = AFont, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AButton("Simpan", null, true) { if (name.isNotBlank()) { s.saveNickname(name); editName = false } }
                    AButton("Batal", null, false) { name = s.nickname; editName = false }
                }
            } else {
                Text(s.nickname, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Ubah nama sapaan", color = C.Aqua, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).clickable { editName = true })
            }
        }

        SettingHeader("Keamanan")
        SettingRow("Kunci aplikasi", if (s.lockEnabled) "Aktif — PIN $PIN_LEN digit" else "Mati", s.lockEnabled) {
            if (s.lockEnabled && s.adults) info = "Kunci aplikasi wajib aktif selama Adults menyala. Matikan Adults dulu."
            else if (s.lockEnabled) { info = ""; err = ""; pinMode = "off" } else { info = ""; err = ""; pinMode = "set1" }
        }
        if (s.lockEnabled && biometricAvailable(ctx)) {
            SettingRow("Buka dengan sidik jari", "Alternatif selain PIN", s.biometric) { s.setBiometricPref(!s.biometric) }
        }
        if (info.isNotEmpty()) Text(info, color = Color(0xFFFFB454), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        SettingHeader("Konten")
        SettingRow("Adults", if (s.adults) "Aktif — film 21+ ditampilkan" else "Mati — film 21+ disembunyikan", s.adults) {
            info = ""
            if (s.adults) s.enableAdults(false) else { pwFor = "adults"; pw = ""; pwErr = ""; askPw = true }
        }
        SettingRow("Extraordinary", if (s.extra) "Aktif — menu Extraordinary menggantikan Daftarku" else "Mati — butuh password", s.extra) {
            info = ""
            if (s.extra) s.enableExtra(false) else { pwFor = "extra"; pw = ""; pwErr = ""; askPw = true }
        }

        SettingHeader("Suara")
        SettingRow("Surround bioskop", "Efek suara ruang ala bioskop (butuh headphone/speaker yang mendukung)", s.surround) { s.updateSurround(!s.surround) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Equalizer", fontSize = 15.sp)
            Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EQ_PRESETS.forEach { (n, lv) -> Chip(n, s.eq == lv) { s.updateEq(lv) } }
            }
            EQ_LABELS.forEachIndexed { i, lb ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lb, color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                    androidx.compose.material3.Slider(
                        value = s.eq[i], onValueChange = { v -> s.updateEq(s.eq.toMutableList().also { it[i] = v }) }, valueRange = -1f..1f,
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = C.Aqua, activeTrackColor = C.Aqua, inactiveTrackColor = C.Outline),
                        modifier = Modifier.weight(1f).height(28.dp),
                    )
                }
            }
        }

        SettingHeader("Pemutar")
        SettingRow("Crop video", if (s.hasCrop) "Memakai crop tersimpan dari pemutar" else "Belum ada crop — atur lewat tombol Crop di pemutar", s.cropOn && s.hasCrop) {
            if (s.hasCrop) s.useCrop(!s.cropOn)
        }
        if (s.hasCrop) Text("Hapus crop tersimpan", color = C.Aqua, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { s.clearCrop() })

        SettingRow("Kualitas di data seluler", if (s.mobileQuality == 0) "Mati — ikut pengaturan Kualitas di pemutar" else "${s.mobileQuality}p saat memakai data seluler (WiFi tetap Otomatis)", s.mobileQuality > 0) {
            val opts = listOf(0, 360, 480, 720)
            s.updateMobileQuality(opts[(opts.indexOf(s.mobileQuality) + 1) % opts.size])
        }
        SettingRow("Poster dari video", if (s.thumbPoster) "Aktif — film tanpa poster memakai thumbnail videonya" else "Mati — film tanpa poster memakai gambar pola", s.thumbPoster) { s.updateThumbPoster(!s.thumbPoster) }

        SettingHeader("Riwayat")
        SettingRow("Riwayat tontonan", "${s.progress.size} judul tercatat — ketuk untuk melihat atau menghapus", false) { onHistory() }

        SettingHeader("Pembaruan")
        Text("Aphodius v${BuildConfig.VERSION_NAME}", color = C.Muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        val u = s.update
        if (u != null) UpdateCard(u, s, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        else {
            var checking by remember { mutableStateOf(false) }
            var msg by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            Column(Modifier.padding(horizontal = 16.dp)) {
                AButton(if (checking) "Memeriksa…" else "Cek pembaruan", Ic.Refresh, false) {
                    if (!checking) scope.launch {
                        checking = true; msg = ""
                        val r = Updater.check(); s.update = r
                        msg = if (r == null) "Kamu sudah memakai versi terbaru." else ""
                        checking = false
                    }
                }
                if (msg.isNotEmpty()) Text(msg, color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(C.Outline))
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BeetleMark(36.dp, Modifier.alpha(.8f))
            Text("Aphodius", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text("Dikembangkan oleh SengkuniDeveloper", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            Text("github.com/efendiyhy/Aphodius", color = C.Aqua, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp).clickable {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/efendiyhy/Aphodius")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
    }
}

@Composable
private fun SettingHeader(t: String) =
    Text(t.uppercase(), color = C.Aqua, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))

@Composable
private fun SettingRow(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(sub, color = C.Muted, fontSize = 12.sp)
        }
        androidx.compose.material3.Switch(
            checked = on, onCheckedChange = null,
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = C.Aqua, checkedThumbColor = C.OnAqua),
        )
    }
}

/* ---------------- UPDATE ---------------- */
@Composable
fun UpdateBanner(info: UpdateInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(C.Ocean, C.Aqua))).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Ic.Refresh, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Pembaruan tersedia: v${info.version}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text("Ketuk untuk memasang", color = Color.White.copy(alpha = .85f), fontSize = 12.sp)
        }
        Text("PASANG", color = Color.White, fontWeight = FontWeight.Black, fontSize = 12.sp)
    }
}

@Composable
fun UpdateCard(info: UpdateInfo, s: AppState, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var err by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf<java.io.File?>(null) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(16.dp)).padding(16.dp)) {
        Text("Versi ${info.version} tersedia", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        if (info.size > 0) Text("Ukuran ± ${"%.1f".format(info.size / 1048576f)} MB", color = C.Muted, fontSize = 12.sp)
        if (info.notes.isNotBlank()) Text(info.notes.take(400), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(10.dp))
        val p = progress
        if (p != null) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .2f))) {
                Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(C.Aqua))
            }
            Text("Mengunduh ${(p * 100).toInt()}%", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            AButton(if (ready != null) "Pasang sekarang" else "Unduh & pasang", Ic.Check, true) {
                val f = ready
                if (f != null) { Updater.install(ctx, f); return@AButton }
                scope.launch {
                    err = ""; progress = 0f
                    try {
                        val file = Updater.download(ctx, info) { progress = it }
                        ready = file; progress = null
                        if (!Updater.install(ctx, file)) err = "Izinkan Aphodius memasang aplikasi, lalu tekan “Pasang sekarang”."
                    } catch (e: Exception) {
                        progress = null; err = "Gagal mengunduh: ${e.message}"
                    }
                }
            }
        }
        if (err.isNotEmpty()) Text(err, color = Color(0xFFFF6B6B), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}


/* ---------------- RIWAYAT ---------------- */
@Composable
fun HistoryScreen(s: AppState, onBack: () -> Unit, onOpen: (Film) -> Unit) {
    BackHandler { onBack() }
    var confirmAll by remember { mutableStateOf(false) }
    val rows = s.progress.entries.sortedByDescending { it.value.ts }.mapNotNull { (k, p) ->
        val base = s.find(k.substringBefore('#')) ?: return@mapNotNull null
        Triple(k, p, base)
    }
    Column(Modifier.fillMaxSize().background(C.Navy).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Kembali", onBack)
            Spacer(Modifier.width(12.dp))
            Text("Riwayat tontonan", fontSize = 20.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            if (rows.isNotEmpty()) Text("Hapus semua", color = C.Aqua, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { confirmAll = true }.padding(horizontal = 10.dp, vertical = 12.dp))
        }
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Belum ada riwayat tontonan.", color = C.Muted) }
        } else LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(rows, key = { it.first }) { (k, p, f) ->
                val ep = k.substringAfter('#', "")
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(f) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(f, Modifier.width(48.dp).aspectRatio(2f / 3f), RoundedCornerShape(8.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name + if (ep.isNotEmpty()) " • Ep $ep" else "", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val done = p.frac > 0.95f
                        Text(if (done) "Selesai · ${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(p.ts))}" else "${fmtTime(p.pos)} dari ${fmtTime(p.dur)}", color = if (done) C.Aqua else C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.SurfaceHigh)) { Box(Modifier.fillMaxWidth(p.frac.coerceIn(0f, 1f)).fillMaxHeight().background(C.Aqua)) }
                    }
                    IconBtn(Ic.Close, "Hapus dari riwayat", { s.removeHistory(k) }, size = 48.dp, tint = C.Muted, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    if (confirmAll) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmAll = false }, containerColor = C.Surface,
        title = { Text("Hapus semua riwayat?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = { Text("Posisi terakhir semua film ikut terhapus.", color = C.Muted, fontSize = 13.sp) },
        confirmButton = { Text("HAPUS", color = C.Aqua, fontWeight = FontWeight.Black, modifier = Modifier.clickable { s.clearHistory(); confirmAll = false }.padding(12.dp)) },
        dismissButton = { Text("BATAL", color = C.Muted, modifier = Modifier.clickable { confirmAll = false }.padding(12.dp)) },
    )
}

@Composable
fun OfflineBanner() {
    Text("Kamu offline, menampilkan data tersimpan", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(Color(0xFF7A4B12)).statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp))
}
