import { useMemo, useState } from 'react'
import { notify } from '../utils/notify'
import {
  FiBookOpen,
  FiCheck,
  FiChevronDown,
  FiCopy,
  FiExternalLink,
  FiEye,
  FiEyeOff,
  FiKey,
  FiPlay,
} from 'react-icons/fi'
import { BASE_URL } from '../api/apiClient'

/** Remember the tester's API key between visits (never sent anywhere but the API). */
const API_KEY_STORAGE = 'ms:externalApiKey'
/** Pull the {param} tokens out of a path template. */
const pathParamsOf = (path: string) => [...path.matchAll(/\{(\w+)\}/g)].map((m) => m[1])

/**
 * Base URL of the public shipping API v2, derived from the app's API client
 * (BASE_URL ends in /api/v1; the v2 controller lives at /api/v2/external).
 */
const EXTERNAL_BASE = `${BASE_URL.replace(/\/v1\/?$/, '/v2')}/external`

/** Fresh Idempotency-Key for the Try-it console. */
const newIdempotencyKey = () =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `try-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
/**
 * Swagger UI lives on the backend origin.
 *
 * Audit A3 — `new URL(BASE_URL)` crashes with TypeError when BASE_URL
 * is relative ('/api/v1' — the dev default per apiClient.ts). This
 * module is lazy-loaded so the crash surfaces as a white-screen the
 * first time an operator navigates to /settings/api-reference. Pass
 * the current window origin as the base so relative BASE_URLs resolve
 * against the SPA's origin (which is the same origin the Vite proxy
 * fronts). Absolute BASE_URLs pass through unchanged.
 */
const SWAGGER_URL = `${new URL(BASE_URL, typeof window !== 'undefined' ? window.location.origin : 'http://localhost').origin}/swagger-ui.html`

const METHOD_BADGE: Record<string, string> = {
  GET: 'bg-sky-50 text-sky-700 ring-sky-200',
  POST: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
}

interface Endpoint {
  method: 'GET' | 'POST'
  path: string
  title: string
  notes: string[]
  /** Required API-key scope (enforced by @RequiresScope on the controller). */
  scope: string
  /** Accepts an Idempotency-Key header (24h replay of the first response). */
  idempotent?: boolean
  request?: string
  response: string
  /** [HTTP status, errorCode(s), meaning] — mirrors the controller's @ApiResponses. */
  errors: Array<[string, string, string]>
}

const ENDPOINTS: Endpoint[] = [
  {
    method: 'POST',
    path: '/rates',
    title: 'List available services for a route',
    scope: 'RATES',
    idempotent: true,
    notes: [
      'Send a shipMethod to resolve the client’s mapped service, or a carrierCode (UPS / FEDEX / USPS) to list that carrier’s services. shipFrom, shipTo and parcel are optional — shipFrom/shipTo country codes decide the domestic vs international scope.',
      'Live pricing is not enabled in this environment — options list the services; the actual cost comes back when the shipment is created.',
    ],
    request: `{
  "clientCode": "ACME",
  "shipMethod": "F77",
  "carrierCode": "FEDEX",
  "shipFrom": { "countryCode": "US" },
  "shipTo": { "countryCode": "US" },
  "parcel": { "weight": 2.5, "weightUnit": "lb" }
}`,
    response: `{
  "status": "SUCCESS",
  "data": {
    "options": [
      { "carrier": "FEDEX", "serviceCode": "FEDEX_GROUND",
        "serviceName": "FedEx Ground", "scope": "DOMESTIC" }
    ],
    "pricingAvailable": false
  }
}`,
    errors: [
      ['400', 'VALIDATION_ERROR / UNIT_REQUIRED / CURRENCY_REQUIRED', 'Malformed body, or weight / currency given without its unit.'],
      ['403', 'FORBIDDEN', 'API key lacks the RATES scope.'],
      ['409', 'IDEMPOTENCY_IN_PROGRESS', 'A request with the same Idempotency-Key is still running.'],
      ['422', 'SERVICE_NOT_ALLOWED / PACKAGE_NOT_ALLOWED', 'The client may not use that service or package.'],
      ['429', 'API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED', 'Slow down and retry.'],
      ['502', 'CARRIER_FAILURE', 'The carrier did not answer.'],
    ],
  },
  {
    method: 'POST',
    path: '/shipments',
    title: 'Create a shipment and get the label',
    scope: 'SHIPMENTS',
    idempotent: true,
    notes: [
      'Send an Idempotency-Key header (any unique string, e.g. a UUID) on every call. A retry with the same key within 24h returns the first response instead of buying a second label. If Redis is down this endpoint fails closed with 503 rather than risk a double charge.',
      'Required: shipTo (with addressLine1) and parcel.weight (> 0). For a platform-wide (WMS) key, clientCode too. Everything else is optional.',
      'Optional with fallbacks — shipFrom: client’s configured ship-from, then the warehouse default. carrierCode (UPS / FEDEX / USPS): overrides the shipMethod rule. accountNumber: overrides the client’s default bill-to account. parcel.packagingCode: a package-preset name; omit to use the custom dims.',
      'refOrderNumber: your WMS’s own order number, recorded server-side for traceability and echoed back unchanged on the response — use it to match this API call to the order in your WMS.',
      'International only (origin ≠ destination country): items (description, sku, hsCode, countryOfOrigin, quantity, unitValue, weight), declaredValue, currency, reasonForExport (SALE | GIFT | SAMPLE | RETURN | REPAIR) and incoterms (DAP | DDP) feed the commercial invoice.',
      'isReturn: true creates a return label. Addresses take name, company, phone, email, addressLine1/2, city, state, postalCode, countryCode (ISO alpha-2).',
    ],
    request: `{
  "clientCode": "ACME",
  "reference": "SO-12345",
  "refOrderNumber": "REF-98765",
  "shipMethod": "F77",
  "carrierCode": "FEDEX",
  "accountNumber": "802255946",
  "shipFrom": {
    "name": "ACME Warehouse", "company": "ACME Inc",
    "phone": "5559876543", "email": "ops@acme.com",
    "addressLine1": "1 Depot Rd", "addressLine2": "Dock 4",
    "city": "Dallas", "state": "TX",
    "postalCode": "75201", "countryCode": "US"
  },
  "shipTo": {
    "name": "Jane Smith", "company": "",
    "phone": "5551234567", "email": "jane@example.com",
    "addressLine1": "10 Baker St", "addressLine2": "",
    "city": "London", "state": "",
    "postalCode": "NW1 6XE", "countryCode": "GB"
  },
  "parcel": {
    "weight": 2.5, "weightUnit": "lb",
    "length": 10, "width": 8, "height": 4, "dimUnit": "in",
    "packagingCode": "Medium Box"
  },
  "items": [
    { "description": "Cotton T-Shirt", "sku": "TS-01",
      "hsCode": "610910", "countryOfOrigin": "US",
      "quantity": 2, "unitValue": 25.00, "weight": 0.5 }
  ],
  "declaredValue": 50.00,
  "currency": "USD",
  "reasonForExport": "SALE",
  "incoterms": "DAP",
  "isReturn": false
}`,
    response: `{
  "status": "SUCCESS",
  "code": 201,
  "timestamp": "2026-10-19T10:30:00",
  "message": "Shipment #900123 created.",
  "data": {
    "shipmentId": 900123,
    "reference": "SO-12345",
    "refOrderNumber": "REF-98765",
    "clientCode": "ACME",
    "warehouseCode": "DAL01",
    "carrier": "FEDEX",
    "service": "FEDEX_GROUND",
    "resolvedVia": "CARRIER_OVERRIDE+SHIPMETHOD",
    "international": true,

    "trackingNumber": "794644790132",
    "trackingUrl": "https://...",
    "labelUrl": "https://...",
    "labelPdf": "<base64>",
    "labelDocumentUrl": "/api/v1/orders/ORD-900123/label",

    "status": "GENERATED",
    "estimatedDelivery": "2026-10-23T17:00:00",
    "dispatchNextBusinessDay": false,

    "carrierAmount": 11.27,
    "billableAmount": 12.40,
    "shippingCost": 12.40,
    "markupKind": "PERCENT",
    "markupValue": 10,
    "markupCurrency": "USD"
  }
}

