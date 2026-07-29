#!/usr/bin/env swift
// Compare two PNGs and report how different they are.
//
// The second half of DECISIONS.md D3. The PWA renders a mascot, the Swift build
// renders the same mascot, and this decides whether the port moved anything.
// Both inputs are PNGs an agent can also just look at — this only exists so the
// answer is a number rather than an opinion.
//
//   swift scripts/pixeldiff.swift a.png b.png [diff.png] [--threshold 0.02]
//
// Exit 0 when the difference is at or under the threshold, 1 when it is over,
// 2 on a usage or decoding error. The optional third argument writes a heat map:
// unchanged pixels dimmed, changed pixels in red at the magnitude of the change.

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(2)
}

func loadRGBA(_ path: String) -> (pixels: [UInt8], width: Int, height: Int) {
    let url = URL(fileURLWithPath: path)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
    else { fail("cannot decode \(path)") }

    let w = image.width, h = image.height
    var buffer = [UInt8](repeating: 0, count: w * h * 4)
    guard let ctx = CGContext(
        data: &buffer, width: w, height: h,
        bitsPerComponent: 8, bytesPerRow: w * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ) else { fail("cannot create a context for \(path)") }
    ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
    return (buffer, w, h)
}

func writePNG(_ pixels: [UInt8], width: Int, height: Int, to path: String) {
    var data = pixels
    guard let ctx = CGContext(
        data: &data, width: width, height: height,
        bitsPerComponent: 8, bytesPerRow: width * 4,
        space: CGColorSpace(name: CGColorSpace.sRGB)!,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
    ), let image = ctx.makeImage() else { fail("cannot encode \(path)") }

    let url = URL(fileURLWithPath: path) as CFURL
    guard let dest = CGImageDestinationCreateWithURL(url, UTType.png.identifier as CFString, 1, nil)
    else { fail("cannot open \(path) for writing") }
    CGImageDestinationAddImage(dest, image, nil)
    guard CGImageDestinationFinalize(dest) else { fail("cannot write \(path)") }
}

// MARK: - Arguments

var positional: [String] = []
var threshold = 0.02
var index = 1
while index < CommandLine.arguments.count {
    let arg = CommandLine.arguments[index]
    if arg == "--threshold" {
        index += 1
        guard index < CommandLine.arguments.count, let v = Double(CommandLine.arguments[index]) else {
            fail("--threshold needs a number")
        }
        threshold = v
    } else {
        positional.append(arg)
    }
    index += 1
}
guard positional.count >= 2 else {
    fail("usage: pixeldiff.swift a.png b.png [diff.png] [--threshold 0.02]")
}

let (a, aw, ah) = loadRGBA(positional[0])
let (b, bw, bh) = loadRGBA(positional[1])

guard aw == bw, ah == bh else {
    // Not a soft failure: a size mismatch means the two harnesses disagree about
    // the render box, and every pixel comparison after this point is meaningless.
    fail("size mismatch: \(aw)x\(ah) vs \(bw)x\(bh)")
}

// MARK: - Compare

// Per-pixel distance is the max of the channel deltas rather than the mean, so a
// single badly-wrong channel cannot be averaged into looking fine.
var changed = 0
var totalDelta = 0.0
var worst = 0
var worstAt = (x: 0, y: 0)
var heat = [UInt8](repeating: 0, count: aw * ah * 4)

// Ignore differences at or below this per-channel value: PNG round-trips and
// text antialiasing produce a point or two of noise that is not a port bug.
let noiseFloor = 2

for y in 0 ..< ah {
    for x in 0 ..< aw {
        let i = (y * aw + x) * 4
        let dr = abs(Int(a[i]) - Int(b[i]))
        let dg = abs(Int(a[i + 1]) - Int(b[i + 1]))
        let db = abs(Int(a[i + 2]) - Int(b[i + 2]))
        let da = abs(Int(a[i + 3]) - Int(b[i + 3]))
        let delta = max(max(dr, dg), max(db, da))

        if delta > worst { worst = delta; worstAt = (x, y) }

        if delta > noiseFloor {
            changed += 1
            totalDelta += Double(delta) / 255.0
            heat[i] = 255
            heat[i + 1] = UInt8(max(0, 200 - delta))
            heat[i + 2] = UInt8(max(0, 200 - delta))
            heat[i + 3] = 255
        } else {
            let grey = UInt8(Int(a[i]) / 5 + 200 / 5)
            heat[i] = grey; heat[i + 1] = grey; heat[i + 2] = grey; heat[i + 3] = 255
        }
    }
}

let total = aw * ah
let fraction = Double(changed) / Double(total)
let meanDelta = changed == 0 ? 0 : totalDelta / Double(changed)

if positional.count >= 3 {
    writePNG(heat, width: aw, height: ah, to: positional[2])
}

let verdict = fraction <= threshold ? "PASS" : "FAIL"
print("""
\(verdict)  \(positional[0]) vs \(positional[1])
  size          \(aw)x\(ah)  (\(total) px)
  changed       \(changed) px  = \(String(format: "%.4f%%", fraction * 100))
  threshold     \(String(format: "%.4f%%", threshold * 100))
  mean delta    \(String(format: "%.1f", meanDelta * 255))/255 over changed pixels
  worst pixel   \(worst)/255 at (\(worstAt.x), \(worstAt.y))
""")

exit(fraction <= threshold ? 0 : 1)
