// D2 enforcement: prove no SVG geometry was retyped on the way to Swift.
//
// D2 says every `d` string is copied VERBATIM from the TSX into a Swift string
// interpolation. That is checkable mechanically: strip the values, keep the
// shape.
//
//   tsx:    `M ${x} ${y} q ${w / 2} ${-h} ${w} 0 Z`
//   swift:  "M \(x) \(y) q \(w / 2) \(-h) \(w) 0 Z"
//   both:   "M @ @ q @ @ @ 0 Z"          <- the SKELETON
//
// The skeleton keeps every command letter, every literal number and every
// separator, and replaces each interpolation with `@`. Two skeletons are equal
// iff the geometry was copied rather than re-authored: re-typing a control
// point, rounding `16.75` to `16.8`, turning `q` into `Q`, or dropping a `0`
// all change the skeleton, while renaming a variable or reformatting an
// arithmetic expression inside an interpolation does not.
//
// Both sides are scanned for path-shaped STRING LITERALS, not for `Path(svg:)`
// / `d={}` call sites, because both languages build a path in a local first as
// often as they inline it:
//
//   tsx:    const d = `M ...`;  <path d={d} />
//   swift:  let d = "M ..."   ; c.fill(Path(svg: d), ...)
//
// Nested literals count on both sides — the dragon's spine builds a run of `L`
// commands with an inner template inside an outer one, and the Swift does the
// same with an inner literal inside a `\(...)`.
//
// MATCHING IS PER FILE PAIR, and that is the whole strength of the check.
// A bare "does this skeleton exist anywhere in src/" test is far too weak:
// there are 174 path literals in ALArt but only ~143 DISTINCT skeletons in the
// whole web app, so shapes like `M@ @ Q@ @ @ @` occur dozens of times and any
// mangled path lands on somebody else's by chance. Verified: deleting the
// trailing `Z` from the cat's ear passes a global check and fails a paired one.
// So each Swift file declares the TS file(s) it was ported from, and a match is
// only clean if it comes from there. Anything found elsewhere in src/ is
// reported as a cross-file match to be explained one by one.
//
// KNOWN BLIND SPOT, stated rather than hidden: what is inside a `\(...)` is
// invisible to a skeleton. Changing `headR * 0.4` to `headR * 0.42` inside an
// interpolation is not detectable this way — only the path GRAMMAR around the
// interpolations is. Expression-level drift needs the pixel diff (D3), not
// this script.
//
// Usage:
//   node scripts/d2-skeleton-diff.mjs            # human report, exit 1 on a miss
//   node scripts/d2-skeleton-diff.mjs --json     # machine-readable
//   node scripts/d2-skeleton-diff.mjs --unported # also list TS paths with no Swift counterpart

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const REPO = '/Users/jonathan/IdeaProjects/attrape-lettres'
const PKG = join(REPO, '.claude/worktrees/swift-ios/ios-native')
const SWIFT_ROOT = join(PKG, 'Sources/ALArt')
const TS_ROOTS = [join(REPO, 'src'), join(REPO, 'public')]

const PLACEHOLDER = '@'

// Which TypeScript source each Swift file was ported from. A Swift file with
// path literals and no entry here is itself a finding — the check refuses to
// guess.
const PROVENANCE = {
  'Mascot/Cat.swift': ['src/mascot/Cat.tsx'],
  'Mascot/Fox.swift': ['src/mascot/Fox.tsx'],
  'Mascot/Rabbit.swift': ['src/mascot/Rabbit.tsx'],
  'Mascot/Unicorn.swift': ['src/mascot/Unicorn.tsx'],
  'Mascot/Dragon.swift': ['src/mascot/Dragon.tsx'],
  'Mascot/DragonParts.swift': ['src/mascot/dragonParts.tsx'],
  'Mascot/Parts.swift': ['src/mascot/parts.tsx'],
  'Mascot/Rig.swift': ['src/mascot/Mascot.tsx'],
  'Mascot/Motion.swift': ['src/mascot/Mascot.tsx'],
  'Icons/ExerciseIcon.swift': ['src/components/ExerciseIcon.tsx'],
  'Icons/ExerciseIconCatalog.swift': ['src/components/ExerciseIcon.tsx'],
  'Icons/SVGText.swift': ['src/components/ExerciseIcon.tsx'],
  'Images/WordImages.swift': [
    'src/img/igloo.svg',
    'src/img/jupe.svg',
    'src/img/macaron.svg',
    'src/img/pyjama.svg',
  ],
  // Substrate, not art: these hold no ported geometry.
  'SVGPath.swift': [],
  'Canvas/SVGCanvas.swift': [],
  'Canvas/Paint.swift': [],
}

