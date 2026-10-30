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

/** One pinch gesture. A consumed reader pinch writes nothing. An index pinch commits once. */
class AppZoomSession(private val start: Int) {
    private var factor = 1f

    fun zoomBy(step: Float) {
        if (step.isNaN() || step == 1f) return
        factor *= step
    }

    fun commit(consumed: Boolean): Int? {
        if (consumed) return null
        return clampTextZoom(Math.round(start * factor).toInt())
    }

    /** Settings still writes when the scrolling column consumed the gesture. */
    fun commitForSettings(pinched: Boolean): Int? {
        if (!pinched) return null
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
                    val session = AppZoomSession(zoom)
                    var consumed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val step = event.calculateZoom()
                            session.zoomBy(step)
                            if (event.changes.any { it.isConsumed }) consumed = true
                        }
                    } while (event.changes.any { it.pressed })
                    val next = session.commit(consumed) ?: return@awaitEachGesture
                    if (next == zoom) return@awaitEachGesture
                    zoom = next
                    scope.launch {
                        val current = SettingsHub.uiNow()
                        SettingsHub.saveUiKeys(context, current.withSetting("appTextZoom", SettingValue(next.toString())), setOf("appTextZoom"))
                    }
                }
            },
        ) {
            content()
        }
    }
}
