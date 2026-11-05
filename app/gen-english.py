#!/usr/bin/env python3
"""Write en-US strings.xml and the S lookup from call sites and the settings tree.

Resource names stay the names already in the catalog. A settings-tree sentence is not
copied out of settings-tree.json. This script does not write the catalog or the tree.
"""
import importlib.util
import json
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/i18n/af.json"
TREE = ROOT / "app/src/main/assets/settings-tree.json"
BINDINGS = ROOT / "app/settings-display-bindings.json"
SRC = ROOT / "app/src"

# Names gen-settings-tree.py emits besides the bound slots.
FIXED_NAMES = (
    "settings_logging_line",
    "settings_all_accounts_summary",
    "settings_account_count",
    "sending_rule_numbered",
    "address_book_row",
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
)

Q_ORDER = ("zero", "one", "two", "few", "many", "other")


def _settings():
    path = ROOT / "app" / "gen-settings-tree.py"
    spec = importlib.util.spec_from_file_location("settings_tree_gen", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _unescape_xml(text):
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
                hexv = text[i + 2 : i + 6]
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


def load_catalog():
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    order = []
    strings = {}
    plurals = {}
    for item in data:
        key = item.get("key")
        source = item.get("source")
        if not isinstance(key, str):
            continue
        order.append(key)
        if isinstance(source, dict):
            plurals[key] = {str(k): v for k, v in source.items() if isinstance(v, str)}
        elif isinstance(source, str):
            strings[key] = source
    return order, strings, plurals


def _xml_escape(text):
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


def write_xml(path, order, strings, plurals):
    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name in order:
        if name in strings:
            lines.append('    <string name="%s">%s</string>' % (name, _xml_escape(strings[name])))
        elif name in plurals:
            lines.append('    <plurals name="%s">' % name)
            quant = plurals[name]
            seen = set()
            for quantity in Q_ORDER:
                if quantity in quant:
                    lines.append(
                        '        <item quantity="%s">%s</item>'
                        % (quantity, _xml_escape(quant[quantity]))
                    )
                    seen.add(quantity)
            for quantity in sorted(quant):
                if quantity not in seen:
                    lines.append(
                        '        <item quantity="%s">%s</item>'
                        % (quantity, _xml_escape(quant[quantity]))
                    )
            lines.append("    </plurals>")
    lines.append("</resources>")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_xml(path):
    root = ET.parse(path).getroot()
    order = []
    strings = {}
    plurals = {}
    for el in root:
        name = el.attrib.get("name")
        if not isinstance(name, str):
            continue
        order.append(name)
        if el.tag == "string":
            strings[name] = _unescape_xml(el.text)
        elif el.tag == "plurals":
            plurals[name] = {
                item.attrib.get("quantity"): _unescape_xml(item.text) for item in el
            }
    return order, strings, plurals


def align_catalog(order, strings, plurals):
    mod = _settings()
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        strings_path = root / "strings.xml"
        bind_path = root / "bindings.json"
        write_xml(strings_path, order, strings, plurals)
        bind_path.write_text(BINDINGS.read_text(encoding="utf-8"), encoding="utf-8")
        mod.align_display_english(TREE, strings_path, bind_path, write=True, report_new=False)
        aligned_order, aligned_strings, aligned_plurals = read_xml(strings_path)
        saved = json.loads(bind_path.read_text(encoding="utf-8"))
    bound = set()
    if isinstance(saved, dict):
        for name in saved.values():
            if isinstance(name, str):
                bound.add(name)
    return aligned_order, aligned_strings, aligned_plurals, bound


def _skip_string(text, i):
    quote = text[i]
    if quote == '"' and text.startswith('"""', i):
        end = text.find('"""', i + 3)
        return len(text) if end < 0 else end + 3
    i += 1
    while i < len(text):
        ch = text[i]
        if ch == "\\":
            i += 2
            continue
        if ch == quote:
            return i + 1
        if ch == "$" and quote == '"':
            # Interpolation: still consume the literal so the scanner stays aligned.
            if i + 1 < len(text) and text[i + 1] == "{":
                depth = 1
                i += 2
                while i < len(text) and depth:
                    if text[i] == "{":
                        depth += 1
                    elif text[i] == "}":
                        depth -= 1
                    i += 1
                continue
        i += 1
    return i


def _decode_kotlin_string(literal):
    # literal includes quotes. None when the text is interpolated.
    if literal.startswith('"""'):
        body = literal[3:-3]
        if "$" in body:
            return None
        return body
    if len(literal) < 2 or literal[0] != '"' or literal[-1] != '"':
        return None
    body = literal[1:-1]
    out = []
    i = 0
    while i < len(body):
        ch = body[i]
        if ch == "\\":
            if i + 1 >= len(body):
                return None
            nxt = body[i + 1]
            mapped = {
                "n": "\n",
                "t": "\t",
                "r": "\r",
                '"': '"',
                "\\": "\\",
                "$": "$",
                "'": "'",
            }
            if nxt in mapped:
                out.append(mapped[nxt])
                i += 2
                continue
            if nxt == "u" and i + 5 < len(body):
                try:
                    out.append(chr(int(body[i + 2 : i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    return None
            return None
        if ch == "$":
            return None
        out.append(ch)
        i += 1
    return "".join(out)


def _explicit_name(args):
    # name = "resource" outside a sentence. The catalog key for this call.
    i = 0
    n = len(args)
    while i < n:
        if args[i] == '"':
            i = _skip_string(args, i)
            continue
        if args.startswith("name", i) and (i == 0 or not (args[i - 1].isalnum() or args[i - 1] == "_")):
            j = i + 4
            while j < n and args[j] in " \t\n\r":
                j += 1
            if j < n and args[j] == "=":
                j += 1
                while j < n and args[j] in " \t\n\r":
                    j += 1
                if j < n and args[j] == '"':
                    end = _skip_string(args, j)
                    decoded = _decode_kotlin_string(args[j:end])
                    if isinstance(decoded, str) and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", decoded):
                        return decoded
        i += 1
    return None


def _scan_kotlin(text):
    sentences = []
    plurals = []
    resources = []
    explicit_s = []
    explicit_p = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end + 1
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if ch == '"':
            i = _skip_string(text, i)
            continue
        if ch == "'":
            i = _skip_string(text, i)
            continue
        if ch.isalpha() or ch == "_":
            start = i
            i += 1
            while i < n and (text[i].isalnum() or text[i] == "_"):
                i += 1
            word = text[start:i]
            j = i
            while j < n and text[j] in " \t\n\r":
                j += 1
            if j >= n or text[j] != "(":
                continue
            if word not in ("S", "P", "phrase", "resource"):
                continue
            prev = start - 1
            while prev >= 0 and text[prev] in " \t\n\r":
                prev -= 1
            if word in ("S", "P", "phrase") and text[max(0, prev - 2) : prev + 1] == "fun":
                continue
            if word == "resource":
                dot = prev
                if dot < 0 or text[dot] != ".":
                    continue
                name_end = dot
                name_start = name_end - 1
                while name_start >= 0 and (text[name_start].isalnum() or text[name_start] == "_"):
                    name_start -= 1
                if text[name_start + 1 : name_end] != "SettingsTreeGenerated":
                    continue
            end, args = _parse_call(text, j)
            if word in ("S", "phrase"):
                sentence = _first_string(args)
                if sentence is not None:
                    sentences.append(sentence)
                explicit = _explicit_name(args)
                if explicit:
                    explicit_s.append(explicit)
            elif word == "P":
                one, other = _named_quantities(args)
                if other is not None:
                    plurals.append((one, other))
                explicit = _explicit_name(args)
                if explicit:
                    explicit_p.append(explicit)
            else:
                name = _first_string(args)
                if name is not None:
                    resources.append(name)
            i = end
            continue
        i += 1
    return sentences, plurals, resources, explicit_s, explicit_p


def _parse_call(text, open_paren):
    depth = 0
    i = open_paren
    start = open_paren + 1
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end + 1
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if ch == '"' or ch == "'":
            i = _skip_string(text, i)
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i + 1, text[start:i]
        i += 1
    return n, text[start:]


def _split_args(args):
    parts = []
    buf = []
    depth = 0
    i = 0
    n = len(args)
    while i < n:
        if args.startswith("//", i):
            end = args.find("\n", i)
            buf.append(args[i : n if end < 0 else end + 1])
            i = n if end < 0 else end + 1
            continue
        ch = args[i]
        if ch == '"' or ch == "'":
            end = _skip_string(args, i)
            buf.append(args[i:end])
            i = end
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "," and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    tail = "".join(buf).strip()
    if tail:
        parts.append(tail)
    return parts


def _string_token(arg):
    stripped = arg.strip()
    if stripped.startswith("one ") or stripped.startswith("other ") or stripped.startswith("one=") or stripped.startswith("other="):
        return None
    match = re.match(r'^(?:[A-Za-z_][A-Za-z0-9_]*\s*=\s*)?("(?:\\.|[^"\\])*"|"""[\s\S]*?""")$', stripped)
    if not match:
        # named form with the string not spanning the whole arg is handled by _named
        found = re.search(r'("(?:\\.|[^"\\])*")', stripped)
        if found and stripped.find(found.group(1)) == 0:
            return _decode_kotlin_string(found.group(1))
        return None
    return _decode_kotlin_string(match.group(1))


def _first_string(args):
    for part in _split_args(args):
        if re.match(r'^[A-Za-z_][A-Za-z0-9_]*\s*=', part) and not part.lstrip().startswith('"'):
            value = part.split("=", 1)[1].strip()
            decoded = _string_token(value)
            if decoded is not None:
                return decoded
            continue
        decoded = _string_token(part)
        if decoded is not None:
            return decoded
        # First positional arg is not a string: keep looking (S(context, "text")).
        if re.match(r'^[A-Za-z_][A-Za-z0-9_]*\s*=', part):
            continue
        # A non-string positional before a string is the context. Keep looking only
        # until the first string, which _string_token returns. If this part is not a
        # string, continue so S(context, "text") still matches.
        if '"' not in part:
            continue
    return None


def _named_quantities(args):
    one = None
    other = None
    for part in _split_args(args):
        match = re.match(
            r'^(one|other)\s*=\s*("(?:\\.|[^"\\])*"|"""[\s\S]*?""")$',
            part.strip(),
        )
        if not match:
            continue
        decoded = _decode_kotlin_string(match.group(2))
        if decoded is None:
            continue
        if match.group(1) == "one":
            one = decoded
        else:
            other = decoded
    return one, other


def kotlin_sources():
    files = []
    if not SRC.is_dir():
        return files
    for path in SRC.rglob("*.kt"):
        parts = path.relative_to(SRC).parts
        if parts and parts[0] in ("test", "androidTest"):
            continue
        files.append(path)
    return files


def scan_sources():
    sentences = []
    plurals = []
    resources = []
    explicit_s = []
    explicit_p = []
    seen_s = set()
    seen_p = set()
    seen_es = set()
    seen_ep = set()
    for path in kotlin_sources():
        text = path.read_text(encoding="utf-8")
        found_s, found_p, found_r, found_es, found_ep = _scan_kotlin(text)
        for sentence in found_s:
            if sentence not in seen_s:
                seen_s.add(sentence)
                sentences.append(sentence)
        for pair in found_p:
            if pair not in seen_p:
                seen_p.add(pair)
                plurals.append(pair)
        for name in found_es:
            if name not in seen_es:
                seen_es.add(name)
                explicit_s.append(name)
        for name in found_ep:
            if name not in seen_ep:
                seen_ep.add(name)
                explicit_p.append(name)
        resources.extend(found_r)
    # A leftover R.string in hand-written code still has to be generated or the build breaks.
    res_re = re.compile(r"\bR\.(?:string|plurals)\.([A-Za-z0-9_]+)")
    fq_re = re.compile(r"com\.davidlang\.liveimap\.R\.(?:string|plurals)\.([A-Za-z0-9_]+)")
    extra = []
    for path in kotlin_sources():
        if path.name in ("S.kt", "Literal.kt"):
            continue
        text = path.read_text(encoding="utf-8")
        extra.extend(res_re.findall(text))
        extra.extend(fq_re.findall(text))
    return sentences, plurals, resources, extra, explicit_s, explicit_p


def _alloc(sentence, used):
    raw = re.sub(r"[^A-Za-z0-9]+", "_", sentence).strip("_").lower()
    if not raw or raw[0].isdigit():
        raw = "s_" + raw
    base = raw
    if len(base) > 70:
        import hashlib
        digest = hashlib.sha1(sentence.encode("utf-8")).hexdigest()[:8]
        base = base[:60].rstrip("_") + "_" + digest
    name = base
    number = 2
    while name in used:
        name = "%s_%d" % (base, number)
        number += 1
    return name


def _owners(order, table, sentence, plural):
    found = []
    for name in order:
        if plural:
            quant = table.get(name)
            if not isinstance(quant, dict):
                continue
            if quant.get("one") == sentence[0] and quant.get("other") == sentence[1]:
                found.append(name)
        elif table.get(name) == sentence:
            found.append(name)
    return found


def build():
    order, strings, plurals = load_catalog()
    order, strings, plurals, bound = align_catalog(order, strings, plurals)
    sentences, plural_calls, resource_names, leftover, explicit_s, explicit_p = scan_sources()
    prefer = set(bound)
    prefer.update(name for name in FIXED_NAMES if name in strings or name in plurals)
    if "app_name" in strings:
        prefer.add("app_name")
    used = set(order)
    chosen_s = {}
    for sentence in sentences:
        hits = _owners(order, strings, sentence, False)
        name = next((hit for hit in hits if hit in prefer), None)
        if name is None and hits:
            name = hits[0]
        if name is None:
            name = _alloc(sentence, used)
            used.add(name)
            order.append(name)
            strings[name] = sentence
        chosen_s[sentence] = name
        prefer.add(name)
    chosen_p = {}
    for pair in plural_calls:
        hits = _owners(order, plurals, pair, True)
        name = next((hit for hit in hits if hit in prefer), None)
        if name is None and hits:
            name = hits[0]
        if name is None:
            name = _alloc(pair[1] or pair[0] or "plural", used)
            used.add(name)
            order.append(name)
            quant = {}
            if pair[0] is not None:
                quant["one"] = pair[0]
            quant["other"] = pair[1]
            plurals[name] = quant
        chosen_p[pair] = name
        prefer.add(name)
    # Same English with different translations stays on the call's own catalog key.
    keep = set(prefer)
    keep.update(name for name in resource_names if name in strings or name in plurals)
    keep.update(name for name in leftover if name in strings or name in plurals)
    keep.update(name for name in explicit_s if name in strings or name in plurals)
    keep.update(name for name in explicit_p if name in strings or name in plurals)
    # titleOf("server") reads this name. The tree has no server group.
    if "settings_group_server_title" in strings:
        keep.add("settings_group_server_title")
    kept_order = [name for name in order if name in keep and (name in strings or name in plurals)]
    named = [
        name
        for name in order
        if name in keep
        and (name in explicit_s or name in explicit_p)
        and (name in strings or name in plurals)
    ]
    return kept_order, strings, plurals, chosen_s, chosen_p, named


def defined_items():
    order, strings, plurals, _chosen_s, _chosen_p, _named = build()
    items = []
    for name in order:
        if name in strings:
            items.append((name, strings[name]))
        elif name in plurals:
            items.append((name, plurals[name]))
    return items


def _kt(text):
    return '"' + (
        text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("$", "\\$")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    ) + '"'


def _chunked_map(lines, name, entries, item, chunk=60):
    # One mapOf of every sentence overflows the JVM method limit.
    parts = []
    rows = list(entries)
    if not rows:
        lines.append("    private val %s: Map<%s> = emptyMap()" % (name, item[0]))
        return
    for index in range(0, len(rows), chunk):
        part = "%s%d" % (name, index // chunk)
        parts.append(part)
        lines.append("    private fun %s(): Map<%s> = mapOf(" % (part, item[0]))
        for row in rows[index : index + chunk]:
            lines.append(item[1](row))
        lines.append("    )")
        lines.append("")
    lines.append(
        "    private val %s: Map<%s> = mutableMapOf<%s>().also { into ->" % (name, item[0], item[0])
    )
    for part in parts:
        lines.append("        into.putAll(%s())" % part)
    lines.append("    }")


def write_outputs(res_out, kt_out):
    order, strings, plurals, chosen_s, chosen_p, named = build()
    res_out = Path(res_out)
    kt_out = Path(kt_out)
    values = res_out / "values"
    values.mkdir(parents=True, exist_ok=True)
    write_xml(values / "strings.xml", order, strings, plurals)
    lines = [
        "package org.dlang.liveimap.i18n",
        "",
        "import com.davidlang.liveimap.R",
        "",
        "// Generated from S, the plural helper, and settings-tree.json. Do not edit.",
        "internal object StringCatalog {",
    ]
    _chunked_map(
        lines,
        "ids",
        list(chosen_s.items()),
        (
            "String, Int",
            lambda row: "        %s to R.%s.%s,"
            % (_kt(row[0]), "plurals" if row[1] in plurals else "string", row[1]),
        ),
    )
    lines.append("")
    _chunked_map(
        lines,
        "plurals",
        list(chosen_p.items()),
        (
            "Pair<String, String>, Int",
            lambda row: "        (%s to %s) to R.plurals.%s,"
            % (_kt(row[0][0] or ""), _kt(row[0][1]), row[1]),
        ),
    )
    lines.append("")
    _chunked_map(
        lines,
        "named",
        named,
        (
            "String, Int",
            lambda name: "        %s to R.%s.%s,"
            % (_kt(name), "plurals" if name in plurals else "string", name),
        ),
    )
    lines += [
        "",
        "    fun id(english: String, name: String? = null): Int =",
        "        if (name != null) named[name] ?: 0 else ids[english] ?: 0",
        "",
        "    fun plural(one: String?, other: String, name: String? = null): Int =",
        "        if (name != null) named[name] ?: 0 else plurals[(one ?: \"\") to other] ?: 0",
        "}",
        "",
    ]
    dest = kt_out / "org/dlang/liveimap/i18n"
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "StringCatalog.kt").write_text("\n".join(lines), encoding="utf-8")
    return len(order)


def main(argv):
    if len(argv) != 2:
        raise SystemExit("usage: gen-english.py <resOut> <ktOut>")
    count = write_outputs(argv[0], argv[1])
    print("english strings", count)


if __name__ == "__main__":
    main(sys.argv[1:])
