package org.dlang.liveimap.ui.index

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private const val FILTER_PATH =
    "M10,18h4v-2h-4v2zM3,6v2h18V6H3zM6,13h12v-2H6v2z"

private var filterCache: ImageVector? = null

internal val filterImage: ImageVector
    get() = filterCache ?: ImageVector.Builder(
        name = "Filter",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(FILTER_PATH).toNodes(),
        fill = SolidColor(Color.Black),
    ).build().also { filterCache = it }
