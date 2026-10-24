package org.dlang.liveimap.ui.reader

import java.io.File
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.TextLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four-way reader display selector (David, 2026-10-12): HTML, Text, Flowed, Monospaced. */
class TextDisplayTest {
    @Test fun textAndMonospacedKeepOriginalBreaks() {
        val lines = listOf("a  b", "c\td", "> q")
        assertEquals(lines, displayLines(lines, TextLayout.Text, flowed = true, delSp = false))
        assertEquals(lines, displayLines(lines, TextLayout.Monospaced, flowed = true, delSp = false))
    }

    @Test fun formatFlowedJoinsSoftBreaksOnly() {
        val lines = listOf("This is a ", "flowed para.", "Hard line", "", "-- ", "Sig")
        assertEquals(
            listOf("This is a flowed para.", "Hard line", "", "-- ", "Sig"),
            displayLines(lines, TextLayout.Flowed, flowed = true, delSp = false),
        )
    }

    @Test fun delSpDropsTheSoftBreakSpace() {
        assertEquals(listOf("Donaudampf"), unflow(listOf("Donau ", "dampf"), delSp = true))
    }

    @Test fun flowedKeepsQuoteDepthAndUnstuffs() {
        val lines = listOf("> one ", "> two", ">> deep ", ">> er", " >not a quote")
        assertEquals(listOf("> one two", ">> deep er", ">not a quote"), unflow(lines, delSp = false))
    }

    @Test fun quoteDepthChangeEndsAFlowedParagraph() {
        assertEquals(listOf("> a ", "b"), unflow(listOf("> a ", "b"), delSp = false))
    }

    @Test fun ordinaryTextReflowsProseButKeepsStructure() {
        val prose = "word ".repeat(14).trim() // 69 chars, near a 72 wrap
        val lines = listOf(prose, prose, "end.", "", "- item one", "- item two", "col1   col2   col3", "Short", "Lines")
        val out = reflow(lines)
        assertEquals("$prose $prose end.", out[0])
        assertEquals("", out[1])
        assertEquals(listOf("- item one", "- item two", "col1   col2   col3", "Short", "Lines"), out.drop(2))
    }

    @Test fun ordinaryQuotedTextReflowsWithinItsDepth() {
        val prose = "word ".repeat(14).trim()
        val out = reflow(listOf("> $prose", "> more", "plain"))
        assertEquals(listOf("> $prose more", "plain"), out)
    }

    @Test fun unflowOfDonauDampfMatchesOnePushAndASplit() {
        val lines = listOf("Donau ", "dampf")
        val joined = unflow(lines, delSp = false)
        val one = FlowedStream().push(lines, TextLayout.Flowed, flowed = true, delSp = false, finished = true)
        val stream = FlowedStream()
        stream.push(listOf("Donau "), TextLayout.Flowed, flowed = true, delSp = false, finished = false)
        val two = stream.push(listOf("dampf"), TextLayout.Flowed, flowed = true, delSp = false, finished = true)
        assertEquals(joined, one)
        assertEquals(joined, two)
    }

    @Test fun plainReflowWaitsUntilTheBodyIsFinished() {
        val prose = "word ".repeat(14).trim()
        val lines = listOf(prose, prose, "end.")
        val expected = reflow(lines)
        assertEquals(
            expected,
            FlowedStream().push(lines, TextLayout.Flowed, flowed = false, delSp = false, finished = true),
        )
        val stream = FlowedStream()
        val partial = stream.push(lines.subList(0, 2), TextLayout.Flowed, flowed = false, delSp = false, finished = false)
        assertEquals(lines.subList(0, 2), partial)
        assertEquals(
            expected,
            stream.push(listOf(lines[2]), TextLayout.Flowed, flowed = false, delSp = false, finished = true),
        )
    }

    @Test fun textAndMonospacedAppendEachChunk() {
        val first = listOf("Donau ", "stay")
        val second = listOf("dampf")
        for (layout in listOf(TextLayout.Text, TextLayout.Monospaced)) {
            val stream = FlowedStream()
            assertEquals(first, stream.push(first, layout, flowed = true, delSp = true, finished = false))
            assertEquals(first + second, stream.push(second, layout, flowed = true, delSp = true, finished = true))
        }
    }

    @Test fun selectorMapsToViewAndLayout() {
        assertEquals(ReaderDisplay.Html, readerDisplayOf(BodyView.PlainOrHtml, TextLayout.Monospaced))
        assertEquals(ReaderDisplay.Flowed, readerDisplayOf(BodyView.PlainOrError, TextLayout.Flowed))
        assertEquals(BodyView.PlainOrHtml, bodyViewOf(ReaderDisplay.Html, BodyView.PlainOrError))
        assertEquals(BodyView.PlainOrError, bodyViewOf(ReaderDisplay.Monospaced, BodyView.PlainOrHtml))
        assertEquals(BodyView.Raw, bodyViewOf(ReaderDisplay.Text, BodyView.Raw))
        assertEquals(TextLayout.Flowed, textLayoutOf(ReaderDisplay.Html, TextLayout.Flowed))
        assertEquals(TextLayout.Monospaced, textLayoutOf(ReaderDisplay.Monospaced, TextLayout.Text))
    }

    @Test fun readerUsesOneSelectorWithZoomAndNoWrap() {
        val reader = File("src/main/kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt").readText()
        assertTrue(reader.contains("ReaderDisplayMenu("))
        assertTrue(!reader.contains("SingleChoiceSegmentedButtonRow"))
        assertTrue(reader.contains("BodyView.entries.filter { it != BodyView.PlainOrHtml && it != BodyView.PlainOrError }"))
        assertTrue(reader.contains("horizontalScroll(hscroll)"))
        assertTrue(reader.contains("softWrap = false"))
        // text zoom (settings) and pinch apply to every layout: one LocalTextStyle scale around the lines
        assertTrue(reader.contains("base.fontSize * (textZoom / 100f)"))
        val jni = File("src/main/cpp/liveimap_jni.cpp").readText()
        assertTrue(jni.contains("paramValue(fields->bd_parameter, \"format\")"))
        assertTrue(jni.contains("Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V"))
    }
}