// Field guide
// shipmentId              — use for /tracking and /void
// labelPdf                — the label itself; decode and print
// labelUrl                — stored copy of the label
// labelDocumentUrl        — internal path (needs a user login, not the API key)
// carrierAmount           — what the carrier charged
// billableAmount          — carrierAmount + client markup (what you bill)
// shippingCost            — same as billableAmount (kept for older clients)
// dispatchNextBusinessDay — true = created after the client's cutoff;
//                           label is valid, pickup moves to next business day
// warehouseCode           — null when the request's shipFrom was used`,
    errors: [
      ['400', 'VALIDATION_ERROR / UNIT_REQUIRED / CURRENCY_REQUIRED / MARKUP_REQUIRED_FOR_CLIENT', 'Missing or invalid fields (shipTo, parcel.weight, clientCode…).'],
      ['403', 'FORBIDDEN', 'API key lacks the SHIPMENTS scope, or names another client.'],
      ['409', 'IDEMPOTENCY_IN_PROGRESS', 'A request with the same Idempotency-Key is still running.'],
      ['422', 'SERVICE_NOT_ALLOWED / PACKAGE_NOT_ALLOWED / SHIP_TO_DENIED / CUSTOMS_REQUIRED', 'Business rule blocked the shipment.'],
      ['429', 'API_KEY_RATE_LIMITED / TENANT_RATE_LIMITED / CARRIER_RATE_LIMITED', 'Slow down and retry with the same Idempotency-Key.'],
      ['502', 'CARRIER_FAILURE / CLIENT_CARRIER_AUTH_FAILED', 'The carrier rejected the shipment or the client’s carrier credentials.'],
      ['503', 'IDEMPOTENCY_UNAVAILABLE', 'Idempotency store is down — nothing was created; retry later.'],
    ],
  },
  {
    method: 'GET',
    path: '/shipments/{shipmentId}/tracking',
    title: 'Current tracking status',
    scope: 'TRACKING',
    notes: [
      'Does a live carrier lookup and degrades to the last-known platform status if the carrier is unreachable.',
      'Returns 404 for shipments the key’s client does not own; 409 if no tracking number exists yet.',
    ],
    response: `{
  "status": "SUCCESS",
  "data": {
    "shipmentId": 900123,
    "trackingNumber": "794644790132",
    "carrier": "FEDEX", "status": "IN_TRANSIT",
    "currentLocation": "MEMPHIS, TN",
    "estimatedDelivery": "2026-07-25T17:00:00",
    "delivered": false
  }
}`,
    errors: [
      ['403', 'FORBIDDEN / CROSS_TENANT_ACCESS_DENIED', 'Missing TRACKING scope, or the shipment belongs to another client.'],
      ['404', 'ORDER_NOT_FOUND', 'Unknown shipmentId.'],
      ['429', 'API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED', 'Slow down and retry.'],
      ['502', 'CARRIER_FAILURE', 'Carrier lookup failed.'],
    ],
  },
  {
    method: 'POST',
    path: '/shipments/{shipmentId}/void',
    title: 'Void a shipment',
    scope: 'VOID',
    idempotent: true,
    notes: [
      'Platform-level cancellation — the order and tracking are marked VOIDED. Carrier-side label cancellation is not wired; ensure the label is not used.',
      'Voiding an already-voided shipment returns 409.',
    ],
    response: `{
  "status": "SUCCESS",
  "data": {
    "shipmentId": 900123,
    "status": "VOIDED",
    "carrierVoided": false
  }
}`,
    errors: [
      ['400', 'VALIDATION_ERROR', 'Bad shipmentId.'],
      ['403', 'FORBIDDEN / CROSS_TENANT_ACCESS_DENIED', 'Missing VOID scope, or the shipment belongs to another client.'],
      ['404', 'ORDER_NOT_FOUND', 'Unknown shipmentId.'],
      ['409', 'IDEMPOTENCY_IN_PROGRESS / LABEL_ALREADY_GENERATED', 'Same Idempotency-Key still running, or the shipment cannot be voided in its state.'],
      ['422', 'BUSINESS_RULE_VIOLATION', 'Carrier refuses (past pickup, delivered, …).'],
      ['429', 'API_KEY_RATE_LIMITED / CARRIER_RATE_LIMITED', 'Slow down and retry.'],
      ['502', 'CARRIER_FAILURE', 'Carrier void call failed.'],
      ['503', 'IDEMPOTENCY_UNAVAILABLE', 'Idempotency store is down — nothing was voided; retry later.'],
    ],
  },
  {
    method: 'POST',
    path: '/addresses/validate',
    title: 'Validate an address (structural)',
    scope: 'ADDRESSES',
    notes: [
      'Checks required fields, 2-letter ISO country and US ZIP format, and returns a normalized copy. No carrier validation.',
    ],
    request: `{
  "name": "John Doe",
  "addressLine1": "123 Main St", "city": "Dallas",
  "state": "tx", "postalCode": "75201", "countryCode": "us"
}`,
    response: `{
  "status": "SUCCESS",
  "data": {
    "valid": true, "issues": [],
    "normalized": { "state": "TX", "countryCode": "US", ... }
  }
}`,
    errors: [
      ['400', 'VALIDATION_ERROR', 'Malformed body.'],
      ['403', 'FORBIDDEN', 'API key lacks the ADDRESSES scope.'],
      ['429', 'API_KEY_RATE_LIMITED', 'Slow down and retry.'],
      ['502', 'CARRIER_FAILURE', 'Validation backend failed.'],
    ],
  },
]

