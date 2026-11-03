#!/usr/bin/env python3
"""Emit SettingsTreeGenerated and SettingsAccessGenerated from settings-tree.json.

Gradle calls: python3 app/gen-settings-tree.py <tree.json> <ktOutDir>
"""
import copy
import hashlib
import json
import os
import re
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
    for section in doc["sections"]:
        walk_nodes([section], visit)
    return found


def groups_of(doc):
    found = []
    def visit(node):
        if "group" in node:
            found.append(node)
    for section in doc["sections"]:
        walk_nodes([section], visit)
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


def load_string_catalog(tree_path, strings_path=None):
    if strings_path is None:
        path = Path(tree_path).resolve().parents[1] / "res" / "values" / "strings.xml"
    else:
        path = Path(strings_path)
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
    ("performance", "On"): "settings_slower_on",
    ("performance", "Off"): "settings_slower_off",
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
        and isinstance(node.get("section"), str)
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
        owner = node["section"]
        if isinstance(node.get("title"), str):
            node["title"] = _page_or_editor_title(catalog, node["title"], "section " + node["section"])
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


_BINDINGS_NAME = "settings-display-bindings.json"
_PH_RE = re.compile(r"%%|%(?:\d+\$)?[#+ 0,\-]*\d*(?:\.\d+)?[a-zA-Z]|%")
_GROK_URL = "https://api.x.ai/v1/chat/completions"
_GROK_MODELS = ("grok-4.20-0309-non-reasoning", "grok-4.3")


def _android_escape(text):
    out = []
    for ch in text:
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "'":
            out.append("\\'")
        elif ch == '"':
            out.append('\\"')
        elif ch == "&":
            out.append("&amp;")
        elif ch == "<":
            out.append("&lt;")
        else:
            out.append(ch)
    return "".join(out)


def _add_slot(out, slot_id, sentence, plural, role, meta):
    if not isinstance(sentence, str) or sentence == "":
        return
    item = {"id": slot_id, "sentence": sentence, "plural": plural, "role": role}
    item.update(meta)
    out.append(item)


def _display_slots(node, scope, owner, out):
    if isinstance(node, list):
        for index, item in enumerate(node):
            _display_slots(item, "%s/%s" % (scope, index), owner, out)
        return
    if not isinstance(node, dict):
        return
    if _is_leaf(node):
        scope = "leaf:" + node["setting"]
        owner = node["setting"]
        _add_slot(out, scope + ":title", node.get("title"), False, "leaf-title", {"setting": owner})
        choices = node.get("choiceTitles")
        if isinstance(choices, dict):
            for key, value in choices.items():
                _add_slot(
                    out,
                    scope + ":choice:" + str(key),
                    value,
                    False,
                    "choice",
                    {"setting": owner, "choice": str(key)},
                )
    elif _is_group(node):
        scope = "group:" + node["group"]
        owner = node["group"]
        _add_slot(out, scope + ":title", node.get("title"), False, "group-title", {"group": owner})
    elif _is_page(node):
        scope = "section:" + node["section"]
        owner = node["section"]
        _add_slot(out, scope + ":title", node.get("title"), False, "section-title", {"section": owner})
    elif _is_editor(node):
        scope = "editor:" + node["editor"]
        owner = node["editor"]
        if isinstance(node.get("title"), str) and node["title"]:
            _add_slot(out, scope + ":title", node["title"], False, "editor-title", {"editor": owner})
    else:
        count = node.get("count") if isinstance(node.get("count"), str) else ""
        _add_slot(out, scope + ":title", node.get("title"), False, "summary-text", {"owner": owner})
        _add_slot(out, scope + ":text", node.get("text"), False, "summary-text", {"owner": owner})
        _add_slot(
            out,
            scope + ":plural",
            node.get("plural"),
            True,
            "summary-plural",
            {"owner": owner, "count": count},
        )
        titles = node.get("titles")
        if isinstance(titles, dict):
            setting = node.get("setting") if isinstance(node.get("setting"), str) else owner
            for key, value in titles.items():
                _add_slot(
                    out,
                    scope + ":titles:" + str(key),
                    value,
                    False,
                    "titles",
                    {"owner": owner, "setting": setting, "key": str(key)},
                )
    if isinstance(node.get("description"), str):
        _add_slot(out, scope + ":description", node.get("description"), False, "description", {"owner": owner})
    for key, value in node.items():
        if isinstance(value, (dict, list)):
            _display_slots(value, scope + ":" + key, owner, out)


def _slot_list(doc):
    out = []
    _display_slots(doc, "", "", out)
    seen = set()
    for slot in out:
        if slot["id"] in seen:
            raise SystemExit("settings display slot repeated: " + slot["id"])
        seen.add(slot["id"])
    return out


