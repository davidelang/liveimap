plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

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
        // Play bundle only (./build_app --play runs bundlePlayRelease): every shipped ABI in one
        // AAB; Play delivers per-device splits. Its debug variant is disabled below.
        create("play") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }
    androidResources {
        localeFilters += listOf("en")
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
    sourceSets.getByName("main") {
        java.srcDir(layout.buildDirectory.dir("generated/settingsTree"))
        res.srcDir(layout.buildDirectory.dir("generated/settingsTreeRes"))
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

tasks.register("generateSettingsTree") {
    val registryFile = file("src/main/kotlin/org/dlang/liveimap/settings/SettingsRegistry.kt")
    val treeFile = file("src/main/assets/settings-tree.json")
    inputs.files(registryFile, treeFile)
    val ktOut = layout.buildDirectory.dir("generated/settingsTree")
    val resOut = layout.buildDirectory.dir("generated/settingsTreeRes")
    outputs.dir(ktOut)
    outputs.dir(resOut)
    doLast {
        val script = layout.buildDirectory.file("generated/gen-settings-tree.py").get().asFile
        script.parentFile.mkdirs()
        script.writeText(settingsTreePython)
        val proc = ProcessBuilder(
            "python3",
            script.absolutePath,
            registryFile.absolutePath,
            treeFile.absolutePath,
            ktOut.get().asFile.absolutePath,
            resOut.get().asFile.absolutePath,
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw GradleException(output.ifBlank { "generateSettingsTree failed" })
    }
}

tasks.configureEach {
    if (name == "preBuild" || (name.startsWith("pre") && name.endsWith("Build"))) {
        dependsOn("generateSettingsTree")
    }
    if (name.startsWith("compile") && name.contains("Kotlin")) {
        dependsOn("generateSettingsTree")
    }
    if (name.startsWith("process") && name.endsWith("Resources")) {
        dependsOn("generateSettingsTree")
    }
}
