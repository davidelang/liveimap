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

// Sender HTML can nest elements arbitrarily deep, so the walk uses an explicit stack: each element is entered
// (opening work, children pushed) and left again (closing work) without recursion.
private class Frame(val node: Node, val leaving: Boolean, val savedQuote: Boolean = false)

private fun renderNode(root: Node, state: HtmlRender) {
    val stack = ArrayDeque<Frame>()
    stack.addLast(Frame(root, leaving = false))
    while (stack.isNotEmpty()) {
        val frame = stack.removeLast()
        val node = frame.node
        if (node is TextNode) {
            appendRaw(state, node.wholeText)
            continue
        }
        if (node !is Element) continue
        val name = node.normalName()
        if (name == "script" || name == "style") continue
        val breaks = lineBreaks.contains(name)
        if (frame.leaving) {
            state.quote = frame.savedQuote
            if (name == "tr") state.cellOpen = false
            if (breaks) breakLine(state)
            continue
        }
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
        stack.addLast(Frame(node, leaving = true, savedQuote = state.quote))
        if (name == "blockquote") state.quote = true
        val children = node.childNodes()
        for (k in children.indices.reversed()) stack.addLast(Frame(children[k], leaving = false))
    }
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
