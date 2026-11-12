package dev.omalauncher.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.AXIS_SOURCE_WHEEL
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.BTN_MIDDLE
import dev.omalauncher.wayland.BTN_RIGHT
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val WHEEL_STEP = 15f

/**
 * Routes pointer input over a Linux window's view to its surfaces. Mouse input
 * is forwarded as-is. Touch is mapped for a phone: tap = left click, long
 * press = right click, drag = scroll; clients with wl_touch get real touch
 * with multi-touch support (pinch-to-zoom, multi-finger gestures).
 * A tap on the row of the window's text cursor (a terminal's prompt) also
 * calls [onCursorTap], after the program gets it.
 */
fun Modifier.linuxWindowInput(
    wm: WindowManager,
    window: XdgToplevel,
    onCursorTap: () -> Unit = {},
) = pointerInput(wm, window) {
    handlePointer(wm, window, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis, onCursorTap)
}

private fun XdgToplevel.hitTest(
    x: Float,
    y: Float,
) = renderer?.hitTest(x, y)

private suspend fun PointerInputScope.handlePointer(
    wm: WindowManager,
    window: XdgToplevel,
    touchSlop: Float,
    longPressMs: Long,
    onCursorTap: () -> Unit,
) = awaitPointerEventScope {
    var mouseButtons = 0
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: continue
        if (change.type == PointerType.Mouse) {
            mouseButtons = handleMouse(wm, window, event, mouseButtons)
            change.consume()
            continue
        }
        if (event.type != PointerEventType.Press || change.isConsumed) continue

        val start = change.position
        val hit = window.hitTest(start.x, start.y)
        change.consume()
        if (!wm.onPress(window, hit) || hit == null) continue

        val wantsTouch = wm.wantsTouch(hit.surface)
        if (wantsTouch) {
            handleMultiTouch(wm, window, change, hit, touchSlop, longPressMs, onCursorTap)
        } else {
            handleSingleTouchFallback(wm, window, change, hit, touchSlop, longPressMs, onCursorTap)
        }
    }
}

private class ActiveTouch(
    val slot: Int,
    val hit: SurfaceHit,
    val start: Offset,
)

private suspend fun AwaitPointerEventScope.handleMultiTouch(
    wm: WindowManager,
    window: XdgToplevel,
    firstChange: PointerInputChange,
    firstHit: SurfaceHit,
    touchSlop: Float,
    longPressMs: Long,
    onCursorTap: () -> Unit,
) {
    val activeTouches = mutableMapOf<PointerId, ActiveTouch>()
    val freeSlots = sortedSetOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

    fun addTouch(
        change: PointerInputChange,
        initialHit: SurfaceHit?,
    ) {
        val slot = freeSlots.firstOrNull() ?: return
        val hit = initialHit ?: window.hitTest(change.position.x, change.position.y)
            ?: SurfaceHit(firstHit.surface, change.position.x / firstHit.scale, change.position.y / firstHit.scale, scale = firstHit.scale)
        freeSlots.remove(slot)
        activeTouches[change.id] = ActiveTouch(slot, hit, change.position)
        wm.touchDown(hit, slot)
    }

    addTouch(firstChange, firstHit)

    var moved = false
    var longPressed = false

    while (activeTouches.isNotEmpty()) {
        val next =
            if (!moved && !longPressed && activeTouches.size == 1) {
                withTimeoutOrNull(longPressMs) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

        if (next == null) {
            longPressed = true
            if (activeTouches.size == 1) {
                val primary = activeTouches.values.first()
                wm.click(primary.hit, BTN_RIGHT)
            }
            continue
        }

        for (c in next.changes) {
            val touch = activeTouches[c.id]
            if (touch != null) {
                c.consume()
                if (!c.pressed) {
                    wm.touchUp(touch.slot)
                    freeSlots.add(touch.slot)
                    activeTouches.remove(c.id)
                } else {
                    val dist = (c.position - touch.start).getDistance()
                    if (dist > touchSlop) moved = true
                    val d = (c.position - touch.start) / touch.hit.scale
                    wm.touchMotion(touch.slot, touch.hit.x + d.x, touch.hit.y + d.y)
                }
            } else if (c.pressed && !c.isConsumed) {
                moved = true
                c.consume()
                addTouch(c, null)
            }
        }
    }

    if (!moved && !longPressed && wm.onTextCursorRow(firstHit)) onCursorTap()
}

private suspend fun AwaitPointerEventScope.handleSingleTouchFallback(
    wm: WindowManager,
    window: XdgToplevel,
    firstChange: PointerInputChange,
    hit: SurfaceHit,
    touchSlop: Float,
    longPressMs: Long,
    onCursorTap: () -> Unit,
) {
    val start = firstChange.position
    var moved = false
    var longPressed = false
    var scrolling = false
    var last = start
    while (true) {
        val next =
            if (!moved && !longPressed && !scrolling) {
                withTimeoutOrNull(longPressMs) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
        if (next == null) {
            longPressed = true
            wm.click(hit, BTN_RIGHT)
            continue
        }
        val c = next.changes.firstOrNull { it.id == firstChange.id } ?: break
        if (c.isConsumed) {
            if (scrolling) wm.scrollStop()
            break
        }
        c.consume()
        if (!c.pressed) {
            if (scrolling) {
                wm.scrollStop()
            }
            if (!moved && !longPressed) {
                wm.click(hit, BTN_LEFT)
            }
            if (!moved && !longPressed && wm.onTextCursorRow(hit)) onCursorTap()
            break
        }
        val dist = (c.position - start).getDistance()
        if (dist > touchSlop) moved = true
        if (!scrolling && !longPressed && dist > touchSlop) {
            scrolling = true
        }
        if (scrolling) {
            val step = c.position - last
            wm.scroll(-step.x / hit.scale, -step.y / hit.scale, AXIS_SOURCE_FINGER)
        }
        last = c.position
    }
}

private fun handleMouse(
    wm: WindowManager,
    window: XdgToplevel,
    event: PointerEvent,
    previousButtons: Int,
): Int {
    val change = event.changes.first()
    when (event.type) {
        PointerEventType.Exit -> {
            wm.pointerMove(null)
            return previousButtons
        }
        PointerEventType.Scroll -> {
            val d = change.scrollDelta
            wm.scroll(d.x * WHEEL_STEP, d.y * WHEEL_STEP, AXIS_SOURCE_WHEEL, d.y.roundToInt())
            return previousButtons
        }
        else -> Unit
    }
    val hit: SurfaceHit? = window.hitTest(change.position.x, change.position.y)
    val buttons =
        (if (event.buttons.isPrimaryPressed) 1 else 0) or
            (if (event.buttons.isSecondaryPressed) 2 else 0) or
            (if (event.buttons.isTertiaryPressed) 4 else 0)
    val pressed = buttons and previousButtons.inv()
    if (pressed != 0 && !wm.onPress(window, hit)) return buttons
    // Keep the grab on the pressed surface while dragging.
    if (previousButtons == 0 || wm.server.input.pointerFocus == hit?.surface) wm.pointerMove(hit)
    for ((bit, code) in BUTTONS) {
        if (pressed and bit != 0) wm.pointerButton(code, true)
        if (previousButtons and bit != 0 && buttons and bit == 0) wm.pointerButton(code, false)
    }
    return buttons
}

private val BUTTONS = listOf(1 to BTN_LEFT, 2 to BTN_RIGHT, 4 to BTN_MIDDLE)
