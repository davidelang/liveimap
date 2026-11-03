package org.dlang.liveimap.settings

enum class SettingScope { Ui, Account }

enum class SettingType { Bool, Int, Long, String, Enum, StringList, IntList, Encoded }

enum class ControlKind { Text, Number, Bool, Choice, Mailbox, Subscreen }

data class SettingSpec(
    val key: String,
    val type: SettingType,
    val scope: SettingScope,
    val control: ControlKind,
    val title: String,
    val enumValues: List<String>,
    val intChoices: List<Long>,
    val nullable: Boolean,
    val server: Boolean,
    val visibleWhen: Condition?,
    val enabledWhen: Condition?,
    val leaf: TreeLeaf,
)

data class TreeLeaf(
    val setting: String,
    val typeName: String,
    val scopeName: String,
    val kind: String,
    val default: JsonValue,
    val title: String,
    val summary: String? = null,
    val choiceTitles: Map<String, String> = emptyMap(),
    val choices: List<String>? = null,
    val min: Long? = null,
    val max: Long? = null,
    val minItems: Long? = null,
    val maxItems: Long? = null,
    val itemMin: Long? = null,
    val itemMax: Long? = null,
    val order: String? = null,
    val many: Boolean = false,
    val server: Boolean = false,
    val derived: Boolean = false,
    val pinerc: String? = null,
    val pinercFormat: String? = null,
    val visibleWhen: Condition? = null,
    val enabledWhen: Condition? = null,
    val editor: String? = null,
    val opens: String? = null,
    val hidden: Boolean = false,
)

sealed class TreeNode {
    data class Page(
        val page: String,
        val title: String,
        val children: List<TreeNode>,
        val summary: List<JsonValue> = emptyList(),
    ) : TreeNode()

    data class Group(
        val id: String,
        val title: String,
        val resource: String,
        val header: String,
        val description: String?,
        val children: List<TreeNode>,
        val summary: List<JsonValue> = emptyList(),
        val visibleWhen: Condition? = null,
        val editor: String? = null,
        val opens: String? = null,
        val introWhen: List<JsonValue> = emptyList(),
    ) : TreeNode()

    data class Editor(
        val editor: String,
        val opens: String,
        val title: String,
        val children: List<TreeNode>,
        val summary: List<JsonValue> = emptyList(),
    ) : TreeNode()

    data class Leaf(val leaf: TreeLeaf) : TreeNode()
}

private val uiInstalled = listOf(
    "indexPageSize",
    "swipeThresholdPercent",
    "reconnectDelaysSec",
    "maxRawViewBytes",
    "maxImageTrustBytes",
    "moveChunk",
    "clientSortChunk",
)

private val uiRequired = uiInstalled

private val serverRequired = listOf(
    "imapHost",
    "imapPort",
    "tlsMode",
    "clientCertAlias",
    "certPin",
    "jmapCertPin",
    "smtpHost",
    "smtpPort",
    "smtpTlsMode",
    "smtpUsername",
    "sieveHost",
    "sieveSameAsInbound",
    "sieveUsername",
    "passwordStorage",
    "sievePort",
    "sieveTlsMode",
    "sieveCertPin",
    "sieveConnectSeconds",
    "sieveReadSeconds",
)

private val leafKeys = setOf(
    "setting", "type", "default", "title", "summary", "choiceTitles", "choices",
    "min", "max", "minItems", "maxItems", "itemMin", "itemMax", "order", "many", "server", "derived",
    "pinerc", "pinercFormat", "visibleWhen", "enabledWhen", "titleWhen", "noteWhen",
    "format", "label", "nullable", "titleArgs", "hidden",
)

private val unstoredTypes = setOf("password", "checkServer", "pinerc", "settingsFile")

private val groupKeys = setOf(
    "group", "title", "label", "header", "children", "summary", "visibleWhen", "enabledWhen",
    "description", "editor", "opens", "introWhen",
)

private val editorKeys = setOf("editor", "opens", "title", "summary", "settings", "children", "label")

private val sectionKeys = setOf("section", "title", "children", "summary")

private val opensOk = setOf("screen", "popup", "inline")

