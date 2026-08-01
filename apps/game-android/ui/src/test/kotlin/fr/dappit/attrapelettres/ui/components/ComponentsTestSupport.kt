package fr.dappit.attrapelettres.ui.components

import java.io.File

// Shared scaffolding for the :ui component suites.
//
// Two of the behaviours ported here are invisible to any assertion that does not
// actually DRAW — z-order and "which view casts the shadow". A host JUnit run
// cannot rasterise (no Robolectric, no compose-ui-test, by decision), so the
// half that IS reachable is the source itself: the modifier is either in the
// file or it is not. Same tool, and same reason, as :core's
// `LicensingSourceScanTest` and :ui's `TouchDownSourceScanTest`.

/** The :ui main source root, found by walking up from the test's working dir. */
internal fun findUiMain(): File? {
    val suffix = "src/main/kotlin/fr/dappit/attrapelettres/ui"
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        val candidates = listOf(
            File(dir, suffix),
            File(dir, "ui/$suffix"),
            File(dir, "apps/game-android/ui/$suffix"),
        )
        for (candidate in candidates) {
            if (candidate.isDirectory) return candidate
        }
        dir = dir.parentFile
    }
    return null
}

internal val uiMainOrNull: File? = findUiMain()

/** Every Kotlin source in :ui's main tree. */
internal fun uiSources(): List<File> =
    uiMainOrNull?.walkTopDown()?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()

/** Every Kotlin source in `ui/components`. */
internal fun componentSources(): List<File> =
    uiMainOrNull?.let { File(it, "components") }
        ?.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }
        ?.toList()
        .orEmpty()

internal fun componentSource(name: String): File? = componentSources().firstOrNull { it.name == name }

/**
 * Lines with comments removed, so a scan matches CODE and not the paragraph that
 * explains why the code does not do that. Copied in spirit from
 * `TouchDownTest`'s helper of the same name — the two suites cannot share a
 * private, and duplicating eighteen lines beats making one of them public.
 */
internal fun codeLines(file: File): List<String> {
    val out = mutableListOf<String>()
    var inBlock = false
    for (raw in file.readLines()) {
        val line = raw.trim()
        if (inBlock) {
            if (line.endsWith("*/")) inBlock = false
            continue
        }
        if (line.startsWith("/*")) {
            if (!line.endsWith("*/")) inBlock = true
            continue
        }
        if (line.startsWith("//") || line.startsWith("*")) continue
        out.add(raw.substringBefore("//"))
    }
    return out
}

internal fun code(file: File): String = codeLines(file).joinToString("\n")