// Geometry that exists in a ported TS file and has NO Swift counterpart, with
// the reason it is absent. Everything here was checked by hand; the point of
// writing them down is that the thirteenth one shows up as a finding instead of
// disappearing into a familiar-looking list.
//
// All of these are UNREACHABLE in the web app. `dragonParts.tsx` exports 32
// components; `Dragon.tsx` imports 20. The other 12 — AngularWings, Bolt,
// ChestArmor, CloudWings, DomeFin, DustPuffs, EggShield, FrillBand, Gem,
// KnightHelmet, RainCloud, Shield — are referenced exactly once each in the
// whole of src/, by their own `export function` line. They are dead code that
// predates the shipped dragon, and the port correctly left them out.
const EXPECTED_UNPORTED = [
  // SpadeTail's `tip === "bolt"` arm. Dragon.tsx:110 casts tailTip to
  // `"spade" | "club" | "flame"`, so "bolt" cannot be selected.
  ['src/mascot/dragonParts.tsx', 'M1 -7 L4.5 -1.5 L2 -1.5 L4.5 4 L-2.5 -1 L0 -1 L-2.5 -7 Z'],
  // `Bolt` — dead export.
  ['src/mascot/dragonParts.tsx', 'M1 -8 L5 -2 L2.2 -2 L5 4 L-3 -1 L-0.2 -1 L-3 -8 Z'],
  // `EggShield` — dead export (shield body, then its star).
  ['src/mascot/dragonParts.tsx', 'M0 -4.4 C3 -4.4 4.4 -3.2 4.4 -1 C4.4 2 2.2 4.4 0 5.6 C-2.2 4.4 -4.4 2 -4.4 -1 C-4.4 -3.2 -3 -4.4 0 -4.4 Z'],
  ['src/mascot/dragonParts.tsx', 'M0 -2.4 L0.75 -0.9 L2.4 -0.7 L1.2 0.45 L1.5 2.1 L0 1.3 L-1.5 2.1 L-1.2 0.45 L-2.4 -0.7 L-0.75 -0.9 Z'],
  // Shared by `DomeFin`, `KnightHelmet` and `Shield` — all three dead exports.
  ['src/mascot/dragonParts.tsx', 'M@ @ C@ @ @ @ @ @ C@ @ @ @ @ @ Z'],
  // `Gem` — reachable only through SpadeTail's `gem`, Crest's `gems` and
  // ChestArmor. Dragon.tsx passes none of them and ChestArmor is dead.
  ['src/mascot/dragonParts.tsx', 'M0 @ L@ 0 L0 @ L@ 0 Z'],
  // `FrillBand` — dead export.
  ['src/mascot/dragonParts.tsx', 'M@ @ A@ @ 0 0 1 @ @'],
  // `KnightHelmet` — dead export.
  ['src/mascot/dragonParts.tsx', 'M@ @ C@ @ @ @ @ @ L@ @ Q@ @ @ @ Z'],
  // `Shield` — dead export.
  ['src/mascot/dragonParts.tsx', 'M@ @ Q@ @ @ @ C@ @ @ @ @ @ C@ @ @ @ @ @ Z'],
  // `ChestArmor` — dead export.
  ['src/mascot/dragonParts.tsx', 'M@ @ Q@ @ @ @ L@ @ Q@ @ @ @ Z'],
].map(([file, skeleton]) => file + ' ' + skeleton)