private val editorsOk = mapOf(
    "hiddenOptions" to setOf("screen"),
    "swipes" to setOf("popup"),
    "clientCert" to setOf("inline"),
    "server" to setOf("inline"),
    "rules" to setOf("screen"),
    "folderLists" to setOf("screen"),
    "addressBooks" to setOf("screen"),
    "paneLayout" to setOf("screen"),
    "compression" to setOf("inline"),
    "deletePolicy" to setOf("inline"),
    "moveMethod" to setOf("inline"),
    "toolbars" to setOf("screen"),
    "accountList" to setOf("screen"),
    "pinerc" to setOf("screen"),
)

internal object InstalledTree {
    var leaves: Map<String, TreeLeaf> = emptyMap()
    var roots: List<TreeNode> = emptyList()
    var specs: Map<String, SettingSpec> = emptyMap()
}

val settingsRegistry: Map<String, SettingSpec>
    get() {
        if (InstalledTree.specs.isEmpty()) installGeneratedSettings()
        return InstalledTree.specs
    }

fun installedSettingsRoots(): List<TreeNode> {
    if (InstalledTree.roots.isEmpty()) installGeneratedSettings()
    return InstalledTree.roots
}

fun findSettingsPage(nodes: List<TreeNode>, page: String): TreeNode.Page? {
    for (node in nodes) {
        when (node) {
            is TreeNode.Page -> {
                if (node.page == page) return node
                findSettingsPage(node.children, page)?.let { return it }
            }
            is TreeNode.Group -> findSettingsPage(node.children, page)?.let { return it }
            else -> Unit
        }
    }
    return null
}

fun findSettingsEditor(nodes: List<TreeNode>, editorId: String?): TreeNode? {
    if (editorId.isNullOrEmpty()) return null
    for (node in nodes) {
        when (node) {
            is TreeNode.Editor -> if (node.editor == editorId) return node
            is TreeNode.Group -> {
                if (node.editor == editorId) return node
                findSettingsEditor(node.children, editorId)?.let { return it }
            }
            is TreeNode.Page -> findSettingsEditor(node.children, editorId)?.let { return it }
            else -> Unit
        }
    }
    return null
}

fun installedLeaf(key: String): TreeLeaf? = InstalledTree.leaves[key]

fun parseSettingsTree(text: String): List<TreeNode> = parseSettingsDocument(text).pages

data class SettingsDocument(val pages: List<TreeNode>)

fun parseSettingsDocument(text: String): SettingsDocument {
    val root = parseJson(text)
    if (root is JsonValue.Arr) {
        return SettingsDocument(root.items.map { node(it, "") })
    }
    val obj = root as? JsonValue.Obj ?: throw SettingsTreeParseException("settings-tree.json root")
    for (name in obj.fields.keys) {
        if (name != "sections") throw SettingsTreeParseException("settings-tree.json root field $name")
    }
    val sections = obj.fields["sections"] as? JsonValue.Arr
        ?: throw SettingsTreeParseException("settings-tree.json sections")
    return SettingsDocument(sections.items.map { readSection(it) })
}

private fun readSection(value: JsonValue): TreeNode.Page {
    val obj = value as? JsonValue.Obj ?: throw SettingsTreeParseException("settings-tree.json section")
    for (name in obj.fields.keys) {
        if (name !in sectionKeys) throw SettingsTreeParseException("settings-tree.json section field $name")
    }
    val section = str(obj, "section") ?: throw SettingsTreeParseException("settings-tree.json section")
    val title = str(obj, "title") ?: throw SettingsTreeParseException("settings-tree.json section title")
    val children = childrenOf(obj, "children", section)
    return TreeNode.Page(section, title, children, arrayField(obj, "summary"))
}

private fun node(value: JsonValue, scopeName: String): TreeNode {
    val obj = value as? JsonValue.Obj ?: throw SettingsTreeParseException("settings-tree.json node")
    return when {
        "section" in obj.fields -> throw SettingsTreeParseException("settings-tree.json nested section")
        "group" in obj.fields -> readGroup(obj, scopeName)
        "setting" in obj.fields -> TreeNode.Leaf(readLeaf(obj, scopeName))
        "editor" in obj.fields -> readEditor(obj, scopeName)
        else -> throw SettingsTreeParseException("settings-tree.json node")
    }
}

