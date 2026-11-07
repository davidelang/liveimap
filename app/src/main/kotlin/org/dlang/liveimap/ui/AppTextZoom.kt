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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import org.dlang.liveimap.settings.SettingValue
import org.dlang.liveimap.settings.SettingsHub
import org.dlang.liveimap.settings.withSetting
import org.dlang.liveimap.settings.clampTextZoom

fun scaledFontScale(systemScale: Float, appTextZoom: Int): Float =
    systemScale * appTextZoom / 100f

/**
 * Set on the main pass by a pinch on the open message (plain text, header, raw, or HTML).
 * A scrolling list outside the message does not set it. The window reads it on the final pass.
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

    /** The zoom the gesture would commit now, for the live preview. */
    fun preview(): Int = clampTextZoom(Math.round(start * factor).toInt())

    fun commit(messageClaimed: Boolean): Int? {
        if (messageClaimed) return null
        return clampTextZoom(Math.round(start * factor).toInt())
    }
}

/** Live scale for the preview: the previewed zoom over the laid-out zoom. 1 when nothing changes. */
fun livePreviewScale(laidOut: Int, preview: Int?): Float =
    if (preview == null || laidOut <= 0) 1f else preview.toFloat() / laidOut

@Composable
fun AppTextZoom(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by SettingsHub.ui.collectAsState()
    var zoom by remember { mutableIntStateOf(100) }
    // Live gesture preview, drawn with a layer scale: no re-layout, no save, until the fingers lift.
    var preview by remember { mutableStateOf<Int?>(null) }
    var pivot by remember { mutableStateOf(TransformOrigin.Center) }
    LaunchedEffect(ui.appTextZoom) {
        zoom = clampTextZoom(ui.appTextZoom)
    }
    val system = LocalDensity.current
    val scaled = Density(system.density, scaledFontScale(system.fontScale, zoom))
    CompositionLocalProvider(LocalDensity provides scaled) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .graphicsLayer {
                    val k = livePreviewScale(zoom, preview)
                    scaleX = k; scaleY = k; transformOrigin = pivot
                }
                .pointerInput(zoom) {
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
                            if (fingers >= 2) {
                                session.zoomBy(event.calculateZoom())
                                if (!messageClaimed) {
                                    val c = event.calculateCentroid(useCurrent = true)
                                    if (c != Offset.Unspecified && size.width > 0 && size.height > 0) {
                                        pivot = TransformOrigin(c.x / size.width, c.y / size.height)
                                    }
                                    preview = session.preview()
                                } else {
                                    preview = null
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        MessagePinchClaim.clear()
                        preview = null
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
                    // Off the main thread: the record read/write and marker checks used to block the frame.
                    scope.launch(Dispatchers.IO) {
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
