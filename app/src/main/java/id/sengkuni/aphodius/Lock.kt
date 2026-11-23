package id.sengkuni.aphodius

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

const val PIN_LEN = 4

fun biometricAvailable(ctx: Context): Boolean =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

fun promptBiometric(ctx: Context, onOk: () -> Unit) {
    val act = ctx.findActivity() as? FragmentActivity ?: return
    val prompt = BiometricPrompt(
        act, ContextCompat.getMainExecutor(act),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
        },
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Buka Aphodius")
        .setSubtitle("Gunakan sidik jari")
        .setNegativeButtonText("Pakai PIN")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    prompt.authenticate(info)
}

/** Papan angka PIN. onComplete dipanggil saat PIN_LEN digit terisi. Ubah resetTick untuk mengosongkan isian (mis. setelah PIN salah). */
@Composable
fun PinPad(title: String, subtitle: String, error: String, resetTick: Int = 0, extraKey: (@Composable () -> Unit)? = null, onComplete: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    LaunchedEffect(resetTick) { pin = "" }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(subtitle, color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(PIN_LEN) { i ->
                Box(Modifier.size(16.dp).clip(CircleShape).then(if (i < pin.length) Modifier.background(C.Aqua) else Modifier.border(1.5.dp, Color.White.copy(alpha = .6f), CircleShape)))
            }
        }
        Text(error, color = Color(0xFFFF6B6B), fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp).height(20.dp))
        Spacer(Modifier.height(10.dp))
        fun press(d: String) {
            if (pin.length >= PIN_LEN) return
            pin += d
            if (pin.length == PIN_LEN) onComplete(pin)
        }
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 7.dp)) {
                r.forEach { d -> Key(d) { press(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(vertical = 7.dp)) {
            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) { extraKey?.invoke() }
            Key("0") { press("0") }
            Box(Modifier.size(68.dp).clip(CircleShape).clickable { pin = pin.dropLast(1) }, contentAlignment = Alignment.Center) {
                Text("⌫", fontSize = 22.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun Key(d: String, onClick: () -> Unit) {
    Box(Modifier.size(68.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(d, fontSize = 26.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun LockScreen(s: AppState, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var err by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val bio = s.biometric && biometricAvailable(ctx)
    LaunchedEffect(Unit) { if (bio) promptBiometric(ctx, onUnlock) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B4F7A), C.Navy))).statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Avatar(s.nickname, 64)
            Spacer(Modifier.height(12.dp))
            Text("Halo,", fontSize = 15.sp, color = C.Muted)
            Text(s.nickname, fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
            Spacer(Modifier.height(18.dp))
            PinPad(
                title = "Masukkan PIN", subtitle = "Aphodius dikunci", error = err, resetTick = tick,
                extraKey = if (bio) { { Text("Sidik jari", fontSize = 12.sp, color = C.Aqua, modifier = Modifier.clickable { promptBiometric(ctx, onUnlock) }) } } else null,
            ) { pin ->
                if (s.checkPin(pin)) onUnlock() else { err = "PIN salah"; tick++ }
            }
        }
    }
}