private fun readGroup(obj: JsonValue.Obj, scopeName: String): TreeNode.Group {
    val id = str(obj, "group") ?: throw SettingsTreeParseException("settings-tree.json group")
    val resource = str(obj, "title") ?: throw SettingsTreeParseException("settings-tree.json group title")
    val label = str(obj, "label") ?: resource
    val header = str(obj, "header") ?: "label"
    val children = childrenOf(obj, "children", scopeName)
    return TreeNode.Group(
        id = id,
        title = label,
        resource = resource,
        header = header,
        description = str(obj, "description"),
        children = children,
        summary = arrayField(obj, "summary"),
        visibleWhen = conditionField(obj, "visibleWhen"),
        editor = str(obj, "editor"),
        opens = str(obj, "opens"),
        introWhen = arrayField(obj, "introWhen"),
    )
}

private fun readEditor(obj: JsonValue.Obj, scopeName: String): TreeNode.Editor {
    val editor = str(obj, "editor") ?: throw SettingsTreeParseException("settings-tree.json editor")
    val opens = str(obj, "opens") ?: ""
    val title = str(obj, "title") ?: ""
    val children = when {
        "settings" in obj.fields -> childrenOf(obj, "settings", scopeName)
        "children" in obj.fields -> childrenOf(obj, "children", scopeName)
        else -> emptyList()
    }
    return TreeNode.Editor(editor, opens, title, children, arrayField(obj, "summary"))
}

private fun readLeaf(obj: JsonValue.Obj, scopeName: String): TreeLeaf {
    val key = str(obj, "setting") ?: throw SettingsTreeParseException("settings-tree.json leaf setting")
    for (name in obj.fields.keys) {
        if (name !in leafKeys) throw SettingsTreeParseException("settings-tree.json leaf $key field $name")
    }
    val typeName = str(obj, "type").orEmpty()
    if ("default" !in obj.fields && typeName !in unstoredTypes) {
        throw SettingsTreeParseException("settings-tree.json leaf $key default")
    }
    fun longField(name: String): Long? = when (val v = obj.fields[name]) {
        null -> null
        is JsonValue.Num -> v.value
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key $name")
    }
    val choices = when (val v = obj.fields["choices"]) {
        null -> null
        is JsonValue.Arr -> v.items.map {
            when (it) {
                is JsonValue.Str -> it.value
                is JsonValue.Num -> it.value.toString()
                else -> throw SettingsTreeParseException("settings-tree.json leaf $key choices")
            }
        }
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key choices")
    }
    val choiceTitles = when (val v = obj.fields["choiceTitles"]) {
        null -> emptyMap()
        is JsonValue.Obj -> v.fields.mapValues { (_, item) ->
            (item as? JsonValue.Str)?.value ?: throw SettingsTreeParseException("settings-tree.json leaf $key choiceTitles")
        }
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key choiceTitles")
    }
    val hidden = when (val v = obj.fields["hidden"]) {
        null -> false
        is JsonValue.Bool -> v.value
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key hidden")
    }
    val many = when (val v = obj.fields["many"]) {
        null -> false
        is JsonValue.Bool -> v.value
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key many")
    }
    return TreeLeaf(
        setting = key,
        typeName = typeName,
        scopeName = scopeName,
        kind = typeName,
        default = obj.fields["default"] ?: JsonValue.Null,
        title = str(obj, "title").orEmpty(),
        summary = str(obj, "summary"),
        choiceTitles = choiceTitles,
        choices = choices,
        min = longField("min"),
        max = longField("max"),
        minItems = longField("minItems"),
        maxItems = longField("maxItems"),
        itemMin = longField("itemMin"),
        itemMax = longField("itemMax"),
        order = str(obj, "order"),
        many = many,
        server = boolField(obj, "server") == true,
        derived = boolField(obj, "derived") == true,
        pinerc = str(obj, "pinerc"),
        pinercFormat = str(obj, "pinercFormat"),
        visibleWhen = conditionField(obj, "visibleWhen"),
        enabledWhen = conditionField(obj, "enabledWhen"),
        editor = str(obj, "editor"),
        opens = str(obj, "opens"),
        hidden = hidden,
    )
}

private fun childrenOf(obj: JsonValue.Obj, name: String, scopeName: String): List<TreeNode> {
    val arr = obj.fields[name] as? JsonValue.Arr ?: throw SettingsTreeParseException("settings-tree.json $name")
    return arr.items.map { node(it, scopeName) }
}

