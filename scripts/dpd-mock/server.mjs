// Fake DPD UK (MyDPD) API for local testing — DPD publishes no sandbox.
// Answers the same JSON shapes DpdConnector expects: login → geoSession,
// network list, create shipment → consignment + parcel numbers, label/manifest
// as a tiny PDF, search, void, manifest. State lives in memory.
//
//   node scripts/dpd-mock/server.mjs            # http://localhost:8099
//   DPD_MOCK_PORT=9000 node scripts/dpd-mock/server.mjs
//
// Any username works; the password "wrong" is rejected with 401 so the
// failure path can be exercised too.
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const PORT = Number(process.env.DPD_MOCK_PORT || 8099)
// State survives restarts so labels bought earlier can still be voided / manifested.
const STATE_FILE = process.env.DPD_MOCK_STATE || path.join(os.tmpdir(), 'dpd-mock-state.json')
const shipments = new Map() // shipmentId -> { consignmentNumber, parcelNumbers, voided, manifestId, body }
let nextShipmentId = 1807000001
let nextConsignment = 15500000000001n
let nextManifest = 90001
try {
  const saved = JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'))
  for (const [id, s] of Object.entries(saved.shipments || {})) shipments.set(id, s)
  nextShipmentId = saved.nextShipmentId ?? nextShipmentId
  nextConsignment = BigInt(saved.nextConsignment ?? nextConsignment)
  nextManifest = saved.nextManifest ?? nextManifest
  console.log(`Loaded ${shipments.size} shipment(s) from ${STATE_FILE}`)
} catch { /* first run */ }
const persist = () => fs.writeFileSync(STATE_FILE, JSON.stringify({
  shipments: Object.fromEntries(shipments), nextShipmentId, nextConsignment: String(nextConsignment), nextManifest,
}))

const NETWORKS = [
  ['1^12', 'DPD Next Day'], ['1^13', 'DPD By 12:00'], ['1^16', 'DPD By 10:30'],
  ['1^32', 'DPD Two Day'], ['1^09', 'DPD Saturday'], ['1^91', 'DPD Classic'],
]

const json = (res, status, payload) => {
  res.writeHead(status, { 'content-type': 'application/json' })
  res.end(JSON.stringify(payload))
}
const ok = (res, data) => json(res, 200, { error: null, data })
const fail = (res, status, errorType, errorMessage, obj) =>
  json(res, status, { error: { errorCode: status, errorType, obj, errorMessage }, data: null })

// Minimal single-page 4x6 PDF that looks like a parcel label: text lines plus a
// fake barcode (just bars — not scannable). Enough for previews and print tests.
function pdf(lines, barcodeText) {
  const esc = t => String(t ?? '').replace(/[()\\]/g, '')
  let y = 405
  let content = ''
  const text = (t, size, x = 18) => { content += `BT /F1 ${size} Tf ${x} ${y} Td (${esc(t)}) Tj ET\n`; y -= size + 6 }
  lines.forEach(([t, size]) => text(t, size))
  if (barcodeText) {
    y -= 10
    let x = 24
    for (const ch of barcodeText) {
      const w = 1 + (ch.charCodeAt(0) % 3)
      content += `${x} ${y - 60} ${w} 60 re f\n`
      x += w + 2
    }
    y -= 72
    text(barcodeText, 10, 24)
  }
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 288 432] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>',
  ]
  let out = '%PDF-1.4\n'
  const offsets = []
  objects.forEach((o, i) => { offsets.push(out.length); out += `${i + 1} 0 obj\n${o}\nendobj\n` })
  const xref = out.length
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  offsets.forEach(o => { out += `${String(o).padStart(10, '0')} 00000 n \n` })
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}

function labelPdf(s) {
  const c = s.body?.consignment?.[0] ?? {}
  const to = c.deliveryDetails ?? {}, from = c.collectionDetails ?? {}
  return pdf([
    ['DPD  (MOCK - NOT A REAL LABEL)', 13],
    [`Service ${c.networkCode ?? '?'}   ${c.numberOfParcels ?? 1} parcel(s)   ${c.totalWeight ?? '?'} kg`, 9],
    ['', 6],
    ['TO', 8],
    [to.contactDetails?.contactName, 12],
    [to.address?.organisation, 10],
    [to.address?.street, 10],
    [[to.address?.town, to.address?.county].filter(Boolean).join(', '), 10],
    [to.address?.postcode, 16],
    ['', 6],
    [`FROM  ${from.address?.organisation ?? from.contactDetails?.contactName ?? ''}  ${from.address?.postcode ?? ''}`, 8],
    [`Ref ${c.shippingRef1 ?? '-'}`, 8],
  ], s.parcelNumbers[0] ?? s.consignmentNumber)
}

const readBody = req => new Promise(resolve => {
  let data = ''
  req.on('data', c => { data += c })
  req.on('end', () => { try { resolve(data ? JSON.parse(data) : {}) } catch { resolve({}) } })
})