/** Errors any endpoint can return, regardless of its own list. */
const ERROR_ROWS: Array<[string, string, string]> = [
  ['401', 'UNAUTHORIZED', 'Missing / invalid / revoked API key or expired OAuth token.'],
  ['403', 'FORBIDDEN', 'Key lacks the endpoint’s scope, or a client-bound key named another clientCode.'],
  ['429', 'API_KEY_RATE_LIMITED', 'Per-key rate limit hit — back off and retry.'],
]

const ERROR_FAILURE_ENVELOPE = `{
  "status": "ERROR",
  "code": 422,
  "timestamp": "2026-10-19T10:30:00",
  "message": "Ship-to country is not allowed for this client.",
  "errorCode": "SHIP_TO_DENIED"
}`

/** Status / errorCode / meaning table. */
function ErrorTable({ rows }: { rows: Array<[string, string, string]> }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left">
        <tbody className="divide-y divide-dashed divide-slate-100">
          {rows.map(([status, code, desc], i) => (
            <tr key={i}>
              <td className="w-12 py-1.5 pr-3 align-top font-mono text-[11px] font-bold text-slate-700">{status}</td>
              <td className="py-1.5 pr-3 align-top">
                <span className="rounded bg-rose-50 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-rose-600 [overflow-wrap:anywhere]">
                  {code}
                </span>
              </td>
              <td className="py-1.5 align-top text-[11.5px] text-slate-500">{desc}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** A code sample with a copy control. */
function CodeBlock({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      notify.error('Copy failed.')
    }
  }
  return (
    <div className="relative">
      <pre className="overflow-x-auto rounded-xl border border-slate-200 bg-[#faf9f7] px-3 py-2.5 font-mono text-[11px] leading-relaxed text-slate-700">
        {code}
      </pre>
      <button
        type="button"
        onClick={() => void copy()}
        aria-label="Copy sample"
        className="absolute right-2 top-2 inline-flex h-6 w-6 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-400 transition hover:text-slate-600"
      >
        {copied ? <FiCheck className="h-3 w-3 text-emerald-600" /> : <FiCopy className="h-3 w-3" />}
      </button>
    </div>
  )
}

interface TryResult {
  status: number
  ok: boolean
  ms: number
  text: string
}

/** Live "try it" console for one endpoint — fills path params + body, sends with the API key, shows the response. */
function TryIt({ ep, apiKey }: { ep: Endpoint; apiKey: string }) {
  const params = useMemo(() => pathParamsOf(ep.path), [ep.path])
  const [pathValues, setPathValues] = useState<Record<string, string>>({})
  const [body, setBody] = useState(ep.request ?? '')
  const [idemKey, setIdemKey] = useState(newIdempotencyKey)
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState<TryResult | null>(null)

  const resolvedPath = params.reduce(
    (p, name) => p.replace(`{${name}}`, pathValues[name]?.trim() ? encodeURIComponent(pathValues[name].trim()) : `{${name}}`),
    ep.path,
  )
  const url = `${EXTERNAL_BASE}${resolvedPath}`
  const hasBody = ep.method === 'POST' && ep.request !== undefined

  const send = async () => {
    if (!apiKey.trim()) return notify.invalid('Enter your API key at the top first.')
    for (const name of params) {
      if (!pathValues[name]?.trim()) return notify.error(`Fill in "${name}".`)
    }
    let payload: string | undefined
    if (hasBody) {
      try {
        JSON.parse(body || '{}')
      } catch {
        return notify.invalid('Request body is not valid JSON.')
      }
      payload = body
    }
    setSending(true)
    setResult(null)
    const started = performance.now()
    try {
      const res = await fetch(url, {
        method: ep.method,
        headers: {
          'X-API-Key': apiKey.trim(),
          ...(ep.idempotent && idemKey.trim() ? { 'Idempotency-Key': idemKey.trim() } : {}),
          ...(payload !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        body: payload,
      })
      const raw = await res.text()
      let text = raw
      try {
        text = JSON.stringify(JSON.parse(raw), null, 2)
      } catch {
        /* non-JSON response — show raw */
      }
      setResult({ status: res.status, ok: res.ok, ms: Math.round(performance.now() - started), text })
    } catch (e) {
      setResult({ status: 0, ok: false, ms: Math.round(performance.now() - started), text: `Network error: ${e instanceof Error ? e.message : String(e)}` })
    } finally {
      setSending(false)
    }
  }

  return (
    <div className="space-y-2.5 rounded-xl border border-slate-200 bg-white p-3">
      <div className="flex items-center gap-2">
        <span className={`inline-flex w-14 shrink-0 justify-center rounded-full px-2 py-0.5 font-mono text-[10px] font-black ring-1 ${METHOD_BADGE[ep.method]}`}>
          {ep.method}
        </span>
        <code className="min-w-0 flex-1 truncate font-mono text-[11px] text-slate-500" title={url}>
          {url}
        </code>
        <button
          type="button"
          onClick={() => void send()}
          disabled={sending}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
        >
          {sending ? (
            <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
          ) : (
            <FiPlay className="h-3 w-3" />
          )}
          {sending ? 'Sending…' : 'Send'}
        </button>
      </div>

      {params.length ? (
        <div className="grid gap-2 sm:grid-cols-2">
          {params.map((name) => (
            <label key={name} className="block">
              <span className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">{name}</span>
              <input
                value={pathValues[name] ?? ''}
                onChange={(e) => setPathValues((v) => ({ ...v, [name]: e.target.value }))}
                placeholder={name === 'shipmentId' ? 'e.g. 900123' : name}
                className="mt-0.5 w-full rounded-lg border border-slate-200 px-2.5 py-1.5 font-mono text-[12px] text-slate-800 outline-none focus:border-slate-400"
              />
            </label>
          ))}
        </div>
      ) : null}

      {ep.idempotent ? (
        <label className="block">
          <span className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
            Idempotency-Key header · same key replays the first response
          </span>
          <div className="mt-0.5 flex gap-2">
            <input
              value={idemKey}
              onChange={(e) => setIdemKey(e.target.value)}
              spellCheck={false}
              className="min-w-0 flex-1 rounded-lg border border-slate-200 px-2.5 py-1.5 font-mono text-[12px] text-slate-800 outline-none focus:border-slate-400"
            />
            <button
              type="button"
              onClick={() => setIdemKey(newIdempotencyKey())}
              className="shrink-0 rounded-lg border border-slate-200 px-2.5 text-[11px] font-semibold text-slate-600 hover:bg-slate-50"
            >
              New key
            </button>
          </div>
        </label>
      ) : null}

      {hasBody ? (
        <div>
          <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Request body (editable)</p>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            spellCheck={false}
            rows={Math.min(16, (body.match(/\n/g)?.length ?? 0) + 2)}
            className="w-full resize-y rounded-lg border border-slate-200 bg-[#faf9f7] px-3 py-2 font-mono text-[11px] leading-relaxed text-slate-700 outline-none focus:border-slate-400"
          />
        </div>
      ) : null}

      {result ? (
        <div>
          <div className="mb-1 flex items-center gap-2">
            <span className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Response</span>
            <span
              className={`rounded px-1.5 py-0.5 font-mono text-[10px] font-bold ${
                result.ok ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-600'
              }`}
            >
              {result.status || 'ERR'}
            </span>
            <span className="text-[10px] text-slate-400">{result.ms} ms</span>
          </div>
          <pre
            className={`max-h-80 overflow-auto rounded-lg border px-3 py-2.5 font-mono text-[11px] leading-relaxed ${
              result.ok ? 'border-emerald-100 bg-emerald-50/40 text-slate-700' : 'border-rose-100 bg-rose-50/40 text-slate-700'
            }`}
          >
            {result.text}
          </pre>
        </div>
      ) : null}
    </div>
  )
}

/**
 * Reference card for the public shipping API (/api/v2/external) — rendered on
 * the API Keys settings page so the docs live next to where keys are minted.
 */
export default function ExternalApiReference() {
  const [open, setOpen] = useState<string | null>(null)
  /** Audit R2 #392 — hidden by default (shoulder-surf / screen-share).
   *  Toggle re-shows so operators can visually verify a paste. */
  const [showApiKey, setShowApiKey] = useState(false)
  const [apiKey, setApiKey] = useState(() => {
    try {
      // Sprint 50 PR P post-audit #16 — sessionStorage instead of
      // localStorage: the API-key playground is a developer convenience;
      // persisting a live msk_ key across sessions is a real leak vector
      // if any XSS gets in. sessionStorage dies on tab close.
      return sessionStorage.getItem(API_KEY_STORAGE) || ''
    } catch {
      return ''
    }
  })
  const updateKey = (v: string) => {
    setApiKey(v)
    try {
      sessionStorage.setItem(API_KEY_STORAGE, v)
    } catch {
      /* ignore storage errors */
    }
  }

  return (
    <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
      <div className="flex items-center justify-between gap-2 bg-[#1f150c] px-4 py-2.5">
        <p className="flex min-w-0 items-center gap-2.5">
          <span className="flex h-6 w-9 shrink-0 items-center justify-center rounded bg-[#e1dcc9]/15 text-[#e1dcc9]">
            <FiBookOpen className="h-3.5 w-3.5" />
          </span>
          <span className="truncate text-[10px] font-black uppercase tracking-[0.2em] text-[#e1dcc9]">
            External API reference
          </span>
        </p>
        <a
          href={SWAGGER_URL}
          target="_blank"
          rel="noreferrer"
          className="inline-flex shrink-0 items-center gap-1 rounded bg-[#e1dcc9]/15 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-[#e1dcc9] transition hover:bg-[#e1dcc9]/25"
        >
          <FiExternalLink className="h-3 w-3" />
          Swagger UI
        </a>
      </div>

      {/* auth + base URL */}
      <div className="space-y-2 border-b border-dashed border-slate-200 px-4 py-3">
        <p className="text-[12px] text-slate-600">
          Authenticate every call with the API key header — either{' '}
          <span className="font-mono font-semibold text-slate-700">X-API-Key: msk_…</span> or{' '}
          <span className="font-mono font-semibold text-slate-700">Authorization: Bearer msk_…</span>. All responses use
          the envelope <span className="font-mono">{'{ status, code, message, data, errorCode }'}</span>.
        </p>
        <label className="block">
          <span className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Your API key · used for “Try it”</span>
          <div className="relative mt-1">
            <FiKey className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-300" />
            {/* Audit R2 #392 — hidden by default so a screen-share doesn't
                leak the key. Password type masks the value; eye toggle
                flips to plain-text for visual paste verification. */}
            <input
              type={showApiKey ? 'text' : 'password'}
              value={apiKey}
              onChange={(e) => updateKey(e.target.value)}
              placeholder="msk_live_…"
              spellCheck={false}
              autoComplete="off"
              className="w-full rounded-xl border border-slate-200 bg-white py-2 pl-8 pr-9 font-mono text-[12px] text-slate-800 outline-none focus:border-slate-400"
            />
            <button
              type="button"
              onClick={() => setShowApiKey((v) => !v)}
              aria-label={showApiKey ? 'Hide API key' : 'Show API key'}
              title={showApiKey ? 'Hide' : 'Show'}
              className="absolute right-2 top-1/2 -translate-y-1/2 rounded p-1 text-slate-400 hover:text-slate-700"
            >
              {showApiKey ? <FiEyeOff className="h-3.5 w-3.5" /> : <FiEye className="h-3.5 w-3.5" />}
            </button>
          </div>
          <span className="mt-1 block text-[10.5px] text-slate-400">
            Stays in your browser; sent only as the <span className="font-mono">X-API-Key</span> header when you press Send.
          </span>
        </label>
        <p className="flex flex-wrap items-center gap-1.5">
          <span className="text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Base URL</span>
          <code className="rounded bg-slate-100 px-2 py-0.5 font-mono text-[11px] font-semibold text-slate-700">
            {EXTERNAL_BASE}
          </code>
        </p>
        <p className="rounded-xl border border-violet-100 bg-violet-50/60 px-3 py-2 text-[11.5px] text-violet-900">
          <span className="font-bold uppercase tracking-wide">Platform-wide (WMS) keys:</span> requests must name the
          client via <span className="font-mono font-semibold">clientCode</span> in the body. Client-bound keys may omit
          it — the key itself decides the client.
        </p>
      </div>

      {/* endpoint accordion */}
      {/* Audit A7 (#393) — the ENDPOINTS array below is a hand-curated
          quick-reference, not an exhaustive catalog. Partners who need
          the full public API surface (webhooks, API keys, admin paths
          added post-Sprint 46) follow the Swagger UI link in the
          header. Framing + explicit "full list elsewhere" cue prevents
          operators from assuming the 5-row list is comprehensive. */}
      <div className="flex items-start gap-2 border-b border-dashed border-slate-200 bg-amber-50/40 px-4 py-2.5 text-[11.5px] text-amber-900">
        <FiExternalLink className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-700" />
        <p>
          Quick reference — the 5 most-used endpoints. The{' '}
          <a
            href={SWAGGER_URL}
            target="_blank"
            rel="noreferrer"
            className="font-semibold underline hover:text-amber-700"
          >
            Swagger UI
          </a>{' '}
          carries the full public API catalog (webhooks, api-keys, admin paths,
          and anything added since this page was hand-updated).
        </p>
      </div>
      <ul className="divide-y divide-dashed divide-slate-200">
        {ENDPOINTS.map((ep) => {
          const key = `${ep.method} ${ep.path}`
          const isOpen = open === key
          return (
            <li key={key}>
              <button
                type="button"
                onClick={() => setOpen(isOpen ? null : key)}
                className="flex w-full items-center gap-2.5 px-4 py-2.5 text-left transition hover:bg-slate-50"
              >
                <span
                  className={`inline-flex w-14 shrink-0 justify-center rounded-full px-2 py-0.5 font-mono text-[10px] font-black tracking-wide ring-1 ${METHOD_BADGE[ep.method]}`}
                >
                  {ep.method}
                </span>
                <code className="min-w-0 shrink truncate font-mono text-[11.5px] font-semibold text-slate-800">{ep.path}</code>
                <span className="min-w-0 flex-1 truncate text-[11.5px] text-slate-400">{ep.title}</span>
                <FiChevronDown
                  className={`h-4 w-4 shrink-0 text-slate-400 transition-transform ${isOpen ? 'rotate-180' : ''}`}
                />
              </button>

              {isOpen ? (
                <div className="space-y-3 border-t border-dashed border-slate-100 bg-[#fdfcfb] px-4 py-3">
                  <p className="flex flex-wrap items-center gap-1.5 text-[10.5px] text-slate-500">
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono font-semibold text-slate-700">
                      {ep.method} {EXTERNAL_BASE}
                      {ep.path}
                    </span>
                    <span className="rounded bg-indigo-50 px-1.5 py-0.5 font-mono font-semibold text-indigo-700">
                      scope: {ep.scope}
                    </span>
                    {ep.idempotent ? (
                      <span className="rounded bg-amber-50 px-1.5 py-0.5 font-mono font-semibold text-amber-700">
                        Idempotency-Key supported
                      </span>
                    ) : null}
                  </p>
                  <ul className="list-disc space-y-1 pl-4">
                    {ep.notes.map((n) => (
                      <li key={n} className="text-[11.5px] leading-relaxed text-slate-600">
                        {n}
                      </li>
                    ))}
                  </ul>
                  <div className={`grid gap-3 ${ep.request ? 'lg:grid-cols-2' : ''}`}>
                    {ep.request ? (
                      <div className="min-w-0">
                        <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Request body</p>
                        <CodeBlock code={ep.request} />
                      </div>
                    ) : null}
                    <div className="min-w-0">
                      <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
                        Response body · success
                      </p>
                      <CodeBlock code={ep.response} />
                    </div>
                  </div>
                  <div>
                    <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Errors</p>
                    <ErrorTable rows={ep.errors} />
                  </div>
                  <details className="group">
                    <summary className="cursor-pointer list-none text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400 transition hover:text-slate-600">
                      <FiChevronDown className="mr-1 inline h-3.5 w-3.5 transition-transform group-open:rotate-180" />
                      Try it
                    </summary>
                    <div className="mt-1.5">
                      <TryIt ep={ep} apiKey={apiKey} />
                    </div>
                  </details>
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>

      {/* error codes */}
      <div className="border-t border-dashed border-slate-200 px-4 py-3">
        <p className="mb-2 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
          Errors every endpoint can return
        </p>
        <ErrorTable rows={ERROR_ROWS} />
        <p className="mb-1 mt-3 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
          Error response body
        </p>
        <CodeBlock code={ERROR_FAILURE_ENVELOPE} />
      </div>

      {/* quick start */}
      <div className="border-t border-dashed border-slate-200 px-4 py-3">
        <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Quick start (curl)</p>
        <CodeBlock
          code={`curl -X POST ${EXTERNAL_BASE}/shipments \\
  -H "X-API-Key: msk_live_..." \\
  -H "Idempotency-Key: 6f1c2e0a-order-SO-12345" \\
  -H "Content-Type: application/json" \\
  -d '{ "clientCode": "ACME", "reference": "SO-12345", "shipMethod": "F77",
        "shipTo": { "name": "Jane Smith", "addressLine1": "456 Oak Ave",
                    "city": "Austin", "state": "TX", "postalCode": "73301",
                    "countryCode": "US" },
        "parcel": { "weight": 2.5 } }'`}
        />
      </div>
    </section>
  )
}
