package fr.dappit.attrapelettres.platform.audio

import android.content.res.AssetManager
import android.util.Log
import fr.dappit.attrapelettres.core.vo.voKey
import java.io.IOException

// Port of `src/vo/clips.ts` — the `voKey(text) -> clip` mapping — via
// `ClipBank.swift`.
//
// The TypeScript glob is eager: Vite inlines every clip URL at build time.
// Here the lookup is lazy and memoised, for the same reason the iOS port made
// that choice: a round needs ~15 distinct keys, and building an eager index
// over 845 entries (an `AssetManager.list("vo")` call plus 845 strings) would
// cost a millisecond at launch for nothing. Negative results are memoised too,
// so a line with no clip does not re-probe the asset table on every repeat.
//
// The key itself is NOT re-derived here. `:core`'s `voKey` is the one
// implementation, it is proven against these very filenames by
// `VoKeyTest` and the manifest oracle, and a second copy of an FNV-1a loop over
// UTF-16 code units is exactly the kind of thing that drifts by one character
// and degrades one line to the robot voice, silently.

/**
 * What the voice channel needs from the bank. An interface so a test can hand
 * the engine a double and prove `pop()` / `nudge()` never reach it
 * (invariant 1), and so previews can run with nothing baked at all.
 */
interface ClipLocating {
    /** The baked clip for this EXACT utterance, as an asset path, or `null`. */
    fun clipAsset(text: String): String?

    /** Whether any clip is actually present in this build. */
    val hasBakedVoice: Boolean
}

/**
 * The shipping bank: the manifest says which extension a key was baked in, the
 * `AssetManager` says whether the file is really in this APK.
 *
 * Both halves are needed. The manifest alone would name clips that a checkout
 * without `scripts/stage-vo.sh` does not have; the assets alone would cost a
 * directory listing and would not know which of several extensions won.
 */
class AssetClipBank(
    private val assets: AssetManager,
    private val manifest: VoManifest,
    private val directory: String = DIRECTORY,
) : ClipLocating {

    // voKey -> asset path, with `null` meaning "probed, absent". `containsKey`
    // is what distinguishes "not yet probed" from "probed and absent" — the
    // doubly-optional value the Swift version spells with `[String: URL?]`.
    private val memo = HashMap<String, String?>()
    private val lock = Any()

    /** The number of clips the bake produced, per the committed manifest. */
    val manifestCount: Int get() = manifest.count

    override fun clipAsset(text: String): String? = assetForKey(voKey(text))

    /**
     * Resolve a key directly. The bank is keyed by `voKey`, and a test that
     * hashes the catalog itself wants this entry point rather than the text one.
     */
    fun assetForKey(key: String): String? {
        synchronized(lock) {
            if (memo.containsKey(key)) return memo[key]
        }
        // Probed OUTSIDE the lock: a probe is a native asset-table lookup and,
        // on a miss, an exception. Two threads racing the same cold key both
        // probe and both write the same answer, which is cheaper and simpler
        // than holding a monitor across I/O on the say path.
        val resolved = probe(key)
        synchronized(lock) { memo[key] = resolved }
        return resolved
    }

    private fun probe(key: String): String? {
        val named = manifest.fileExtension(key)
        if (named != null) {
            val path = "$directory/$key.$named"
            if (exists(path)) return path
            // The clips are a fixed asset set staged by a script, so this is a
            // BUILD defect, not a runtime condition: the bake produced this file
            // and the APK does not carry it. Loud, because the only symptom a
            // human gets is one line of narration in the robot voice.
            Log.e(
                TAG,
                "$path is listed in ${VoManifest.ASSET_NAME} but is not in this build. " +
                    "Run scripts/stage-vo.sh before assembling. Falling back to text-to-speech.",
            )
            return null
        }
        // Unknown to the manifest. Probe the RANK order anyway so a clip dropped
        // in by hand still resolves — the same courtesy the Swift port keeps.
        for (ext in FALLBACK_EXTENSIONS) {
            val path = "$directory/$key.$ext"
            if (exists(path)) return path
        }
        // Expected exactly once in normal play: the score read-out speaks the
        // balance as a number (`App.tsx:44`), the one utterance in the app that
        // is not in the authored catalog and therefore has no clip. Anything
        // else reaching this line is catalog drift — a French literal edited
        // without a re-bake — which `AudioClipBankTests`' Kotlin twin is meant
        // to catch long before a child does.
        Log.w(TAG, "no baked clip for voKey $key — falling back to text-to-speech")
        return null
    }

    /**
     * Does this asset exist, and can it be handed to `MediaPlayer` as a file
     * descriptor?
     *
     * `openFd` answers both questions at once, which is why it is used instead
     * of `open`. A COMPRESSED asset has no descriptor and throws here even
     * though it exists — and that is the behaviour we want, because
     * `MediaPlayer.setDataSource(AssetFileDescriptor)` would fail on it too.
     * The audio formats (m4a/mp3/wav) are all on the packager's default
     * no-compress list, so in a correct build this never trips; if a future
     * `androidResources.noCompress` change breaks that, the failure surfaces
     * here, once, with a log line, instead of as a mute exercise.
     */
    private fun exists(path: String): Boolean =
        try {
            assets.openFd(path).close()
            true
        } catch (notThere: IOException) {
            // FileNotFoundException (not staged) and the "cannot be opened as a
            // file descriptor" IOException (compressed) are both just "no". The
            // caller decides how loud a "no" is; this is only the detail.
            Log.d(TAG, "asset probe missed $path: ${notThere.message}")
            false
        }

    /**
     * True when the audio has actually been staged into this build. Probes ONE
     * manifest entry, once; an unstaged build answers `false` and every `say()`
     * takes the text-to-speech path. That is the whole degradation story: no
     * clips is quieter, never broken (invariant 3).
     */
    override val hasBakedVoice: Boolean by lazy {
        val first = manifest.keys.minOrNull() ?: return@lazy false
        assetForKey(first) != null
    }

    companion object {
        private const val TAG = "AL.ClipBank"

        /** Where `scripts/stage-vo.sh` puts the clips, inside `src/main/assets`. */
        const val DIRECTORY: String = "vo"

        /** `RANK` order, for a key the manifest has never heard of. */
        private val FALLBACK_EXTENSIONS = listOf("m4a", "mp3", "wav")

        /** The shipping composition: the committed manifest plus the APK's assets. */
        fun shipped(assets: AssetManager): AssetClipBank =
            AssetClipBank(assets, VoManifest.fromAssets(assets))
    }
}

/**
 * Test and preview double: nothing is ever baked, so every line takes the
 * text-to-speech path. Pure Kotlin — no `AssetManager` — so a host test on a
 * bare JVM can use it.
 */
class EmptyClipBank : ClipLocating {
    override fun clipAsset(text: String): String? = null
    override val hasBakedVoice: Boolean = false
}
