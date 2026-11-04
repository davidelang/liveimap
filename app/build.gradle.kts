plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// en stays res/values/strings.xml. These 106 locales are the committed i18n set.
val i18nLocales = listOf(
    "af",
    "am",
    "as",
    "az",
    "b+ceb",
    "b+en+001",
    "b+es+419",
    "b+sr+Latn",
    "be",
    "bg",
    "bn",
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
    "fo",
    "fr",
    "fr-rCA",
    "fy",
    "ga",
    "gd",
    "gl",
    "gu",
    "ha",
    "hi",
    "hr",
    "hu",
    "hy",
    "ig",
    "in",
    "is",
    "it",
    "ja",
    "jv",
    "ka",
    "kk",
    "km",
    "kn",
    "ko",
    "ku",
    "ky",
    "lb",
    "lo",
    "lt",
    "lv",
    "mg",
    "mi",
    "mk",
    "ml",
    "mn",
    "mr",
    "ms",
    "mt",
    "my",
    "nb",
    "ne",
    "nl",
    "nn",
    "or",
    "pa",
    "pl",
    "pt",
    "pt-rPT",
    "qu",
    "ro",
    "rm",
    "ru",
    "rw",
    "si",
    "sk",
    "sl",
    "so",
    "sq",
    "sr",
    "su",
    "sv",
    "sw",
    "ta",
    "te",
    "tg",
    "th",
    "tk",
    "tl",
    "tr",
    "tt",
    "uk",
    "uz",
    "vi",
    "xh",
    "yo",
    "zh-rCN",
    "zh-rHK",
    "zh-rTW",
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
        generateLocaleConfig = true
        // Replaces the default pattern. settings-tree.json stays a build input and is not in the APK.
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!settings-tree.json"
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
    sourceSets {
        getByName("arm64").kotlin.directories.add("src/sideload/kotlin")
        getByName("x86_64").kotlin.directories.add("src/sideload/kotlin")
        getByName("armv7").kotlin.directories.add("src/sideload/kotlin")
    }
}

// The play flavor is for the release bundle only; debug builds stay one APK per ABI.
androidComponents {
    beforeVariants(selector().withFlavor("abi" to "play").withBuildType("debug")) { it.enable = false }
}

// org/dlang FindClass literals, plus BuildConfig, must survive arm64 R8.
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
    implementation("androidx.credentials:credentials:1.3.0")
    add("playImplementation", "com.google.android.gms:play-services-auth-blockstore:16.4.0")
    add("playImplementation", "androidx.credentials:credentials-play-services-auth:1.3.0")
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


// AGP packages this directory, not src/main/assets. ignoreAssetsPattern still applies to it.
val packagedAssetsDir = layout.buildDirectory.dir("generated/packagedAssets")

abstract class StagePackagedAssetsTask : DefaultTask() {
    @get:InputFile
    abstract val scriptFile: RegularFileProperty

    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val pb = ProcessBuilder(
            "python3",
            scriptFile.get().asFile.absolutePath,
            "stage",
            sourceDir.get().asFile.absolutePath,
            outDir.get().asFile.absolutePath,
        )
        pb.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw GradleException(output.ifBlank { "stagePackagedAssets failed" })
        if (output.isNotBlank()) println(output.trim())
    }
}

val stagePackagedAssets = tasks.register<StagePackagedAssetsTask>("stagePackagedAssets") {
    scriptFile.set(file("packaged-assets.py"))
    sourceDir.set(file("src/main/assets"))
    outDir.set(packagedAssetsDir)
}

// src/main/assets is not an asset source. The staged directory is added below as a
// generated source so every reader, including Play lint, depends on stagePackagedAssets.
android.sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(stagePackagedAssets)
    }
}

