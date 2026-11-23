package id.sengkuni.aphodius

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Foto profil disimpan lokal di filesDir/avatar.jpg (persegi 512px). */
object AvatarStore {
    var version by mutableIntStateOf(0)
    private var cache: Pair<Int, ImageBitmap?>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "avatar.jpg")

    fun load(ctx: Context): ImageBitmap? {
        cache?.let { if (it.first == version) return it.second }
        val f = file(ctx)
        val bmp = if (f.exists()) BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
        cache = version to bmp
        return bmp
    }

    fun save(ctx: Context, uri: Uri): Boolean {
        return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 768 && opts.outHeight / (sample * 2) >= 768) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(sq, 512, 512, true)
        file(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        version++
        true
        } catch (e: Exception) {
            false
        }
    }

    fun clear(ctx: Context) { file(ctx).delete(); version++ }
    fun has(ctx: Context) = file(ctx).exists()
}

@Composable
fun Avatar(name: String, size: Int = 40) {
    val ctx = LocalContext.current
    val v = AvatarStore.version
    val photo by produceState<ImageBitmap?>(null, v) { value = withContext(Dispatchers.IO) { AvatarStore.load(ctx) } }
    val ch = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    Box(Modifier.size(size.dp).clip(CircleShape).background(C.Brand), contentAlignment = Alignment.Center) {
        val p = photo
        if (p != null) Image(p, null, Modifier.size(size.dp), contentScale = ContentScale.Crop)
        else Text(ch, color = Color.White, fontWeight = FontWeight.Black, fontSize = (size * 0.42f).sp)
    }
}
