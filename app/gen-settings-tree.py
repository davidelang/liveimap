#!/usr/bin/env python3
"""Emit SettingsTreeGenerated and SettingsAccessGenerated from settings-tree.json.

Gradle calls: python3 app/gen-settings-tree.py <tree.json> <ktOutDir>
"""
import copy
import hashlib
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

def loads_settings_tree(data):
    # A comma after the last value of a list or object is not part of the tree.
    if isinstance(data, (bytes, bytearray)):
        text = data.decode("utf-8")
    else:
        text = data
    return json.loads(_without_trailing_commas(text))


def _without_trailing_commas(text):
    out = []
    i = 0
    n = len(text)
    in_str = False
    esc = False
    last = ""
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
                last = '"'
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == ",":
            j = i + 1
            while j < n and text[j] in " \t\r\n":
                j += 1
            if j < n and text[j] in "]}" and last in '0123456789"el]}':
                i += 1
                continue
            out.append(c)
            last = ","
            i += 1
            continue
        out.append(c)
        if c not in " \t\r\n":
            last = c
        i += 1
    return "".join(out)


def walk_nodes(nodes, visit):
    for node in nodes:
        visit(node)
        for key in ("children", "settings"):
            if isinstance(node.get(key), list) and node[key] and isinstance(node[key][0], dict):
                walk_nodes(node[key], visit)


def all_leaves(doc):
    found = []
    def visit(node):
        if "setting" in node:
            found.append(node)
    for page in doc["pages"]:
        walk_nodes([page], visit)
    walk_nodes(doc["stored"], visit)
    return found


def groups_of(doc):
    found = []
    def visit(node):
        if "group" in node:
            found.append(node)
    for page in doc["pages"]:
        walk_nodes([page], visit)
    return found


def kt_string(value):
    text = str(value).replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
    return '"' + text + '"'