private fun arrayField(obj: JsonValue.Obj, name: String): List<JsonValue> = when (val v = obj.fields[name]) {
    null -> emptyList()
    is JsonValue.Arr -> v.items
    else -> throw SettingsTreeParseException("settings-tree.json $name")
}

private fun conditionField(obj: JsonValue.Obj, name: String): Condition? {
    val raw = obj.fields[name] ?: return null
    return parseCondition(raw, name)
}

private fun str(obj: JsonValue.Obj, name: String): String? = (obj.fields[name] as? JsonValue.Str)?.value

private fun boolField(obj: JsonValue.Obj, name: String): Boolean? = (obj.fields[name] as? JsonValue.Bool)?.value

fun groupTitles(nodes: List<TreeNode>): List<String> {
    val out = ArrayList<String>()
    fun walk(node: TreeNode) {
        when (node) {
            is TreeNode.Group -> {
                out.add(node.id)
                node.children.forEach { walk(it) }
            }
            is TreeNode.Page -> node.children.forEach { walk(it) }
            is TreeNode.Editor -> node.children.forEach { walk(it) }
            is TreeNode.Leaf -> Unit
        }
    }
    nodes.forEach { walk(it) }
    return out
}

fun validateSettingsTree(text: String, complete: Boolean = true): List<String> {
    val errors = ArrayList<String>()
    val doc = try {
        parseSettingsDocument(text)
    } catch (e: SettingsTreeParseException) {
        return listOf(e.message ?: "settings-tree.json malformed")
    }
    val seen = HashSet<String>()
    val seenSections = HashSet<String>()
    fun walk(node: TreeNode, section: String?) {
        when (node) {
            is TreeNode.Page -> {
                if (node.page !in setOf("ui", "account")) errors.add("settings-tree.json section ${node.page}")
                else if (!seenSections.add(node.page)) errors.add("settings-tree.json duplicate section ${node.page}")
                checkResource(node.title, complete, errors)
                checkSummary(node.summary, "section ${node.page}", complete, errors)
                node.children.forEach { walk(it, node.page) }
            }
            is TreeNode.Group -> {
                if (node.header != "label" && node.header != "arrow") errors.add("settings-tree.json group ${node.id} header")
                checkResource(node.resource, complete, errors)
                checkSummary(node.summary, "group ${node.id}", complete, errors)
                checkSummary(node.introWhen, "group ${node.id} intro", complete, errors)
                checkOpens(node.editor, node.opens, "group ${node.id}", errors)
                if (node.visibleWhen != null) checkCondition(node.visibleWhen, errors)
                node.children.forEach { walk(it, section) }
            }
            is TreeNode.Editor -> {
                checkResource(node.title, complete, errors)
                checkOpens(node.editor, node.opens, "editor ${node.editor}", errors)
                checkSummary(node.summary, "editor ${node.editor}", complete, errors)
                node.children.forEach { walk(it, section) }
            }
            is TreeNode.Leaf -> {
                if (!seen.add(node.leaf.setting)) errors.add("settings-tree.json duplicate setting ${node.leaf.setting}")
                errors.addAll(checkLeaf(node.leaf, section, complete))
            }
        }
    }
    doc.pages.forEach { walk(it, null) }
    if (complete) {
        if (seenSections != setOf("ui", "account")) errors.add("settings-tree.json sections")
        for (key in uiRequired) {
            val leaf = findLeaf(doc.pages, key)
            if (leaf == null || leaf.scopeName != "ui") {
                errors.add("settings-tree.json $key is not ui")
            }
        }
        for (key in serverRequired) {
            val leaf = findLeaf(doc.pages, key)
            if (leaf == null || !leaf.server || leaf.scopeName != "account") {
                errors.add("settings-tree.json $key server")
            }
        }
    }
    return errors
}

private fun findLeaf(nodes: List<TreeNode>, key: String): TreeLeaf? {
    for (node in nodes) {
        when (node) {
            is TreeNode.Leaf -> if (node.leaf.setting == key) return node.leaf
            is TreeNode.Group -> findLeaf(node.children, key)?.let { return it }
            is TreeNode.Page -> findLeaf(node.children, key)?.let { return it }
            is TreeNode.Editor -> findLeaf(node.children, key)?.let { return it }
        }
    }
    return null
}

