plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// en stays res/values/strings.xml. These 87 locales are the committed i18n set.
val i18nLocales = listOf(
    "be",
    "bg",
    "bs",
    "ca",
    "cs",
    "cy",
    "da",
    "de",
    "el",
    "en-rAU",
    "en-rCA",
    "en-rGB",
    "en-rIN",
    "es",
    "es-rUS",
    "et",
    "eu",
    "fi",
    "fr",
    "fr-rCA",
    "ga",
    "gl",
    "hr",
    "hu",
    "is",
    "it",
    "lb",
    "lt",
    "lv",
    "mk",
    "mt",
    "nb",
    "nl",
    "pl",
    "pt",
    "pt-rPT",
    "ro",
    "ru",
    "sk",
    "sl",
    "sq",
    "b+sr+Latn",
    "sr",
    "sv",
    "uk",
    "hy",
    "ka",
    "az",
    "kk",
    "ky",
    "uz",
    "tr",
    "mn",
    "tg",
    "as",
    "bn",
    "gu",
    "hi",
    "kn",
    "ml",
    "mr",
    "ne",
    "or",
    "pa",
    "si",
    "ta",
    "te",
    "in",
    "km",
    "lo",
    "ms",
    "my",
    "th",
    "tl",
    "vi",
    "ja",
    "ko",
    "zh-rCN",
    "zh-rHK",
    "zh-rTW",
    "af",
    "am",
    "sw",
    "ha",
    "yo",
    "ig",
    "zu"
)

