package org.dlang.liveimap.window

import java.util.Locale

const val LayoutAutomatic = "automatic"
const val LayoutIndexBesideMessage = "index-beside-message"
const val LayoutListAboveMessage = "list-above-message"
const val LayoutFolderTreePinned = "folder-tree-pinned"
const val LayoutDrawerPinned = "drawer-pinned"

val factoryLayoutIds: List<String> = listOf(
    LayoutAutomatic,
    LayoutIndexBesideMessage,
    LayoutListAboveMessage,
    LayoutFolderTreePinned,
    LayoutDrawerPinned,
)

private fun panel(id: String, pin: WindowLayerKind? = null) = LayoutNode.Panel(LayoutPanel(id, pin))

private fun beside(share: Float, first: LayoutNode, second: LayoutNode) =
    LayoutNode.Split(SplitDirection.Beside, share, first, second)

private fun above(share: Float, first: LayoutNode, second: LayoutNode) =
    LayoutNode.Split(SplitDirection.Above, share, first, second)

/** Factory bodies are code. They are not copied into the catalog until edited. */
fun factoryRecord(id: String): LayoutRecord? = when (id) {
    LayoutAutomatic -> LayoutRecord(panel("panel"), UnusedPanel.Collapse, coverPanels = 0, unpinnedKeep = 1)
    LayoutIndexBesideMessage -> LayoutRecord(
        beside(0.45f, panel("index"), panel("message")),
        UnusedPanel.Collapse,
        coverPanels = 0,
        unpinnedKeep = 2,
    )
    LayoutListAboveMessage -> LayoutRecord(
        above(0.4f, panel("list"), panel("message")),
        UnusedPanel.Collapse,
        coverPanels = 0,
        unpinnedKeep = 2,
    )
    LayoutFolderTreePinned -> LayoutRecord(
        beside(
            0.22f,
            panel("folders", WindowLayerKind.FolderTree),
            beside(0.45f, panel("index"), panel("message")),
        ),
        UnusedPanel.Collapse,
        coverPanels = 0,
        unpinnedKeep = 2,
    )
    LayoutDrawerPinned -> LayoutRecord(
        beside(
            0.22f,
            panel("drawer", WindowLayerKind.Drawer),
            beside(0.45f, panel("index"), panel("message")),
        ),
        UnusedPanel.Collapse,
        coverPanels = 0,
        unpinnedKeep = 2,
    )
    else -> null
}

data class CustomLayout(val id: String, val record: LayoutRecord)

/** One criterion token and the layout it selects. An unknown token is stored and does not match. */
data class LayoutRule(val criterion: String, val layoutId: String)

const val LayoutCriterionPortrait = "portrait"
const val LayoutCriterionLandscape = "landscape"
const val LayoutCriterionUpright = "upright"
const val LayoutCriterionLeft = "left"
const val LayoutCriterionUpside = "upside"
const val LayoutCriterionRight = "right"
const val LayoutCriterionExternal = "external"

/** Display rotation values: upright, turned left, upside down, turned right. */
const val LayoutRotationUpright = 0
const val LayoutRotationLeft = 1
const val LayoutRotationUpside = 2
const val LayoutRotationRight = 3

/**
 * This window's rotation and whether its display is the built-in one.
 * [portrait] comes from Configuration.orientation. Null is neither portrait nor landscape.
 */
data class LayoutSituation(
    val rotation: Int,
    val portrait: Boolean?,
    val builtIn: Boolean,
)

/**
 * Custom records, factory ids the user removed, the selected id, and choice rules.
 * The selected id is the default layout. An empty selection, a missing id, and a removed id are the single-panel factory.
 * Rules are ordered. The most specific match wins, and the earlier rule wins a tie.
 */
data class LayoutCatalog(
    val selectedId: String = "",
    val removedFactories: Set<String> = emptySet(),
    val custom: List<CustomLayout> = emptyList(),
    val rules: List<LayoutRule> = emptyList(),
)

private fun LayoutCatalog.layoutRecord(id: String): LayoutRecord? {
    if (id.isEmpty()) return null
    custom.firstOrNull { it.id == id }?.let { return it.record }
    if (id in removedFactories) return null
    return factoryRecord(id)
}

fun LayoutCatalog.resolve(situation: LayoutSituation? = null): LayoutRecord {
    if (situation != null) {
        winningRule(situation)?.let { return it }
    }
    return layoutRecord(selectedId) ?: factoryRecord(LayoutAutomatic)!!
}

private const val RankOrientation = 1
private const val RankRotation = 2
private const val RankExternal = 3

private fun criterionRank(token: String): Int? = when (token) {
    LayoutCriterionPortrait, LayoutCriterionLandscape -> RankOrientation
    LayoutCriterionUpright, LayoutCriterionLeft, LayoutCriterionUpside, LayoutCriterionRight -> RankRotation
    LayoutCriterionExternal -> RankExternal
    else -> null
}