def _android_unescape(text):
    if text is None:
        return ""
    out = []
    i = 0
    simple = {"n": "\n", "t": "\t", "'": "'", '"': '"', "\\": "\\", "r": "\r"}
    while i < len(text):
        if text[i] == "\\" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt in simple:
                out.append(simple[nxt])
                i += 2
                continue
            if nxt == "u" and i + 5 < len(text):
                hexv = text[i + 2:i + 6]
                try:
                    out.append(chr(int(hexv, 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
            out.append(nxt)
            i += 2
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


class _Catalog:
    def __init__(self, strings, plurals):
        self.strings = strings
        self.plurals = plurals
        owners = {}
        for name, text in strings.items():
            owners.setdefault(text, []).append(name)
        self.owners = owners


def load_string_catalog(tree_path):
    path = Path(tree_path).resolve().parents[1] / "res" / "values" / "strings.xml"
    root = ET.parse(path).getroot()
    strings = {}
    plurals = {}
    for el in root:
        name = el.attrib.get("name")
        if el.tag == "string":
            strings[name] = _android_unescape(el.text)
        elif el.tag == "plurals":
            plurals[name] = {
                item.attrib.get("quantity"): _android_unescape(item.text) for item in el
            }
    return _Catalog(strings, plurals)


# Same English, different resources. The name is the one this tree already uses.
_CHOICE_NAME = {
    ("tlsMode", "None"): "settings_tls_none",
    ("smtpTlsMode", "None"): "settings_tls_none",
    ("sieveTlsMode", "None"): "settings_tls_none",
    ("previewSize", "None"): "settings_preview_none",
    ("previewSize", "Large"): "settings_preview_large",
    ("density", "Large"): "label_large",
    ("threadIndexStyle", "Expanded"): "settings_thread_expanded",
    ("threadIndexStyle", "Collapsed"): "settings_thread_collapsed",
    ("bodyView", "PlainOrHtml"): "label_html",
    ("clientSortCollation", "plain"): "setting_clientSortCollation_plain",
    ("dsnMode", "Off"): "settings_dsn_off",
    ("compression", "Off"): "settings_compression_off",
    ("compression", "On"): "settings_compression_on",
    ("multiPane", "Off"): "settings_multi_pane_off",
    ("multiPane", "Wide"): "settings_multi_pane_wide",
    ("folderPlace", "Off"): "settings_multi_pane_off",
    ("foldPosture", "Off"): "settings_multi_pane_off",
    ("foldPosture", "On"): "settings_multi_pane_wide",
}

_SUMMARY_NAME = {
    ("watched_folders", "None"): "settings_watched_none",
    ("folder_views", "%1$s, %2$s"): "settings_sort_line",
    ("folders", "%1$s, %2$s"): "settings_sort_line",
    ("display", "%1$s, %2$s"): "settings_display_line",
    ("slower", "On"): "settings_slower_on",
    ("slower", "Off"): "settings_slower_off",
    ("custom_panel_layout", "Custom panel layout"): "layout_custom",
    ("forwarding", "Forward as attachment"): "settings_forward_attachment",
}

# Leaf titles whose English is also some other resource. The name is the one the leaf uses now.
_LEAF_TITLE = {
    "signature": "settings_signature",
    "quotePrefix": "pinerc_quote_prefix",
    "dsnMode": "settings_dsn",
    "forwardAsAttachment": "settings_forward_attachment",
}


def _owners(catalog, sentence):
    return list(catalog.owners.get(sentence, []))


def _non_group(names):
    return [name for name in names if not name.startswith("settings_group_")]


def _one_name(sentence, names, where):
    if len(names) == 1:
        return names[0]
    raise SystemExit("settings-tree.json display %r at %s matches %s" % (sentence, where, ", ".join(names)))


def _resource_name(catalog, value):
    return value in catalog.strings or value in catalog.plurals


def _string_name(catalog, sentence, prefer, where):
    if sentence in catalog.strings or sentence in catalog.plurals:
        return sentence
    names = _owners(catalog, sentence)
    if not names:
        raise SystemExit("settings-tree.json display %r at %s" % (sentence, where))
    if prefer is not None and prefer in names:
        return prefer
    return _one_name(sentence, names, where)


def _leaf_title(catalog, setting, sentence):
    if _resource_name(catalog, sentence):
        return sentence
    fixed = _LEAF_TITLE.get(setting)
    if fixed is not None and catalog.strings.get(fixed) == sentence:
        return fixed
    names = _owners(catalog, sentence)
    prefer = "setting_%s_title" % setting
    if prefer in names:
        return prefer
    return _one_name(sentence, _non_group(names) or names, "leaf " + setting)


def _page_or_editor_title(catalog, sentence, where):
    if _resource_name(catalog, sentence):
        return sentence
    names = _owners(catalog, sentence)
    return _one_name(sentence, _non_group(names) or names, where)


def _group_title(catalog, group_id, sentence):
    if _resource_name(catalog, sentence):
        return sentence
    name = "settings_group_%s_title" % group_id
    if catalog.strings.get(name) != sentence:
        raise SystemExit("settings-tree.json group %s title %r" % (group_id, sentence))
    return name


def _choice_name(catalog, setting, choice, sentence):
    if _resource_name(catalog, sentence):
        return sentence
    fixed = _CHOICE_NAME.get((setting, choice))
    return _string_name(catalog, sentence, fixed, "choice %s %s" % (setting, choice))


def _summary_name(catalog, owner, sentence):
    if _resource_name(catalog, sentence):
        return sentence
    fixed = _SUMMARY_NAME.get((owner, sentence))
    return _string_name(catalog, sentence, fixed, "summary " + owner)


def _plural_name(catalog, sentence, count):
    if sentence in catalog.plurals or sentence in catalog.strings:
        return sentence
    names = [name for name, quant in catalog.plurals.items() if quant.get("other") == sentence]
    if count == "$accounts" and "settings_account_count" in names:
        return "settings_account_count"
    return _one_name(sentence, names, "plural")


def _titles_name(catalog, setting, key, sentence):
    if _resource_name(catalog, sentence):
        return sentence
    prefer = None
    if setting == "defaultView" and key == "true":
        prefer = "reader_newest"
    return _string_name(catalog, sentence, prefer, "titles %s %s" % (setting, key))


def _is_leaf(node):
    return isinstance(node, dict) and "type" in node and isinstance(node.get("setting"), str)


def _is_group(node):
    return isinstance(node, dict) and isinstance(node.get("group"), str) and "children" in node


def _is_page(node):
    return (
        isinstance(node, dict)
        and isinstance(node.get("page"), str)
        and "children" in node
        and "setting" not in node
    )


def _is_editor(node):
    return (
        isinstance(node, dict)
        and isinstance(node.get("editor"), str)
        and "type" not in node
        and "setting" not in node
    )


def _resolve_node(node, owner, catalog):
    if isinstance(node, list):
        for item in node:
            _resolve_node(item, owner, catalog)
        return
    if not isinstance(node, dict):
        return
    if _is_leaf(node):
        owner = node["setting"]
        if isinstance(node.get("title"), str):
            node["title"] = _leaf_title(catalog, node["setting"], node["title"])
        choices = node.get("choiceTitles")
        if isinstance(choices, dict):
            for key, value in list(choices.items()):
                if isinstance(value, str):
                    choices[key] = _choice_name(catalog, node["setting"], key, value)
    elif _is_group(node):
        owner = node["group"]
        if isinstance(node.get("title"), str):
            node["title"] = _group_title(catalog, node["group"], node["title"])
    elif _is_page(node):
        owner = node["page"]
        if isinstance(node.get("title"), str):
            node["title"] = _page_or_editor_title(catalog, node["title"], "page " + node["page"])
    elif _is_editor(node):
        owner = node["editor"]
        if isinstance(node.get("title"), str) and node["title"]:
            node["title"] = _page_or_editor_title(catalog, node["title"], "editor " + node["editor"])
    else:
        if isinstance(node.get("title"), str):
            node["title"] = _string_name(catalog, node["title"], None, "title")
        if isinstance(node.get("text"), str):
            node["text"] = _summary_name(catalog, owner, node["text"])
        if isinstance(node.get("plural"), str):
            count = node.get("count") if isinstance(node.get("count"), str) else ""
            node["plural"] = _plural_name(catalog, node["plural"], count)
        titles = node.get("titles")
        if isinstance(titles, dict):
            setting = node.get("setting") if isinstance(node.get("setting"), str) else owner
            for key, value in list(titles.items()):
                if isinstance(value, str):
                    titles[key] = _titles_name(catalog, setting, key, value)
    if isinstance(node.get("description"), str):
        node["description"] = _string_name(catalog, node["description"], None, "description")
    for value in node.values():
        if isinstance(value, (dict, list)):
            _resolve_node(value, owner, catalog)


def resolve_display_names(doc, catalog):
    # The file stores en-US sentences. Emitted Kotlin still uses the resource name.
    copy_doc = copy.deepcopy(doc)
    _resolve_node(copy_doc, "", catalog)
    return copy_doc


def _remember_sentence(found, catalog, value):
    if not isinstance(value, str) or not value:
        return
    if value in catalog.strings:
        found.add(catalog.strings[value])
    elif value in catalog.plurals:
        other = catalog.plurals[value].get("other")
        if other:
            found.add(other)
    else:
        found.add(value)


def display_sentences(doc, catalog):
    found = set()

    def walk(node):
        if isinstance(node, list):
            for item in node:
                walk(item)
            return
        if not isinstance(node, dict):
            return
        _remember_sentence(found, catalog, node.get("title"))
        _remember_sentence(found, catalog, node.get("description"))
        _remember_sentence(found, catalog, node.get("text"))
        _remember_sentence(found, catalog, node.get("plural"))
        for key in ("choiceTitles", "titles"):
            mapped = node.get(key)
            if isinstance(mapped, dict):
                for value in mapped.values():
                    _remember_sentence(found, catalog, value)
        for value in node.values():
            if isinstance(value, (dict, list)):
                walk(value)

    walk(doc)
    return sorted(found)


def emit(doc, kt_root, tree_bytes, tree_path):
    catalog = load_string_catalog(tree_path)
    sentences = display_sentences(doc, catalog)
    doc = resolve_display_names(doc, catalog)
    leaves = all_leaves(doc)
    groups = groups_of(doc)
    by_key = {}
    for leaf in leaves:
        if leaf["setting"] in by_key:
            raise SystemExit("duplicate " + leaf["setting"])
        by_key[leaf["setting"]] = leaf
    fingerprint = hashlib.sha256(tree_bytes).hexdigest()
    kt_dir = Path(kt_root) / "org" / "dlang" / "liveimap" / "settings"
    kt_dir.mkdir(parents=True, exist_ok=True)
    resource_names = []
    plural_names = set()
    def add_res(name):
        if isinstance(name, str) and name and name not in resource_names:
            resource_names.append(name)
    def collect_strings(value):
        if isinstance(value, dict):
            if isinstance(value.get("text"), str):
                add_res(value["text"])
            if isinstance(value.get("plural"), str):
                plural_names.add(value["plural"])
                add_res(value["plural"])
            if isinstance(value.get("note"), str):
                add_res(value["note"])
            if isinstance(value.get("title"), str):
                add_res(value["title"])
            titles = value.get("titles")
            if isinstance(titles, dict):
                for item in titles.values():
                    add_res(item)
            choices = value.get("choiceTitles")
            if isinstance(choices, dict):
                for item in choices.values():
                    add_res(item)
            for child in value.values():
                collect_strings(child)
        elif isinstance(value, list):
            for item in value:
                collect_strings(item)
    collect_strings(doc)
    add_res("settings_all_accounts_summary")
    add_res("settings_logging_line")
    add_res("settings_account_count")
    plural_names.add("settings_account_count")
    add_res("sending_rule_numbered")
    add_res("address_book_row")
    for name in (
        "label_arrival",
        "label_date",
        "label_from",
        "compose_subject",
        "compose_to",
        "compose_cc",
        "label_size",
        "label_thread",
        "label_ordered_subject",
        "drawer_delete",
        "label_move",
        "compose_reply",
        "compose_reply_all",
        "label_set_flag",
        "label_clear_flag",
        "label_flag_screen",
        "reader_next",
        "reader_previous",
    ):
        add_res(name)
    resource_names = sorted(set(resource_names))
    def res_expr(name):
        kind = "plurals" if name in plural_names else "string"
        return "R.%s.%s" % (kind, name)
    group_ids = [group["group"] for group in groups]
    lines = [
        "package org.dlang.liveimap.settings",
        "",
        "import com.davidlang.liveimap.R",
        "",
        "object SettingsTreeGenerated {",
        "    const val fingerprint: String = %s" % kt_string(fingerprint),
        "    val groupIds: List<String> = listOf(%s)" % ", ".join(kt_string(i) for i in group_ids),
        "    val resourceNames: List<String> = listOf(%s)" % ", ".join(kt_string(n) for n in resource_names),
        "    private val resourceKeys: Array<String> = arrayOf(%s)" % ", ".join(kt_string(n) for n in resource_names),
        "    private val resourceIds: IntArray = intArrayOf(%s)" % ", ".join(res_expr(n) for n in resource_names),
        "    val pluralNames: List<String> = listOf(%s)" % ", ".join(kt_string(n) for n in sorted(plural_names)),
        "    private val sentenceKeys: Array<String> = arrayOf(%s)" % ", ".join(kt_string(n) for n in sentences),
        "",
        "    fun sentence(name: String): Boolean = sentenceKeys.binarySearch(name) >= 0",
        "",
        "    fun resource(name: String): Int {",
        "        val index = resourceKeys.binarySearch(name)",
        "        return if (index >= 0) resourceIds[index] else 0",
        "    }",
        "",
        "    fun titleOf(id: String): Int = resource(\"settings_group_${id}_title\")",
        "",
        "    private val titleKeys: Array<String> = arrayOf(%s)" % ", ".join(kt_string(leaf["setting"]) for leaf in sorted(leaves, key=lambda item: item["setting"])),
        "    private val titleIds: IntArray = intArrayOf(%s)" % ", ".join("R.string.%s" % leaf.get("title", "drawer_settings") for leaf in sorted(leaves, key=lambda item: item["setting"])),
        "",
        "    fun regTitle(key: String): Int {",
        "        val index = titleKeys.binarySearch(key)",
        "        return if (index >= 0) titleIds[index] else 0",
        "    }",
        "",
        "    fun warm(): Int {",
        "        var n = 0",
    ]
    for name in resource_names:
        lines.append("        n += resource(%s)" % kt_string(name))
    lines += [
        "        return n",
        "    }",
        "}",
        "",
        "object SettingsDefaults {",
    ]
    for leaf in leaves:
        key = leaf["setting"]
        typ = leaf["type"]
        default = leaf["default"]
        if typ == "Int":
            lines.append("    const val %s: Int = %s" % (key, int(default)))
        elif typ == "Long":
            lines.append("    const val %s: Long = %sL" % (key, int(default)))
        elif typ == "Bool":
            lines.append("    const val %s: Boolean = %s" % (key, "true" if default else "false"))
        elif key in ("tlsMode", "multiPane", "folderPane", "paneArrangement", "previewSize", "folderPosition"):
            lines.append("    const val %s: String = %s" % (key, kt_string(default)))
    extra_max = by_key["extraIdleBudget"].get("max", 8)
    lines.append("    const val extraIdleBudgetMax: Int = %s" % int(extra_max))
    lines.append("}")
    lines.append("")
    lines.append(install_kotlin(doc))
    (kt_dir / "SettingsTreeGenerated.kt").write_text("\n".join(lines) + "\n")
    emit_access(doc, leaves, groups, kt_dir)
    print("groups", len(groups), "leaves", len(leaves), "fingerprint", fingerprint)


ENUM_CLASS = {
    "tlsMode": "TlsMode",
    "smtpTlsMode": "TlsMode",
    "smtpNoAuth": "SmtpNoAuth",
    "sieveTlsMode": "TlsMode",
    "passwordStorage": "PasswordStorageChoice",
    "threadIndexStyle": "ThreadIndexStyle",
    "inboxStart": "StartRule",
    "folderStart": "StartRule",
    "startAfterChange": "StartAfterChange",
    "pinercStartDefault": "PinercStartDefault",
    "bodyView": "BodyView",
    "readerTextLayout": "TextLayout",
    "nonAsciiDisplay": "NonAsciiDisplay",
    "deletePolicy": "DeletePolicy",
    "moveMethod": "MoveMethod",
    "compression": "Compression",
    "saveNameRule": "SaveNameRule",
    "dsnMode": "org.dlang.liveimap.ui.compose.DsnMode",
    "theme": "ThemeMode",
    "density": "Density",
    "dateFormat": "DateFormat",
    "multiPane": "MultiPane",
    "foldPosture": "LayoutChoice",
    "paneArrangement": "ArrangementChoice",
    "previewSize": "PreviewChoice",
    "folderPlace": "FolderPlace",
    "clientSortCollation": "ClientSortCollation",
}

SET_FIELDS = {
    "expandedFolders",
    "hiddenCapabilities",
    "sortAskSkip",
    "threadAskSkip",
    "searchAskSkip",
    "fallbackAskSkip",
}

TYPE_OVERRIDE = {
    "defaultView": "FolderView",
    "folderViews": "Map<String, FolderView>",
    "folderStarts": "Map<String, StartRule>",
    "favorites": "List<FolderFavorite>",
    "inboundRules": "List<InboundRule>",
    "savedSearches": "List<SavedSearch>",
    "savedAdvanced": "List<SavedAdvanced>",
    "swipeTrailing": "SwipeBinding",
    "swipeLeading": "SwipeBinding",
    "readerSwipeTrailing": "SwipeBinding",
    "readerSwipeLeading": "SwipeBinding",
    "altAddresses": "List<String>",
    "watchedFolders": "List<String>",
    "completionSources": "List<String>",
    "extraAddressBooks": "List<String>",
    "globalAddressBooks": "List<String>",
}

# Readers that are not the type's ordinary decode. The id still comes from the JSON leaf.
DECODE_READER = {
    "authMechanism": 'percentDecode(values.getValue("authMechanism")).trim().uppercase()',
    "completionSources": 'decodeCompletionSources(values["completionSources"])',
    "customLayout": 'values["customLayout"]?.let { percentDecode(it) }?.takeIf { decodePaneLayout(it) != null }.orEmpty()',
    "bodyFetchChunk": 'parseIntField(values.getValue("bodyFetchChunk")).coerceIn(1024, 1048576)',
    "composerWrapColumn": 'parseIntField(values.getValue("composerWrapColumn")).coerceIn(0, 998)',
    "sievePort": 'parseSievePort(values.getValue("sievePort"))',
    "maxConnections": 'org.dlang.liveimap.session.clampMaxConnections(parseIntField(values.getValue("maxConnections")))',
    "askSort": 'parseAskLimit(values.getValue("askSort"))',
    "askThread": 'parseAskLimit(values.getValue("askThread"))',
    "askSearch": 'parseAskLimit(values.getValue("askSearch"))',
    "askFallback": 'parseAskLimit(values.getValue("askFallback"))',
    "bodyView": "decodeBodyView(values)",
    "sigDashes": 'values.getValue("sigDashes") == "true"',
    "sigAtBottom": 'values.getValue("sigAtBottom") == "true"',
    "smtpSameAsImap": 'values["smtpSameAsImap"] == "true"',
}

SETTINGS_DEFAULT_ENUM = {
    "tlsMode",
    "multiPane",
    "paneArrangement",
    "previewSize",
}


def account_leaves(leaves):
    found = [leaf for leaf in leaves if leaf.get("scope") == "account"]
    for leaf in found:
        if leaf.get("derived") and leaf["setting"] != "preferHtml":
            raise SystemExit("derived account leaf " + leaf["setting"])
    return found


def kt_type(leaf):
    key = leaf["setting"]
    if key in ENUM_CLASS:
        return ENUM_CLASS[key]
    if key in SET_FIELDS:
        return "Set<String>"
    if key in TYPE_OVERRIDE:
        return TYPE_OVERRIDE[key]
    kind = leaf["type"]
    if kind in ("String", "Encoded"):
        return "String"
    if kind == "Int":
        return "Int"
    if kind == "Bool":
        return "Boolean"
    if kind == "StringList":
        return "List<String>"
    if kind == "Long":
        return "Long"
    if kind == "IntList":
        return "List<Int>"
    if kind == "Enum":
        return "String"
    raise SystemExit("no Kotlin type for " + key)


def kt_default(leaf):
    key = leaf["setting"]
    typ = kt_type(leaf)
    default = leaf.get("default")
    if key == "smtpTlsMode":
        return "enumValueOf(SettingsDefaults.tlsMode)"
    if key in SETTINGS_DEFAULT_ENUM:
        return "enumValueOf(SettingsDefaults.%s)" % key
    if key == "clientSortCollation":
        if default == "locale":
            return "ClientSortCollation.Locale"
        if default == "plain":
            return "ClientSortCollation.Plain"
        raise SystemExit("clientSortCollation default")
    if key in ENUM_CLASS:
        return "%s.%s" % (ENUM_CLASS[key], default)
    if typ == "Int":
        return "SettingsDefaults.%s" % key
    if typ == "Long":
        return "SettingsDefaults.%s" % key
    if typ == "Boolean":
        return "true" if default else "false"
    if typ == "String":
        return kt_string("" if default is None else default)
    if typ == "List<Int>":
        if not default:
            return "emptyList()"
        return "listOf(%s)" % ", ".join(str(int(item)) for item in default)
    if typ == "FolderView":
        return "parseView(%s)" % kt_string(default)
    if typ == "SwipeBinding":
        return "parseSwipe(%s)" % kt_string(default)
    if typ.startswith("Map"):
        if default not in (None, ""):
            raise SystemExit("map default " + key)
        return "emptyMap()"
    if typ.startswith("Set"):
        if default not in (None, "", []):
            raise SystemExit("set default " + key)
        return "emptySet()"
    if typ.startswith("List"):
        if key == "completionSources":
            if default != ["pine"]:
                raise SystemExit("completionSources default")
            return "listOf(pineSourceId)"
        if default not in (None, "", []):
            raise SystemExit("list default " + key)
        return "emptyList()"
    raise SystemExit("default " + key)


def encode_put(leaf):
    key = leaf["setting"]
    if key in COMPLEX:
        value = COMPLEX[key][0]
    else:
        typ = kt_type(leaf)
        if leaf.get("type") == "Encoded":
            value = key
        elif typ == "String":
            value = "percentEncode(%s)" % key
        elif typ in ("Int", "Boolean", "Long"):
            value = key
        elif typ == "List<Int>":
            value = '%s.joinToString(",")' % key
        elif key in ENUM_CLASS:
            value = "%s.name" % key
        else:
            raise SystemExit("encode " + key)
    return 'put("%s=${%s}")' % (key, value)


def decode_reader(leaf):
    key = leaf["setting"]
    if key in DECODE_READER:
        return DECODE_READER[key]
    if key in COMPLEX and key != "clientSortCollation":
        return COMPLEX[key][1].replace("value.text", 'values.getValue("%s")' % key)
    typ = kt_type(leaf)
    if leaf.get("type") == "Encoded":
        return 'values.getValue("%s")' % key
    if typ == "String":
        return 'percentDecode(values.getValue("%s"))' % key
    if typ == "Boolean":
        return 'parseBoolean(values.getValue("%s"))' % key
    if typ == "Int":
        return 'parseIntField(values.getValue("%s"))' % key
    if typ == "Long":
        return 'values.getValue("%s").toLong()' % key
    if typ == "List<Int>":
        return 'decodeIntList(values.getValue("%s"))' % key
    if key in ENUM_CLASS:
        return "enumValueOf<%s>(values.getValue(%s))" % (ENUM_CLASS[key], json.dumps(key))
    raise SystemExit("decode " + key)


def special_decode(key):
    if key == "tlsMode":
        return "tlsMode = decodedTlsMode,"
    if key == "smtpTlsMode":
        return "smtpTlsMode = decodedSmtpTls,"
    if key == "readerTextLayout":
        return 'readerTextLayout = if ("readerTextLayout" !in values) d.readerTextLayout else enumValueOf<TextLayout>(values.getValue("readerTextLayout")),'
    if key == "compression":
        return "\n".join([
            'compression = if ("compression" !in values) d.compression else when (values.getValue("compression")) {',
            '    "Auto" -> Compression.On',
            '    else -> enumValueOf<Compression>(values.getValue("compression"))',
            "},",
        ])
    if key == "smtpNoAuth":
        return "\n".join([
            'smtpNoAuth = if ("smtpNoAuth" !in values) d.smtpNoAuth else when (values.getValue("smtpNoAuth")) {',
            '    "Auto" -> SmtpNoAuth.Off',
            '    else -> enumValueOf<SmtpNoAuth>(values.getValue("smtpNoAuth"))',
            "},",
        ])
    if key == "paneArrangement":
        return 'paneArrangement = if ("paneArrangement" !in values) d.paneArrangement else enumValueOf<ArrangementChoice>(values.getValue("paneArrangement")),'
    if key == "folderPosition":
        return 'folderPosition = if ("folderPosition" !in values) d.folderPosition else enumValueOf<FolderPositionChoice>(values.getValue("folderPosition")),'
    if key == "dsnMode":
        return 'dsnMode = if ("dsnMode" !in values) d.dsnMode else enumValueOf<org.dlang.liveimap.ui.compose.DsnMode>(values.getValue("dsnMode")),'
    if key == "nonAsciiDisplay":
        return 'nonAsciiDisplay = if ("nonAsciiDisplay" !in values) d.nonAsciiDisplay else enumValueOf<NonAsciiDisplay>(values.getValue("nonAsciiDisplay")),'
    if key == "previewSize":
        return 'previewSize = if ("previewSize" !in values) d.previewSize else enumValueOf<PreviewChoice>(values.getValue("previewSize")),'
    if key == "idleWakeSeconds":
        return "\n".join([
            'idleWakeSeconds = field("idleWakeSeconds", d.idleWakeSeconds) {',
            '    val seconds = parseIntField(values.getValue("idleWakeSeconds"))',
            '    if (seconds !in IdleWakeSeconds) throw IllegalArgumentException("bad idleWakeSeconds")',
            "    seconds",
            "},",
        ])
    if key == "clientSortCollation":
        return "\n".join([
            'clientSortCollation = if ("clientSortCollation" !in values) {',
            "    d.clientSortCollation",
            "} else {",
            '    val word = values.getValue("clientSortCollation")',
            '    if (word != "locale" && word != "plain") throw IllegalArgumentException("clientSortCollation")',
            '    field("clientSortCollation", d.clientSortCollation) { clientSortCollationOf(word) }',
            "},",
        ])
    if key == "unlabeledCharset":
        return "\n".join([
            'unlabeledCharset = if ("unlabeledCharset" !in values) {',
            "    d.unlabeledCharset",
            "} else {",
            "    val decoded = try {",
            '        percentDecode(values.getValue("unlabeledCharset"))',
            "    } catch (_: IllegalArgumentException) {",
            '        throw IllegalArgumentException("unlabeledCharset")',
            "    }",
            '    if (!knownUnlabeledCharset(decoded)) throw IllegalArgumentException("unlabeledCharset")',
            '    field("unlabeledCharset", d.unlabeledCharset) { decoded }',
            "},",
        ])
    return None


def decode_line(leaf):
    key = leaf["setting"]
    special = special_decode(key)
    if special is not None:
        return special
    return '%s = field("%s", d.%s) { %s },' % (key, key, key, decode_reader(leaf))


def indent_block(text, spaces):
    pad = " " * spaces
    return "\n".join(pad + line if line else line for line in text.split("\n"))


def generated_account_record(leaves):
    fields = []
    names = []
    puts = []
    ui_puts = []
    ui_copy = []
    decoded = []
    for leaf in leaves:
        names.append('    "%s",' % leaf["setting"])
        if leaf.get("scope") == "ui":
            ui_puts.append("    " + encode_put(leaf))
            if not leaf.get("derived"):
                ui_copy.append("    %s = ui.%s," % (leaf["setting"], leaf["setting"]))
        else:
            puts.append("    " + encode_put(leaf))
        if leaf.get("derived"):
            continue
        fields.append("    val %s: %s = %s," % (leaf["setting"], kt_type(leaf), kt_default(leaf)))
        decoded.append(indent_block(decode_line(leaf), 8))
    return {
        "gen-fields": "\n".join(fields),
        "gen-names": "\n".join(names),
        "gen-encode": "\n".join(puts),
        "gen-ui-encode": "\n".join(ui_puts),
        "gen-ui-copy": "\n".join(ui_copy),
        "gen-decode": "\n".join(decoded),
    }


def emit_access(doc, leaves, groups, kt_dir):
    # Constructor fields, fieldNames, encode() lines and the decode table come from the JSON ids.
    template_path = Path(__file__).with_name("gen-account-template.kt")
    if not template_path.is_file():
        (kt_dir / "SettingsAccessGenerated.kt").write_text(access_stub())
        return
    body = template_path.read_text()
    for marker, text in generated_account_record(leaves).items():
        token = "/* @%s */" % marker
        if token not in body:
            raise SystemExit("missing " + token)
        body = body.replace(token, text, 1)
    global _FIELD_TYPES
    _FIELD_TYPES = {}
    for leaf in leaves:
        if not leaf.get("derived"):
            _FIELD_TYPES[leaf["setting"]] = kt_type(leaf)
    group_of = {}
    def visit(node, group):
        if "group" in node:
            group = node["group"]
        if "setting" in node and not node.get("derived"):
            group_of[node["setting"]] = group or ("ui" if node.get("scope") == "ui" else "account")
        for key in ("children", "settings"):
            kids = node.get(key)
            if isinstance(kids, list):
                for child in kids:
                    if isinstance(child, dict):
                        visit(child, group)
    for page in doc["pages"]:
        visit(page, None)
    for leaf in doc.get("stored", []):
        if isinstance(leaf, dict) and "setting" in leaf and not leaf.get("derived"):
            group_of.setdefault(leaf["setting"], "stored")
    buckets = {}
    for key, group in group_of.items():
        buckets.setdefault(group, []).append(key)
    imports = account_imports()
    parts = [
        "@file:JvmName(\"SettingsAccessGeneratedKt\")",
        "package org.dlang.liveimap.settings",
        "",
        "import android.content.Context",
        imports,
        "",
        body.rstrip(),
        "",
        generated_with(buckets),
        "",
        generated_ui(leaves),
        "",
    ]
    (kt_dir / "SettingsAccessGenerated.kt").write_text("\n".join(parts) + "\n")


def access_stub():
    return "\n".join([
        "@file:JvmName(\"SettingsAccessGeneratedKt\")",
        "package org.dlang.liveimap.settings",
        "",
        "// Template missing: app/gen-account-template.kt",
        "",
    ])


def generated_with(buckets):
    pairs = []
    for group, keys in buckets.items():
        for key in keys:
            pairs.append((key, group))
    pairs.sort()
    lines = [
        "class SettingValue(val text: String)",
        "",
        "private val accountKeys: Array<String> = arrayOf(%s)" % ", ".join(json.dumps(key) for key, _group in pairs),
        "private val accountGroups: Array<String> = arrayOf(%s)" % ", ".join(json.dumps(group) for _key, group in pairs),
        "",
        "fun Settings.withSetting(key: String, value: SettingValue): Settings {",
        "    if (key == \"preferHtml\") {",
        "        val html = value.text == \"true\"",
        "        val view = if (html) BodyView.PlainOrHtml else if (bodyView == BodyView.PlainOrHtml) BodyView.PlainOrText else bodyView",
        "        return copy(bodyView = view)",
        "    }",
        "    val index = accountKeys.binarySearch(key)",
        "    if (index < 0) return this",
        "    return when (accountGroups[index]) {",
    ]
    for group in buckets:
        lines.append('        "%s" -> with_%s(key, value)' % (group, group))
    lines += [
        "        else -> this",
        "    }",
        "}",
        "",
        "fun Settings.getSetting(key: String): String {",
        "    if (key == \"preferHtml\") return preferHtml.toString()",
        "    val index = accountKeys.binarySearch(key)",
        "    if (index < 0) return \"\"",
        "    return when (accountGroups[index]) {",
    ]
    for group in buckets:
        lines.append('        "%s" -> get_%s(key)' % (group, group))
    lines += [
        '        else -> ""',
        "    }",
        "}",
        "",
    ]
    for group, keys in buckets.items():
        lines.append("fun Settings.with_%s(key: String, value: SettingValue): Settings = when (key) {" % group)
        for key in keys:
            lines.append('    "%s" -> %s' % (key, with_expr(key)))
        lines += ["    else -> this", "}", ""]
        lines.append("fun Settings.get_%s(key: String): String = when (key) {" % group)
        for key in keys:
            lines.append('    "%s" -> %s' % (key, get_expr(key)))
        lines += ['    else -> ""', "}", ""]
    lines += [
        "fun Settings.changedKeysAgainst(other: Settings): Set<String> {",
        "    val out = HashSet<String>()",
        "    for (key in accountKeys) {",
        "        if (getSetting(key) != other.getSetting(key)) out.add(key)",
        "    }",
        "    return out",
        "}",
        "",
    ]
    return "\n".join(lines)


COMPLEX = {
    "defaultView": ("encodeView(defaultView)", "parseView(value.text)"),
    "folderViews": ("encodeFolderViews(folderViews)", "parseFolderViews(value.text)"),
    "expandedFolders": (
        'expandedFolders.joinToString(";") { percentEncode(it) }',
        "parseExpanded(value.text)",
    ),
    "swipeTrailing": ("encodeSwipe(swipeTrailing)", "parseSwipe(value.text)"),
    "swipeLeading": ("encodeSwipe(swipeLeading)", "parseSwipe(value.text)"),
    "readerSwipeTrailing": ("encodeSwipe(readerSwipeTrailing)", "parseSwipe(value.text)"),
    "readerSwipeLeading": ("encodeSwipe(readerSwipeLeading)", "parseSwipe(value.text)"),
    "favorites": ("encodeFavorites(favorites)", "parseFavorites(value.text)"),
    "folderStarts": ("encodeFolderStarts(folderStarts)", "parseFolderStarts(value.text)"),
    "altAddresses": ("encodeAltAddresses(altAddresses)", "decodeAltAddresses(value.text)"),
    "completionSources": ("encodeCompletionSources(completionSources)", "decodeCompletionSources(value.text)"),
    "extraAddressBooks": ("encodeCompletionSources(extraAddressBooks)", "decodeCompletionSources(value.text)"),
    "globalAddressBooks": ("encodeCompletionSources(globalAddressBooks)", "decodeCompletionSources(value.text)"),
    "clientSortCollation": ("clientSortCollationWord(clientSortCollation)", "clientSortCollationOf(value.text)"),
    "hiddenCapabilities": ("encodeHiddenCapabilities(hiddenCapabilities)", "decodeHiddenCapabilities(value.text)"),
    "inboundRules": ("encodeInboundRules(inboundRules)", "decodeInboundRules(value.text)"),
    "watchedFolders": ("encodeWatchedFolders(watchedFolders)", "decodeWatchedFolders(value.text)"),
    "savedSearches": ("encodeSavedSearches(savedSearches)", "decodeSavedSearches(value.text)"),
    "savedAdvanced": ("encodeSavedAdvanced(savedAdvanced)", "decodeSavedAdvanced(value.text)"),
    "sortAskSkip": ("encodeWatchedFolders(sortAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
    "threadAskSkip": ("encodeWatchedFolders(threadAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
    "searchAskSkip": ("encodeWatchedFolders(searchAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
    "fallbackAskSkip": ("encodeWatchedFolders(fallbackAskSkip.sorted())", "decodeWatchedFolders(value.text).toSet()"),
}


_FIELD_TYPES = {}


def field_types():
    if not _FIELD_TYPES:
        raise SystemExit("account field types are not loaded")
    return _FIELD_TYPES


def with_expr(key):
    special = {
        "jmapFolders": 'withMailAccess(this, value.text == "true")',
        "username": "copy(username = value.text, email = emailDefaultedFromUsername(value.text, email))",
        "tlsMode": 'withTlsMode(TlsMode.valueOf(value.text)).withoutPreserved("tlsMode")',
        "sieveTlsMode": 'copy(sieveTlsMode = TlsMode.valueOf(value.text)).withoutPreserved("sieveTlsMode")',
    }
    if key in special:
        return special[key]
    if key in COMPLEX:
        return "copy(%s = %s)" % (key, COMPLEX[key][1])
    typ = field_types().get(key, "String")
    if typ == "String":
        return "copy(%s = value.text)" % key
    if typ == "Int":
        return "copy(%s = value.text.toInt())" % key
    if typ == "Long":
        return "copy(%s = value.text.toLong())" % key
    if typ == "List<Int>":
        return "copy(%s = decodeIntList(value.text))" % key
    if typ == "Boolean":
        return "copy(%s = value.text == \"true\")" % key
    return "copy(%s = %s.valueOf(value.text))" % (key, typ)


def account_imports():
    source = Path(__file__).resolve().parent / "src/main/kotlin/org/dlang/liveimap/settings/AccountSettings.kt"
    lines = []
    for line in source.read_text().splitlines():
        if line.startswith("import "):
            lines.append(line)
        elif line.startswith("enum class ") or line.startswith("data class ") or line.startswith("class "):
            break
    return "\n".join(lines)


def get_expr(key):
    if key in COMPLEX:
        return COMPLEX[key][0]
    typ = field_types().get(key, "String")
    if typ == "List<Int>":
        return '%s.joinToString(",")' % key
    if typ in ("String", "Int", "Boolean", "Long"):
        return "%s.toString()" % key if typ != "String" else key
    return "%s.name" % key


def generated_ui(leaves):
    return ""


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--upgrade":
        raise SystemExit("gen-settings-tree.py --upgrade is removed")
    if len(sys.argv) != 3:
        raise SystemExit("usage: gen-settings-tree.py <tree.json> <ktOut>")
    self_check()
    tree_path, kt_root = sys.argv[1:]
    raw = Path(tree_path).read_bytes()
    doc = loads_settings_tree(raw)
    validate_doc(doc)
    emit(doc, kt_root, raw, tree_path)


ROOT_KEYS = {"pages", "stored"}
PAGE_KEYS = {"page", "title", "children", "summary"}
GROUP_KEYS = {
    "group", "title", "label", "header", "children", "summary", "visibleWhen", "enabledWhen",
    "description", "editor", "opens", "introWhen",
}
EDITOR_KEYS = {"editor", "opens", "title", "summary", "settings", "children", "label"}
LEAF_KEYS = {
    "setting", "type", "scope", "kind", "default", "title", "summary", "choiceTitles", "choices",
    "min", "max", "minItems", "maxItems", "itemMin", "itemMax", "order", "server", "derived",
    "pinerc", "pinercFormat", "visibleWhen", "enabledWhen", "titleWhen", "noteWhen", "editor",
    "opens", "format", "label", "nullable", "titleArgs",
}


def self_check():
    samples = [
        ("[1,2,]", [1, 2]),
        ("[ 1 , 2 , ]", [1, 2]),
        ('{"bar":1,"baz":2,}', {"bar": 1, "baz": 2}),
        ('{ "bar" : 1 , "baz" : 2 , }', {"bar": 1, "baz": 2}),
        ("[]", []),
        ("{}", {}),
        ('{"a":[1,{"b":2,},],}', {"a": [1, {"b": 2}]}),
        ('{"note":"1,2,"}', {"note": "1,2,"}),
    ]
    for text, expected in samples:
        got = loads_settings_tree(text)
        if got != expected:
            raise SystemExit("trailing comma load failed: " + text)
    for bad in ("[,]", "[1,,2]", "{,}", '{"a":1,,}'):
        try:
            loads_settings_tree(bad)
        except json.JSONDecodeError:
            continue
        raise SystemExit("bad comma loaded: " + bad)
    try:
        loads_settings_tree("[1,2,] extra")
    except json.JSONDecodeError:
        return
    raise SystemExit("trailing text loaded")


def _extra(keys, allowed, where):
    extra = [key for key in keys if key not in allowed]
    if extra:
        raise SystemExit("settings-tree.json extra key %s at %s" % (", ".join(extra), where))


def validate_doc(doc):
    if not isinstance(doc, dict):
        raise SystemExit("settings-tree.json root")
    _extra(doc.keys(), ROOT_KEYS, "root")
    if "pages" not in doc or not isinstance(doc["pages"], list):
        raise SystemExit("settings-tree.json missing pages")
    seen = set()

    def leaf(node, where):
        _extra(node.keys(), LEAF_KEYS, where)
        for required in ("setting", "type", "scope", "kind", "default"):
            if required not in node:
                raise SystemExit("settings-tree.json missing %s at %s" % (required, where))
        key = node["setting"]
        if not isinstance(key, str) or not key:
            raise SystemExit("settings-tree.json misspelt setting at " + where)
        if key in seen:
            raise SystemExit("settings-tree.json duplicate " + key)
        seen.add(key)
        if node["type"] == "Int" and node.get("choiceTitles"):
            raise SystemExit("settings-tree.json %s choiceTitles" % key)

    def walk(node, where):
        if not isinstance(node, dict):
            raise SystemExit("settings-tree.json node " + where)
        if "setting" in node:
            leaf(node, where)
            return
        if "page" in node:
            _extra(node.keys(), PAGE_KEYS, where)
            if "title" not in node or "children" not in node:
                raise SystemExit("settings-tree.json missing page field at " + where)
            for index, child in enumerate(node["children"]):
                walk(child, "%s/%s" % (node["page"], index))
            return
        if "group" in node:
            _extra(node.keys(), GROUP_KEYS, where)
            if "children" not in node:
                raise SystemExit("settings-tree.json missing children at " + node["group"])
            for index, child in enumerate(node["children"]):
                walk(child, "%s/%s" % (node["group"], index))
            return
        if "editor" in node:
            _extra(node.keys(), EDITOR_KEYS, where)
            kids = node.get("settings", node.get("children", []))
            if not isinstance(kids, list):
                raise SystemExit("settings-tree.json editor " + node["editor"])
            for index, child in enumerate(kids):
                walk(child, "%s/%s" % (node["editor"], index))
            return
        raise SystemExit("settings-tree.json missing kind at " + where)

    for index, page in enumerate(doc["pages"]):
        walk(page, "pages/%s" % index)
    stored = doc.get("stored", [])
    if not isinstance(stored, list):
        raise SystemExit("settings-tree.json stored")
    for index, node in enumerate(stored):
        if not isinstance(node, dict) or "setting" not in node:
            raise SystemExit("settings-tree.json stored %s" % index)
        leaf(node, "stored/%s" % index)


def kt_json(value):
    if isinstance(value, bool):
        return "JsonValue.Bool(%s)" % ("true" if value else "false")
    if isinstance(value, int):
        return "JsonValue.Num(%sL)" % value
    if isinstance(value, str):
        return "JsonValue.Str(%s)" % kt_string(value)
    if value is None:
        return "JsonValue.Null"
    if isinstance(value, list):
        if not value:
            return "JsonValue.Arr(emptyList())"
        return "JsonValue.Arr(listOf(%s))" % ", ".join(kt_json(item) for item in value)
    if isinstance(value, dict):
        if not value:
            return "JsonValue.Obj(emptyMap())"
        parts = ", ".join("%s to %s" % (kt_string(key), kt_json(item)) for key, item in value.items())
        return "JsonValue.Obj(mapOf(%s))" % parts
    raise SystemExit("json value")


def kt_json_list(items):
    if not items:
        return "emptyList()"
    return "listOf(%s)" % ", ".join(kt_json(item) for item in items)


def kt_opt_str(node, name):
    value = node.get(name)
    if not isinstance(value, str) or value == "":
        return "null"
    return kt_string(value)


def kt_opt_long(node, name):
    if name not in node or node[name] is None:
        return "null"
    return "%sL" % int(node[name])


def kt_leaf(leaf, stored):
    choices = leaf.get("choices")
    if choices is None:
        choices_kt = "null"
    else:
        shown = []
        for item in choices:
            shown.append(kt_string(item if isinstance(item, str) else str(item)))
        choices_kt = "listOf(%s)" % ", ".join(shown)
    titles = leaf.get("choiceTitles") or {}
    if titles:
        titles_kt = "mapOf(%s)" % ", ".join(
            "%s to %s" % (kt_string(key), kt_string(value)) for key, value in titles.items()
        )
    else:
        titles_kt = "emptyMap()"
    visible = "null"
    if "visibleWhen" in leaf:
        visible = 'parseCondition(%s, "visibleWhen")' % kt_json(leaf["visibleWhen"])
    enabled = "null"
    if "enabledWhen" in leaf:
        enabled = 'parseCondition(%s, "enabledWhen")' % kt_json(leaf["enabledWhen"])
    summary = leaf.get("summary")
    summary_kt = kt_string(summary) if isinstance(summary, str) else "null"
    return "\n".join([
        "TreeLeaf(",
        "setting = %s," % kt_string(leaf["setting"]),
        "typeName = %s," % kt_string(leaf.get("type") or ""),
        "scopeName = %s," % kt_string(leaf.get("scope") or ""),
        "kind = %s," % kt_string(leaf.get("kind") or ""),
        "default = %s," % kt_json(leaf.get("default")),
        "title = %s," % kt_string(leaf.get("title") or ""),
        "summary = %s," % summary_kt,
        "choiceTitles = %s," % titles_kt,
        "choices = %s," % choices_kt,
        "min = %s," % kt_opt_long(leaf, "min"),
        "max = %s," % kt_opt_long(leaf, "max"),
        "minItems = %s," % kt_opt_long(leaf, "minItems"),
        "maxItems = %s," % kt_opt_long(leaf, "maxItems"),
        "itemMin = %s," % kt_opt_long(leaf, "itemMin"),
        "itemMax = %s," % kt_opt_long(leaf, "itemMax"),
        "order = %s," % kt_opt_str(leaf, "order"),
        "server = %s," % ("true" if leaf.get("server") else "false"),
        "derived = %s," % ("true" if leaf.get("derived") else "false"),
        "pinerc = %s," % kt_opt_str(leaf, "pinerc"),
        "pinercFormat = %s," % kt_opt_str(leaf, "pinercFormat"),
        "visibleWhen = %s," % visible,
        "enabledWhen = %s," % enabled,
        "editor = %s," % kt_opt_str(leaf, "editor"),
        "opens = %s," % kt_opt_str(leaf, "opens"),
        "stored = %s," % ("true" if stored else "false"),
        ")",
    ])


def install_kotlin(doc):
    functions = []
    seq = [0]

    def fname(prefix):
        seq[0] += 1
        safe = "".join(ch if ch.isalnum() else "_" for ch in prefix)
        return "tree_%s_%s" % (safe, seq[0])

    def emit_node(node):
        if "setting" in node:
            return "TreeNode.Leaf(%s)" % kt_leaf(node, False)
        if "page" in node:
            name = fname(node.get("page") or "page")
            kids = ",\n".join(emit_node(child) for child in node.get("children") or [])
            functions.append(
                "private fun %s(): TreeNode = TreeNode.Page(%s, %s, listOf(\n%s\n), %s)\n"
                % (name, kt_string(node.get("page") or ""), kt_string(node.get("title") or ""), kids or "", kt_json_list(node.get("summary") or []))
            )
            return "%s()" % name
        if "group" in node:
            name = fname(node.get("group") or "group")
            kids = ",\n".join(emit_node(child) for child in node.get("children") or [])
            visible = "null"
            if "visibleWhen" in node:
                visible = 'parseCondition(%s, "visibleWhen")' % kt_json(node["visibleWhen"])
            functions.append("\n".join([
                "private fun %s(): TreeNode = TreeNode.Group(" % name,
                "id = %s," % kt_string(node.get("group") or ""),
                "title = %s," % kt_string(node.get("label") or node.get("title") or ""),
                "resource = %s," % kt_string(node.get("title") or ""),
                "header = %s," % kt_string(node.get("header") or "label"),
                "description = %s," % kt_opt_str(node, "description"),
                "children = listOf(\n%s\n)," % kids,
                "summary = %s," % kt_json_list(node.get("summary") or []),
                "visibleWhen = %s," % visible,
                "editor = %s," % kt_opt_str(node, "editor"),
                "opens = %s," % kt_opt_str(node, "opens"),
                "introWhen = %s," % kt_json_list(node.get("introWhen") or []),
                ")",
                "",
            ]))
            return "%s()" % name
        if "editor" in node:
            name = fname(node.get("editor") or "editor")
            kids = node.get("settings", node.get("children", []))
            body = ",\n".join(emit_node(child) for child in kids)
            functions.append(
                "private fun %s(): TreeNode = TreeNode.Editor(%s, %s, %s, listOf(\n%s\n), %s)\n"
                % (
                    name,
                    kt_string(node.get("editor") or ""),
                    kt_string(node.get("opens") or ""),
                    kt_string(node.get("title") or ""),
                    body,
                    kt_json_list(node.get("summary") or []),
                )
            )
            return "%s()" % name
        raise SystemExit("install node")

    page_calls = ", ".join(emit_node(page) for page in doc["pages"])
    stored = ",\n".join(kt_leaf(leaf, True) for leaf in doc.get("stored") or [])
    head = "\n".join([
        "internal fun installGeneratedSettings() {",
        "    installBuiltTree(",
        "        listOf(%s)," % page_calls,
        "        listOf(%s)," % stored,
        "    )",
        "}",
        "",
    ])
    return head + "\n" + "\n".join(functions)


if __name__ == "__main__":
    main()
