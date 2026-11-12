package dev.omalauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.ui.ExtraKeys
import dev.omalauncher.ui.ImagePicker
import dev.omalauncher.ui.KeyboardSink
import dev.omalauncher.ui.WidgetAsks
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.linuxWindowInput
import dev.omalauncher.wayland.WindowRenderer
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.flow.first

/**
 * Shows a single Linux window as its own Android task, so it gets its own
 * Recents card and behaves like a separate app.
 */
class LinuxWindowActivity : InstantActivity() {
    private var linuxWindow: XdgToplevel? = null
    private val imagePicker = ImagePicker(this) { runtime }
    private val asks = WidgetAsks(this)
    private var isFullscreen by mutableStateOf(true)

    private fun applyFullscreenBars(fullscreen: Boolean) {
        val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleFullscreen() {
        val next = !isFullscreen
        isFullscreen = next
        getSharedPreferences("onelinux_window_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pref_fullscreen", next)
            .apply()
        applyFullscreenBars(next)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val window = runtime.wm.windowByHandle(intent.data?.lastPathSegment)
        if (window == null) {
            // The window is gone, e.g. the process restarted since Recents was built.
            finishAndRemoveTask()
            return
        }
        this.linuxWindow = window
        val isDesktopWindow = window.appId.equals("org.freedesktop.niri", ignoreCase = true) ||
            window.appId.equals("niri", ignoreCase = true)
        val prefs = getSharedPreferences("onelinux_window_prefs", Context.MODE_PRIVATE)
        isFullscreen = if (isDesktopWindow) true else prefs.getBoolean("pref_fullscreen", true)
        applyFullscreenBars(isFullscreen)
        setInstantContent {
            AppWindow(
                runtime = runtime,
                window = window,
                isFullscreen = isFullscreen,
                onToggleFullscreen = { toggleFullscreen() },
            )
            BackHandler { backInstantly() }
            LaunchedEffect(window) {
                snapshotFlow { window in runtime.wm.appWindows }.first { !it }
                closeInstantly()
            }
            // Terminals retitle constantly; only a new label changes the Recents card.
            val label = runtime.displayName(window.appId, window.title)
            val appIcon = runtime.iconForWindow(window.appId, window.title)
            LaunchedEffect(label, appIcon, Palette.accent) { describeTask(label, appIcon?.asAndroidBitmap()) }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.screenStarted()
    }

    override fun onResume() {
        super.onResume()
        linuxWindow?.let {
            runtime.wm.focus(it)
            val isDesktopWindow = it.appId.equals("org.freedesktop.niri", ignoreCase = true) ||
                it.appId.equals("niri", ignoreCase = true)
            applyFullscreenBars(if (isDesktopWindow) true else isFullscreen)
        }
        runtime.keyboard.imagePicker = imagePicker
        asks.resumed(runtime)
    }

    override fun onStop() {
        super.onStop()
        runtime.screenStopped()
    }

    /** Swiping the card away in Recents closes the Linux window, like closing an app. */
    override fun onDestroy() {
        super.onDestroy()
        val w = linuxWindow ?: return
        if (isFinishing && w in runtime.wm.appWindows) runtime.wm.close(w)
    }

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Super+Esc leaves the window as Back does, where Esc itself is the window's: it keeps running in Recents.
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) backInstantly()
            return true
        }
        if (runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean {
        if (runtime.keyboard.handle(event)) return true
        return super.dispatchKeyShortcutEvent(event)
    }

    /** Keys held as the window goes, like Super, would stay down for it: Android sends their lift elsewhere. */
    override fun onPause() {
        super.onPause()
        runtime.keyboard.releaseHeld()
        if (runtime.keyboard.imagePicker === imagePicker) runtime.keyboard.imagePicker = null
        asks.paused(runtime)
    }
}

/**
 * Recents shows the manifest label ("Linux", or an alias's), but it does use the
 * task icon: the app's drawer icon, else its initials in the theme colors.
 */
internal fun taskIcon(
    name: String,
    background: Int,
    accent: Int,
): Bitmap {
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(background)
    val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    val initials =
        when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.isNotEmpty() -> words[0].take(2).replaceFirstChar(Char::uppercase)
            else -> "◆"
        }
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(initials, size / 2f, y, paint)
    return bmp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppWindow(
    runtime: Runtime,
    window: XdgToplevel,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
) {
    var sink by remember { mutableStateOf<KeyboardSink?>(null) }
    var surfaceView by remember { mutableStateOf<WindowSurfaceView?>(null) }
    // Shows wherever the window has yet to draw, as when it grows; the terminal's own background, not a lighter one.
    val background = Palette.background.toArgb()
    val density = LocalDensity.current
    // Where the keyboard ends up, not each frame of its slide, and read here so the extra keys come and go in the same
    // frame: the window then resizes once, and a terminal reflows once.
    val isDesktop = remember(window.appId) {
        window.appId.equals("org.freedesktop.niri", ignoreCase = true) ||
            window.appId.equals("niri", ignoreCase = true)
    }
    val keyboard = (WindowInsets.imeAnimationTarget.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .then(if (isFullscreen || isDesktop) Modifier else Modifier.systemBarsPadding()),
    ) {
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { v -> sink = v } },
            modifier = Modifier.size(1.dp),
        )
        // Always the whole screen: a SurfaceView that changes size shows its last frame stretched until the next one,
        // so the keyboard and the extra keys go over it, and only the Linux window gets smaller.
        AndroidView(
            factory = { context -> WindowSurfaceView(context, runtime, window).also { surfaceView = it } },
            update = { it.background = background },
            modifier = Modifier.fillMaxSize(),
        )
        val keyboardHeight = with(density) { keyboard.toDp() }
        // Where the keyboard is still sliding in, so the window's old frame doesn't show beneath it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboardHeight).background(Palette.background))
        Column(Modifier.fillMaxSize().padding(bottom = keyboardHeight)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { surfaceView?.visibleHeight = it.height },
            ) {
                // Over the SurfaceView, which Compose's pointer input doesn't reach.
                // A tap on the prompt line brings the keyboard back, as the ⌨ button does.
                Box(Modifier.fillMaxSize().linuxWindowInput(runtime.wm, window) { sink?.takeUnless { it.showing }?.show() })
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isDesktop) {
                        // Fullscreen toggle button
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(Palette.overlay, CircleShape)
                                .clickable { onToggleFullscreen() },
                            contentAlignment = Alignment.Center,
                        ) {
                            FullscreenVectorIcon(
                                isFullscreen = isFullscreen,
                                tint = if (isFullscreen) Palette.accent else Palette.foreground,
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(if (isDesktop) 32.dp else 40.dp)
                            .background(if (isDesktop) Palette.overlay.copy(alpha = 0.35f) else Palette.overlay, CircleShape)
                            .clickable { sink?.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            "⌨",
                            style = Mono.copy(
                                color = if (isDesktop) Palette.foreground.copy(alpha = 0.5f) else Palette.foreground
                            ),
                        )
                    }
                }
            }
            // Below the window rather than over it, so the window shrinks and nothing hides behind the keys; only over a
            // docked keyboard (a hardware keyboard's toolbar takes no height, and that keyboard has these keys).
            if (keyboard > 0) ExtraKeys(runtime, Modifier.background(Palette.background))
        }
    }
}

