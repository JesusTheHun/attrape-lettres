import AVFoundation
import Foundation
import Testing

@testable import ALPlatform

// The crash a six-year-old found on the first tap.
//
//   -[AVAudioPlayerNode scheduleBuffer:atTime:options:completionHandler:]
//   → +[NSException raise:format:] → objc_exception_throw → abort()
//   ← GameAudioGraph.play(_:) ← LiveAudioEngine.pop() ← SinglePickModel.pick(_:)
//   ← TilePress.pointerDown ← TouchDownCore.began()
//
// `build()` connected the SFX player nodes with `format: nil`. That does not
// mean "adapt to whatever arrives" — it means "use the source node's current
// output format", and for a player node holding no buffer that is the engine's
// STANDARD format: stereo. `renderBuffers` renders MONO. Scheduling a 1-channel
// buffer into a 2-channel connection raises an ObjC exception, and an ObjC
// exception in Swift is an uncatchable abort.
//
// The whole tap path is invariant 1's — the one thing that must never be slow
// and never be behind anything. It turned out to be the one thing that could
// kill the app.
//
// **Why 1430 tests missed it.** Nothing constructed the real graph. `AVAudio*`
// was mocked away behind `SfxPlaying` everywhere, `AudioSfxTests` checks only
// the SAMPLES, and `isReady` is false on a Mac with no audio device — so even a
// test that called `play` would have returned at the first guard. The assertion
// that catches this needs no device and no running engine: it compares the
// format the nodes are CONNECTED with against the format the buffers CARRY.

@Suite("GameAudioGraph — the connection format must match the buffers", .serialized)
@MainActor
struct AudioGraphFormatTests {

    /// The regression, stated exactly, against the ENGINE's own report of the
    /// node's output bus — the value `scheduleBuffer` is checked against — and
    /// not against our bookkeeping, which could agree with itself while the
    /// engine disagreed.
    @Test("every rendered buffer's format is what the engine reports for the node")
    func engineFormatMatchesBuffers() throws {
        let graph = GameAudioGraph()
        graph.prewarm()

        let actual = try #require(
            graph.actualSfxNodeFormatForTesting,
            Comment(rawValue: "no SFX node to ask"))

        // Not a tautology: the buffers come from `SfxSynth`, the connection from
        // `ensureSfxFormat`, and the bug was precisely that nothing tied them.
        var checked = 0
        for sfx in Sfx.allCases {
            let buffer = try #require(
                graph.bufferForTesting(sfx),
                Comment(rawValue: "no buffer rendered for \(sfx)"))
            #expect(
                buffer.format == actual,
                Comment(
                    rawValue:
                        "\(sfx): buffer \(buffer.format.channelCount)ch @\(buffer.format.sampleRate) "
                        + "vs node \(actual.channelCount)ch @\(actual.sampleRate)"))
            checked += 1
        }
        #expect(checked == Sfx.allCases.count)
        #expect(checked > 0, Comment(rawValue: "an empty Sfx set would make this vacuous"))
    }

    /// The buffers are mono by construction, so the node must be mono too.
    /// Spelled out because **stereo is exactly what `format: nil` produced**, and
    /// it is what a future edit would silently produce again.
    @Test("the node's output bus is single-channel, which `format: nil` was not")
    func nodeIsMono() throws {
        let graph = GameAudioGraph()
        graph.prewarm()
        let actual = try #require(graph.actualSfxNodeFormatForTesting)
        #expect(actual.channelCount == 1)
    }

    /// Bookkeeping and reality must agree, or `canSchedule` is guarding against
    /// the wrong thing.
    @Test("what ensureSfxFormat recorded is what the engine actually wired")
    func bookkeepingMatchesEngine() throws {
        let graph = GameAudioGraph()
        graph.prewarm()
        let recorded = try #require(graph.connectedSfxFormatForTesting)
        let actual = try #require(graph.actualSfxNodeFormatForTesting)
        #expect(recorded == actual)
    }

    /// The last line of defence: if the connection and the buffers ever drift
    /// apart again, `play` must go QUIET rather than fatal — audio may fail, it
    /// may never take the game down (invariant 3).
    ///
    /// This exercises `canSchedule`, which is the real predicate `play` calls,
    /// rather than a copy of the guard: an audio device is needed to reach
    /// `play`, none is needed to reach the rule it obeys.
    @Test("a buffer whose format was never connected is refused, not scheduled")
    func mismatchIsRefused() throws {
        let graph = GameAudioGraph()
        graph.prewarm()

        // Stereo: the exact shape `format: nil` used to produce, and the one
        // that aborted when a mono buffer met it.
        let stereo = try #require(
            AVAudioFormat(
                commonFormat: .pcmFormatFloat32, sampleRate: 48_000, channels: 2,
                interleaved: false))
        let stray = try #require(AVAudioPCMBuffer(pcmFormat: stereo, frameCapacity: 128))
        #expect(
            !GameAudioGraph.canSchedule(
                connected: graph.connectedSfxFormatForTesting, buffer: stray))

        // …and the real buffers ARE schedulable, so the guard is not simply
        // refusing everything (which would pass the line above and mute the app).
        let pop = try #require(graph.bufferForTesting(.pop))
        #expect(
            GameAudioGraph.canSchedule(
                connected: graph.connectedSfxFormatForTesting, buffer: pop))

        // Nothing connected yet ⇒ refuse. A fresh graph has no connection.
        #expect(!GameAudioGraph.canSchedule(connected: nil, buffer: pop))
    }
}