http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)
  const path = url.pathname
  const geoClient = req.headers.geoclient || ''
  console.log(`${req.method} ${req.url}  GEOClient=${geoClient || '-'}`)

  if (!/^account\/\S+$/.test(geoClient)) return fail(res, 401, 'AUTH', 'GEOClient header missing or malformed')

  if (req.method === 'POST' && path === '/user/' && url.searchParams.get('action') === 'login') {
    const basic = (req.headers.authorization || '').replace(/^Basic\s+/i, '')
    const [user, pass] = Buffer.from(basic, 'base64').toString().split(':')
    if (!user || !pass || pass === 'wrong') return fail(res, 401, 'AUTH', 'Invalid username or password')
    return ok(res, { geoSession: `MOCK-${Date.now().toString(36)}`, user })
  }

  if (!/^MOCK-/.test(req.headers.geosession || '')) return fail(res, 401, 'AUTH', 'Session expired')

  if (req.method === 'GET' && path === '/shipping/network') {
    const intl = url.searchParams.get('deliveryDetails.address.countryCode') !== 'GB'
    return ok(res, NETWORKS.filter(([code]) => intl ? code === '1^91' : code !== '1^91').map(([code, name]) => ({
      network: { networkCode: code, networkDescription: name },
      product: { productCode: code.split('^')[1], productDescription: 'Parcel' },
      service: { serviceCode: code.split('^')[0], serviceDescription: name.replace('DPD ', '') },
    })))
  }

  if (req.method === 'POST' && path === '/shipping/shipment') {
    const body = await readBody(req)
    const c = body.consignment?.[0]
    if (!c) return fail(res, 400, 'VALIDATION', 'consignment is required', 'consignment')
    const postcode = c.deliveryDetails?.address?.postcode
    if (!postcode) return fail(res, 400, 'VALIDATION', 'Invalid postcode', 'consignment[0].deliveryDetails.address.postcode')
    if (!c.networkCode) return fail(res, 400, 'VALIDATION', 'networkCode is required', 'consignment[0].networkCode')
    const shipmentId = nextShipmentId++
    const consignmentNumber = String(nextConsignment++)
    const n = Math.max(1, Number(c.numberOfParcels) || 1)
    const parcelNumbers = Array.from({ length: n }, (_, i) => consignmentNumber + String.fromCharCode(65 + i))
    shipments.set(String(shipmentId), { consignmentNumber, parcelNumbers, voided: false, manifestId: null, body })
    persist()
    return ok(res, { shipmentId, consolidated: false, consignmentDetail: [{ consignmentNumber, parcelNumbers }] })
  }

  if (req.method === 'GET' && path === '/shipping/shipment/') {
    const wanted = url.searchParams.get('searchCriteria.consignmentNumber')
    const results = [...shipments.entries()]
      .filter(([, s]) => !wanted || s.consignmentNumber === wanted || s.parcelNumbers.includes(wanted))
      .map(([id, s]) => ({ shipmentId: Number(id), isVoided: s.voided,
        consignmentDetail: [{ consignmentNumber: s.consignmentNumber, parcelNumbers: s.parcelNumbers }] }))
    return ok(res, { results, totalResults: results.length })
  }

  let m = path.match(/^\/shipping\/shipment\/(\d+)\/label\/?$/)
  if (req.method === 'GET' && m) {
    const s = shipments.get(m[1])
    if (!s) return fail(res, 404, 'NOT_FOUND', `Shipment ${m[1]} not found`)
    if (s.voided) return fail(res, 400, 'VALIDATION', `Shipment ${m[1]} is voided`)
    res.writeHead(200, { 'content-type': req.headers.accept?.includes('html') ? 'text/html' : 'application/pdf' })
    return res.end(req.headers.accept?.includes('html')
      ? `<h1>DPD MOCK LABEL ${s.consignmentNumber}</h1>` : labelPdf(s))
  }

  m = path.match(/^\/shipping\/shipment\/(\d+)\/?$/)
  if (req.method === 'PUT' && m && url.searchParams.get('action') === 'Void') {
    const s = shipments.get(m[1])
    if (!s) return fail(res, 404, 'NOT_FOUND', `Shipment ${m[1]} not found`)
    if (s.manifestId) return fail(res, 400, 'VALIDATION', 'Shipment already manifested — cannot void')
    s.voided = true
    persist()
    return ok(res, { shipmentId: Number(m[1]), isVoided: true })
  }

  if (req.method === 'POST' && path === '/shipping/manifest/') {
    const open = [...shipments.values()].filter(s => !s.voided && !s.manifestId)
    if (open.length === 0) return ok(res, [])
    const manifestId = String(nextManifest++)
    open.forEach(s => { s.manifestId = manifestId })
    persist()
    return ok(res, [{ manifestId, consignmentCount: open.length, collectionDate: url.searchParams.get('collectionDate') }])
  }

  m = path.match(/^\/shipping\/manifest\/(\d+)\/?$/)
  if (req.method === 'GET' && m) {
    res.writeHead(200, { 'content-type': 'application/pdf' })
    const rows = [...shipments.values()].filter(x => x.manifestId === m[1])
    return res.end(pdf([['DPD MANIFEST (MOCK)', 14], [`Manifest ${m[1]}  -  ${rows.length} consignment(s)`, 10], ['', 6],
      ...rows.map(x => [`${x.consignmentNumber}  ${x.body?.consignment?.[0]?.deliveryDetails?.address?.postcode ?? ''}`, 9])]))
  }

  return fail(res, 404, 'NOT_FOUND', `No mock route for ${req.method} ${path}`)
}).listen(PORT, () => console.log(`DPD mock API listening on http://localhost:${PORT}`))