@Composable
private fun FullscreenVectorIcon(
    isFullscreen: Boolean,
    tint: androidx.compose.ui.graphics.Color,
) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(16.dp)) {
        val stroke = 2.dp.toPx()
        val w = size.width
        val h = size.height
        val c = 4.5.dp.toPx()
        if (isFullscreen) {
            // Restore icon (collapse inwards)
            drawLine(tint, Offset(0f, c), Offset(c, c), stroke)
            drawLine(tint, Offset(c, 0f), Offset(c, c), stroke)
            drawLine(tint, Offset(w, c), Offset(w - c, c), stroke)
            drawLine(tint, Offset(w - c, 0f), Offset(w - c, c), stroke)
            drawLine(tint, Offset(0f, h - c), Offset(c, h - c), stroke)
            drawLine(tint, Offset(c, h), Offset(c, h - c), stroke)
            drawLine(tint, Offset(w, h - c), Offset(w - c, h - c), stroke)
            drawLine(tint, Offset(w - c, h), Offset(w - c, h - c), stroke)
        } else {
            // Fullscreen icon (expand outwards)
            drawLine(tint, Offset(0f, c), Offset(0f, 0f), stroke)
            drawLine(tint, Offset(0f, 0f), Offset(c, 0f), stroke)
            drawLine(tint, Offset(w - c, 0f), Offset(w, 0f), stroke)
            drawLine(tint, Offset(w, 0f), Offset(w, c), stroke)
            drawLine(tint, Offset(0f, h - c), Offset(0f, h), stroke)
            drawLine(tint, Offset(0f, h), Offset(c, h), stroke)
            drawLine(tint, Offset(w - c, h), Offset(w, h), stroke)
            drawLine(tint, Offset(w, h), Offset(w, h - c), stroke)
        }
    }
}

/**
 * The window's pixels: a [WindowRenderer] draws the Linux surfaces straight
 * into this view's surface, without Compose or bitmaps in between. The
 * window is as wide as the view and [visibleHeight] tall, at its top.
 */
private class WindowSurfaceView(
    context: Context,
    private val runtime: Runtime,
    private val window: XdgToplevel,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private val renderer = WindowRenderer(runtime.server, window)

    var background by renderer::background

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** How much of the view, from its top, the keyboard and the extra keys leave the window; 0 for all of it. */
    var visibleHeight = 0
        set(value) {
            if (value == field) return
            field = value
            layoutWindow()
        }

    private fun layoutWindow() {
        if (surfaceWidth == 0) return
        val h = if (visibleHeight in 1..surfaceHeight) visibleHeight else surfaceHeight
        renderer.visibleHeight = h
        runtime.wm.applyAppLayout(window, surfaceWidth, h)
    }

    init {
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        window.renderer = renderer
    }

    override fun onDetachedFromWindow() {
        if (window.renderer === renderer) window.renderer = null
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        renderer.attach(holder.surface, width, height)
        layoutWindow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.detach()
}
