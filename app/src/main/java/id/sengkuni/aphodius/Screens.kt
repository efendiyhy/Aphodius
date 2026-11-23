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
    Home("Beranda", Ic.Home), New("Baru", Ic.Eps), Movie("Movie", Ic.Play), MyList("Daftarku", Ic.Bookmark)
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

fun meta(f: Film): String = listOfNotNull(f.year?.toString(), f.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")).joinToString(" • ")

/* ---------------- Poster ---------------- */
@Composable
fun Poster(film: Film, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp)) {
    val seed = film.id.hashCode() and 0x7fffffff
    val g = gradients[seed % gradients.size]
    Box(modifier.clip(shape).background(Brush.linearGradient(g)), contentAlignment = Alignment.Center) {
        if (film.poster.isNotBlank()) {
            val model: Any = remember(film.poster) {
                if (film.poster.startsWith("data:")) {
                    try { Base64.decode(film.poster.substringAfter("base64,"), Base64.DEFAULT) } catch (e: Exception) { "" }
                } else film.poster
            }
            AsyncImage(model, film.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
            Text("TONTON TANPA BATAS", color = C.Muted, fontSize = 11.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(30.dp))
            CircularProgressIndicator(color = C.Aqua, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("dari", color = C.Subtle, fontSize = 11.sp)
            Text("SengkuniDeveloper", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/* ---------------- Navigasi bawah ---------------- */
@Composable
fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.Surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Outline))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab.values().forEach { t ->
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
                    Text(t.label, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) C.Aqua else C.Muted)
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
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PosterRow(films: List<Film>, s: AppState, onOpen: (Film) -> Unit, showProgress: Boolean = false) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(films, key = { it.id }) { f ->
            Column(Modifier.width(118.dp).clickable { onOpen(f) }) {
                Box {
                    Poster(f, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                    if (showProgress) {
                        val p = s.progress[f.id]?.frac ?: 0f
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
        Text(text, color = C.Muted, textAlign = TextAlign.Center)
        if (s.netError) {
            Text("Gagal memuat data: ${s.lastError}", color = C.Subtle, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
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
fun HomeScreen(s: AppState, onOpen: (Film) -> Unit, onPlay: (Film) -> Unit, onSearch: () -> Unit, onProfile: () -> Unit) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    val land = LocalConfiguration.current.screenWidthDp > 600
    val used = remember(s.films) { s.films.flatMap { it.genres }.distinct().sorted() }
    val genre = picked?.takeIf { it in used }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BeetleMark(32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Wordmark(13.dp)
                    if (s.nickname.isNotBlank()) Text("Halo, ${s.nickname}", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                }
                IconBtn(Ic.Refresh, "Muat ulang", { scope.launch { s.refresh() } }, tint = if (s.refreshing) C.Aqua else Color.White)
                Spacer(Modifier.width(8.dp))
                IconBtn(Ic.Search, "Cari", onSearch)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clickable { onProfile() }) { Avatar(s.nickname, 40) }
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
            val cols = if (land) 6 else 3
            items(s.films.filter { genre in it.genres }.chunked(cols)) { rowFilms ->
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowFilms.forEach { f -> Poster(f, Modifier.weight(1f).aspectRatio(2f / 3f).clickable { onOpen(f) }) }
                    repeat(cols - rowFilms.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            val hero = s.films.first()
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
                        Text("SOROTAN", color = C.Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
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
            val cont = s.films.filter { (s.progress[it.id]?.frac ?: 0f) in 0.02f..0.95f }.sortedByDescending { s.progress[it.id]?.ts ?: 0L }
            if (cont.isNotEmpty()) {
                item { SectionTitle("Lanjutkan Menonton") }
                item { PosterRow(cont, s, onPlay, showProgress = true) }
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
}

/* ---------------- CARI ---------------- */
@Composable
fun SearchScreen(s: AppState, onBack: () -> Unit, onOpen: (Film) -> Unit, onPlay: (Film) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, s.films) {
        val k = q.trim().lowercase()
        if (k.isEmpty()) s.films else s.films.filter { (it.name + " " + (it.year ?: "") + " " + it.genres.joinToString(" ")).lowercase().contains(k) }
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
                if (q.isEmpty()) Text("Cari film, tahun, genre", color = C.Subtle, fontSize = 15.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth())
            }
            if (q.isNotEmpty()) Icon(Ic.Close, "Hapus", tint = C.Muted, modifier = Modifier.size(20.dp).clickable { q = "" })
        }
        Text(
            if (q.isBlank()) "PENCARIAN TERATAS" else "HASIL (${list.size})",
            fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = C.Muted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(f) }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(f, Modifier.width(56.dp).aspectRatio(2f / 3f), RoundedCornerShape(10.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(f.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Film" + (f.year?.let { " • $it" } ?: ""), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
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
                        Text(df.format(Date(f.added)).uppercase(), color = C.Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(dd.format(Date(f.added)), fontSize = 26.sp, fontWeight = FontWeight.Black)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).clickable { onOpen(f) }, contentAlignment = Alignment.Center) {
                        Poster(f, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                        IconBtn(Ic.PlayFill, "Putar", { onPlay(f) }, glass = true, size = 48.dp)
                        if (f.added > 0 && now - f.added < 604_800_000L) {
                            Text(
                                "BARU", color = C.OnAqua, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
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

/* ---------------- DAFTARKU ---------------- */
@Composable
fun MyListScreen(s: AppState, onOpen: (Film) -> Unit) {
    val land = LocalConfiguration.current.screenWidthDp > 600
    val cols = if (land) 6 else 3
    val list = s.films.filter { it.id in s.favs }
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
                rowFilms.forEach { f -> Poster(f, Modifier.weight(1f).aspectRatio(2f / 3f).clickable { onOpen(f) }) }
                repeat(cols - rowFilms.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/* ---------------- DETAIL ---------------- */
@Composable
fun DetailScreen(f: Film, s: AppState, onBack: () -> Unit, onPlay: () -> Unit) {
    val ctx = LocalContext.current
    val fav = f.id in s.favs
    val prog = s.progress[f.id]
    val resume = prog != null && prog.frac in 0.02f..0.95f
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val land = LocalConfiguration.current.screenWidthDp > 600
        Box(Modifier.fillMaxWidth().height(if (land) 280.dp else 360.dp), contentAlignment = Alignment.Center) {
            Poster(f, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent, C.Navy))))
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconBtn(Ic.Back, "Kembali", onBack, glass = true)
                IconBtn(Ic.Share, "Bagikan", { shareFilm(ctx, f) }, glass = true)
            }
            Box(Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).border(2.dp, Color.White, CircleShape).clickable { onPlay() }, contentAlignment = Alignment.Center) {
                Icon(Ic.PlayFill, "Putar", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(f.name, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black)
            Text("Film" + (f.year?.let { " • $it" } ?: ""), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
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
            AButton(if (resume) "LANJUTKAN ${fmtTime(prog!!.pos)}" else "PUTAR", Ic.PlayFill, true, Modifier.fillMaxWidth()) { onPlay() }
            if (resume) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.SurfaceHigh)) {
                    Box(Modifier.fillMaxWidth(prog!!.frac).fillMaxHeight().background(C.Aqua))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(if (fav) Ic.Check else Ic.Plus, if (fav) "Di Daftarku" else "Daftarku", Modifier.weight(1f)) { s.toggleFav(f.id) }
                ActionTile(Ic.Share, "Bagikan", Modifier.weight(1f)) { shareFilm(ctx, f) }
                ActionTile(Ic.External, "Buka di X", Modifier.weight(1f)) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Spacer(Modifier.height(28.dp))
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

fun shareFilm(ctx: Context, f: Film) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${f.name}\n${f.link}")
    ctx.startActivity(Intent.createChooser(i, "Bagikan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
                BasicTextField(name, { if (it.length <= 24) name = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
            val ok = name.isNotBlank()
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(if (ok) C.Aqua else Color.White.copy(alpha = .12f)).clickable(enabled = ok) { onDone(name.trim()) },
                contentAlignment = Alignment.Center,
            ) { Text("MULAI", color = if (ok) C.OnAqua else C.Muted, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }
            Text("Profil hanya tersimpan di perangkat ini.", color = C.Subtle, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/* ---------------- MOVIE (per genre) ---------------- */
@Composable
fun MovieScreen(s: AppState, onOpen: (Film) -> Unit) {
    val groups = remember(s.films) {
        s.films.flatMap { f -> f.genres.map { it to f } }.groupBy({ it.first }, { it.second }).toSortedMap()
    }
    val none = remember(s.films) { s.films.filter { it.genres.isEmpty() } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Text("Movie", fontSize = 26.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) }
        if (s.films.isEmpty()) item { EmptyState(s, "Belum ada film.") }
        groups.forEach { (g, list) ->
            item(key = "g_$g") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).rotate(45f).background(C.Aqua))
                    Spacer(Modifier.width(10.dp))
                    Text(g, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${list.size}", color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(50)).background(C.SurfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp))
                }
                PosterRow(list, s, onOpen)
            }
        }
        if (none.isNotEmpty()) item(key = "g_none") {
            SectionTitle("Lainnya")
            PosterRow(none, s, onOpen)
        }
    }
}

/* ---------------- PROFIL & PENGATURAN ---------------- */
@Composable
fun ProfileScreen(s: AppState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var editName by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(s.nickname) }
    var pinMode by remember { mutableStateOf<String?>(null) } // "set1","set2","off"
    var firstPin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }

    BackHandler { if (pinMode != null) pinMode = null else onBack() }

    if (pinMode != null) {
        Box(Modifier.fillMaxSize().background(C.Navy).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val title = when (pinMode) { "set1" -> "Buat PIN"; "set2" -> "Ulangi PIN"; else -> "Masukkan PIN" }
                val sub = when (pinMode) { "set1" -> "$PIN_LEN digit untuk mengunci aplikasi"; "set2" -> "Masukkan PIN yang sama"; else -> "Untuk mematikan kunci aplikasi" }
                PinPad(title, sub, err, resetTick = tick) { pin ->
                    when (pinMode) {
                        "set1" -> { firstPin = pin; err = ""; pinMode = "set2"; tick++ }
                        "set2" -> if (pin == firstPin) { s.setPin(pin); pinMode = null; err = "" } else { err = "PIN tidak sama, ulangi"; pinMode = "set1"; tick++ }
                        else -> if (s.checkPin(pin)) { s.clearPin(); pinMode = null; err = "" } else { err = "PIN salah"; tick++ }
                    }
                }
                Text("Batal", color = C.Aqua, modifier = Modifier.padding(top = 18.dp).clickable { pinMode = null; err = "" })
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
            Avatar(s.nickname, 84)
            Spacer(Modifier.height(10.dp))
            if (editName) {
                Box(Modifier.width(240.dp).clip(RoundedCornerShape(14.dp)).background(C.Surface).border(1.dp, C.Outline, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    BasicTextField(name, { if (it.length <= 24) name = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth())
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
            if (s.lockEnabled) { err = ""; pinMode = "off" } else { err = ""; pinMode = "set1" }
        }
        if (s.lockEnabled && biometricAvailable(ctx)) {
            SettingRow("Buka dengan sidik jari", "Alternatif selain PIN", s.biometric) { s.setBiometricPref(!s.biometric) }
        }

        SettingHeader("Pemutar")
        SettingRow("Crop video", if (s.hasCrop) "Memakai crop tersimpan dari pemutar" else "Belum ada crop — atur lewat tombol Crop di pemutar", s.cropOn && s.hasCrop) {
            if (s.hasCrop) s.useCrop(!s.cropOn)
        }
        if (s.hasCrop) Text("Hapus crop tersimpan", color = C.Aqua, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { s.clearCrop() })

        SettingHeader("Tentang")
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
    }
}

@Composable
private fun SettingHeader(t: String) =
    Text(t.uppercase(), color = C.Aqua, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))

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
            Text("Ketuk untuk memasang", color = Color.White.copy(alpha = .85f), fontSize = 11.sp)
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