private fun checkLeaf(leaf: TreeLeaf, page: String?, complete: Boolean): List<String> {
    val errors = ArrayList<String>()
    val key = leaf.setting
    if (leaf.typeName.isEmpty()) errors.add("settings-tree.json leaf $key type")
    if (page == "ui" || page == "account") {
        if (leaf.scopeName != page) errors.add("settings-tree.json leaf $key scope")
    } else if (leaf.scopeName != "ui" && leaf.scopeName != "account") {
        errors.add("settings-tree.json leaf $key scope")
    }
    if (leaf.kind.isEmpty()) errors.add("settings-tree.json leaf $key kind")
    if (leaf.title.isEmpty()) errors.add("settings-tree.json leaf $key title")
    if (leaf.title.isNotEmpty()) checkResource(leaf.title, complete, errors)
    leaf.summary?.let { checkResource(it, complete, errors) }
    val numeric = leaf.typeName == "number" || leaf.typeName == "Int" || leaf.typeName == "Long"
    val list = leaf.many
    if (!numeric && (leaf.min != null || leaf.max != null)) errors.add("settings-tree.json leaf $key has min or max")
    if (leaf.min != null && leaf.max != null && leaf.min > leaf.max) errors.add("settings-tree.json leaf $key min above max")
    if (leaf.choices != null && leaf.choices.isEmpty()) errors.add("settings-tree.json leaf $key empty choices")
    if ((leaf.typeName == "choice" || leaf.typeName == "Enum") && leaf.choices != null) {
        for (choice in leaf.choices) {
            val titled = leaf.choiceTitles[choice]
            if (titled == null) errors.add("settings-tree.json leaf $key choice $choice")
            else checkResource(titled, complete, errors)
        }
    }
    if (leaf.typeName != "number" && leaf.typeName != "Int" && leaf.typeName != "choice" && leaf.typeName != "Enum" && leaf.choices != null && leaf.typeName.isNotEmpty()) {
        errors.add("settings-tree.json leaf $key has choices")
    }
    if ((leaf.typeName == "number" || leaf.typeName == "Int") && leaf.choiceTitles.isNotEmpty()) {
        errors.add("settings-tree.json leaf $key choiceTitles")
    }
    if (leaf.typeName == "either" && (leaf.choiceTitles["false"] == null || leaf.choiceTitles["true"] == null)) {
        errors.add("settings-tree.json leaf $key choiceTitles")
    }
    if (!list && (leaf.minItems != null || leaf.maxItems != null || leaf.itemMin != null || leaf.itemMax != null)) {
        errors.add("settings-tree.json leaf $key has a list constraint")
    }
    if (leaf.many && leaf.order !in setOf("user", "sorted", "increasing")) {
        errors.add("settings-tree.json leaf $key order")
    }
    if (!leaf.many && leaf.order != null) errors.add("settings-tree.json leaf $key order")
    if (leaf.pinerc != null && leaf.pinercFormat.isNullOrEmpty()) errors.add("settings-tree.json leaf $key pinercFormat")
    if (leaf.visibleWhen != null) checkCondition(leaf.visibleWhen, errors)
    if (leaf.enabledWhen != null) checkCondition(leaf.enabledWhen, errors)
    checkOpens(leaf.editor, leaf.opens, "leaf $key", errors)
    errors.addAll(checkDefault(leaf))
    return errors
}