private fun criterionMatches(token: String, situation: LayoutSituation): Boolean = when (token) {
    LayoutCriterionPortrait -> situation.portrait == true
    LayoutCriterionLandscape -> situation.portrait == false
    LayoutCriterionUpright -> situation.rotation == LayoutRotationUpright
    LayoutCriterionLeft -> situation.rotation == LayoutRotationLeft
    LayoutCriterionUpside -> situation.rotation == LayoutRotationUpside
    LayoutCriterionRight -> situation.rotation == LayoutRotationRight
    LayoutCriterionExternal -> !situation.builtIn
    else -> false
}

/** The earlier rule at the highest matching rank. A missing layout and an unknown token are skipped. */
private fun LayoutCatalog.winningRule(situation: LayoutSituation): LayoutRecord? {
    var bestRank = 0
    var best: LayoutRecord? = null
    for (rule in rules) {
        val rank = criterionRank(rule.criterion) ?: continue
        if (!criterionMatches(rule.criterion, situation)) continue
        val record = layoutRecord(rule.layoutId) ?: continue
        if (rank > bestRank) {
            bestRank = rank
            best = record
        }
    }
    return best
}

fun LayoutCatalog.addRule(criterion: String, layoutId: String): LayoutCatalog =
    copy(rules = rules + LayoutRule(criterion, layoutId))

fun LayoutCatalog.removeRule(index: Int): LayoutCatalog {
    if (index !in rules.indices) return this
    return copy(rules = rules.filterIndexed { at, _ -> at != index })
}

fun LayoutCatalog.moveRuleEarlier(index: Int): LayoutCatalog {
    if (index !in 1..rules.lastIndex) return this
    val next = rules.toMutableList()
    val rule = next.removeAt(index)
    next.add(index - 1, rule)
    return copy(rules = next)
}

fun LayoutCatalog.offeredIds(): List<String> {
    val factories = factoryLayoutIds.filter { it !in removedFactories }
    return factories + custom.map { it.id }
}

fun LayoutCatalog.delete(id: String): LayoutCatalog {
    val nextCustom = custom.filter { it.id != id }
    val nextRemoved = if (factoryRecord(id) != null) removedFactories + id else removedFactories
    val nextSelected = if (selectedId == id) "" else selectedId
    return copy(custom = nextCustom, removedFactories = nextRemoved, selectedId = nextSelected)
}

/** Puts factory ids back. Custom records stay. */
fun LayoutCatalog.restoreDefaults(): LayoutCatalog = copy(removedFactories = emptySet())

fun LayoutCatalog.editFactory(factoryId: String): LayoutCatalog {
    val body = factoryRecord(factoryId) ?: return this
    val id = nextCustomId(custom)
    return copy(custom = custom + CustomLayout(id, body), selectedId = id)
}

fun LayoutCatalog.replaceCustom(id: String, record: LayoutRecord): LayoutCatalog {
    if (layoutRecordProblem(record) != null) return this
    if (custom.none { it.id == id }) return this
    return copy(custom = custom.map { if (it.id == id) it.copy(record = record) else it })
}

private fun nextCustomId(custom: List<CustomLayout>): String {
    val ids = custom.map { it.id }.toSet()
    var n = custom.size + 1
    while ("c$n" in ids) n += 1
    return "c$n"
}

fun encodeLayoutCatalog(catalog: LayoutCatalog): String {
    val lines = ArrayList<String>()
    if (catalog.selectedId.isNotEmpty()) lines.add("s ${catalog.selectedId}")
    for (id in catalog.removedFactories.sorted()) lines.add("x $id")
    for (item in catalog.custom) lines.add("u ${item.id} ${encodeLayoutRecord(item.record)}")
    for (rule in catalog.rules) lines.add("r ${rule.criterion} ${rule.layoutId}")
    return lines.joinToString("\n")
}

fun decodeLayoutCatalog(text: String): LayoutCatalog {
    if (text.isBlank()) return LayoutCatalog()
    var selected = ""
    val removed = LinkedHashSet<String>()
    val custom = ArrayList<CustomLayout>()
    val rules = ArrayList<LayoutRule>()
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val kind = line.substringBefore(' ')
        val rest = line.substringAfter(' ', "")
        when (kind) {
            "s" -> selected = rest.trim()
            "x" -> if (rest.isNotBlank()) removed.add(rest.trim())
            "u" -> {
                val id = rest.substringBefore(' ').trim()
                val body = rest.substringAfter(' ', "")
                val record = decodeLayoutRecord(body)
                if (id.isNotEmpty() && record != null) custom.add(CustomLayout(id, record))
            }
            "r" -> {
                val token = rest.substringBefore(' ').trim()
                val layoutId = rest.substringAfter(' ', "").trim()
                if (token.isNotEmpty() && layoutId.isNotEmpty()) rules.add(LayoutRule(token, layoutId))
            }
        }
    }
    return LayoutCatalog(selected, removed, custom, rules)
}

