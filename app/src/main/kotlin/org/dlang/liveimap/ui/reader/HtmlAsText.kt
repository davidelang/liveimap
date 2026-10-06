package org.dlang.liveimap.ui.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val lineBreaks = setOf(
    "p",
    "div",
    "br",
    "tr",
    "li",
    "blockquote",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
)

private class HtmlRender {
    val out = StringBuilder()
    val links = ArrayList<String>()
    var quote = false
    var atLineStart = true
    var cellOpen = false
}

fun htmlAsText(html: String): String {
    val document = Jsoup.parse(html)
    document.select("script, style").remove()
    val state = HtmlRender()
    val body = document.body()
    if (body != null) renderNode(body, state)
    return finishHtmlText(state)
}

private fun renderNode(node: Node, state: HtmlRender) {
    if (node is TextNode) {
        appendRaw(state, node.wholeText)
        return
    }
    if (node !is Element) return
    val name = node.normalName()
    if (name == "script" || name == "style") return
    val breaks = lineBreaks.contains(name)
    if (breaks) breakLine(state)
    when (name) {
        "li" -> {
            val parent = node.parent()?.normalName().orEmpty()
            if (parent == "ol") appendRaw(state, liNumber(node).toString() + ". ")
            else appendRaw(state, "* ")
        }
        "img" -> {
            val alt = node.attr("alt")
            appendRaw(state, if (alt.isEmpty()) "[image]" else alt)
        }
        "td", "th" -> {
            if (state.cellOpen) appendRaw(state, " ")
            state.cellOpen = true
        }
        "tr" -> state.cellOpen = false
        "a" -> {
            val href = node.attr("href")
            if (href.isNotEmpty()) state.links.add(href)
        }
    }
    val savedQuote = state.quote
    if (name == "blockquote") state.quote = true
    for (child in node.childNodes()) renderNode(child, state)
    state.quote = savedQuote
    if (name == "tr") state.cellOpen = false
    if (breaks) breakLine(state)
}

private fun liNumber(element: Element): Int {
    val parent = element.parent() ?: return 1
    var number = 0
    for (child in parent.children()) {
        if (child.normalName() == "li") number++
        if (child === element) return number
    }
    return number
}

private fun breakLine(state: HtmlRender) {
    if (state.out.isNotEmpty() && state.out.last() != '\n') state.out.append('\n')
    state.atLineStart = true
}

private fun appendRaw(state: HtmlRender, text: String) {
    for (ch in text) {
        if (state.atLineStart && state.quote && ch != '\n') {
            state.out.append("> ")
            state.atLineStart = false
        }
        state.out.append(ch)
        state.atLineStart = ch == '\n'
    }
}

private fun finishHtmlText(state: HtmlRender): String {
    val normalized = state.out.toString().replace("\r\n", "\n").replace('\r', '\n')
    val collapsed = Regex("\n{3,}").replace(normalized, "\n\n").trim('\n')
    if (state.links.isEmpty()) return collapsed
    val block = state.links.mapIndexed { index, href -> "[${index + 1}] $href" }.joinToString("\n")
    if (collapsed.isEmpty()) return block
    return collapsed + "\n\n" + block
}