private fun checkDefault(leaf: TreeLeaf): List<String> {
    val key = leaf.setting
    val value = leaf.default
    fun bad() = listOf("settings-tree.json leaf $key default")
    val known = setOf(
        "bool", "either", "number", "choice", "text", "password", "folder", "mailbox",
        "checkServer", "clientCert", "pinerc", "settingsFile", "rules", "toolbar",
        "paneLayout", "swipe", "Bool", "Int", "Long", "String", "Enum", "StringList", "IntList", "Encoded", "",
    )
    if (leaf.typeName !in known) return listOf("settings-tree.json leaf $key type")
    if (leaf.typeName in unstoredTypes) return if (value is JsonValue.Null) emptyList() else bad()
    return when (leaf.typeName) {
        "bool", "either", "Bool" -> if (value is JsonValue.Bool) emptyList() else bad()
        "number", "Int", "Long" -> {
            if (leaf.many) {
                if (value !is JsonValue.Arr || value.items.any { it !is JsonValue.Num }) return bad()
                val nums = value.items.map { (it as JsonValue.Num).value }
                if (leaf.order == "increasing" && nums.zipWithNext().any { (a, b) -> a >= b }) return bad()
                emptyList()
            } else {
                if (value !is JsonValue.Num) return bad()
                if (numberRejection(key, leaf, value.value) != null) bad() else emptyList()
            }
        }
        "text", "String", "Encoded", "mailbox", "clientCert", "paneLayout", "rules", "" -> when {
            leaf.typeName.isEmpty() -> emptyList()
            leaf.many && value is JsonValue.Arr -> emptyList()
            !leaf.many && value is JsonValue.Str -> emptyList()
            else -> bad()
        }
        "choice", "Enum" -> {
            if (value !is JsonValue.Str) return bad()
            if (leaf.choices != null && value.value !in leaf.choices) return bad()
            emptyList()
        }
        "folder" -> if (value is JsonValue.Arr) emptyList() else bad()
        "swipe", "toolbar" -> if (value is JsonValue.Obj) emptyList() else bad()
        "StringList" -> {
            if (value !is JsonValue.Arr || value.items.any { it !is JsonValue.Str }) bad() else emptyList()
        }
        "IntList" -> {
            if (value !is JsonValue.Arr || value.items.any { it !is JsonValue.Num }) return bad()
            emptyList()
        }
        else -> emptyList()
    }
}

private fun ordered(nums: List<Long>, order: String?): Boolean = when (order) {
    null, "any" -> true
    "nondecreasing" -> nums.zipWithNext().all { (a, b) -> a <= b }
    "increasing" -> nums.zipWithNext().all { (a, b) -> a < b }
    else -> false
}

fun numberRejection(key: String, leaf: TreeLeaf, value: Long): String? {
    val numeric = leaf.typeName == "number" || leaf.typeName == "Int" || leaf.typeName == "Long" || leaf.typeName.isEmpty()
    if (!numeric) return "$key rejected $value"
    val fitsInt = leaf.typeName == "Int" || (leaf.typeName == "number" && leaf.max != null && leaf.max <= Int.MAX_VALUE && (leaf.min == null || leaf.min >= Int.MIN_VALUE))
    if ((fitsInt || leaf.typeName.isEmpty()) && (value < Int.MIN_VALUE || value > Int.MAX_VALUE)) {
        return "$key rejected $value"
    }
    if (leaf.min != null && value < leaf.min) return "$key rejected $value"
    if (leaf.max != null && value > leaf.max) return "$key rejected $value"
    if (leaf.choices != null && value.toString() !in leaf.choices) return "$key rejected $value"
    return null
}

fun rejectedNumber(treeJson: String, key: String, value: Long): String? {
    val doc = parseSettingsDocument(treeJson)
    val leaf = findLeaf(doc.pages, key) ?: return "unknown setting $key"
    return numberRejection(key, leaf, value)
}

private fun checkResource(name: String, complete: Boolean, errors: MutableList<String>) {
    val ident = name.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))
    if (name.isEmpty() || (!complete && !ident)) {
        errors.add("settings-tree.json resource $name")
        return
    }
    if (!complete) return
    if (ident && name in SettingsTreeGenerated.resourceNames) return
    if (SettingsTreeGenerated.sentence(name)) return
    errors.add("settings-tree.json resource $name")
}

private fun checkOpens(editor: String?, opens: String?, where: String, errors: MutableList<String>) {
    if (editor == null) {
        if (opens != null) errors.add("settings-tree.json $where opens")
        return
    }
    val allowed = editorsOk[editor]
    if (allowed == null) errors.add("settings-tree.json $where editor")
    if (opens == null || opens !in opensOk || (allowed != null && opens !in allowed)) {
        errors.add("settings-tree.json $where opens")
    }
}

private fun checkSummaryDisplays(obj: JsonValue.Obj, complete: Boolean, errors: MutableList<String>) {
    val text = (obj.fields["text"] as? JsonValue.Str)?.value
    val plural = (obj.fields["plural"] as? JsonValue.Str)?.value
    if (text != null) checkResource(text, complete, errors)
    if (plural != null) checkResource(plural, complete, errors)
    val titles = obj.fields["titles"] as? JsonValue.Obj
    if (titles != null) {
        for (item in titles.fields.values) {
            val name = (item as? JsonValue.Str)?.value ?: continue
            checkResource(name, complete, errors)
        }
    }
    val nested = obj.fields["value"] as? JsonValue.Obj
    if (nested != null) checkSummaryDisplays(nested, complete, errors)
    val args = obj.fields["args"] as? JsonValue.Arr
    if (args != null) {
        for (item in args.items) {
            val child = item as? JsonValue.Obj ?: continue
            checkSummaryDisplays(child, complete, errors)
        }
    }
}