fun encodeLayoutRecord(record: LayoutRecord): String {
    val unused = if (record.unused == UnusedPanel.Collapse) "C" else "E"
    val root = record.root?.let { encodeNode(it) } ?: "-"
    return "U$unused ${record.coverPanels} ${record.unpinnedKeep} $root"
}

fun decodeLayoutRecord(text: String): LayoutRecord? {
    if (!text.startsWith("U") || text.length < 2) return null
    val unused = when (text[1]) {
        'C' -> UnusedPanel.Collapse
        'E' -> UnusedPanel.StayEmpty
        else -> return null
    }
    val parts = text.drop(2).trim().split(' ', limit = 3)
    if (parts.size < 3) return null
    val cover = parts[0].toIntOrNull() ?: return null
    val keep = parts[1].toIntOrNull() ?: return null
    val root = if (parts[2] == "-") null else decodeNode(parts[2]) ?: return null
    return LayoutRecord(root, unused, cover, keep)
}

private fun encodeNode(node: LayoutNode): String = when (node) {
    is LayoutNode.Panel -> {
        val pin = node.panel.pin
        if (pin == null) "p:${node.panel.id}" else "p:${node.panel.id}@${pin.name}"
    }
    is LayoutNode.Split -> {
        val mark = if (node.direction == SplitDirection.Beside) "B" else "A"
        val share = "%.3f".format(Locale.ROOT, node.share).trimEnd('0').trimEnd('.')
        "$mark$share(${encodeNode(node.first)},${encodeNode(node.second)})"
    }
}

private fun decodeNode(text: String): LayoutNode? {
    var at = 0
    fun parse(depth: Int): LayoutNode? {
        if (depth > 8 || at >= text.length) return null
        if (text.startsWith("p:", at)) {
            at += 2
            val start = at
            while (at < text.length && text[at] != ',' && text[at] != ')') at += 1
            val token = text.substring(start, at)
            if (token.isEmpty()) return null
            val id = token.substringBefore('@')
            val pinName = token.substringAfter('@', "")
            if (id.isEmpty()) return null
            val pin = if (pinName.isEmpty()) {
                null
            } else {
                WindowLayerKind.entries.firstOrNull { it.name == pinName } ?: return null
            }
            return LayoutNode.Panel(LayoutPanel(id, pin))
        }
        val mark = text.getOrNull(at) ?: return null
        val direction = when (mark) {
            'B' -> SplitDirection.Beside
            'A' -> SplitDirection.Above
            else -> return null
        }
        at += 1
        val open = text.indexOf('(', at)
        if (open < 0) return null
        val share = text.substring(at, open).toFloatOrNull()?.takeIf { it > 0f && it < 1f } ?: return null
        at = open + 1
        val first = parse(depth + 1) ?: return null
        if (text.getOrNull(at) != ',') return null
        at += 1
        val second = parse(depth + 1) ?: return null
        if (text.getOrNull(at) != ')') return null
        at += 1
        return LayoutNode.Split(direction, share, first, second)
    }
    val node = parse(0) ?: return null
    return if (at == text.length) node else null
}

fun LayoutNode.splitPanel(panelId: String, direction: SplitDirection, added: LayoutPanel): LayoutNode = when (this) {
    is LayoutNode.Panel -> if (panel.id == panelId) {
        LayoutNode.Split(direction, 0.5f, this, LayoutNode.Panel(added))
    } else {
        this
    }
    is LayoutNode.Split -> copy(
        first = first.splitPanel(panelId, direction, added),
        second = second.splitPanel(panelId, direction, added),
    )
}

fun LayoutNode.removePanel(panelId: String): LayoutNode = when (this) {
    is LayoutNode.Panel -> this
    is LayoutNode.Split -> when {
        first is LayoutNode.Panel && first.panel.id == panelId -> second
        second is LayoutNode.Panel && second.panel.id == panelId -> first
        else -> copy(first = first.removePanel(panelId), second = second.removePanel(panelId))
    }
}

fun LayoutNode.withPin(panelId: String, pin: WindowLayerKind?): LayoutNode = when (this) {
    is LayoutNode.Panel -> if (panel.id == panelId) LayoutNode.Panel(panel.copy(pin = pin)) else this
    is LayoutNode.Split -> copy(first = first.withPin(panelId, pin), second = second.withPin(panelId, pin))
}

fun LayoutNode.withShare(panelId: String, share: Float): LayoutNode = when (this) {
    is LayoutNode.Panel -> this
    is LayoutNode.Split -> {
        val firstPanel = first is LayoutNode.Panel && first.panel.id == panelId
        val secondPanel = second is LayoutNode.Panel && second.panel.id == panelId
        if (firstPanel || secondPanel) copy(share = share.coerceIn(0.1f, 0.9f))
        else copy(first = first.withShare(panelId, share), second = second.withShare(panelId, share))
    }
}

fun freshPanelId(record: LayoutRecord): String {
    val ids = record.panels.map { it.id }.toSet()
    var n = ids.size + 1
    while ("p$n" in ids) n += 1
    return "p$n"
}