// The settings tree is the only source. app/gen-settings-tree.py emits defaults, R.string ids, and SettingsAccessGenerated.kt.
val generateSettingsTree = tasks.register<liveimap.GenerateSettingsTreeTask>("generateSettingsTree") {
    treeFile.set(file("src/main/assets/settings-tree.json"))
    scriptFile.set(file("gen-settings-tree.py"))
    templateFile.set(file("gen-account-template.kt"))
    ktOut.set(layout.buildDirectory.dir("generated/settingsTree"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stagePackagedAssets) { it.outDir }
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateSettingsTree) { it.ktOut }
    }
}

// Checks app/src/main/i18n and writes values-<locale>/strings.xml.
// Outstanding strings go to Grok once per non-English locale. Any other source mismatch fails the task.
private val i18nPython: String = """

import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict
from pathlib import Path

import icu

TARGET = (
    "af am as az b+ceb b+en+001 b+es+419 b+sr+Latn be bg bn bs ca cs cy da de el "
    "en-rAU en-rCA en-rGB en-rIN es es-rUS et eu fi fo fr fr-rCA fy ga gd gl gu ha "
    "hi hr hu hy ig in is it ja jv ka kk km kn ko ku ky lb lo lt lv mg mi mk ml mn "
    "mr ms mt my nb ne nl nn or pa pl pt pt-rPT qu ro rm ru rw si sk sl so sq sr su "
    "sv sw ta te tg th tk tl tr tt uk uz vi xh yo zh-rCN zh-rHK zh-rTW zu"
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
    if qual == "b+ceb":
        return "ceb"
    if qual == "b+en+001":
        return "en-001"
    if qual == "b+es+419":
        return "es-419"
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


def placeholder_lists(got, expected):
    got_list = placeholders(got) if isinstance(got, str) else []
    expected_list = placeholders(expected) if isinstance(expected, str) else []
    return "placeholder mismatch: %s vs %s" % (got_list, expected_list)


def check_texts(issues, key, text, current, locale, file, plural, required, old):
    if plural:
        got = text if isinstance(text, dict) else {}
        for quantity in required:
            if quantity not in got or not isinstance(got.get(quantity), str):
                add(issues, key, "missing plural quantity: " + quantity, old, current, locale, file)
                continue
            en_text = current[quantity] if quantity in current else current.get("other", "")
            if placeholders(got[quantity]) != placeholders(en_text):
                add(issues, key, placeholder_lists(got[quantity], en_text), old, current, locale, file)
    else:
        if not isinstance(text, str):
            add(issues, key, placeholder_lists("", current if isinstance(current, str) else ""), old, current, locale, file)
            return
        if placeholders(text) != placeholders(current):
            add(issues, key, placeholder_lists(text, current), old, current, locale, file)


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


def load_settings_gen(path):
    import importlib.util
    spec = importlib.util.spec_from_file_location("settings_tree_gen", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def check(strings_path, i18n_dir, locale_filters, target, gen=None, display_names=None):
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
        return issues, {}, 0, ""
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

    display_names = set(display_names or [])
    grok_note = ""
    if gen is not None:
        grok_note = gen.refresh_display_translations(
            machines,
            items,
            display_names,
            keywords,
            {loc: language_tag(loc) for loc in target},
        ) or ""

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
                if entry.get("pending") is not True:
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
    return issues, generated, len(items), grok_note


def issue_reason(key, why):
    if why == "changed":
        return "source text differs for %s" % key
    if why == "missing":
        return "key missing: %s" % key
    if why == "extra":
        return "extra key: %s" % key
    if why.startswith("placeholder mismatch"):
        detail = why[len("placeholder mismatch"):].lstrip(": ")
        if detail:
            return "placeholder mismatch for %s: %s" % (key, detail)
        return "placeholder mismatch for %s" % key
    return "%s for %s" % (why, key)


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
    first = None
    lines = []
    for (key, why, old, new), bucket in groups.items():
        reason = issue_reason(key, why)
        if first is None:
            first = reason
        lines.append("key: " + key)
        lines.append("why: " + reason)
        lines.append("old: " + old)
        lines.append("new: " + new)
        lines.append("locales: " + ", ".join(bucket["locales"]))
        lines.append("files: " + ", ".join(bucket["files"]))
        lines.append("")
    lines = [first or "translation check failed", ""] + lines
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


def clear_generated_locales(out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for child in list(out.iterdir()):
        if child.is_dir() and child.name.startswith("values-"):
            shutil.rmtree(child)


def main(argv):
    english_only = "--english-only" in argv[1:]
    args = [argv[0]] + [arg for arg in argv[1:] if arg != "--english-only"]
    if len(args) not in (5, 8):
        print(
            "usage: i18n_check.py strings.xml i18n_dir out_dir locale,filters [tree.json bindings.json gen.py]",
            file=sys.stderr,
        )
        return 2
    strings_path, i18n_dir, out_dir, filter_arg = args[1:5]
    gen = None
    display_names = set()
    if len(args) == 8:
        tree_path, binding_path, gen_path = args[5:]
        gen = load_settings_gen(gen_path)
        display_names = gen.align_display_english(tree_path, strings_path, binding_path)
    if english_only:
        # Settings-tree failures already raised. No Grok, no locale writes, no stale-source failure.
        clear_generated_locales(out_dir)
        print("i18n english-only: translations skipped")
        return 0
    filters = [part for part in filter_arg.split(",") if part]
    issues, generated, string_count, grok_note = check(
        strings_path, i18n_dir, filters, TARGET, gen, display_names
    )
    if grok_note:
        print(grok_note)
    if len(TARGET) != 106:
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

    @get:InputFile
    abstract val treeFile: RegularFileProperty

    @get:InputFile
    abstract val bindingFile: RegularFileProperty

    @get:InputFile
    abstract val settingsScript: RegularFileProperty

    @get:Input
    abstract val englishOnly: Property<Boolean>

    @get:OutputDirectory
    abstract val resOut: DirectoryProperty

    @TaskAction
    fun generate() {
        val script = temporaryDir.resolve("i18n_check.py")
        script.writeText(python.get())
        val command = mutableListOf(
            "python3",
            script.absolutePath,
            stringsFile.get().asFile.absolutePath,
            i18nDir.get().asFile.absolutePath,
            resOut.get().asFile.absolutePath,
            localeFilters.get().joinToString(","),
            treeFile.get().asFile.absolutePath,
            bindingFile.get().asFile.absolutePath,
            settingsScript.get().asFile.absolutePath,
        )
        if (englishOnly.get()) command.add("--english-only")
        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw GradleException(output.ifBlank { "generateAppI18n failed" })
        if (output.isNotBlank()) println(output.trim())
    }
}

// A settings-tree display sentence keeps its resource name. When its English changes, every locale source is updated and that sentence joins the locale batch. Outstanding strings in a non-English locale go in one Grok request. An .override.json file is not edited. A reply equal to the source is not sent again. If Grok cannot run, the previous text stays, pending is set, and the build finishes. A source mismatch for a string that is not part of that batch still fails the task. Any other strings.xml add, change, or remove still has to be translated by the coder in the same change.
val generateAppI18n = tasks.register<GenerateI18nTask>("generateAppI18n") {
    stringsFile.set(file("src/main/res/values/strings.xml"))
    i18nDir.set(file("src/main/i18n"))
    python.set(i18nPython)
    localeFilters.set(listOf("en") + i18nLocales)
    treeFile.set(file("src/main/assets/settings-tree.json"))
    bindingFile.set(file("settings-display-bindings.json"))
    settingsScript.set(file("gen-settings-tree.py"))
    englishOnly.set(project.providers.gradleProperty("liveimap.englishOnly").map { true }.orElse(false))
    resOut.set(layout.buildDirectory.dir("generated/i18n/res"))
}

generateSettingsTree.configure { dependsOn(generateAppI18n) }

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(generateAppI18n) }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateAppI18n) { it.resOut }
    }
}
