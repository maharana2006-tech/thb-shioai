const fs = require('fs')
const html = fs.readFileSync('dist/stats.html', 'utf-8')

// Data lives in `const data = {...};` inside a <script> tag (visualizer 5.x).
// Extract by finding the start marker and walking braces until balanced.
const start = html.indexOf('const data = {')
if (start < 0) { console.error('no const data marker'); process.exit(1) }
const braceStart = html.indexOf('{', start)
let depth = 0, end = -1
for (let i = braceStart; i < html.length; i++) {
  const c = html[i]
  if (c === '{') depth++
  else if (c === '}') { depth--; if (depth === 0) { end = i; break } }
}
if (end < 0) { console.error('unbalanced'); process.exit(1) }
const json = html.slice(braceStart, end + 1)
const data = JSON.parse(json)

const parts = data.nodeParts || {}

// Build full path per uid by walking the tree.
const pathByUid = new Map()
function walk(node, prefix) {
  const here = prefix + (node.name || '')
  if (node.uid) pathByUid.set(node.uid, here)
  if (node.children) node.children.forEach(c => walk(c, here + '/'))
}
walk(data.tree, '')

// Per-chunk aggregation.
const chunks = (data.tree.children || []).map(chunk => {
  const chunkUids = []
  function w(n) { if (n.uid) chunkUids.push(n.uid); (n.children || []).forEach(w) }
  w(chunk)
  let ren = 0, gz = 0
  chunkUids.forEach(uid => {
    const p = parts[uid]
    if (p) { ren += (p.renderedLength || 0); gz += (p.gzipLength || 0) }
  })
  return { name: chunk.name, ren, gz, uids: chunkUids }
})
chunks.sort((a, b) => b.ren - a.ren)

console.log('=== CHUNK TOTALS (rendered / gzip) ===')
chunks.slice(0, 12).forEach(c => {
  console.log('  ' + String(Math.round(c.ren / 1024)).padStart(4) + ' KB / ' +
    String(Math.round(c.gz / 1024)).padStart(3) + ' KB gz  ' + c.name)
})

// Deep dive on the biggest chunk (index.js).
const indexChunk = chunks.find(c => c.name.includes('index-') && c.name.endsWith('.js'))
if (indexChunk) {
  console.log('\n=== TOP 30 MODULES INSIDE ' + indexChunk.name + ' ===')
  const rows = indexChunk.uids.map(uid => ({
    uid, path: pathByUid.get(uid) || uid,
    ren: (parts[uid] || {}).renderedLength || 0,
    gz: (parts[uid] || {}).gzipLength || 0,
  }))
  rows.sort((a, b) => b.ren - a.ren)
  rows.slice(0, 30).forEach(r => {
    const short = r.path
      .replace(/^root\/assets\/[^/]+\.js\//, '')
      .replace(/^D:\/GIT-REPOS\/thb-shioai\/multiship-react\//, '')
    console.log('  ' + String(Math.round(r.ren / 1024)).padStart(4) + ' KB / ' +
      String(Math.round(r.gz / 1024)).padStart(3) + ' KB gz  ' + short)
  })
}

// zod attribution.
let zodRen = 0, zodGz = 0, zodFiles = 0
chunks.forEach(c => c.uids.forEach(uid => {
  const p = pathByUid.get(uid) || ''
  if (p.includes('node_modules/zod')) {
    zodRen += (parts[uid] || {}).renderedLength || 0
    zodGz += (parts[uid] || {}).gzipLength || 0
    zodFiles++
  }
}))
console.log('\n=== ZOD TOTAL (across all chunks) ===')
console.log('  ' + Math.round(zodRen / 1024) + ' KB / ' + Math.round(zodGz / 1024) + ' KB gz across ' + zodFiles + ' files')

// react-dom + scheduler.
let rdomRen = 0, rdomGz = 0
chunks.forEach(c => c.uids.forEach(uid => {
  const p = pathByUid.get(uid) || ''
  if (p.includes('node_modules/react-dom') || p.includes('node_modules/scheduler')) {
    rdomRen += (parts[uid] || {}).renderedLength || 0
    rdomGz += (parts[uid] || {}).gzipLength || 0
  }
}))
console.log('\n=== REACT-DOM + SCHEDULER TOTAL ===')
console.log('  ' + Math.round(rdomRen / 1024) + ' KB / ' + Math.round(rdomGz / 1024) + ' KB gz')

// LabelDocumentPage attribution (eager import - candidate to move to lazy).
let labelRen = 0, labelGz = 0
chunks.forEach(c => c.uids.forEach(uid => {
  const p = pathByUid.get(uid) || ''
  if (p.includes('LabelDocumentPage')) {
    labelRen += (parts[uid] || {}).renderedLength || 0
    labelGz += (parts[uid] || {}).gzipLength || 0
  }
}))
console.log('\n=== LabelDocumentPage.tsx (currently eager in AppRoutes) ===')
console.log('  ' + Math.round(labelRen / 1024) + ' KB / ' + Math.round(labelGz / 1024) + ' KB gz')
