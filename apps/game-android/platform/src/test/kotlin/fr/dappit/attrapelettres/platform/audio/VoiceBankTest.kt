package fr.dappit.attrapelettres.platform.audio

import fr.dappit.attrapelettres.core.vo.enumeratePreviewUtterances
import fr.dappit.attrapelettres.core.vo.enumerateUtterances
import fr.dappit.attrapelettres.core.vo.voKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The host-testable half of the voice channel: the manifest loader, the voice
// picker and the watchdog arithmetic. Port of the parts of
// `AudioClipBankTests.swift`, `AudioSpeechTests.swift` and
// `AudioVoiceChannelTests.swift` that need no audio device.
//
// A JVM unit test in an Android module has no Android runtime, so everything
// asserted here is deliberately pure Kotlin over plain data: `VoManifest.parse`
// takes lines rather than an `AssetManager`, and `FrenchVoicePicker` takes
// `FrenchVoiceCandidate` rather than `android.speech.tts.Voice`. The Android
// halves — `AssetClipBank`, `MediaPlayerVoicePlayer`, `AndroidSpeechFallback`,
// `AndroidAudioFocus` — are the thin adapters on top, and what is left of them
// needs a device (ARCHITECTURE §4, W16).
//
// The manifest is read from its committed path rather than from assets for the
// same reason `:core`'s own `VoManifest` helper does: it makes the test that
// matters — "every utterance the app can speak has a clip" — green or red on a
// machine that has never run `scripts/stage-vo.sh`.
class VoiceBankTest {

    private val manifest: VoManifest = VoManifest.parse(committedManifest().readLines())

    private val catalog: List<String> =
        enumerateUtterances() + enumeratePreviewUtterances().map { it.text }

    // ------------------------------------------------------------- the manifest

    @Test
    fun `m4a beats mp3 beats wav when a key has more than one file`() {
        // `RANK = { m4a: 3, mp3: 2, wav: 1 }` in clips.ts, and the winner is
        // independent of the order the files are listed in.
        val parsed = VoManifest.parse(
            listOf("x.wav", "x.m4a", "x.mp3", "y.wav", "y.mp3", "z.wav", "junk", "z.aiff"),
        )
        assertEquals("m4a", parsed.fileExtension("x"))
        assertEquals("mp3", parsed.fileExtension("y"))
        assertEquals("wav", parsed.fileExtension("z"))
        assertNull(parsed.fileExtension("junk"))
        assertEquals(3, parsed.count)
    }

    @Test
    fun `blank lines, comments and dotless names are skipped`() {
        val parsed = VoManifest.parse(listOf("", "   ", "# a.m4a", "b.m4a", ".m4a"))
        assertEquals(setOf("b"), parsed.keys)
    }

    @Test
    fun `every utterance the app can speak has a baked clip`() {
        assertEquals(845, manifest.count)
        val missing = catalog.filter { !manifest.contains(voKey(it)) }
        assertTrue(missing.isEmpty(), "${missing.size} unvoiced utterance(s), e.g. ${missing.take(5)}")
    }

    @Test
    fun `no orphans - the bank holds exactly the catalog, nothing stale`() {
        val reachable = catalog.map { voKey(it) }.toSet()
        val orphans = manifest.keys - reachable
        assertTrue(
            orphans.isEmpty(),
            "${orphans.size} clip(s) no utterance can reach: ${orphans.sorted().take(5)}",
        )
    }

    @Test
    fun `a build with no manifest at all degrades to no baked voice`() {
        assertEquals(0, VoManifest.EMPTY.count)
        assertNull(VoManifest.EMPTY.fileExtension("upd4kb"))
        // `EmptyClipBank` is the same answer one layer up: every line then takes
        // the text-to-speech path, which is quieter but never broken.
        assertNull(EmptyClipBank().clipAsset("Bravo ! Tu as tout réussi !"))
        assertTrue(!EmptyClipBank().hasBakedVoice)
    }

    // ---------------------------------------------------------- the voice picker

    @Test
    fun `isFrench accepts fr, fr-FR and fr_CA and nothing else`() {
        assertTrue(FrenchVoicePicker.isFrench("fr"))
        assertTrue(FrenchVoicePicker.isFrench("fr-FR"))
        assertTrue(FrenchVoicePicker.isFrench("fr_CA"))
        assertTrue(!FrenchVoicePicker.isFrench("fry"))
        assertTrue(!FrenchVoicePicker.isFrench("frr"))
        assertTrue(!FrenchVoicePicker.isFrench("en-US"))
        assertTrue(FrenchVoicePicker.isFranceFrench("fr-FR"))
        assertTrue(!FrenchVoicePicker.isFranceFrench("fr-CA"))
    }

