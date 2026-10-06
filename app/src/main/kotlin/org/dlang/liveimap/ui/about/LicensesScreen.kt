package org.dlang.liveimap.ui.about

import android.content.res.AssetManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LicensesScreen() {
    val assets = LocalContext.current.assets
    var paragraphs by remember { mutableStateOf<List<String>?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(assets) {
        val text = withContext(Dispatchers.IO) { readNotices(assets) }
        if (text == null) {
            missing = true
        } else {
            paragraphs = noticeParagraphs(text)
        }
    }
    if (missing) {
        Text("The license text could not be read.")
        return
    }
    val shown = paragraphs ?: return
    SelectionContainer {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(shown) { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun noticeParagraphs(text: String): List<String> {
    val out = ArrayList<String>()
    val block = StringBuilder()
    fun flush() {
        if (block.isNotEmpty()) {
            out.add(block.toString())
            block.clear()
        }
    }
    for (line in text.split('\n')) {
        if (line.isBlank()) {
            flush()
        } else {
            if (block.isNotEmpty()) block.append('\n')
            block.append(line)
        }
    }
    flush()
    return out
}

private fun readNotices(assets: AssetManager): String? {
    return try {
        assets.open("licenses/NOTICES.txt").bufferedReader().use { it.readText() }
    } catch (e: IOException) {
        null
    }
}