// `public/icon.svg` is the app's launcher mark (the "A"), not artwork any screen
// draws. It belongs in an asset catalog, so it has no provenance entry above and
// is not expected to appear in ALArt at all.

const walk = (dir, test, out = []) => {
  let entries
  try {
    entries = readdirSync(dir)
  } catch {
    return out
  }
  for (const e of entries) {
    if (e === 'node_modules' || e === '.git' || e === 'dist' || e === 'clips.bak') continue
    const p = join(dir, e)
    if (statSync(p).isDirectory()) walk(p, test, out)
    else if (test(e)) out.push(p)
  }
  return out
}

// ---------------------------------------------------------------- skeletons

/** Collapse whitespace so line-wrapping a long `d` cannot look like a rewrite. */
const normalise = (s) => s.replace(/\s+/g, ' ').trim()

/**
 * Does this literal hold SVG path data? Must open with a moveto and carry at
 * least one further command letter, so that `M`-initial prose ("Ma licorne")
 * and colour strings never qualify.
 */
const isPathData = (skeleton) => {
  if (!/^[Mm][\s,]*(?:[-+.\d]|@)/.test(skeleton)) return false
  const commands = skeleton.replace(/[^A-Za-z@]/g, '').replace(/@/g, '')
  return /[MmLlHhVvCcSsQqTtAaZz]/.test(commands.slice(1))
}

// ------------------------------------------------------------ Swift scanner
//
// Walks the file character by character rather than by regex, because it must
// (a) ignore comments — every rig file quotes the original TSX in a doc
// comment, and matching against that text would let the check pass by reading
// its own source material — and (b) follow `\( ... )` interpolations, which
// nest and may contain string literals of their own.

function swiftLiterals(src) {
  const found = []
  const lineAt = (offset) => src.slice(0, offset).split('\n').length

  const record = (skeleton, offset) => {
    if (isPathData(skeleton)) found.push({ skeleton, line: lineAt(offset) })
  }

  const readLiteral = (start) => {
    // src[start] === '"'. Returns { skeleton, end } with end past the closer.
    let out = ''
    let j = start + 1
    while (j < src.length) {
      const c = src[j]
      if (c === '\\') {
        if (src[j + 1] === '(') {
          // An interpolation: balance parens, and recurse into any string
          // literal inside it (`\(pts.map { "L\(x) \(y)" }.joined())` is real).
          let depth = 1
          let k = j + 2
          while (k < src.length && depth > 0) {
            if (src[k] === '"') {
              const inner = readLiteral(k)
              record(inner.skeleton, k)
              k = inner.end
              continue
            }
            if (src[k] === '(') depth++
            else if (src[k] === ')') depth--
            k++
          }
          out += PLACEHOLDER
          j = k
          continue
        }
        out += src[j + 1] === 'n' ? '\n' : src[j + 1]
        j += 2
        continue
      }
      if (c === '"') return { skeleton: normalise(out), end: j + 1 }
      if (c === '\n') return { skeleton: normalise(out), end: j } // unterminated
      out += c
      j++
    }
    return { skeleton: normalise(out), end: j }
  }

  let i = 0
  while (i < src.length) {
    const c = src[i]
    if (c === '/' && src[i + 1] === '/') {
      while (i < src.length && src[i] !== '\n') i++
      continue
    }
    if (c === '/' && src[i + 1] === '*') {
      let depth = 1
      i += 2
      while (i < src.length && depth > 0) {
        if (src[i] === '/' && src[i + 1] === '*') { depth++; i += 2; continue }
        if (src[i] === '*' && src[i + 1] === '/') { depth--; i += 2; continue }
        i++
      }
      continue
    }
    if (c === '"') {
      // A `"""` multi-line literal would need its own reader; there are none
      // in ALArt and this throw asserts that stays true.
      if (src[i + 1] === '"' && src[i + 2] === '"') {
        throw new Error('multi-line string literal found — the Swift scanner does not read those')
      }
      const lit = readLiteral(i)
      record(lit.skeleton, i)
      i = lit.end
      continue
    }
    i++
  }
  return found
}

