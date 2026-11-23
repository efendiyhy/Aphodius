package id.sengkuni.aphodius

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design system Aphodius (lihat 00-design-system.svg).
 * Nama lama (Ocean, Aqua, Navy, Surface, Muted, Brand) dipertahankan agar layar lain tetap jalan.
 */
object C {
    val Ocean = Color(0xFF0A84C6)
    val Aqua = Color(0xFF22C7EC)        // warna utama (aksi, aktif)
    val Navy = Color(0xFF050D16)        // latar
    val Surface = Color(0xFF0D1A28)     // kartu, bilah navigasi
    val SurfaceHigh = Color(0xFF15283B) // input, chip, tombol tonal
    val Outline = Color(0xFF223850)     // garis tipis
    val Muted = Color(0xFF8FA8BF)       // teks sekunder
    val Subtle = Color(0xFF7692AC)      // teks tersier
    val OnAqua = Color(0xFF031019)      // teks/ikon di atas Aqua
    val Gold = Color(0xFFF2A33A)        // aksen scarab: lencana & sorotan
    val Brand = Brush.horizontalGradient(listOf(Ocean, Aqua))
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val AFont = androidx.compose.ui.text.font.FontFamily(
    listOf(400, 500, 600, 700, 800, 900).map { w ->
        androidx.compose.ui.text.font.Font(
            R.font.montserrat, androidx.compose.ui.text.font.FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
        )
    }
)

@Composable
fun AphodiusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Aqua, secondary = C.Gold, background = C.Navy, surface = C.Surface,
            surfaceVariant = C.SurfaceHigh, outline = C.Outline,
            onPrimary = C.OnAqua, onBackground = Color.White, onSurface = Color.White,
        ),
        content = { androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontFamily = AFont, color = Color.White), content) },
    )
}

/* ---------------- Logo ---------------- */
// Rasio lebar/tinggi dari res/drawable/ic_beetle.xml dan ic_wordmark.xml (dibuat tools/branding/build.py)
const val BEETLE_RATIO = 0.9006f
const val WORDMARK_RATIO = 8.3256f

@Composable
fun BeetleMark(height: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_beetle), null, modifier.size(height * BEETLE_RATIO, height))
}

@Composable
fun Wordmark(height: Dp, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Icon(painterResource(R.drawable.ic_wordmark), "Aphodius", tint = tint, modifier = modifier.size(height * WORDMARK_RATIO, height))
}

/** Latar low-poly: beberapa segitiga putih transparan (sama dengan ikon aplikasi). */
@Composable
fun LowPolyBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val kx = size.width / 108f
        val ky = size.height / 108f
        fun poly(alpha: Float, vararg p: Float) {
            val path = Path()
            path.moveTo(p[0] * kx, p[1] * ky)
            var i = 2
            while (i < p.size) { path.lineTo(p[i] * kx, p[i + 1] * ky); i += 2 }
            path.close()
            drawPath(path, Color.White.copy(alpha = alpha))
        }
        poly(.06f, 0f, 0f, 62f, 0f, 0f, 58f)
        poly(.035f, 62f, 0f, 108f, 0f, 108f, 40f, 86f, 30f)
        poly(.05f, 0f, 58f, 40f, 108f, 0f, 108f)
        poly(.045f, 108f, 40f, 108f, 108f, 70f, 108f)
        poly(.02f, 0f, 58f, 62f, 0f, 86f, 30f, 40f, 108f)
    }
}

/* ---------------- Komponen ---------------- */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .background(if (selected) C.Aqua else C.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text, color = if (selected) C.OnAqua else C.Muted, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

/** primary = true: tombol utama (Aqua). primary = false: tonal transparan, aman di atas gambar. */
@Composable
fun AButton(text: String, icon: ImageVector?, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val fg = if (primary) C.OnAqua else Color.White
    Row(
        modifier.height(48.dp).clip(shape)
            .background(if (primary) C.Aqua else Color.White.copy(alpha = .14f))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.5.sp)
    }
}

/** Tombol ikon bulat. glass = true untuk di atas gambar/video. */
@Composable
fun IconBtn(
    icon: ImageVector, desc: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, glass: Boolean = false, size: Dp = 40.dp, tint: Color = Color.White,
) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (glass) Color.Black.copy(alpha = .42f) else C.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}
