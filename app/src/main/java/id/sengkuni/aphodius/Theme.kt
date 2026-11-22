package id.sengkuni.aphodius

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Ocean = Color(0xFF0A84C6)
    val Aqua = Color(0xFF00B4D8)
    val Navy = Color(0xFF030B14)
    val Surface = Color(0xFF10263B)
    val Muted = Color(0xFF9FB3C8)
    val Brand = Brush.horizontalGradient(listOf(Ocean, Aqua))
}

@Composable
fun AphodiusTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Aqua, background = C.Navy, surface = C.Surface,
            onPrimary = Color.Black, onBackground = Color.White, onSurface = Color.White,
        ),
        content = content,
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape)
            .then(if (selected) Modifier.background(C.Aqua) else Modifier.border(1.dp, Color.White.copy(alpha = .45f), shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AButton(text: String, icon: ImageVector?, white: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.height(44.dp).clip(shape)
            .background(if (white) Color.White else Color.White.copy(alpha = .18f))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (white) Color.Black else Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (white) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
