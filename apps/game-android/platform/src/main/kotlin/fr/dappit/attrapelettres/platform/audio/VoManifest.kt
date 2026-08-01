package fr.dappit.attrapelettres.platform.audio

import android.content.res.AssetManager
import android.util.Log
import java.io.IOException

// The committed index of the baked voice bank. Port of `VOManifest.swift`,
// which is itself the answer to a problem `src/vo/clips.ts` does not have.
//
// The web builds its map at BUILD time from a Vite glob, because Vite can see
// the whole `src/vo/clips/` directory. There is no glob in an Android build,
// and — more importantly — the 845 clips are not committed under
// `apps/game-android/`: they live once, in the web app, and are hard-linked
// into `platform/src/main/assets/vo/` by `scripts/stage-vo.sh` before a build.
//
// So the MANIFEST is committed instead: one `<voKey>.<ext>` per line, exactly
// the filenames the bake produced. That is what makes the test that actually
// matters — "every utterance the app can speak has a clip" — run on a machine
// that has never staged a byte of audio. `:core`'s own `VoManifest` test helper
// reads the same committed file for exactly that reason. A new word shipping
// without a voice is then a red test, not a robot voice a child hears three
// weeks later.
//
// The manifest is data ABOUT the bank; the bank is the bank. [AssetClipBank]
// consults the manifest for the extension and the AssetManager for the file, so
// an unstaged build resolves cleanly to `null` and degrades to text-to-speech
// (invariant 3).
//
// Everything here is pure Kotlin over plain strings on purpose: [parse] takes
// lines, not an AssetManager, so the ranking rule is host-testable on a bare
// JVM with no Android runtime. [fromAssets] is the three-line adapter on top.

/**
 * `voKey` → the extension the bake wrote for it.
 *
 * Construct with [parse] from the committed `vo-manifest.txt`, or directly from
 * a map in a test.
 */
class VoManifest(val extensions: Map<String, String>) {

    /** The number of clips the bake produced, per the committed manifest. */
    val count: Int get() = extensions.size

    /** Every key the bake produced a clip for. */
    val keys: Set<String> get() = extensions.keys

    fun contains(key: String): Boolean = extensions.containsKey(key)

    /** The extension the bake wrote for this key, or `null` if it was never baked. */
    fun fileExtension(key: String): String? = extensions[key]

    companion object {
        private const val TAG = "AL.VoManifest"

        /** The asset the staging script writes next to the `vo/` directory. */
        const val ASSET_NAME: String = "vo-manifest.txt"

        /**
         * `RANK` from `clips.ts`: prefer the compressed formats when a key has
         * more than one file on disk. In practice the bank is 100 % `.m4a`; the
         * mp3/wav arms are as dead here as they are in the TypeScript, and kept
         * for the same reason — a hand-dropped clip in either format still has
         * to resolve, and the tie-break must not depend on directory order.
         */
        val RANK: Map<String, Int> = mapOf("m4a" to 3, "mp3" to 2, "wav" to 1)

        /** The manifest of a build with no bake at all. */
        val EMPTY: VoManifest = VoManifest(emptyMap())

        /**
         * Parse `<key>.<ext>` lines. Blank lines and `#` comments are ignored;
         * anything without a known extension is ignored (mirrors the TypeScript
         * regex, which only ever matched m4a/mp3/wav).
         */
        fun parse(lines: Iterable<String>): VoManifest {
            val best = HashMap<String, Int>()
            val chosen = HashMap<String, String>()
            for (raw in lines) {
                // `trim()` rather than a whitespace class: this is a file listing,
                // and the only thing that ever needs stripping is a stray CR from
                // a checkout with CRLF line endings.
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val dot = line.lastIndexOf('.')
                // `dot <= 0` covers both "no extension" and "no key" — a leading
                // dot would otherwise produce an empty key that can never match a
                // voKey but would still occupy a map slot.
                if (dot <= 0) continue
                val key = line.substring(0, dot)
                val ext = line.substring(dot + 1)
                val rank = RANK[ext] ?: continue
                if (rank > (best[key] ?: 0)) {
                    best[key] = rank
                    chosen[key] = ext
                }
            }
            return VoManifest(chosen)
        }

        /**
         * Read the committed manifest out of the APK's assets.
         *
         * `AssetManager.open` — not `openFd` — because a `.txt` asset IS
         * compressed by the packager, and a compressed asset has no file
         * descriptor to hand out. (The clips themselves are the opposite case;
         * see [AssetClipBank].)
         *
         * A missing or unreadable manifest is a PACKAGING bug, not a runtime
         * condition, and it still degrades to "no baked voice" rather than
         * throwing: an audio layer that can take the app down at launch is worse
         * than one that speaks in the robot voice (invariant 3).
         */
        fun fromAssets(assets: AssetManager, name: String = ASSET_NAME): VoManifest =
            try {
                assets.open(name).use { stream ->
                    parse(stream.bufferedReader(Charsets.UTF_8).readLines())
                }
            } catch (e: IOException) {
                Log.e(TAG, "$name is missing from the assets — the whole voice bank is unreachable", e)
                EMPTY
            }
    }
}