// --------------------------------------------------------------- TS scanner
//
// Three literal flavours carry path data in the web app: the `d="..."`
// attribute of a static `.svg`/`.tsx` element, a backtick template, and a plain
// quoted string. Templates nest, so their interpolations are scanned
// recursively and each inner template is recorded in its own right.

function tsLiterals(src, isSvgFile) {
  const found = []
  const record = (skeleton) => {
    if (isPathData(skeleton)) found.push(skeleton)
  }

  // Static `d="..."` / `d='...'` attributes (SVG files and JSX alike).
  for (const m of src.matchAll(/(?<![\w$])d\s*=\s*"([^"]*)"/g)) record(normalise(m[1]))
  for (const m of src.matchAll(/(?<![\w$])d\s*=\s*'([^']*)'/g)) record(normalise(m[1]))

  if (isSvgFile) return found

  const readTemplate = (start) => {
    // src[start] === '`'. Returns { skeleton, end } with end past the closer.
    let out = ''
    let j = start + 1
    while (j < src.length) {
      const c = src[j]
      if (c === '\\') { out += src[j + 1]; j += 2; continue }
      if (c === '$' && src[j + 1] === '{') {
        let depth = 1
        let k = j + 2
        while (k < src.length && depth > 0) {
          if (src[k] === '`') {
            const inner = readTemplate(k)
            record(inner.skeleton)
            k = inner.end
            continue
          }
          if (src[k] === '{') depth++
          else if (src[k] === '}') depth--
          k++
        }
        out += PLACEHOLDER
        j = k
        continue
      }
      if (c === '`') return { skeleton: normalise(out), end: j + 1 }
      out += c
      j++
    }
    return { skeleton: normalise(out), end: j }
  }

  let i = 0
  while (i < src.length) {
    const c = src[i]
    if (c === '`') {
      const t = readTemplate(i)
      record(t.skeleton)
      i = t.end
      continue
    }
    if (c === '"' || c === "'") {
      let j = i + 1
      let out = ''
      while (j < src.length && src[j] !== c && src[j] !== '\n') {
        if (src[j] === '\\') { out += src[j + 1]; j += 2; continue }
        out += src[j]
        j++
      }
      record(normalise(out))
      i = j + 1
      continue
    }
    i++
  }
  return found
}

// ------------------------------------------------------------------- run it

/** skeleton -> Set(ts file) over the whole web app. */
const tsIndex = new Map()
/** ts file -> Set(skeleton). */
const tsByFile = new Map()

for (const root of TS_ROOTS) {
  for (const file of walk(root, (e) => /\.(tsx?|svg)$/.test(e))) {
    const rel = relative(REPO, file)
    const skeletons = tsLiterals(readFileSync(file, 'utf8'), file.endsWith('.svg'))
    if (!tsByFile.has(rel)) tsByFile.set(rel, new Set())
    for (const s of skeletons) {
      tsByFile.get(rel).add(s)
      if (!tsIndex.has(s)) tsIndex.set(s, new Set())
      tsIndex.get(s).add(rel)
    }
  }
}

const paired = []
const crossFile = []
const misses = []
const unknownProvenance = []
const matchedTs = new Set()

const swiftFiles = walk(SWIFT_ROOT, (e) => e.endsWith('.swift')).sort()
for (const file of swiftFiles) {
  const rel = relative(SWIFT_ROOT, file)
  const literals = swiftLiterals(readFileSync(file, 'utf8'))
  if (literals.length === 0) continue

  const sources = PROVENANCE[rel]
  if (sources === undefined) {
    unknownProvenance.push({ file: rel, count: literals.length })
    continue
  }

  const allowed = new Set()
  for (const s of sources) for (const k of tsByFile.get(s) ?? []) allowed.add(k)

  for (const { skeleton, line } of literals) {
    const entry = { file: rel, line, skeleton }
    if (allowed.has(skeleton)) {
      paired.push(entry)
      for (const s of sources) if ((tsByFile.get(s) ?? new Set()).has(skeleton)) matchedTs.add(s + ' ' + skeleton)
      continue
    }
    const elsewhere = tsIndex.get(skeleton)
    if (elsewhere) {
      crossFile.push({ ...entry, expected: sources, foundIn: [...elsewhere] })
      continue
    }
    misses.push(entry)
  }
}