private fun checkSummary(cases: List<JsonValue>, where: String, complete: Boolean, errors: MutableList<String>) {
    if (cases.isEmpty()) return
    cases.forEachIndexed { index, raw ->
        val obj = raw as? JsonValue.Obj
        if (obj == null) {
            errors.add("settings-tree.json $where summary")
            return@forEachIndexed
        }
        if (index == cases.lastIndex && "when" in obj.fields) errors.add("settings-tree.json $where summary when")
        val text = (obj.fields["text"] as? JsonValue.Str)?.value
        val plural = (obj.fields["plural"] as? JsonValue.Str)?.value
        checkSummaryDisplays(obj, complete, errors)
        if (text == null && plural == null && "value" !in obj.fields) errors.add("settings-tree.json $where summary")
        val whenRaw = obj.fields["when"]
        if (whenRaw != null) {
            try {
                checkCondition(parseCondition(whenRaw, where), errors)
            } catch (e: SettingsTreeParseException) {
                errors.add(e.message ?: "settings-tree.json $where summary")
            }
        }
    }
}

private fun checkCondition(condition: Condition, errors: MutableList<String>) {
    when (condition) {
        is Condition.All -> condition.parts.forEach { checkCondition(it, errors) }
        is Condition.AnyOf -> condition.parts.forEach { checkCondition(it, errors) }
        is Condition.Equals -> checkValue(condition.setting, condition.expected, errors)
        is Condition.NotEquals -> checkValue(condition.setting, condition.expected, errors)
        is Condition.In -> if (condition.expected.isEmpty()) errors.add("settings-tree.json ${condition.setting} in")
        is Condition.Empty -> Unit
        is Condition.SdkAtLeast -> Unit
        is Condition.Not -> checkCondition(condition.part, errors)
    }
}

private fun checkValue(setting: String, expected: JsonValue, errors: MutableList<String>) {
    val leaf = InstalledTree.leaves[setting]
    if (completeLeaf(leaf) && expected is JsonValue.Str && leaf!!.choices != null && expected.value !in leaf.choices) {
        errors.add("settings-tree.json $setting equals ${expected.value}")
    }
}

private fun completeLeaf(leaf: TreeLeaf?): Boolean = leaf != null && leaf.choices != null

internal fun installBuiltTree(pages: List<TreeNode>) {
    val leaves = linkedMapOf<String, TreeLeaf>()
    fun walk(node: TreeNode) {
        when (node) {
            is TreeNode.Leaf -> leaves[node.leaf.setting] = node.leaf
            is TreeNode.Group -> node.children.forEach { walk(it) }
            is TreeNode.Page -> node.children.forEach { walk(it) }
            is TreeNode.Editor -> node.children.forEach { walk(it) }
        }
    }
    pages.forEach { walk(it) }
    InstalledTree.leaves = leaves
    InstalledTree.roots = pages
    InstalledTree.specs = leaves.mapValues { (_, leaf) -> specOf(leaf) }
    val titles = groupTitles(pages)
    val ids = SettingsTreeGenerated.groupIds
    if (titles.size == ids.size) SettingsTreeIndex.install(titles.zip(ids))
}