def _lookup_slot(slot, catalog):
    role = slot["role"]
    sentence = slot["sentence"]
    try:
        if role == "leaf-title":
            return _leaf_title(catalog, slot["setting"], sentence)
        if role == "choice":
            return _choice_name(catalog, slot["setting"], slot["choice"], sentence)
        if role == "group-title":
            return _group_title(catalog, slot["group"], sentence)
        if role == "section-title":
            return _page_or_editor_title(catalog, sentence, "section " + slot["section"])
        if role == "editor-title":
            return _page_or_editor_title(catalog, sentence, "editor " + slot["editor"])
        if role == "description":
            return _string_name(catalog, sentence, None, "description")
        if role == "summary-text":
            return _summary_name(catalog, slot.get("owner") or "", sentence)
        if role == "summary-plural":
            return _plural_name(catalog, sentence, slot.get("count") or "")
        if role == "titles":
            return _titles_name(catalog, slot.get("setting") or slot.get("owner") or "", slot.get("key") or "", sentence)
    except SystemExit as exc:
        # "matches a, b" is ambiguous. "matches" with no name is a missing sentence.
        text = str(exc)
        listed = text.split(" matches ", 1)[1].strip() if " matches " in text else ""
        if listed:
            raise
        return None
    return None


def _resolved_names(doc, catalog):
    resolved = resolve_display_names(doc, catalog)
    named = {}
    for slot in _slot_list(resolved):
        named[slot["id"]] = slot["sentence"]
    return named


def _resource_sentence(catalog, name):
    if name in catalog.plurals:
        return catalog.plurals[name].get("other")
    return catalog.strings.get(name)


def _set_owner(catalog, name, old, sentence):
    if old is not None:
        owners = catalog.owners.get(old, [])
        if name in owners:
            owners.remove(name)
        if not owners and old in catalog.owners:
            del catalog.owners[old]
    catalog.owners.setdefault(sentence, [])
    if name not in catalog.owners[sentence]:
        catalog.owners[sentence].append(name)


def _set_string(catalog, name, sentence):
    old = catalog.strings.get(name)
    if old == sentence and name not in catalog.plurals:
        return False
    _set_owner(catalog, name, old, sentence)
    catalog.strings[name] = sentence
    return True


def _set_plural_other(catalog, name, sentence):
    # When one and other are the same sentence, both move. A distinct one stays.
    quant = catalog.plurals.get(name)
    if quant is None:
        catalog.plurals[name] = {"one": sentence, "other": sentence}
        return "both"
    old_other = quant.get("other")
    if old_other == sentence:
        return None
    same = quant.get("one") == old_other
    quant["other"] = sentence
    if same:
        quant["one"] = sentence
        return "both"
    return "other"


def _alloc_name(catalog, slot_id):
    raw = re.sub(r"[^A-Za-z0-9]+", "_", slot_id).strip("_").lower()
    if not raw or raw[0].isdigit():
        raw = "s_" + raw
    base = raw
    if len(base) > 70:
        digest = hashlib.sha1(slot_id.encode("utf-8")).hexdigest()[:8]
        base = base[:60].rstrip("_") + "_" + digest
    name = base
    number = 2
    while name in catalog.strings or name in catalog.plurals:
        name = "%s_%d" % (base, number)
        number += 1
    return name


def _write_if_changed(path, text):
    path = Path(path)
    if path.is_file() and path.read_text(encoding="utf-8") == text:
        return False
    path.write_text(text, encoding="utf-8")
    return True


def _apply_string_xml(strings_path, catalog, edits):
    if not edits:
        return False
    path = Path(strings_path)
    original = path.read_text(encoding="utf-8")
    text = original
    additions = []
    for edit in edits:
        kind, name, sentence = edit[0], edit[1], edit[2]
        escaped = _android_escape(sentence)
        if kind == "string":
            pattern = re.compile(
                r'(<string name="%s">)(.*?)(</string>)' % re.escape(name),
                re.DOTALL,
            )
            updated, count = pattern.subn(lambda match: match.group(1) + escaped + match.group(3), text, count=1)
            if count != 1:
                raise SystemExit("strings.xml missing string " + name)
            text = updated
        elif kind == "plural":
            pattern = re.compile(
                r'(<plurals name="%s">)(.*?)(</plurals>)' % re.escape(name),
                re.DOTALL,
            )
            match = pattern.search(text)
            if match is None:
                raise SystemExit("strings.xml missing plural " + name)
            block = match.group(2)

            def replace_quantity(body, quantity):
                item = re.compile(
                    r'(<item quantity="%s">)(.*?)(</item>)' % quantity,
                    re.DOTALL,
                )
                replaced, count = item.subn(lambda found: found.group(1) + escaped + found.group(3), body, count=1)
                if count != 1:
                    raise SystemExit("strings.xml missing plural %s %s" % (name, quantity))
                return replaced

            block = replace_quantity(block, "other")
            if len(edit) > 3 and edit[3] == "both":
                block = replace_quantity(block, "one")
            text = text[: match.start(2)] + block + text[match.end(2) :]
        elif kind == "new-string":
            additions.append('    <string name="%s">%s</string>' % (name, escaped))
        elif kind == "new-plural":
            additions.append(
                "    <plurals name=\"%s\">\n        <item quantity=\"one\">%s</item>\n        <item quantity=\"other\">%s</item>\n    </plurals>"
                % (name, escaped, escaped)
            )
        else:
            raise SystemExit("strings.xml edit " + kind)
    if additions:
        marker = "</resources>"
        at = text.rfind(marker)
        if at < 0:
            raise SystemExit("strings.xml missing resources")
        block = "\n".join(additions) + "\n"
        text = text[:at] + block + text[at:]
    if text == original:
        return False
    path.write_text(text, encoding="utf-8")
    return True


