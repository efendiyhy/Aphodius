package id.sengkuni.aphodius

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Route {
    data class Detail(val id: String) : Route
    data class Play(val id: String) : Route
    data object Search : Route
    data object Profile : Route
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
        )
        val state = AppState(applicationContext)
        setContent { AphodiusTheme { App(state) } }
    }
}

@Composable
fun App(s: AppState) {
    var splash by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var stack by remember { mutableStateOf(listOf<Route>()) }
    var locked by remember { mutableStateOf(s.lockEnabled) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        s.loadCache()
        launch { s.refresh() }
        launch {
            // Terus ikuti perubahan dari web: cek ulang setiap 60 detik selama aplikasi terbuka
            while (true) { delay(60_000); s.refresh() }
        }
        launch {
            // Cek pembaruan aplikasi: saat dibuka, lalu tiap 6 jam
            while (true) {
                Updater.check()?.let { s.update = it }
                delay(6 * 3600_000L)
            }
        }
        delay(1300)
        if (!s.loaded) withTimeoutOrNull(6000) { snapshotFlow { s.loaded || s.netError }.first { it } }
        splash = false
    }

    // Perbarui data otomatis saat aplikasi dibuka kembali
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && s.lockEnabled) locked = true
            if (e == Lifecycle.Event.ON_RESUME && System.currentTimeMillis() - s.lastRefresh > 20_000) {
                scope.launch { s.refresh() }
            }
        }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }

    if (splash) { SplashScreen(); return }

    if (s.nickname.isBlank()) { OnboardingScreen { s.saveNickname(it) }; return }
    if (locked && s.lockEnabled) { LockScreen(s) { locked = false }; return }

    val top = stack.lastOrNull()
    BackHandler(enabled = top == null && tab != Tab.Home) { tab = Tab.Home }
    BackHandler(enabled = top is Route.Detail || top is Route.Search) { stack = stack.dropLast(1) }

    fun open(f: Film) { stack = stack + Route.Detail(f.id) }
    fun play(f: Film) { stack = stack + Route.Play(f.id) }

    Box(Modifier.fillMaxSize().background(C.Navy)) {
        when (top) {
            is Route.Play -> {
                val f = s.find(top.id)
                if (f == null) { stack = stack.dropLast(1) } else {
                    val idx = s.films.indexOfFirst { it.id == f.id }
                    val next = if (s.films.size > 1) s.films[(idx + 1) % s.films.size] else null
                    key(f.id) {
                        PlayerScreen(f, next, s, onBack = { stack = stack.dropLast(1) }, onNext = { n -> stack = stack.dropLast(1) + Route.Play(n.id) })
                    }
                }
            }
            is Route.Detail -> {
                val f = s.find(top.id)
                if (f == null) { stack = stack.dropLast(1) } else DetailScreen(f, s, onBack = { stack = stack.dropLast(1) }, onPlay = { play(f) })
            }
            Route.Search -> SearchScreen(s, onBack = { stack = stack.dropLast(1) }, onOpen = ::open, onPlay = ::play)
            Route.Profile -> ProfileScreen(s, onBack = { stack = stack.dropLast(1) })
            null -> Column(Modifier.fillMaxSize()) {
                s.update?.let { u -> UpdateBanner(u) { stack = stack + Route.Profile } }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Home -> HomeScreen(s, ::open, ::play, onSearch = { stack = stack + Route.Search }, onProfile = { stack = stack + Route.Profile })
                        Tab.New -> NewScreen(s, ::open, ::play)
                        Tab.Movie -> MovieScreen(s, ::open)
                        Tab.MyList -> MyListScreen(s, ::open)
                    }
                }
                BottomNav(tab) { tab = it }
            }
        }
    }
}