private fun specOf(leaf: TreeLeaf): SettingSpec {
    val type = when (leaf.typeName) {
        "bool", "either", "Bool" -> SettingType.Bool
        "number", "Int" -> if (leaf.many) SettingType.IntList else if (leaf.max == null || leaf.max > Int.MAX_VALUE) SettingType.Long else SettingType.Int
        "Long" -> SettingType.Long
        "choice", "Enum" -> SettingType.Enum
        "StringList" -> SettingType.StringList
        "IntList" -> SettingType.IntList
        "Encoded" -> SettingType.Encoded
        "text" -> if (leaf.many) SettingType.StringList else SettingType.String
        else -> SettingType.String
    }
    val control = when (leaf.typeName) {
        "number", "Int", "Long" -> ControlKind.Number
        "bool", "either", "Bool" -> ControlKind.Bool
        "choice", "Enum" -> ControlKind.Choice
        "mailbox" -> ControlKind.Mailbox
        "rules", "toolbar", "paneLayout", "swipe", "folder", "clientCert", "pinerc", "settingsFile", "checkServer" -> ControlKind.Subscreen
        else -> ControlKind.Text
    }
    val scope = if (leaf.scopeName == "ui") SettingScope.Ui else SettingScope.Account
    val intChoices = leaf.choices.orEmpty().mapNotNull { it.toLongOrNull() }
    return SettingSpec(
        key = leaf.setting,
        type = type,
        scope = scope,
        control = control,
        title = leaf.title,
        enumValues = if (type == SettingType.Enum) leaf.choices.orEmpty() else emptyList(),
        intChoices = if (type == SettingType.Int) intChoices else emptyList(),
        nullable = false,
        server = leaf.server,
        visibleWhen = leaf.visibleWhen,
        enabledWhen = leaf.enabledWhen,
        leaf = leaf,
    )
}

object SettingsTreeIndex {
    private val ids = HashMap<String, String>()

    fun install(pairs: List<Pair<String, String>>) {
        ids.clear()
        for ((title, id) in pairs) ids[title] = id
    }

    fun id(title: String): String = ids[title] ?: title
}

fun rejectedAccountSettings(settings: Settings): String? {
    val leaves = InstalledTree.leaves
    if (leaves.isEmpty()) return null
    val values = listOf(
        "imapPort" to settings.imapPort.toLong(),
        "smtpPort" to settings.smtpPort.toLong(),
        "sievePort" to settings.sievePort.toLong(),
        "extraIdleBudget" to settings.extraIdleBudget.toLong(),
        "maxConnections" to settings.maxConnections.toLong(),
        "bodyFetchChunk" to settings.bodyFetchChunk.toLong(),
        "composerWrapColumn" to settings.composerWrapColumn.toLong(),
        "idleWakeSeconds" to settings.idleWakeSeconds.toLong(),
        "pollSeconds" to settings.pollSeconds.toLong(),
        "addressBookHistory" to settings.addressBookHistory.toLong(),
        "askSort" to settings.askSort.toLong(),
        "askThread" to settings.askThread.toLong(),
        "askSearch" to settings.askSearch.toLong(),
        "askFallback" to settings.askFallback.toLong(),
        "folderCountFreshSeconds" to settings.folderCountFreshSeconds.toLong(),
        "androidContactsCacheSeconds" to settings.androidContactsCacheSeconds.toLong(),
    )
    for ((key, value) in values) {
        val leaf = leaves[key] ?: continue
        val reason = numberRejection(key, leaf, value) ?: continue
        return reason
    }
    return null
}

fun globalRecordError(text: String): String? {
    if (documentError(text) == null) return null
    return "global_settings undecodable record"
}

internal fun globalValueOk(spec: SettingSpec, value: String): Boolean {
    val leaf = spec.leaf
    return when (spec.type) {
        SettingType.Bool -> value == "true" || value == "false"
        SettingType.Int, SettingType.Long -> value.toLongOrNull()?.let { numberRejection(spec.key, leaf, it) == null } == true
        SettingType.String, SettingType.Encoded -> true
        SettingType.Enum -> spec.enumValues.isEmpty() || value in spec.enumValues
        SettingType.IntList -> intListOk(leaf, value)
        SettingType.StringList -> true
    }
}

private fun intListOk(leaf: TreeLeaf, value: String): Boolean {
    if (value.isEmpty()) return leaf.minItems == null || leaf.minItems <= 0
    val nums = value.split(",").map { it.toLongOrNull() ?: return false }
    if (leaf.minItems != null && nums.size < leaf.minItems) return false
    if (leaf.maxItems != null && nums.size > leaf.maxItems) return false
    return nums.all { (leaf.itemMin == null || it >= leaf.itemMin) && (leaf.itemMax == null || it <= leaf.itemMax) }
}

fun uiKeysInstalled(): List<String> = uiInstalled

internal fun leafByKey(key: String): TreeLeaf? {
    if (InstalledTree.leaves.isEmpty()) installGeneratedSettings()
    return InstalledTree.leaves[key]
}