def align_display_english(tree_path, strings_path=None, binding_path=None):
    # A settings-tree sentence keeps the Android resource name it already has.
    # A shared name stays on the uses whose English did not change.
    tree_path = Path(tree_path)
    if strings_path is None:
        strings_path = tree_path.resolve().parents[1] / "res" / "values" / "strings.xml"
    strings_path = Path(strings_path)
    if binding_path is None:
        binding_path = Path(__file__).resolve().parent / _BINDINGS_NAME
    binding_path = Path(binding_path)
    doc = loads_settings_tree(tree_path.read_bytes())
    catalog = load_string_catalog(tree_path, strings_path)
    slots = _slot_list(doc)
    if not binding_path.is_file():
        named = _resolved_names(doc, catalog)
        if set(named) != set(slot["id"] for slot in slots):
            raise SystemExit("settings display slots do not match resolved names")
        body = json.dumps(named, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        _write_if_changed(binding_path, body)
        return set(named.values())

    saved = json.loads(binding_path.read_text(encoding="utf-8"))
    if not isinstance(saved, dict):
        raise SystemExit("settings display bindings")
    assigned = {}
    for slot in slots:
        name = saved.get(slot["id"])
        if isinstance(name, str) and _resource_sentence(catalog, name) == slot["sentence"]:
            assigned[slot["id"]] = name
    edits = []
    for slot in slots:
        if slot["id"] in assigned:
            continue
        found = _lookup_slot(slot, catalog)
        if found:
            assigned[slot["id"]] = found
            continue
        previous = saved.get(slot["id"])
        keepers = [
            other
            for other in slots
            if other["id"] != slot["id"]
            and saved.get(other["id"]) == previous
            and other["id"] in assigned
        ]
        if isinstance(previous, str) and previous and not keepers and _resource_sentence(catalog, previous) is not None:
            sentence = slot["sentence"]
            if slot["plural"] or previous in catalog.plurals:
                plural_edit = _set_plural_other(catalog, previous, sentence)
                if plural_edit:
                    edits.append(("plural", previous, sentence, plural_edit))
            else:
                if _set_string(catalog, previous, sentence):
                    edits.append(("string", previous, sentence))
            assigned[slot["id"]] = previous
            saved[slot["id"]] = previous
            continue
        name = _alloc_name(catalog, slot["id"])
        if slot["plural"]:
            _set_plural_other(catalog, name, slot["sentence"])
            edits.append(("new-plural", name, slot["sentence"]))
        else:
            _set_string(catalog, name, slot["sentence"])
            edits.append(("new-string", name, slot["sentence"]))
        assigned[slot["id"]] = name
        saved[slot["id"]] = name
    if set(assigned) != set(slot["id"] for slot in slots):
        raise SystemExit("settings display slot was not named")
    named = _resolved_names(doc, catalog)
    for slot_id, name in assigned.items():
        if named.get(slot_id) != name:
            raise SystemExit("settings display name drifted: %s -> %s, resolved %s" % (slot_id, name, named.get(slot_id)))
    _apply_string_xml(strings_path, catalog, edits)
    body = json.dumps(assigned, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    _write_if_changed(binding_path, body)
    return set(assigned.values())


def _same_text(stored, current):
    if isinstance(current, dict):
        if not isinstance(stored, dict):
            return False
        return set(stored) == set(current) and all(stored[k] == current[k] for k in current)
    return isinstance(stored, str) and stored == current


def _placeholders(text):
    if not isinstance(text, str):
        return []
    return sorted(_PH_RE.findall(text))


def _ordered_text(value):
    if not isinstance(value, dict):
        return value
    ordered = {}
    for key in ("zero", "one", "two", "few", "many", "other"):
        if key in value:
            ordered[key] = value[key]
    for key in sorted(value):
        if key not in ordered:
            ordered[key] = value[key]
    return ordered


def _entry_body(entry):
    obj = {"key": entry["key"], "source": _ordered_text(entry["source"]), "text": _ordered_text(entry["text"])}
    if entry.get("pending") is True:
        obj["pending"] = True
    return json.dumps(obj, ensure_ascii=False, separators=(", ", ": "))


def _rewrite_locale(path, updates, additions):
    path = Path(path)
    original = path.read_text(encoding="utf-8")
    lines = original.splitlines()
    changed = False
    last_entry = None
    pending = dict(updates)
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.startswith("{"):
            continue
        payload = stripped[:-1] if stripped.endswith(",") else stripped
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            continue
        key = obj.get("key") if isinstance(obj, dict) else None
        last_entry = index
        if key not in pending:
            continue
        comma = stripped.endswith(",")
        indent = line[: len(line) - len(line.lstrip())]
        new_line = indent + pending[key] + ("," if comma else "")
        if new_line != line:
            lines[index] = new_line
            changed = True
        del pending[key]
    if pending:
        raise SystemExit("i18n entry missing while refreshing " + path.name)
    if additions:
        if last_entry is None:
            raise SystemExit("i18n file has no entries: " + path.name)
        if not lines[last_entry].rstrip().endswith(","):
            lines[last_entry] = lines[last_entry].rstrip() + ","
        indent = lines[last_entry][: len(lines[last_entry]) - len(lines[last_entry].lstrip())]
        insert_at = last_entry + 1
        for body in additions:
            lines.insert(insert_at, indent + body + ",")
            insert_at += 1
        if lines[insert_at - 1].rstrip().endswith(","):
            lines[insert_at - 1] = lines[insert_at - 1].rstrip()[:-1]
        changed = True
    if not changed:
        return False
    ending = "\n" if original.endswith("\n") else ""
    path.write_text("\n".join(lines) + ending, encoding="utf-8")
    return True


def _grok_request(api_key, prompt):
    import urllib.error
    import urllib.request

    for model in _GROK_MODELS:
        payload = {
            "model": model,
            "temperature": 0,
            "messages": [
                {
                    "role": "system",
                    "content": "You translate Android UI strings. Reply with one JSON object and no other text.",
                },
                {"role": "user", "content": prompt},
            ],
        }
        data = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            _GROK_URL,
            data=data,
            headers={"Authorization": "Bearer " + api_key, "Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            exc.close()
            if exc.code in (400, 404):
                continue
            return None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            return None
        try:
            body = json.loads(raw)
            content = body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError, json.JSONDecodeError):
            return None
        return _parse_grok_json(content)
    return None


def _parse_grok_json(content):
    if not isinstance(content, str):
        return None
    text = content.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        if lines and lines[0].startswith("```"):
            lines = lines[1:]
        if lines and lines[-1].startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None
    if not isinstance(parsed, dict):
        return None
    return parsed


def _translation_ok(english, translated, quantities):
    if isinstance(english, dict):
        if not isinstance(translated, dict):
            return False
        for quantity in quantities:
            got = translated.get(quantity)
            sample = english.get(quantity, english.get("other", ""))
            if not isinstance(got, str) or _placeholders(got) != _placeholders(sample):
                return False
        return True
    return isinstance(translated, str) and _placeholders(translated) == _placeholders(english)


def _grok_translate(api_key, english, locale_tags, quantities):
    # One xAI call per batch. Any other host is not a translation service we call.
    locales = list(locale_tags)
    found = {}
    step = 20
    for start in range(0, len(locales), step):
        batch = locales[start : start + step]
        lines = []
        for loc in batch:
            tag = locale_tags[loc]
            if quantities.get(loc):
                lines.append("%s (%s) quantities %s" % (loc, tag, ", ".join(quantities[loc])))
            else:
                lines.append("%s (%s)" % (loc, tag))
        if isinstance(english, dict):
            prompt = (
                "Translate this en-US Android plural into each locale. "
                "Keep every placeholder and format argument exactly as written. "
                "Return a JSON object whose keys are the locale ids and whose values are objects of quantity to text. "
                "Use only the quantities listed for that locale.\n\n"
                "en-US: %s\n\n%s" % (json.dumps(english, ensure_ascii=False), "\n".join(lines))
            )
        else:
            prompt = (
                "Translate this en-US Android string into each locale. "
                "Keep every placeholder and format argument exactly as written. "
                "Return a JSON object whose keys are the locale ids and whose values are the translations.\n\n"
                "en-US: %s\n\n%s" % (json.dumps(english, ensure_ascii=False), "\n".join(lines))
            )
        parsed = _grok_request(api_key, prompt)
        if not isinstance(parsed, dict):
            return None
        for loc in batch:
            translated = parsed.get(loc)
            if not _translation_ok(english, translated, quantities.get(loc) or []):
                return None
            if isinstance(translated, dict):
                kept = {}
                for quantity in quantities.get(loc) or []:
                    kept[quantity] = translated[quantity]
                found[loc] = kept
            else:
                found[loc] = translated.strip()
    return found


def _fallback_text(current, quantities):
    if isinstance(current, dict):
        text = {}
        for quantity in quantities or ["other"]:
            if quantity in current and isinstance(current[quantity], str):
                text[quantity] = current[quantity]
            else:
                text[quantity] = current.get("other", "")
        return text
    return current


def refresh_display_translations(machines, items, display_names, keywords, locale_tags):
    # Updates machine source for a changed settings-tree sentence.
    # Grok writes text. If Grok cannot run, the previous text stays and pending retries later.
    # Strings outside display_names are left for the caller to reject.
    if not display_names:
        return 0, 0
    by_current = {key: value for key, value in items}
    work = {}
    for loc, pair in machines.items():
        _path, data = pair
        found = {}
        for entry in data:
            if isinstance(entry, dict) and isinstance(entry.get("key"), str):
                found[entry["key"]] = entry
        for key in display_names:
            if key not in by_current:
                continue
            current = by_current[key]
            entry = found.get(key)
            if entry is None:
                work.setdefault(key, []).append((loc, None))
                continue
            pending = entry.get("pending") is True
            if pending or not _same_text(entry.get("source"), current):
                work.setdefault(key, []).append((loc, entry))
    if not work:
        return 0, 0
    api_key = os.environ.get("XAI_API_KEY", "").strip()
    updated = 0
    kept = 0
    file_updates = {}
    file_additions = {}
    for key, rows in work.items():
        current = by_current[key]
        quantities = {}
        tags = {}
        for loc, _entry in rows:
            tags[loc] = locale_tags.get(loc, loc)
            if isinstance(current, dict):
                quantities[loc] = list(keywords.get(loc) or [])
        translated = _grok_translate(api_key, current, tags, quantities) if api_key else None
        for loc, entry in rows:
            path, data = machines[loc]
            got = None if translated is None else translated.get(loc)
            if entry is None:
                previous = _fallback_text(current, quantities.get(loc))
                text = got if got is not None else previous
                pending = got is None
                created = {"key": key, "source": current, "text": text}
                if pending:
                    created["pending"] = True
                    kept += 1
                else:
                    updated += 1
                data.append(created)
                file_additions.setdefault(path, []).append(_entry_body(created))
                continue
            old_text = entry.get("text")
            if got is None:
                entry["source"] = current
                entry["pending"] = True
                if "text" not in entry:
                    entry["text"] = _fallback_text(current, quantities.get(loc))
                kept += 1
            else:
                entry["source"] = current
                entry["text"] = got
                entry.pop("pending", None)
                updated += 1
            if entry.get("text") is None:
                entry["text"] = old_text if isinstance(old_text, (str, dict)) else _fallback_text(current, quantities.get(loc))
            file_updates.setdefault(path, {})[key] = _entry_body(entry)
    paths = set(file_updates) | set(file_additions)
    for path in paths:
        _rewrite_locale(path, file_updates.get(path, {}), file_additions.get(path, []))
    return updated, kept


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
    align_display_english(tree_path)
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
        if leaf.get("type") in UNSTORED_TYPES or "default" not in leaf:
            continue
        key = leaf["setting"]
        default = leaf["default"]
        try:
            stored = kt_type(leaf)
        except SystemExit:
            continue
        if stored == "Int" and isinstance(default, int) and not isinstance(default, bool):
            lines.append("    const val %s: Int = %s" % (key, int(default)))
        elif stored == "Long" and isinstance(default, int) and not isinstance(default, bool):
            lines.append("    const val %s: Long = %sL" % (key, int(default)))
        elif stored == "Boolean" and isinstance(default, bool):
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

SET_FIELDS = set()

TYPE_OVERRIDE = {
    "defaultView": "FolderView",
    "folderViews": "Map<String, FolderView>",
    "folderStarts": "Map<String, StartRule>",
    "favorites": "List<FolderFavorite>",
    "addressBooks": "List<AddressBookEntry>",
    "inboundRules": "List<InboundRule>",
    "savedSearches": "List<SavedSearch>",
    "savedAdvanced": "List<SavedAdvanced>",
    "swipeTrailing": "SwipeBinding",
    "swipeLeading": "SwipeBinding",
    "readerSwipeTrailing": "SwipeBinding",
    "readerSwipeLeading": "SwipeBinding",
    "altAddresses": "List<String>",
    "watchedFolders": "List<String>",
    "sendingRules": "String",
    "customLayout": "String",
    "indexBar": "IndexBarLayout",
    "selectionBar": "SelectionBarLayout",
    "folderBar": "FolderBarLayout",
    "readerToolbar": "ReaderToolbarLayout",
    "composeBar": "ComposeBarLayout",
}

# Readers that are not the type's ordinary decode. The id still comes from the JSON leaf.
DECODE_READER = {
    "authMechanism": "jsonString(it).trim().uppercase()",
    "customLayout": "jsonString(it).takeIf { decodePaneLayout(it) != null }.orEmpty()",
    "sievePort": "parseSievePort(jsonLong(it).toString())",
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


UNSTORED_TYPES = {"password", "checkServer", "pinerc", "settingsFile"}

INT_MAX = 2147483647
INT_MIN = -2147483648


def number_is_long(leaf):
    if leaf.get("many"):
        return False
    if "max" not in leaf or leaf.get("max") is None:
        return True
    limit = int(leaf["max"])
    return limit > INT_MAX or limit < INT_MIN


def kt_type(leaf):
    key = leaf["setting"]
    if key in ENUM_CLASS:
        return ENUM_CLASS[key]
    if key in TYPE_OVERRIDE:
        return TYPE_OVERRIDE[key]
    if key in SET_FIELDS:
        return "Set<String>"
    kind = leaf["type"]
    many = bool(leaf.get("many"))
    if kind in ("bool", "either", "Bool"):
        return "Boolean"
    if kind in ("number", "Int", "Long"):
        if many or kind == "IntList":
            return "List<Int>"
        if kind == "Long" or number_is_long(leaf):
            return "Long"
        return "Int"
    if kind in ("text", "String", "Encoded", "mailbox", "clientCert", "paneLayout", "rules"):
        return "List<String>" if many else "String"
    if kind == "StringList":
        return "List<String>"
    if kind == "IntList":
        return "List<Int>"
    if kind in ("choice", "Enum"):
        return "String"
    if kind == "folder":
        return "List<FolderFavorite>" if many else "String"
    if kind == "swipe":
        return "SwipeBinding"
    if kind == "toolbar":
        return TYPE_OVERRIDE[key]
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
        return "FolderView(SortKey.Arrival, true)"
    if typ == "SwipeBinding":
        action = "Delete"
        if isinstance(default, dict) and isinstance(default.get("action"), str):
            action = default["action"]
        return "SwipeBinding(SwipeAction.%s)" % action
    if typ == "IndexBarLayout":
        return "defaultIndexBar()"
    if typ == "SelectionBarLayout":
        return "defaultSelectionBar()"
    if typ == "FolderBarLayout":
        return "defaultFolderBar()"
    if typ == "ReaderToolbarLayout":
        return "defaultReaderToolbar()"
    if typ == "ComposeBarLayout":
        return "defaultComposeBar()"
    if typ == "List<AddressBookEntry>":
        return "emptyList()"
    if typ.startswith("Map"):
        if default not in (None, ""):
            raise SystemExit("map default " + key)
        return "emptyMap()"
    if typ.startswith("Set"):
        if default not in (None, "", []):
            raise SystemExit("set default " + key)
        return "emptySet()"
    if typ.startswith("List"):
        if default not in (None, "", []):
            raise SystemExit("list default " + key)
        return "emptyList()"
    raise SystemExit("default " + key)


JSON_EXPR = {
    "defaultView": "viewJson(defaultView)",
    "folderViews": "folderViewsJson(folderViews)",
    "folderStarts": "folderStartsJson(folderStarts)",
    "favorites": "favoritesJson(favorites)",
    "addressBooks": "addressBooksJson(addressBooks)",
    "inboundRules": "inboundRulesJson(inboundRules)",
    "savedSearches": "savedSearchesJson(savedSearches)",
    "savedAdvanced": "savedAdvancedJson(savedAdvanced)",
    "swipeTrailing": "swipeJson(swipeTrailing)",
    "swipeLeading": "swipeJson(swipeLeading)",
    "readerSwipeTrailing": "swipeJson(readerSwipeTrailing)",
    "readerSwipeLeading": "swipeJson(readerSwipeLeading)",
    "indexBar": "indexBarJson(indexBar)",
    "selectionBar": "selectionBarJson(selectionBar)",
    "folderBar": "folderBarJson(folderBar)",
    "readerToolbar": "readerToolbarJson(readerToolbar)",
    "composeBar": "composeBarJson(composeBar)",
}

JSON_READ = {
    "defaultView": "readView(it)",
    "folderViews": "readFolderViews(it)",
    "folderStarts": "readFolderStarts(it)",
    "favorites": "readFavorites(it)",
    "addressBooks": "readAddressBooks(it)",
    "inboundRules": "readInboundRules(it)",
    "savedSearches": "readSavedSearches(it)",
    "savedAdvanced": "readSavedAdvanced(it)",
    "swipeTrailing": "readSwipe(it)",
    "swipeLeading": "readSwipe(it)",
    "readerSwipeTrailing": "readSwipe(it)",
    "readerSwipeLeading": "readSwipe(it)",
    "indexBar": "readIndexBar(it)",
    "selectionBar": "readSelectionBar(it)",
    "folderBar": "readFolderBar(it)",
    "readerToolbar": "readReaderToolbar(it)",
    "composeBar": "readComposeBar(it)",
}


def json_expr(leaf):
    key = leaf["setting"]
    if key in JSON_EXPR:
        return JSON_EXPR[key]
    typ = kt_type(leaf)
    if typ == "Boolean":
        return "JsonValue.Bool(%s)" % key
    if typ == "Int":
        return "JsonValue.Num(%s.toLong())" % key
    if typ == "Long":
        return "JsonValue.Num(%s)" % key
    if typ == "String":
        return "JsonValue.Str(%s)" % key
    if typ == "List<Int>":
        return "intListJson(%s)" % key
    if typ == "List<String>":
        return "stringListJson(%s)" % key
    if key in ENUM_CLASS:
        return "JsonValue.Str(%s.name)" % key
    raise SystemExit("json " + key + " " + typ)


def encode_put(leaf):
    key = leaf["setting"]
    return 'put("%s", %s)' % (key, json_expr(leaf))


def decode_reader(leaf):
    key = leaf["setting"]
    if key in DECODE_READER:
        return DECODE_READER[key]
    if key in JSON_READ:
        return JSON_READ[key]
    typ = kt_type(leaf)
    if typ == "String":
        return "jsonString(it)"
    if typ == "Boolean":
        return "jsonBool(it)"
    if typ == "Int":
        return "jsonInt(it)"
    if typ == "Long":
        return "jsonLong(it)"
    if typ == "List<Int>":
        return "readIntList(it)"
    if typ == "List<String>":
        return "readStringList(it)"
    if key in ENUM_CLASS:
        return "enumValueOf<%s>(jsonString(it))" % ENUM_CLASS[key]
    raise SystemExit("decode " + key)


def special_decode(key):
    if key == "clientSortCollation":
        return "\n".join([
            'clientSortCollation = field("clientSortCollation", d.clientSortCollation) {',
            "    val word = jsonString(it)",
            '    if (word != "locale" && word != "plain") throw IllegalArgumentException("clientSortCollation")',
            "    clientSortCollationOf(word)",
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
        if leaf.get("type") in UNSTORED_TYPES:
            continue
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
        if not leaf.get("derived") and leaf.get("type") not in UNSTORED_TYPES:
            _FIELD_TYPES[leaf["setting"]] = kt_type(leaf)
    group_of = {}
    def visit(node, group):
        if "group" in node:
            group = node["group"]
        if "setting" in node and not node.get("derived") and node.get("type") not in UNSTORED_TYPES:
            group_of[node["setting"]] = group or node.get("scope") or "account"
        for key in ("children", "settings"):
            kids = node.get(key)
            if isinstance(kids, list):
                for child in kids:
                    if isinstance(child, dict):
                        visit(child, group)
    for section in doc["sections"]:
        visit(section, None)
    buckets = {}
    for key, group in group_of.items():
        buckets.setdefault(group, []).append(key)
    imports = account_imports()
    extra_imports = [
        "import org.dlang.liveimap.ui.toolbar.defaultReaderToolbar",
    ]
    have = set(imports.splitlines())
    missing_imports = [line for line in extra_imports if line not in have]
    if missing_imports:
        imports = imports + "\n" + "\n".join(missing_imports)
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
    if key in JSON_READ:
        return "copy(%s = %s)" % (key, JSON_READ[key].replace("(it)", "(parseJson(value.text))"))
    typ = field_types().get(key, "String")
    if typ == "String":
        return "copy(%s = value.text)" % key
    if typ == "Int":
        return "copy(%s = value.text.toInt())" % key
    if typ == "Long":
        return "copy(%s = value.text.toLong())" % key
    if typ == "List<Int>":
        return "copy(%s = readIntList(parseJson(value.text)))" % key
    if typ == "List<String>":
        return "copy(%s = readStringList(parseJson(value.text)))" % key
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
    if key in JSON_EXPR:
        return "encodeJson(%s)" % JSON_EXPR[key]
    typ = field_types().get(key, "String")
    if typ == "List<Int>":
        return "encodeJson(intListJson(%s))" % key
    if typ == "List<String>":
        return "encodeJson(stringListJson(%s))" % key
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
    stamp_scopes(doc)
    emit(doc, kt_root, raw, tree_path)


ROOT_KEYS = {"sections"}
SECTION_KEYS = {"section", "title", "children", "summary"}
GROUP_KEYS = {
    "group", "title", "label", "header", "children", "summary", "visibleWhen", "enabledWhen",
    "description", "editor", "opens", "introWhen",
}
EDITOR_KEYS = {"editor", "opens", "title", "summary", "settings", "children", "label"}
LEAF_KEYS = {
    "setting", "type", "default", "title", "summary", "choiceTitles", "choices",
    "min", "max", "minItems", "maxItems", "itemMin", "itemMax", "order", "many", "server", "derived",
    "pinerc", "pinercFormat", "visibleWhen", "enabledWhen", "titleWhen", "noteWhen",
    "format", "label", "nullable", "titleArgs", "hidden",
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


def stamp_scopes(doc):
    def walk(node, scope):
        if isinstance(node, list):
            for item in node:
                walk(item, scope)
            return
        if not isinstance(node, dict):
            return
        if "setting" in node and "type" in node:
            node["scope"] = scope
        for key in ("children", "settings"):
            kids = node.get(key)
            if isinstance(kids, list):
                for child in kids:
                    walk(child, scope)

    for section in doc["sections"]:
        for child in section.get("children") or []:
            walk(child, section["section"])


def validate_doc(doc):
    if not isinstance(doc, dict):
        raise SystemExit("settings-tree.json root")
    _extra(doc.keys(), ROOT_KEYS, "root")
    sections = doc.get("sections")
    if not isinstance(sections, list) or not sections:
        raise SystemExit("settings-tree.json missing sections")
    seen = set()
    seen_sections = []

    def leaf(node, where):
        _extra(node.keys(), LEAF_KEYS, where)
        for required in ("setting", "type"):
            if required not in node:
                raise SystemExit("settings-tree.json missing %s at %s" % (required, where))
        if node["type"] not in UNSTORED_TYPES and "default" not in node:
            raise SystemExit("settings-tree.json missing default at " + where)
        allowed_types = {
            "bool", "either", "number", "choice", "text", "password", "folder", "mailbox",
            "checkServer", "clientCert", "pinerc", "settingsFile", "rules", "toolbar",
            "paneLayout", "swipe",
        }
        if node["type"] not in allowed_types:
            raise SystemExit("settings-tree.json type %s at %s" % (node["type"], where))
        if node.get("many") and node.get("order") not in ("user", "sorted", "increasing"):
            raise SystemExit("settings-tree.json order at " + where)
        if node.get("order") and not node.get("many"):
            raise SystemExit("settings-tree.json order at " + where)
        key = node["setting"]
        if not isinstance(key, str) or not key:
            raise SystemExit("settings-tree.json misspelt setting at " + where)
        if key in seen:
            raise SystemExit("settings-tree.json duplicate " + key)
        seen.add(key)
        if node["type"] == "Int" and node.get("choiceTitles"):
            raise SystemExit("settings-tree.json %s choiceTitles" % key)
        if "hidden" in node and node["hidden"] is not True and node["hidden"] is not False:
            raise SystemExit("settings-tree.json hidden at " + where)

    def walk(node, where):
        if not isinstance(node, dict):
            raise SystemExit("settings-tree.json node " + where)
        if "section" in node:
            raise SystemExit("settings-tree.json nested section at " + where)
        if "setting" in node and "type" in node:
            leaf(node, where)
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

    for index, section in enumerate(sections):
        if not isinstance(section, dict):
            raise SystemExit("settings-tree.json section %s" % index)
        _extra(section.keys(), SECTION_KEYS, "sections/%s" % index)
        sid = section.get("section")
        if sid not in ("account", "ui"):
            raise SystemExit("settings-tree.json section %s" % sid)
        if sid in seen_sections:
            raise SystemExit("settings-tree.json duplicate section %s" % sid)
        seen_sections.append(sid)
        if "title" not in section or not isinstance(section.get("children"), list):
            raise SystemExit("settings-tree.json missing section field at " + str(sid))
        for child_index, child in enumerate(section["children"]):
            walk(child, "%s/%s" % (sid, child_index))
    if len(seen_sections) != 2 or set(seen_sections) != {"account", "ui"}:
        raise SystemExit("settings-tree.json sections")


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


def kt_leaf(leaf):
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
        "kind = %s," % kt_string(leaf.get("type") or ""),
        "many = %s," % ("true" if leaf.get("many") else "false"),
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
        "hidden = %s," % ("true" if leaf.get("hidden") is True else "false"),
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
            return "TreeNode.Leaf(%s)" % kt_leaf(node)
        if "section" in node:
            name = fname(node.get("section") or "section")
            kids = ",\n".join(emit_node(child) for child in node.get("children") or [])
            functions.append(
                "private fun %s(): TreeNode = TreeNode.Page(%s, %s, listOf(\n%s\n), %s)\n"
                % (name, kt_string(node.get("section") or ""), kt_string(node.get("title") or ""), kids or "", kt_json_list(node.get("summary") or []))
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

    section_calls = ", ".join(emit_node(section) for section in doc["sections"])
    head = "\n".join([
        "internal fun installGeneratedSettings() {",
        "    installBuiltTree(",
        "        listOf(%s)," % section_calls,
        "    )",
        "}",
        "",
    ])
    return head + "\n" + "\n".join(functions)


if __name__ == "__main__":
    main()
