package id.sengkuni.aphodius

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    Home("Beranda", Ic.Home), New("Baru", Ic.Eps), Search("Cari", Ic.Search), MyList("Daftarku", Ic.Bookmark)
}

private val gradients = listOf(
    listOf(Color(0xFF0A84C6), Color(0xFF012A4A)),
    listOf(Color(0xFF00B4D8), Color(0xFF03045E)),
    listOf(Color(0xFF48CAE4), Color(0xFF0A3A5C)),
    listOf(Color(0xFF1B6CA8), Color(0xFF05192B)),
    listOf(Color(0xFF5FA8D3), Color(0xFF13405F)),
    listOf(Color(0xFF0096C7), Color(0xFF020F1E)),
)

fun meta(f: Film): String = listOfNotNull(f.year?.toString(), f.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")).joinToString(" • ")

@Composable
fun Poster(film: Film, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(6.dp)) {
    val g = gradients[(film.id.hashCode() and 0x7fffffff) % gradients.size]
    Box(modifier.clip(shape).background(Brush.linearGradient(g)), contentAlignment = Alignment.Center) {
        if (film.poster.isNotBlank()) {
            val model: Any = remember(film.poster) {
                if (film.poster.startsWith("data:")) {
                    try { Base64.decode(film.poster.substringAfter("base64,"), Base64.DEFAULT) } catch (e: Exception) { "" }
                } else film.poster
            }
            AsyncImage(model, film.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Ic.Play, null, tint = Color.White.copy(alpha = .5f), modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B4F7A), C.Navy))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp).border(3.dp, C.Aqua, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Ic.PlayFill, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text("APHODIUS", fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, color = C.Aqua)
            Text("Tonton tanpa batas", color = C.Muted, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator(color = C.Aqua, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("dari", color = C.Muted, fontSize = 11.sp)
            Text("SengkuniDeveloper", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
fun BottomNav(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF061523)).navigationBarsPadding().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.values().forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onTab(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(t.icon, t.label, tint = if (on) C.Aqua else Color.White, modifier = Modifier.size(24.dp))
                Text(t.label, fontSize = 10.sp, color = if (on) C.Aqua else C.Muted)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) =
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 10.dp))

@Composable
fun PosterRow(films: List<Film>, s: AppState, onOpen: (Film) -> Unit, showProgress: Boolean = false) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(films, key = { it.id }) { f ->
            Column(Modifier.width(112.dp).clickable { onOpen(f) }) {
                Poster(f, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
                if (showProgress) {
                    val p = s.progress[f.id]?.frac ?: 0f
                    Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .25f))) {
                        Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(C.Aqua))
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(s: AppState, text: String) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = C.Muted, textAlign = TextAlign.Center)
        if (s.netError) Text("Gagal memuat data: ${s.lastError}", color = C.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        else if (s.loaded && s.lastRefresh != 0L) Text("Data web terakhir dimuat: ${fmtTime(0).let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(s.lastRefresh)) }}", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(14.dp))
        AButton(if (s.refreshing) "Memuat…" else "Muat ulang", Ic.Refresh, true) { scope.launch { s.refresh() } }
    }
}

/* ---------------- BERANDA ---------------- */
@Composable
fun HomeScreen(s: AppState, onOpen: (Film) -> Unit, onPlay: (Film) -> Unit, onSearch: () -> Unit) {
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<String?>(null) }
    val land = LocalConfiguration.current.screenWidthDp > 600
    val used = remember(s.films) { s.films.flatMap { it.genres }.distinct().sorted() }
    val genre = picked?.takeIf { it in used }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("APHODIUS", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, style = androidx.compose.ui.text.TextStyle(brush = C.Brand), modifier = Modifier.weight(1f))
                Icon(Ic.Refresh, "Muat ulang", tint = if (s.refreshing) C.Aqua else Color.White, modifier = Modifier.size(26.dp).clickable { scope.launch { s.refresh() } })
                Spacer(Modifier.width(18.dp))
                Icon(Ic.Search, "Cari", tint = Color.White, modifier = Modifier.size(26.dp).clickable { onSearch() })
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
                Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowFilms.forEach { f -> Poster(f, Modifier.weight(1f).aspectRatio(2f / 3f).clickable { onOpen(f) }) }
                    repeat(cols - rowFilms.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            val hero = s.films.first()
            item {
                val h = if (land) 280.dp else 440.dp
                Box(Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp).fillMaxWidth().height(h).clip(RoundedCornerShape(10.dp)).clickable { onOpen(hero) }) {
                    Poster(hero, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to C.Navy.copy(alpha = .95f))))
                    Column(Modifier.align(Alignment.BottomCenter).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(hero.name.uppercase(), fontSize = 26.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(meta(hero), color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp), textAlign = TextAlign.Center)
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
fun SearchScreen(s: AppState, onOpen: (Film) -> Unit, onPlay: (Film) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(q, s.films) {
        val k = q.trim().lowercase()
        if (k.isEmpty()) s.films else s.films.filter { (it.name + " " + (it.year ?: "") + " " + it.genres.joinToString(" ")).lowercase().contains(k) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Ic.Search, null, tint = C.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Cari film, tahun, genre", color = C.Muted, fontSize = 15.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(C.Aqua), modifier = Modifier.fillMaxWidth())
            }
            if (q.isNotEmpty()) Icon(Ic.Close, "Hapus", tint = C.Muted, modifier = Modifier.size(20.dp).clickable { q = "" })
        }
        Text(if (q.isBlank()) "Pencarian Teratas" else "Hasil (${list.size})", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(list, key = { it.id }) { f ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(f) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(f, Modifier.width(56.dp).aspectRatio(2f / 3f))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(f.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("Film" + (f.year?.let { " • $it" } ?: ""), color = C.Muted, fontSize = 12.sp)
                    }
                    Box(Modifier.size(36.dp).border(1.5.dp, Color.White, CircleShape).clickable { onPlay(f) }, contentAlignment = Alignment.Center) {
                        Icon(Ic.PlayFill, "Putar", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
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
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text("Baru dan Populer", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        if (s.films.isEmpty()) item { EmptyState(s, "Belum ada film.") }
        items(s.films, key = { it.id }) { f ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column(Modifier.width(46.dp)) {
                    if (f.added > 0) {
                        Text(df.format(Date(f.added)).uppercase(), color = C.Muted, fontSize = 12.sp)
                        Text(dd.format(Date(f.added)), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).clickable { onOpen(f) }, contentAlignment = Alignment.Center) {
                        Poster(f, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
                        Box(Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).border(1.5.dp, Color.White, CircleShape).clickable { onPlay(f) }, contentAlignment = Alignment.Center) {
                            Icon(Ic.PlayFill, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Text(f.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(meta(f), color = C.Muted, fontSize = 12.sp)
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
        item { Text("Daftarku", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        if (list.isEmpty()) item { Text("Daftarmu masih kosong.\nTekan “Daftarku” pada sebuah film untuk menyimpannya di sini.", color = C.Muted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
        items(list.chunked(cols)) { rowFilms ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
        Box(Modifier.fillMaxWidth().height(if (land) 260.dp else 300.dp), contentAlignment = Alignment.Center) {
            Poster(f, Modifier.fillMaxSize(), RoundedCornerShape(0.dp))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent, C.Navy))))
            Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Icon(Ic.Back, "Kembali", tint = Color.White, modifier = Modifier.size(28.dp).clickable { onBack() })
                Icon(Ic.Share, "Bagikan", tint = Color.White, modifier = Modifier.size(26.dp).clickable { shareFilm(ctx, f) })
            }
            Box(Modifier.size(58.dp).clip(CircleShape).background(Color.Black.copy(alpha = .4f)).border(2.dp, Color.White, CircleShape).clickable { onPlay() }, contentAlignment = Alignment.Center) {
                Icon(Ic.PlayFill, "Putar", tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(f.name, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("Film" + (f.year?.let { " • $it" } ?: ""), color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(14.dp))
            AButton(if (resume) "LANJUTKAN ${fmtTime(prog!!.pos)}" else "PUTAR", Ic.PlayFill, true, Modifier.fillMaxWidth()) { onPlay() }
            Spacer(Modifier.height(8.dp))
            AButton(if (fav) "DI DAFTARKU" else "DAFTARKU", if (fav) Ic.Check else Ic.Plus, false, Modifier.fillMaxWidth()) { s.toggleFav(f.id) }
            if (f.genres.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    f.genres.forEach { g ->
                        Text(g, fontSize = 12.sp, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(50)).background(C.Surface).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DetailAction(if (fav) Ic.Check else Ic.Plus, "Daftarku") { s.toggleFav(f.id) }
                DetailAction(Ic.Share, "Bagikan") { shareFilm(ctx, f) }
                DetailAction(Ic.External, "Buka di X") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(f.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(label, fontSize = 11.sp, color = C.Muted, modifier = Modifier.padding(top = 4.dp))
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
