// Pull every SVG path `d` string out of the TSX/SVG sources so the Swift parser
// can be golden-tested against the real corpus rather than invented input.
//
// Three shapes appear in the source:
//   d="M 1 2 ..."            static, exact
//   d={`M ${x} ${y} ...`}    template — interpolations replaced by sentinel numbers
//   d={someIdentifier}       indirect — reported, not captured
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const ROOT = '/Users/jonathan/IdeaProjects/attrape-lettres'
const walk = (dir, out = []) => {
  for (const e of readdirSync(dir)) {
    if (e === 'node_modules' || e === '.git' || e === 'dist' || e === 'clips.bak') continue
    const p = join(dir, e)
    const s = statSync(p)
    if (s.isDirectory()) walk(p, out)
    else if (/\.(tsx?|svg)$/.test(e)) out.push(p)
  }
  return out
}

// Sentinel values chosen to exercise sign handling and decimals without being
// mistaken for a command letter.
const SENTINELS = [12, -7.5, 3.25, -0.5, 48, 16.75, -21, 9]
let sentinelIdx = 0
const nextSentinel = () => SENTINELS[sentinelIdx++ % SENTINELS.length]

// Walk a template literal body, replacing each balanced ${...} with a number.
//
// An interpolation is not always a scalar: dragonParts.tsx has
//   `M${a} ${b} ${pts.slice(1).map(([dx,dy]) => `L${x+dx} ${y+dy}`).join(" ")}`
// where the third slot expands to a whole run of commands. Substituting a
// number there yields a path that is malformed by construction, which would
// look exactly like a parser bug. So: if an interpolation contains its own
// template literal, fill that recursively and repeat it, which is a faithful
// instance of what the component actually renders.
const fillTemplate = (body) => {
  let out = ''
  for (let i = 0; i < body.length; i++) {
    if (body[i] === '$' && body[i + 1] === '{') {
      let depth = 1
      let j = i + 2
      while (j < body.length && depth > 0) {
        if (body[j] === '{') depth++
        else if (body[j] === '}') depth--
        j++
      }
      const expr = body.slice(i + 2, j - 1)
      const nested = expr.indexOf('`')
      if (nested !== -1) {
        const end = expr.indexOf('`', nested + 1)
        const inner = end === -1 ? '' : expr.slice(nested + 1, end)
        const once = fillTemplate(inner)
        out += `${once} ${fillTemplate(inner)}` // a map emits at least two
        void once
      } else {
        out += String(nextSentinel())
      }
      i = j - 1
    } else out += body[i]
  }
  return out
}

// Read one backtick template starting at src[i] === '`'. Returns the raw body
// (interpolations left intact) and the index of the closing backtick.
const readTemplate = (src, i) => {
  let j = i + 1
  let depth = 0
  let body = ''
  while (j < src.length) {
    const c = src[j]
    if (c === '$' && src[j + 1] === '{') { depth++; body += '${'; j += 2; continue }
    if (c === '}' && depth > 0) { depth--; body += c; j++; continue }
    if (c === '`' && depth === 0) break
    body += c
    j++
  }
  return { body, end: j }
}

// Path data is sometimes assembled by concatenating templates across lines:
//   const path = `M${a} ${b} ` + `C${c} ...` + `Z`;
// Capturing only the first operand yields a lone moveto, which then looks like
// a parser bug rather than an extraction bug. Follow the `+` chain.
const readConcatenated = (src, i) => {
  let { body, end } = readTemplate(src, i)
  let j = end + 1
  for (;;) {
    const rest = src.slice(j)
    const m = rest.match(/^\s*\+\s*/)
    if (!m) break
    const k = j + m[0].length
    if (src[k] === '`') {
      const next = readTemplate(src, k)
      body += next.body
      j = next.end + 1
      continue
    }
    const q = src[k]
    if (q === '"' || q === "'") {
      let e = k + 1
      while (e < src.length && src[e] !== q) e += src[e] === '\\' ? 2 : 1
      body += src.slice(k + 1, e)
      j = e + 1
      continue
    }
    break // a `+ identifier` — cannot resolve statically
  }
  return { body, end: j }
}

const statics = []
const templates = []
const indirect = []

for (const file of walk(join(ROOT, 'src')).concat([join(ROOT, 'public/icon.svg')])) {
  const src = readFileSync(file, 'utf8')
  const rel = relative(ROOT, file)

  // d="..." — static. (?<![\w$]) keeps `id="` from matching.
  for (const m of src.matchAll(/(?<![\w$])d="([^"]+)"/g)) {
    statics.push({ file: rel, d: m[1] })
  }
  // Path-valued variables: `const CUP_PATH = \`M ...\``. These reach a <path>
  // indirectly, so the d={ident} sites below find no literal to capture.
  for (const m of src.matchAll(/(?<![\w$])(?:const|let)\s+[\w$]+\s*=\s*`(\s*[Mm][\s,]*(?:-?[\d.]|\$\{))/g)) {
    const start = src.indexOf('`', m.index)
    const { body } = readConcatenated(src, start)
    templates.push({ file: rel, raw: body, filled: fillTemplate(body), viaVariable: true })
  }
  // d={`...`} — template. Scan manually so nested ${} with backticks survive.
  let idx = -1
  while ((idx = src.indexOf('d={`', idx + 1)) !== -1) {
    if (/[\w$]/.test(src[idx - 1] ?? '')) continue // `id={\`` etc.
    const { body, end } = readConcatenated(src, idx + 3)
    templates.push({ file: rel, raw: body, filled: fillTemplate(body) })
    idx = end - 1
  }
  // d={identifier} — indirect
  for (const m of src.matchAll(/\bd=\{([A-Za-z_$][\w$.]*)\}/g)) {
    indirect.push({ file: rel, expr: m[1] })
  }
}

// Command-letter histogram over everything we captured.
const hist = {}
for (const s of [...statics.map((x) => x.d), ...templates.map((x) => x.filled)]) {
  for (const c of s.replace(/[^A-Za-z]/g, '')) hist[c] = (hist[c] || 0) + 1
}

const corpus = {
  statics,
  templates,
  indirect,
  counts: {
    statics: statics.length,
    templates: templates.length,
    indirect: indirect.length,
    total: statics.length + templates.length,
  },
  commandHistogram: Object.fromEntries(Object.entries(hist).sort((a, b) => b[1] - a[1])),
}

writeFileSync(process.argv[2], JSON.stringify(corpus, null, 2))
console.log(JSON.stringify({ counts: corpus.counts, hist: corpus.commandHistogram }, null, 2))
console.log('indirect:', JSON.stringify(indirect.slice(0, 20)))