// Reverse direction: geometry that exists in a ported TS file and has no Swift
// counterpart at all. Not a D2 violation — a completeness gap.
const unported = []
for (const [swiftFile, sources] of Object.entries(PROVENANCE)) {
  for (const s of sources) {
    for (const skeleton of tsByFile.get(s) ?? []) {
      if (!matchedTs.has(s + ' ' + skeleton)) unported.push({ ts: s, swiftFile, skeleton })
    }
  }
}
// A skeleton is only unported if NO Swift file claiming that source matched it.
const stillUnported = unported.filter(
  (u) => !unported.some((v) => v.ts === u.ts && v.skeleton === u.skeleton && v.swiftFile !== u.swiftFile && matchedTs.has(v.ts + ' ' + v.skeleton))
)
const uniqueUnported = [...new Map(stillUnported.map((u) => [u.ts + u.skeleton, u])).values()]
  .filter((u) => !EXPECTED_UNPORTED.includes(u.ts + ' ' + u.skeleton))

// An allowlist entry that no longer matches anything is itself a finding: the
// TS moved and the reason written above it is now fiction.
const staleAllowlist = EXPECTED_UNPORTED.filter((entry) => {
  const [file, ...rest] = entry.split(' ')
  return !(tsByFile.get(file) ?? new Set()).has(rest.join(' '))
})

const total = paired.length + crossFile.length + misses.length
const report = {
  swiftFilesScanned: swiftFiles.length,
  swiftPathLiterals: total,
  distinctTsSkeletons: tsIndex.size,
  pairedMatch: paired.length,
  crossFileMatch: crossFile,
  noCounterpart: misses,
  unknownProvenance,
  unportedTsPaths: uniqueUnported.length,
}

if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ ...report, unported: uniqueUnported }, null, 2))
} else {
  console.log(`Swift files scanned              : ${report.swiftFilesScanned}`)
  console.log(`Swift path literals              : ${total}`)
  console.log(`Distinct TS path skeletons       : ${report.distinctTsSkeletons}`)
  console.log(`  matched in the PAIRED TS file  : ${paired.length}`)
  console.log(`  matched only in ANOTHER file   : ${crossFile.length}`)
  console.log(`  NO counterpart anywhere in src : ${misses.length}`)
  console.log(`  Swift files of unknown origin  : ${unknownProvenance.length}`)
  console.log(`TS paths unported and unexplained: ${uniqueUnported.length}`)
  console.log(`  (explained dead-code gaps      : ${EXPECTED_UNPORTED.length})`)
  console.log(`Stale allowlist entries          : ${staleAllowlist.length}`)
  for (const s of staleAllowlist) console.log(`\n# allowlist entry matches nothing any more:\n  ${s}`)
  for (const u of unknownProvenance) {
    console.log(`\n? ${u.file} — ${u.count} path literal(s), no PROVENANCE entry`)
  }
  for (const x of crossFile) {
    console.log(`\n~ ${x.file}:${x.line}\n  ${x.skeleton}\n  expected in ${x.expected.join(', ')}; found in ${x.foundIn.join(', ')}`)
  }
  for (const m of misses) {
    console.log(`\n! ${m.file}:${m.line}\n  ${m.skeleton}`)
  }
  if (process.argv.includes('--unported')) {
    for (const u of uniqueUnported) {
      console.log(`\n- [${u.ts}] not ported into ${u.swiftFile}\n  ${u.skeleton}`)
    }
  }
}

const clean =
  misses.length === 0 &&
  unknownProvenance.length === 0 &&
  uniqueUnported.length === 0 &&
  staleAllowlist.length === 0
process.exit(clean ? 0 : 1)
