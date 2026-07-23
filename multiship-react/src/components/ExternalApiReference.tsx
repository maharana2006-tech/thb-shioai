import { useState } from 'react'
import toast from 'react-hot-toast'
import {
  FiBookOpen,
  FiCheck,
  FiChevronDown,
  FiCopy,
  FiExternalLink,
} from 'react-icons/fi'
import { BASE_URL } from '../api/apiClient'

/** Base URL of the public shipping API, derived from the app's API client. */
const EXTERNAL_BASE = `${BASE_URL}/external`
/** Swagger UI lives on the backend origin. */
const SWAGGER_URL = `${new URL(BASE_URL).origin}/swagger-ui.html`

const METHOD_BADGE: Record<string, string> = {
  GET: 'bg-sky-50 text-sky-700 ring-sky-200',
  POST: 'bg-emerald-50 text-emerald-700 ring-emerald-200',
}

interface Endpoint {
  method: 'GET' | 'POST'
  path: string
  title: string
  notes: string[]
  request?: string
  response: string
}

const ENDPOINTS: Endpoint[] = [
  {
    method: 'POST',
    path: '/rates',
    title: 'List available services for a route',
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
  },
  {
    method: 'POST',
    path: '/shipments',
    title: 'Create a shipment and get the label',
    notes: [
      'Required: shipTo (with addressLine1) and parcel.weight (> 0). For a platform-wide (WMS) key, clientCode too. Everything else is optional.',
      'Optional with fallbacks — shipFrom: client’s configured ship-from, then the warehouse default. carrierCode (UPS / FEDEX / USPS): overrides the shipMethod rule. accountNumber: overrides the client’s default bill-to account. parcel.packagingCode: a package-preset name; omit to use the custom dims.',
      'International only (origin ≠ destination country): items (description, sku, hsCode, countryOfOrigin, quantity, unitValue, weight), declaredValue, currency, reasonForExport (SALE | GIFT | SAMPLE | RETURN | REPAIR) and incoterms (DAP | DDP) feed the commercial invoice.',
      'isReturn: true creates a return label. Addresses take name, company, phone, email, addressLine1/2, city, state, postalCode, countryCode (ISO alpha-2).',
    ],
    request: `{
  "clientCode": "ACME",
  "reference": "SO-12345",
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
  "status": "SUCCESS", "code": 201,
  "data": {
    "shipmentId": 900123,
    "carrier": "FEDEX", "service": "FEDEX_GROUND",
    "trackingNumber": "794644790132",
    "trackingUrl": "https://...",
    "labelUrl": "https://...",
    "labelPdf": "<base64>",
    "shippingCost": 12.40,
    "status": "GENERATED"
  }
}`,
  },
  {
    method: 'GET',
    path: '/shipments/{shipmentId}/tracking',
    title: 'Current tracking status',
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
  },
  {
    method: 'POST',
    path: '/shipments/{shipmentId}/void',
    title: 'Void a shipment',
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
  },
  {
    method: 'POST',
    path: '/addresses/validate',
    title: 'Validate an address (structural)',
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
  },
]

const ERROR_ROWS: Array<[string, string, string]> = [
  ['401', 'UNAUTHORIZED', 'Missing / invalid / revoked API key.'],
  ['403', 'UNAUTHORIZED', 'A client-bound key sent a clientCode that is not its own.'],
  ['404', 'ORDER_NOT_FOUND', 'Unknown shipment, or one owned by another client.'],
  ['409', 'VALIDATION_ERROR', 'No tracking number yet, or the shipment is already voided.'],
  ['422', 'VALIDATION_ERROR', 'Missing / invalid fields (shipTo, parcel.weight, clientCode…).'],
  ['422', 'NO_DEFAULT_ACCOUNT', 'No carrier account available — add one or send accountNumber.'],
  ['502', 'CARRIER_FAILURE', 'The carrier rejected the shipment.'],
]

/** A code sample with a copy control. */
function CodeBlock({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      toast.error('Copy failed.')
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

/**
 * Reference card for the public shipping API (/api/v1/external) — rendered on
 * the API Keys settings page so the docs live next to where keys are minted.
 */
export default function ExternalApiReference() {
  const [open, setOpen] = useState<string | null>(null)

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
                <code className="shrink-0 font-mono text-[11.5px] font-semibold text-slate-800">{ep.path}</code>
                <span className="min-w-0 flex-1 truncate text-[11.5px] text-slate-400">{ep.title}</span>
                <FiChevronDown
                  className={`h-4 w-4 shrink-0 text-slate-400 transition-transform ${isOpen ? 'rotate-180' : ''}`}
                />
              </button>

              {isOpen ? (
                <div className="space-y-3 border-t border-dashed border-slate-100 bg-[#fdfcfb] px-4 py-3">
                  <ul className="list-disc space-y-1 pl-4">
                    {ep.notes.map((n) => (
                      <li key={n} className="text-[11.5px] leading-relaxed text-slate-600">
                        {n}
                      </li>
                    ))}
                  </ul>
                  <div className={`grid gap-3 ${ep.request ? 'lg:grid-cols-2' : ''}`}>
                    {ep.request ? (
                      <div>
                        <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
                          Request body
                        </p>
                        <CodeBlock code={ep.request} />
                      </div>
                    ) : null}
                    <div>
                      <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
                        Response
                      </p>
                      <CodeBlock code={ep.response} />
                    </div>
                  </div>
                </div>
              ) : null}
            </li>
          )
        })}
      </ul>

      {/* error codes */}
      <div className="border-t border-dashed border-slate-200 px-4 py-3">
        <p className="mb-2 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Error codes</p>
        <div className="overflow-x-auto">
          <table className="w-full text-left">
            <tbody className="divide-y divide-dashed divide-slate-100">
              {ERROR_ROWS.map(([status, code, desc], i) => (
                <tr key={i}>
                  <td className="w-12 py-1.5 pr-3 font-mono text-[11px] font-bold text-slate-700">{status}</td>
                  <td className="w-44 py-1.5 pr-3">
                    <span className="rounded bg-rose-50 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-rose-600">
                      {code}
                    </span>
                  </td>
                  <td className="py-1.5 text-[11.5px] text-slate-500">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* quick start */}
      <div className="border-t border-dashed border-slate-200 px-4 py-3">
        <p className="mb-1 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">Quick start (curl)</p>
        <CodeBlock
          code={`curl -X POST ${EXTERNAL_BASE}/shipments \\
  -H "X-API-Key: msk_live_..." \\
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