android {
    namespace = "com.davidlang.liveimap"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.davidlang.liveimap"
        minSdk = 26
        targetSdk = 36
        // Must increase on every Play upload: raise app/versionCode.txt before each ./build_app --play.
        // A missing or non-numeric file fails the build.
        versionCode = file("versionCode.txt").readText().trim().toInt()
        val rawVersion = providers.exec {
            workingDir = project.rootDir
            commandLine(
                "bash",
                "-c",
                "BRANCH_NAME=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'HEAD') && (git describe --tags --match \"${'$'}{BRANCH_NAME}-start\" 2>/dev/null || git describe --always 2>/dev/null || echo 'dev')"
            )
        }.standardOutput.asText.orNull?.trim() ?: "dev"
        versionName = rawVersion
        val gitCommit = providers.exec {
            workingDir = project.rootDir
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull?.trim().orEmpty()
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        manifestPlaceholders["gitCommit"] = gitCommit
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // One APK per device ABI (as VehicleExpenses): app-arm64-<type>.apk for the Pixels, app-x86_64-<type>.apk
    // for the emulator. Every ABI shares the versionCode from app/versionCode.txt.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
        create("armv7") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }
        // Play bundle only (./build_app --play runs bundlePlayRelease): every shipped ABI in one
        // AAB; Play delivers per-device splits. Its debug variant is disabled below.
        create("play") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a") }
        }
    }
    androidResources {
        localeFilters += listOf("en") + i18nLocales
    }
    val androidUserHome = System.getenv("ANDROID_USER_HOME")
        ?: error("ANDROID_USER_HOME unset; run ./build_app (sets worktree .android-shared)")
    signingConfigs {
        getByName("debug") {
            storeFile = file("$androidUserHome/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // F20: R8 shrinking and resource shrinking; JNI and reflection entry points in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// The play flavor is for the release bundle only; debug builds stay one APK per ABI.
androidComponents {
    beforeVariants(selector().withFlavor("abi" to "play").withBuildType("debug")) { it.enable = false }
}

// Same org/dlang FindClass literals as ProguardKeepSyncTest, plus BuildConfig, must survive arm64 R8.
tasks.register("checkArm64ReleaseKeeps") {
    dependsOn("assembleArm64Release")
    doLast {
        val usagePath = "app/build/outputs/mapping/arm64Release/usage.txt"
        val usage = rootProject.file(usagePath)
        if (!usage.isFile) {
            throw GradleException(usagePath)
        }
        val literal = Regex(""""(org/dlang/[^"]+)"""")
        val classes = file("src/main/cpp").walkTopDown()
            .filter { it.isFile && it.extension in setOf("c", "cc", "cpp", "h", "hpp") }
            .flatMap { literal.findAll(it.readText()).map { m -> m.groupValues[1].replace('/', '.') } }
            .toMutableSet()
        if (classes.isEmpty()) {
            throw GradleException("no org/dlang FindClass names in src/main/cpp")
        }
        classes.add("com.davidlang.liveimap.BuildConfig")
        // R8 prints static void <clinit>() after moving a kept $stable value into the dex
        // field. A -keep rule does not retain that method. Any other member, or the class
        // itself, is a failure.
        val bad = mutableListOf<String>()
        var header: String? = null
        var headerName: String? = null
        val members = mutableListOf<String>()
        fun flush() {
            val name = headerName ?: return
            val line = header ?: return
            if (name in classes) {
                val other = members.filter { it.trim() != "static void <clinit>()" }
                if (members.isEmpty() || other.isNotEmpty()) {
                    bad.add(line)
                    bad.addAll(other)
                }
            }
            header = null
            headerName = null
            members.clear()
        }
        for (line in usage.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            if (line[0] == ' ' || line[0] == '\t') {
                if (header != null) members.add(line)
            } else {
                flush()
                header = line
                headerName = line.trim().removeSuffix(":")
            }
        }
        flush()
        if (bad.isNotEmpty()) {
            bad.forEach { println(it) }
            throw GradleException("usage.txt lists a kept class or member")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-layout")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    // F20: secrets are sealed with tink-android (security-crypto removed; no legacy read).
    implementation("com.google.crypto.tink:tink-android:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}


// Titles and hidden group ids come from settings-tree.json. A hardLimit mismatch fails this task.
private val settingsTreePython: String = """
import hashlib, json, sys
from pathlib import Path

registry_path, tree_path, kt_root, res_root = sys.argv[1:5]
src = Path(registry_path).read_text()
mark = '"' * 3
start = src.index("const val registrySpec: String = " + mark)
start = src.index("\n", start) + 1
end = src.index("\n" + mark, start)
spec_lines = [line for line in src[start:end].splitlines() if line.strip()]

def parse_hard(text):
    if text == "":
        return None
    if text.startswith("list:"):
        bits = text[len("list:"):].split(":")
        if len(bits) != 4:
            raise SystemExit("bad list limit " + text)
        def num(s):
            return int(s) if s else None
        return {"minItems": num(bits[0]), "maxItems": num(bits[1]), "itemMin": num(bits[2]), "itemMax": num(bits[3])}
    dots = text.find("..")
    if dots <= 0:
        raise SystemExit("bad hard limit " + text)
    return {"min": int(text[:dots]), "max": int(text[dots+2:])}

registry = {}
order = []
for line in spec_lines:
    parts = line.split("|")
    if len(parts) != 7:
        raise SystemExit("bad registry line " + line)
    key, typ, scope, control, title, extra, hard = parts
    enum_values = extra.split(",") if typ == "Enum" and extra else []
    int_choices = []
    if typ == "Int" and extra and all(c.isdigit() or c == "," for c in extra):
        int_choices = [int(x) for x in extra.split(",")]
    registry[key] = {
        "type": typ, "scope": scope, "title": title, "enum": enum_values,
        "choices": int_choices, "hard": parse_hard(hard), "nullable": extra == "nullable",
    }
    order.append(key)

def limit_text(limit):
    if not limit:
        return "none"
    names = ["min", "max", "minItems", "maxItems", "itemMin", "itemMax"]
    parts = []
    for name in names:
        if limit.get(name) is not None:
            parts.append('"%s": %s' % (name, limit[name]))
    if not parts:
        return "none"
    return "{" + ", ".join(parts) + "}"

def hard_message(key, leaf_text, reg_text):
    return (
        "settings-tree.json leaf `%s` has hardLimit %s but SettingsRegistry limits it to %s. "
        "hardLimit is documentation of the registry limit and the build never writes it. "
        "The fix is to edit the JSON or the registry. "
        "If the registry limit is right, set the leaf's hardLimit to match it; if the limit should change, "
        "change SettingsRegistry.kt and settings-tree.json together, and keep the leaf's min/max and default inside the new limit."
    ) % (key, leaf_text, reg_text)

tree_bytes = Path(tree_path).read_bytes()
tree = json.loads(tree_bytes)
errors = []
seen = set()
groups = []
leaves = {}

def as_limit(obj, key):
    if not isinstance(obj, dict):
        errors.append("settings-tree.json leaf %s hardLimit" % key)
        return {}
    out = {}
    for name in ("min", "max", "minItems", "maxItems", "itemMin", "itemMax"):
        if name in obj:
            if not isinstance(obj[name], int) or isinstance(obj[name], bool):
                errors.append("settings-tree.json leaf %s hardLimit" % key)
            else:
                out[name] = obj[name]
    return out

def walk(node):
    if not isinstance(node, dict):
        errors.append("settings-tree.json node")
        return
    if "setting" in node and "children" in node:
        errors.append("settings-tree.json node has setting and children")
        return
    if "children" in node:
        title = node.get("title")
        if not isinstance(title, str) or not title:
            errors.append("settings-tree.json group title")
            return
        children = node["children"]
        if not isinstance(children, list) or not children:
            errors.append("settings-tree.json group %s has no children" % title)
            return
        groups.append(title)
        for child in children:
            walk(child)
        return
    key = node.get("setting")
    if not isinstance(key, str) or not key:
        errors.append("settings-tree.json leaf setting")
        return
    if "default" not in node:
        errors.append("settings-tree.json leaf %s default" % key)
        return
    spec = registry.get(key)
    if spec is None:
        errors.append("settings-tree.json unknown setting %s" % key)
        return
    if key in seen:
        errors.append("settings-tree.json duplicate setting %s" % key)
    seen.add(key)
    leaves[key] = node
    numeric = spec["type"] in ("Int", "Long")
    listing = spec["type"] in ("StringList", "IntList")
    if not numeric and ("min" in node or "max" in node):
        errors.append("settings-tree.json leaf %s has min or max" % key)
    if spec["type"] not in ("Enum", "Int") and "choices" in node:
        errors.append("settings-tree.json leaf %s has choices" % key)
    if not listing and any(n in node for n in ("minItems", "maxItems", "itemMin", "itemMax", "order")):
        errors.append("settings-tree.json leaf %s has a list constraint" % key)
    if spec["type"] == "StringList" and any(n in node for n in ("itemMin", "itemMax", "order")):
        errors.append("settings-tree.json leaf %s has a list constraint" % key)
    if "min" in node and "max" in node and node["min"] > node["max"]:
        errors.append("settings-tree.json leaf %s min above max" % key)
    if "choices" in node and not node["choices"]:
        errors.append("settings-tree.json leaf %s empty choices" % key)
    if "order" in node and node["order"] not in ("nondecreasing", "increasing", "any"):
        errors.append("settings-tree.json leaf %s order" % key)
    leaf_limit = as_limit(node["hardLimit"], key) if "hardLimit" in node else None
    reg_limit = spec["hard"]
    if limit_text(leaf_limit) != limit_text(reg_limit):
        errors.append(hard_message(key, limit_text(leaf_limit), limit_text(reg_limit)))
    hard = spec["hard"] or {}
    for edge, bound, cmp in (
        ("min", "min", lambda a, b: a < b),
        ("max", "max", lambda a, b: a > b),
        ("minItems", "minItems", lambda a, b: a < b),
        ("maxItems", "maxItems", lambda a, b: a > b),
        ("itemMin", "itemMin", lambda a, b: a < b),
        ("itemMax", "itemMax", lambda a, b: a > b),
    ):
        if edge in node and bound in hard and cmp(node[edge], hard[bound]):
            errors.append("settings-tree.json leaf %s %s outside hard limit" % (key, edge))
    if spec["type"] == "Enum" and "choices" in node:
        for choice in node["choices"]:
            if choice not in spec["enum"]:
                errors.append("settings-tree.json leaf %s choice %s" % (key, choice))
    if spec["type"] == "Int" and "choices" in node:
        want = spec["choices"]
        got = node["choices"]
        if got != want:
            errors.append("settings-tree.json leaf %s choices" % key)
    default = node["default"]
    typ = spec["type"]
    ok = True
    if typ == "Bool":
        ok = isinstance(default, bool)
    elif typ in ("Int", "Long"):
        ok = isinstance(default, int) and not isinstance(default, bool)
        if ok:
            if "min" in node and default < node["min"]:
                ok = False
            if "max" in node and default > node["max"]:
                ok = False
            if hard.get("min") is not None and default < hard["min"]:
                ok = False
            if hard.get("max") is not None and default > hard["max"]:
                ok = False
            if "choices" in node and default not in node["choices"]:
                ok = False
    elif typ in ("String", "Encoded"):
        ok = isinstance(default, str)
    elif typ == "Enum":
        ok = isinstance(default, str) and default in spec["enum"]
        if ok and "choices" in node and default not in node["choices"]:
            ok = False
    elif typ == "StringList":
        ok = isinstance(default, list) and all(isinstance(x, str) for x in default)
    elif typ == "IntList":
        ok = isinstance(default, list) and all(isinstance(x, int) and not isinstance(x, bool) for x in default)
    if not ok:
        errors.append("settings-tree.json leaf %s default" % key)

if not isinstance(tree, list):
    errors.append("settings-tree.json root")
else:
    for item in tree:
        walk(item)
for key, spec in registry.items():
    if spec["scope"] != "global" and key not in seen:
        errors.append("settings-tree.json missing setting %s" % key)
if errors:
    raise SystemExit("\n".join(errors))

def leaf_default(key):
    node = leaves[key]
    return node["default"]

fingerprint = hashlib.sha256(tree_bytes).hexdigest()
kt_dir = Path(kt_root) / "org" / "dlang" / "liveimap" / "settings"
res_dir = Path(res_root) / "values"
kt_dir.mkdir(parents=True, exist_ok=True)
res_dir.mkdir(parents=True, exist_ok=True)

def xml_escape(text):
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "\\'"))

strings = []
title_cases = []
for i, title in enumerate(groups):
    name = "settings_tree_g%d_title" % i
    strings.append('    <string name="%s">%s</string>' % (name, xml_escape(title)))
    title_cases.append('        "g%d" -> R.string.%s' % (i, name))
reg_cases = []
for key in order:
    name = "settings_reg_%s_title" % key
    strings.append('    <string name="%s">%s</string>' % (name, xml_escape(registry[key]["title"])))
    reg_cases.append('        "%s" -> R.string.%s' % (key, name))
warm_titles = "\n".join("        n += titleOf(\"g%d\")" % i for i in range(len(groups)))
warm_regs = "\n".join('        n += regTitle("%s")' % key for key in order)
ids = ", ".join('"g%d"' % i for i in range(len(groups)))

def kt_string(value):
    return '"' + str(value).replace("\\", "\\\\").replace('"', '\\"') + '"'

defaults = []
for key, const_type in (
    ("imapPort", "Int"),
    ("smtpPort", "Int"),
    ("tlsMode", "String"),
    ("multiPane", "String"),
    ("folderPane", "String"),
    ("paneArrangement", "String"),
    ("previewSize", "String"),
    ("folderPosition", "String"),
):
    value = leaf_default(key)
    if const_type == "Int":
        rendered = str(int(value))
    else:
        rendered = kt_string(value)
    defaults.append("    const val %s: %s = %s" % (key, const_type, rendered))

kt = "\n".join([
    "package org.dlang.liveimap.settings",
    "",
    "import com.davidlang.liveimap.R",
    "",
    "object SettingsTreeGenerated {",
    '    const val fingerprint: String = "%s"' % fingerprint,
    "    val groupIds: List<String> = listOf(%s)" % ids,
    "",
    "    fun titleOf(id: String): Int = when (id) {",
    "\n".join(title_cases),
    "        else -> 0",
    "    }",
    "",
    "    fun regTitle(key: String): Int = when (key) {",
    "\n".join(reg_cases),
    "        else -> 0",
    "    }",
    "",
    "    fun warm(): Int {",
    "        var n = 0",
    warm_titles,
    warm_regs,
    "        return n",
    "    }",
    "}",
    "",
    "object SettingsDefaults {",
    "\n".join(defaults),
    "}",
    "",
])
(kt_dir / "SettingsTreeGenerated.kt").write_text(kt)
xml = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "\n".join(strings) + "\n</resources>\n"
(res_dir / "settings_tree_strings.xml").write_text(xml)
print("groups", len(groups), "leaves", len(seen), "fingerprint", fingerprint)
"""

abstract class GenerateSettingsTreeTask : DefaultTask() {
    @get:InputFile
    abstract val registryFile: RegularFileProperty

    @get:InputFile
    abstract val treeFile: RegularFileProperty

    @get:Input
    abstract val python: Property<String>

    @get:OutputDirectory
    abstract val ktOut: DirectoryProperty

    @get:OutputDirectory
    abstract val resOut: DirectoryProperty

    @TaskAction
    fun generate() {
        val script = temporaryDir.resolve("gen-settings-tree.py")
        script.writeText(python.get())
        val proc = ProcessBuilder(
            "python3",
            script.absolutePath,
            registryFile.get().asFile.absolutePath,
            treeFile.get().asFile.absolutePath,
            ktOut.get().asFile.absolutePath,
            resOut.get().asFile.absolutePath,
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw GradleException(output.ifBlank { "generateSettingsTree failed" })
    }
}

val generateSettingsTree = tasks.register<GenerateSettingsTreeTask>("generateSettingsTree") {
    registryFile.set(file("src/main/kotlin/org/dlang/liveimap/settings/SettingsRegistry.kt"))
    treeFile.set(file("src/main/assets/settings-tree.json"))
    python.set(settingsTreePython)
    ktOut.set(layout.buildDirectory.dir("generated/settingsTree"))
    resOut.set(layout.buildDirectory.dir("generated/settingsTreeRes"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateSettingsTree) { it.ktOut }
        variant.sources.res?.addGeneratedSourceDirectory(generateSettingsTree) { it.resOut }
    }
}

// Checks app/src/main/i18n and writes values-<locale>/strings.xml. No network and no translator.
private val i18nPython: String = """

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict
from pathlib import Path

import icu

TARGET = (
    "be bg bs ca cs cy da de el en-rAU en-rCA en-rGB en-rIN es es-rUS et eu fi "
    "fr fr-rCA ga gl hr hu is it lb lt lv mk mt nb nl pl pt pt-rPT ro ru sk sl sq "
    "b+sr+Latn sr sv uk hy ka az kk ky uz tr mn tg "
    "as bn gu hi kn ml mr ne or pa si ta te "
    "in km lo ms my th tl vi "
    "ja ko zh-rCN zh-rHK zh-rTW "
    "af am sw ha yo ig zu"
).split()

Q_ORDER = ("zero", "one", "two", "few", "many", "other")
PH_RE = re.compile(r"%%|%(?:\d+\$)?[#+ 0,\-]*\d*(?:\.\d+)?[a-zA-Z]|%")


def android_unescape(text):
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


def android_escape(text):
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


def placeholders(text):
    return sorted(PH_RE.findall(text))


def language_tag(qual):
    if qual == "b+sr+Latn":
        return "sr-Latn"
    if qual == "in":
        return "id"
    if "-r" in qual:
        lang, region = qual.split("-r", 1)
        return lang + "-" + region
    return qual


def plural_keywords(qual):
    loc = icu.Locale.forLanguageTag(language_tag(qual))
    rules = icu.PluralRules.forLocale(loc)
    found = [kw for kw in rules.getKeywords() if kw]
    return [q for q in Q_ORDER if q in found]


def show(value):
    if value is None:
        return ""
    if isinstance(value, dict):
        ordered = OrderedDict()
        for q in Q_ORDER:
            if q in value:
                ordered[q] = value[q]
        for q in sorted(value):
            if q not in ordered:
                ordered[q] = value[q]
        return json.dumps(ordered, ensure_ascii=False)
    return str(value)


def load_source(path):
    root = ET.parse(path).getroot()
    items = []
    by_key = {}
    for el in root:
        name = el.attrib.get("name")
        if el.tag == "string":
            value = android_unescape(el.text)
            items.append((name, value))
            by_key[name] = value
        elif el.tag == "plurals":
            quant = OrderedDict()
            for item in el:
                quant[item.attrib.get("quantity")] = android_unescape(item.text)
            items.append((name, quant))
            by_key[name] = quant
    return items, by_key


def read_json(path, issues, locale):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        issues.append({
            "key": locale or path.name,
            "why": "missing",
            "old": "",
            "new": "",
            "locale": locale or path.name,
            "file": str(path),
        })
        issues[-1]["why"] = "unreadable: " + str(exc).splitlines()[0]
        return None
    if not isinstance(data, list):
        issues.append({
            "key": locale or path.name,
            "why": "extra",
            "old": "",
            "new": "",
            "locale": locale or path.name,
            "file": rel_file(path),
        })
        return None
    return data


def rel_file(path):
    path = Path(path)
    parts = path.parts
    if "i18n" in parts:
        i = parts.index("i18n")
        return "app/src/main/" + "/".join(parts[i:])
    if path.name == "build.gradle.kts":
        return "app/build.gradle.kts"
    return str(path)


def add(issues, key, why, old, new, locale, file):
    issues.append({
        "key": key,
        "why": why,
        "old": show(old) if not isinstance(old, str) else old,
        "new": show(new) if not isinstance(new, str) else new,
        "locale": locale,
        "file": file,
    })


def as_text_map(value, plural):
    if plural:
        if isinstance(value, dict):
            return value
        return None
    if isinstance(value, str):
        return value
    return None


def same_source(stored, current):
    if isinstance(current, dict):
        if not isinstance(stored, dict):
            return False
        if set(stored) != set(current):
            return False
        return all(stored[k] == current[k] for k in current)
    return isinstance(stored, str) and stored == current


def check_texts(issues, key, text, current, locale, file, plural, required, old):
    if plural:
        got = text if isinstance(text, dict) else {}
        for quantity in required:
            if quantity not in got or not isinstance(got.get(quantity), str):
                add(issues, key, "missing plural quantity: " + quantity, old, current, locale, file)
                continue
            en_text = current[quantity] if quantity in current else current.get("other", "")
            if placeholders(got[quantity]) != placeholders(en_text):
                add(issues, key, "placeholder mismatch", old, current, locale, file)
    else:
        if not isinstance(text, str):
            add(issues, key, "placeholder mismatch", old, current, locale, file)
            return
        if placeholders(text) != placeholders(current):
            add(issues, key, "placeholder mismatch", old, current, locale, file)


def entries_by_key(data, issues, locale, file):
    found = {}
    for entry in data:
        if not isinstance(entry, dict) or not isinstance(entry.get("key"), str):
            add(issues, locale, "extra", "", "", locale, file)
            continue
        key = entry["key"]
        if key in found:
            add(issues, key, "duplicate", "", "", locale, file)
            continue
        found[key] = entry
    return found


def check(strings_path, i18n_dir, locale_filters, target):
    i18n_dir = Path(i18n_dir)
    issues = []
    items, by_key = load_source(strings_path)
    filters = list(locale_filters)
    target = list(target)
    expected = ["en"] + [loc for loc in target if loc != "en"]
    missing_filters = [loc for loc in expected if loc not in filters]
    extra_filters = [loc for loc in filters if loc not in expected]
    for loc in missing_filters:
        add(issues, loc, "locale missing from localeFilters", "", "", loc, "app/build.gradle.kts")
    for loc in extra_filters:
        add(issues, loc, "extra", "", "", loc, "app/build.gradle.kts")
    if len(filters) != len(set(filters)):
        add(issues, "localeFilters", "duplicate", "", "", "en", "app/build.gradle.kts")

    keywords = {loc: plural_keywords(loc) for loc in target}
    machines = {}
    overrides = {}
    if not i18n_dir.is_dir():
        add(issues, "i18n", "missing", "", "", "en", "app/src/main/i18n")
        return issues, {}, 0
    for path in sorted(i18n_dir.iterdir()):
        if not path.is_file():
            add(issues, path.name, "extra", "", "", path.name, rel_file(path))
            continue
        name = path.name
        if name.endswith(".override.json"):
            loc = name[: -len(".override.json")]
            if loc not in target:
                add(issues, loc, "extra", "", "", loc, rel_file(path))
                continue
            data = read_json(path, issues, loc)
            if data is not None:
                overrides[loc] = (path, data)
        elif name.endswith(".json"):
            loc = name[: -len(".json")]
            if loc not in target:
                add(issues, loc, "extra", "", "", loc, rel_file(path))
                continue
            data = read_json(path, issues, loc)
            if data is not None:
                machines[loc] = (path, data)
        else:
            add(issues, name, "extra", "", "", name, rel_file(path))

    generated = {}
    for loc in target:
        file = "app/src/main/i18n/" + loc + ".json"
        pair = machines.get(loc)
        if pair is None:
            for key, current in items:
                add(issues, key, "missing", "", current, loc, file)
            continue
        path, data = pair
        found = entries_by_key(data, issues, loc, file)
        for key in found:
            if key not in by_key:
                stored = found[key].get("source", "")
                add(issues, key, "extra", stored if isinstance(stored, (str, dict)) else "", "", loc, file)
        override_file = "app/src/main/i18n/" + loc + ".override.json"
        override_found = {}
        if loc in overrides:
            opath, odata = overrides[loc]
            override_found = entries_by_key(odata, issues, loc, override_file)
            for key in override_found:
                if key not in by_key:
                    add(issues, key, "extra", "", "", loc, override_file)
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
        required = keywords[loc]
        expect = []
        for key, current in items:
            plural = isinstance(current, dict)
            entry = found.get(key)
            if entry is None:
                add(issues, key, "missing", "", current, loc, file)
                chosen = None
                old = ""
            else:
                if "source" not in entry or entry["source"] is None:
                    add(issues, key, "no source", "", current, loc, file)
                    old = ""
                else:
                    old = entry["source"]
                    if not same_source(old, current):
                        add(issues, key, "changed", old, current, loc, file)
                text = entry.get("text")
                check_texts(issues, key, text, current, loc, file, plural, required, old)
                chosen = text
                if key in override_found:
                    otext = override_found[key].get("text")
                    check_texts(issues, key, otext, current, loc, override_file, plural, required, old)
                    chosen = otext
            if plural:
                got = chosen if isinstance(chosen, dict) else {}
                emitted = OrderedDict()
                lines.append('    <plurals name="%s">' % key)
                for quantity in required:
                    value = got.get(quantity, "")
                    if not isinstance(value, str):
                        value = ""
                    emitted[quantity] = value
                    lines.append('        <item quantity="%s">%s</item>' % (quantity, android_escape(value)))
                lines.append("    </plurals>")
                expect.append((key, emitted))
            else:
                value = chosen if isinstance(chosen, str) else ""
                lines.append('    <string name="%s">%s</string>' % (key, android_escape(value)))
                expect.append((key, value))
        lines.append("</resources>")
        xml = "\n".join(lines) + "\n"
        parsed = round_trip(xml)
        if len(parsed) != len(expect):
            add(issues, loc, "changed", "", "", loc, file)
        else:
            for (name, value), (want_name, want_value) in zip(parsed, expect):
                if name != want_name or value != want_value:
                    add(issues, want_name, "changed", show(value), show(want_value), loc, file)
                    break
        generated[loc] = xml
    return issues, generated, len(items)


def format_issues(issues):
    groups = OrderedDict()
    for item in issues:
        key = (item["key"], item["why"], item["old"], item["new"])
        bucket = groups.get(key)
        if bucket is None:
            bucket = {"locales": [], "files": []}
            groups[key] = bucket
        if item["locale"] not in bucket["locales"]:
            bucket["locales"].append(item["locale"])
        if item["file"] not in bucket["files"]:
            bucket["files"].append(item["file"])
    lines = ["Regenerate the listed Android string translations.", ""]
    for (key, why, old, new), bucket in groups.items():
        lines.append("key: " + key)
        lines.append("why: " + why)
        lines.append("old: " + old)
        lines.append("new: " + new)
        lines.append("locales: " + ", ".join(bucket["locales"]))
        lines.append("files: " + ", ".join(bucket["files"]))
        lines.append("")
    lines.append("Rules:")
    lines.append("- keep every placeholder and format argument exactly as in the en-US text")
    lines.append("- set each source to the new en-US text, exactly")
    lines.append("- delete extra entries")
    lines.append("- change no other entry")
    lines.append("- never edit an .override.json file")
    return "\n".join(lines) + "\n"


def round_trip(xml_text):
    root = ET.fromstring(xml_text)
    seen = []
    for el in root:
        if el.tag == "string":
            seen.append((el.attrib["name"], android_unescape(el.text)))
        else:
            quant = OrderedDict()
            for item in el:
                quant[item.attrib["quantity"]] = android_unescape(item.text)
            seen.append((el.attrib["name"], quant))
    return seen


def main(argv):
    if len(argv) != 5:
        print("usage: i18n_check.py strings.xml i18n_dir out_dir locale,filters", file=sys.stderr)
        return 2
    strings_path, i18n_dir, out_dir, filter_arg = argv[1:]
    filters = [part for part in filter_arg.split(",") if part]
    issues, generated, string_count = check(strings_path, i18n_dir, filters, TARGET)
    if len(TARGET) != 87:
        issues.append({
            "key": "locales",
            "why": "missing",
            "old": "",
            "new": "",
            "locale": "en",
            "file": "app/build.gradle.kts",
        })
    if issues:
        sys.stdout.write(format_issues(issues))
        return 1
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for loc, xml_text in generated.items():
        folder = out / ("values-" + loc)
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "strings.xml").write_text(xml_text, encoding="utf-8")
    print("i18n ok: %d locales, %d strings" % (len(generated), string_count))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
"""

abstract class GenerateI18nTask : DefaultTask() {
    @get:InputFile
    abstract val stringsFile: RegularFileProperty

    @get:InputDirectory
    abstract val i18nDir: DirectoryProperty

    @get:Input
    abstract val python: Property<String>

    @get:Input
    abstract val localeFilters: ListProperty<String>

    @get:OutputDirectory
    abstract val resOut: DirectoryProperty

    @TaskAction
    fun generate() {
        val script = temporaryDir.resolve("i18n_check.py")
        script.writeText(python.get())
        val proc = ProcessBuilder(
            "python3",
            script.absolutePath,
            stringsFile.get().asFile.absolutePath,
            i18nDir.get().asFile.absolutePath,
            resOut.get().asFile.absolutePath,
            localeFilters.get().joinToString(","),
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw GradleException(output.ifBlank { "generateAppI18n failed" })
        if (output.isNotBlank()) println(output.trim())
    }
}

val generateAppI18n = tasks.register<GenerateI18nTask>("generateAppI18n") {
    stringsFile.set(file("src/main/res/values/strings.xml"))
    i18nDir.set(file("src/main/i18n"))
    python.set(i18nPython)
    localeFilters.set(listOf("en") + i18nLocales)
    resOut.set(layout.buildDirectory.dir("generated/i18n/res"))
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generateAppI18n) }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateAppI18n) { it.resOut }
    }
}
