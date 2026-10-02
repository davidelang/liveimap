package org.dlang.liveimap.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private const val MOVE_PATH =
    "M10,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z"

private const val SPAM_PATH =
    "M15.73,3H8.27L3,8.27v7.46L8.27,21h7.46L21,15.73V8.27L15.73,3zM12,17.3c-0.72,0 -1.3,-0.58 -1.3,-1.3 0,-0.72 0.58,-1.3 1.3,-1.3 0.72,0 1.3,0.58 1.3,1.3 0,0.72 -0.58,1.3 -1.3,1.3zM11,13h2V7h-2v6z"

private var moveCache: ImageVector? = null
private var spamCache: ImageVector? = null

internal val moveImage: ImageVector
    get() = moveCache ?: vectorIcon("Move", MOVE_PATH).also { moveCache = it }

internal val spamImage: ImageVector
    get() = spamCache ?: vectorIcon("Spam", SPAM_PATH).also { spamCache = it }

private fun vectorIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
