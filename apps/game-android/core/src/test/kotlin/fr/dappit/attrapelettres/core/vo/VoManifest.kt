package fr.dappit.attrapelettres.core.vo

import java.io.File

// The strongest oracle available is the bake itself. The web generator wrote
// 845 real clips named `<voKey(utterance)>.m4a` from the SHIPPED TypeScript
// manifest, and `scripts/stage-vo.sh` stages them into this app under
// `platform/src/main/assets/vo/`, with the committed `vo-manifest.txt` listing
// every staged filename. So: hash every utterance this port enumerates and
// require its filename in that listing. That single assertion transitively
// proves
//
//   - every French literal in Utterances.kt, byte for byte, apostrophes and
//     accents included (a one-character drift changes the hash);
//   - every `levels` prompt/success declaration it calls;
//   - the content tables those declarations read;
//   - and `voKey` itself, on 845 strings rather than the hand-picked vectors
//     in VoKeyTest.
//
// A drifted line does not crash the app — the lookup misses and that ONE line
// speaks in the robot text-to-speech voice mid-narration. This is the test
// helper that notices.
//
// The manifest is read rather than the clip directory listed so the oracle is
// a COMMITTED file: `:core` tests stay runnable from a fresh checkout even if
// the (large, binary) clips themselves were not staged on this machine.
internal object VoManifest {

    // :core cannot know the working directory Gradle gives its tests (the
    // module dir, usually), so walk upwards and accept the manifest whether
    // the walk starts inside `apps/game-android` or at the repo root.
    private val file: File = run {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                "platform/src/main/assets/vo-manifest.txt",
                "apps/game-android/platform/src/main/assets/vo-manifest.txt",
            )) {
                val f = File(dir, candidate)
                if (f.isFile) return@run f
            }
            dir = dir.parentFile
        }
        error("vo-manifest.txt not found above ${File("").absoluteFile}")
    }

    /** `<key>` for every staged clip — presence is all that matters. */
    val keys: Set<String> = file
        .readLines()
        .filter { it.isNotBlank() }
        .map { it.trim().substringBeforeLast('.') }
        .toSet()
}