    @Test
    fun `quality maps onto the web's ranking`() {
        // +5 premium, +4 enhanced, +1 for France French — `voiceScore` with the
        // name heuristics replaced by the fact they were a proxy for.
        assertEquals(VoiceQuality.PREMIUM, VoiceQuality.ofAndroidQuality(500))
        assertEquals(VoiceQuality.ENHANCED, VoiceQuality.ofAndroidQuality(400))
        assertEquals(VoiceQuality.STANDARD, VoiceQuality.ofAndroidQuality(300))
        assertEquals(VoiceQuality.STANDARD, VoiceQuality.ofAndroidQuality(100))
        assertEquals(6, FrenchVoicePicker.score(candidate("a", "fr-FR", VoiceQuality.PREMIUM)))
        assertEquals(5, FrenchVoicePicker.score(candidate("a", "fr-CA", VoiceQuality.PREMIUM)))
        assertEquals(1, FrenchVoicePicker.score(candidate("a", "fr-FR", VoiceQuality.STANDARD)))
    }

    @Test
    fun `a network voice is never chosen, whatever its quality`() {
        // Not a ranking decision: server-side synthesis would send the utterance
        // to a third party, and nothing identifying — nothing at all — leaves
        // this device (invariant 10).
        val local = candidate("local", "fr-FR", VoiceQuality.STANDARD)
        val remote = candidate("remote", "fr-FR", VoiceQuality.PREMIUM, networkRequired = true)
        assertSame(local, FrenchVoicePicker.pickBest(listOf(remote, local)))
        assertNull(FrenchVoicePicker.pickBest(listOf(remote)))
    }

    @Test
    fun `nothing French installed returns null rather than an English voice`() {
        assertNull(FrenchVoicePicker.pickBest(listOf(candidate("a", "en-US", VoiceQuality.PREMIUM))))
        assertNull(FrenchVoicePicker.pickBest(emptyList()))
    }

    @Test
    fun `ties keep the inventory's own order`() {
        // The web sorts with a stable sort and takes [0]; `maxByOrNull` returns
        // the first maximal element. Otherwise the chosen voice could differ
        // between two launches with the same inventory.
        val first = candidate("first", "fr-FR", VoiceQuality.ENHANCED)
        val second = candidate("second", "fr-FR", VoiceQuality.ENHANCED)
        assertSame(first, FrenchVoicePicker.pickBest(listOf(first, second)))
        assertSame(second, FrenchVoicePicker.pickBest(listOf(second, first)))
    }

    @Test
    fun `the web's rate and pitch cross to Android unchanged`() {
        // The iOS trap — `AVSpeechUtteranceDefaultSpeechRate` is 0.5 — has no
        // Android counterpart: `setSpeechRate` and `setPitch` are both
        // 1_0-is-normal, exactly like `SpeechSynthesisUtterance`.
        assertEquals(0.94f, SpeechRate.ttsRate(0.94))
        assertEquals(1.1f, SpeechRate.ttsPitch(1.1))
        // The clamps exist only so nobody can hand the engine a zero.
        assertTrue(SpeechRate.ttsRate(0.0) > 0f)
        assertEquals(2.0f, SpeechRate.ttsPitch(9.0))
    }

    // ------------------------------------------------------------ the watchdogs

    @Test
    fun `the tts watchdog is the web's formula, on UTF-16 code units`() {
        // max(TTS_MIN_MS, text.length * TTS_MS_PER_CHAR) / rate + WATCHDOG_MARGIN_MS
        assertEquals(1200.0 / 0.94 + 800.0, LiveAudioEngine.ttsWatchdogMs("1234", 0.94))
        val line = "Bravo ! Tu as tout réussi !"
        assertEquals(line.length * 90.0 / 0.94 + 800.0, LiveAudioEngine.ttsWatchdogMs(line, 0.94))
    }

    @Test
    fun `no rate can arm a watchdog that never fires`() {
        // `say()` may not hang, so the divisor is floored. The app only ever
        // passes 0.94; this is the guard against a future caller passing 0.
        assertTrue(LiveAudioEngine.ttsWatchdogMs("x", 0.0).isFinite())
        assertTrue(LiveAudioEngine.ttsWatchdogMs("x", -1.0).isFinite())
    }

    // ---------------------------------------------------------------- Helpers

    private fun candidate(
        name: String,
        language: String,
        quality: VoiceQuality,
        networkRequired: Boolean = false,
    ) = FrenchVoiceCandidate(name, language, quality, networkRequired)

    /**
     * Walk upwards for the committed manifest, so the test runs whether Gradle
     * starts it in the module directory or at the repo root — the same walk
     * `:core`'s helper does, for the same reason.
     */
    private fun committedManifest(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (candidate in listOf(
                "src/main/assets/vo-manifest.txt",
                "platform/src/main/assets/vo-manifest.txt",
                "apps/game-android/platform/src/main/assets/vo-manifest.txt",
            )) {
                val file = File(dir, candidate)
                if (file.isFile) return file
            }
            dir = dir.parentFile
        }
        error("vo-manifest.txt not found above ${File("").absoluteFile}")
    }
}
