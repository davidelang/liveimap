package org.dlang.liveimap.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.launch
import org.dlang.liveimap.settings.SettingValue
import org.dlang.liveimap.settings.SettingsHub
import org.dlang.liveimap.settings.withSetting
import org.dlang.liveimap.settings.clampTextZoom

fun scaledFontScale(systemScale: Float, appTextZoom: Int): Float =
    systemScale * appTextZoom / 100f

/**
 * Set by [org.dlang.liveimap.ui.reader.pinchToZoom] on the main pass when the message magnifies.
 * A scrolling child consuming the pointers is not this claim. The window reads it on the final pass.
 */
internal object MessagePinchClaim {
    private var claimed = false

    fun claim() {
        claimed = true
    }

    fun read(): Boolean = claimed

    fun clear() {
        claimed = false
    }
}

/** One pinch gesture. A message claim writes nothing. A scroll consume still commits once. */
class AppZoomSession(private val start: Int) {
    private var factor = 1f

    fun zoomBy(step: Float) {
        if (step.isNaN() || step == 1f) return
        factor *= step
    }

    fun commit(messageClaimed: Boolean): Int? {
        if (messageClaimed) return null
        return clampTextZoom(Math.round(start * factor).toInt())
    }
}

@Composable
fun AppTextZoom(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by SettingsHub.ui.collectAsState()
    var zoom by remember { mutableIntStateOf(100) }
    LaunchedEffect(ui.appTextZoom) {
        zoom = clampTextZoom(ui.appTextZoom)
    }
    val system = LocalDensity.current
    val scaled = Density(system.density, scaledFontScale(system.fontScale, zoom))
    CompositionLocalProvider(LocalDensity provides scaled) {
        androidx.compose.foundation.layout.Box(
            Modifier.pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    MessagePinchClaim.clear()
                    val session = AppZoomSession(zoom)
                    var messageClaimed = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (MessagePinchClaim.read()) messageClaimed = true
                            val fingers = event.changes.count { it.pressed }
                            if (fingers >= 2) session.zoomBy(event.calculateZoom())
                        } while (event.changes.any { it.pressed })
                    } finally {
                        MessagePinchClaim.clear()
                    }
                    val next = session.commit(messageClaimed) ?: return@awaitEachGesture
                    if (next == zoom) return@awaitEachGesture
                    val editors = org.dlang.liveimap.settings.openSettingsEditors.get()
                    if (editors.isNotEmpty()) {
                        var accepted = false
                        for (editor in editors) {
                            if (editor.applySettingsPinch(next)) accepted = true
                        }
                        if (!accepted) return@awaitEachGesture
                    }
                    zoom = next
                    scope.launch {
                        val current = SettingsHub.uiNow()
                        try {
                            SettingsHub.saveUiKeys(context, current.withSetting("appTextZoom", SettingValue(next.toString())), setOf("appTextZoom"))
                        } catch (_: org.dlang.liveimap.window.SettingsHeldElsewhere) {
                        }
                    }
                }
            },
        ) {
            content()
        }
    }
}
