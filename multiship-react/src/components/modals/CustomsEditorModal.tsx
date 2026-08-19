import { useEffect, useRef, useState } from 'react'
import { FiGlobe, FiX } from 'react-icons/fi'
import { customsProfileService, type CustomsProfile } from '../../api/customsProfileService'
import { orderService, type OrderLine } from '../../api/orderService'
import { isAbortError } from '../../api/apiClient'
import { countryName } from '../../utils/countries'
import { useModalDismiss } from '../../hooks/useModalDismiss'

interface CustomsEditorModalProps {
  orderNo: number
  onClose: () => void
  onSaved?: () => void
}

/** One read-only party block for the auto-resolved importer/broker. */
function PartyView({ title, lines }: { title: string; lines: string[] }) {
  const filled = lines.filter(Boolean)
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3">
      <p className="text-[11px] font-bold uppercase tracking-[0.1em] text-slate-400">{title}</p>
      {filled.length ? (
        <div className="mt-1 space-y-0.5 text-[12.5px] text-slate-800">
          {filled.map((l, i) => (
            <p key={i} className={i === 0 ? 'font-semibold text-slate-950' : ''}>{l}</p>
          ))}
        </div>
      ) : (
        <p className="mt-1 text-[12px] text-slate-400">Not set for this country.</p>
      )}
    </div>
  )
}

const money = (v: number | null | undefined) => (typeof v === 'number' ? `$${v.toFixed(2)}` : '—')

/**
 * Order-level customs VIEW (read-only). Everything is automated: the importer +
 * broker resolve from the client's (client, destination country) profile, and
 * the declared goods come from the order's own line items. The operator only
 * looks — there is nothing to edit here.
 */
export default function CustomsEditorModal({ orderNo, onClose }: CustomsEditorModalProps) {
  // A11y audit — focus trap + Escape-to-close + focus restoration.
  const dialogRef = useRef<HTMLElement>(null)
  useModalDismiss(true, dialogRef, onClose)
  const [loading, setLoading] = useState(true)
  const [country, setCountry] = useState('')
  const [clientCode, setClientCode] = useState('')
  const [profile, setProfile] = useState<CustomsProfile | null>(null)
  const [lines, setLines] = useState<OrderLine[]>([])

  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading spinner before async order+profile fetch
    setLoading(true)
    orderService
      .getOrderWithLines(orderNo)
      .then(async (res) => {
        if (cancelled) return
        const o = res.data?.order
        setLines(res.data?.order?.orderLines ?? [])
        const code = (o?.tenantId?.trim() || o?.custNo?.trim() || '').toUpperCase()
        const dest = (o?.shiptoCountryCd?.trim() || '').toUpperCase()
        setClientCode(code)
        setCountry(dest)
        if (code && dest) {
          try {
            const profiles = await customsProfileService.list(code)
            if (!cancelled) setProfile(profiles.find((p) => (p.countries ?? []).includes(dest)) ?? null)
          } catch {
            /* best-effort */
          }
        }
      })
      // Sprint 51 FE-L3 — log instead of silently hiding order-load failures.
      .catch((e) => {
        if (!isAbortError(e)) console.debug('[secondary load] order/customs prefetch', e)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderNo])

  const total = lines.reduce((s, l) => s + (l.customsDeclValue ?? l.totalPrice ?? 0), 0)

  return (
    <>
      <div
        className="fixed inset-0 z-[60] bg-slate-950/45 backdrop-blur-sm"
        onClick={(e) => {
          e.stopPropagation()
          onClose()
        }}
        aria-hidden="true"
      />
      <aside
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label={`Customs declaration for order ${orderNo}`}
        onClick={(e) => e.stopPropagation()}
        className="fixed inset-y-0 right-0 z-[61] flex w-full max-w-[560px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">Customs details</p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Order #{orderNo}
              {country ? <span className="ml-2 text-[12px] font-semibold text-slate-400">→ {country}</span> : null}
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
            aria-label="Close"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <div className="py-12 text-center text-sm text-slate-500">Loading…</div>
          ) : (
            <div className="space-y-5">
              {/* Auto-resolved importer + broker */}
              <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3.5">
                <p className="inline-flex items-center gap-1.5 text-[12.5px] font-semibold text-slate-950">
                  <FiGlobe className="h-3.5 w-3.5 text-[#412d15]" /> Importer &amp; Broker
                </p>
                <p className="mb-3 text-[11px] leading-5 text-slate-500">
                  Resolved automatically from {clientCode || 'the client'}'s profile for {country ? countryName(country) : 'this country'}.
                  {profile ? null : ' None set — configure it in Settings → Importer/Broker.'}
                </p>
                <div className="grid gap-2.5 sm:grid-cols-2">
                  {profile?.importerType === 'RECEIVER' ? (
                    <div className="rounded-xl border border-sky-100 bg-sky-50/70 p-2.5">
                      <p className="text-[10px] font-bold uppercase tracking-[0.1em] text-sky-700">Importer of record</p>
                      <p className="mt-1 text-[12px] font-semibold text-slate-800">The order's receiver (DAP)</p>
                      <p className="mt-0.5 text-[11px] leading-4 text-slate-500">
                        The consignee imports the goods and pays duties — the carrier collects their identity
                        documents (KYC) at destination.
                      </p>
                    </div>
                  ) : (
                    <PartyView
                      title="Importer of record"
                      lines={[
                        profile?.importerName ?? '',
                        profile?.importerContact ?? '',
                        profile?.importerAddress1 ?? '',
                        profile?.importerAddress2 ?? '',
                        [profile?.importerCity, profile?.importerState, profile?.importerPostcode].filter(Boolean).join(', '),
                        countryName(profile?.importerCountry),
                        profile?.importerPhone ?? '',
                        [
                          profile?.importerTaxId ? `${profile?.importerTaxIdType || 'Tax ID'}: ${profile.importerTaxId}` : '',
                          profile?.importerEori ? `EORI: ${profile.importerEori}` : '',
                          profile?.importerIoss ? `IOSS: ${profile.importerIoss}` : '',
                          profile?.importerIec ? `IEC: ${profile.importerIec}` : '',
                          profile?.importerGstin ? `GSTIN: ${profile.importerGstin}` : '',
                        ]
                          .filter(Boolean)
                          .join(' · '),
                      ]}
                    />
                  )}
                  {profile && !profile.brokerName && !profile.brokerCompany ? (
                    <div className="rounded-xl border border-emerald-100 bg-emerald-50/70 p-2.5">
                      <p className="text-[10px] font-bold uppercase tracking-[0.1em] text-emerald-700">Customs broker</p>
                      <p className="mt-1 text-[12px] font-semibold text-slate-800">Carrier clears customs</p>
                      <p className="mt-0.5 text-[11px] leading-4 text-slate-500">
                        Brokerage is included with the carrier's international service — no third-party broker.
                      </p>
                    </div>
                  ) : (
                    <PartyView
                      title="Customs broker"
                      lines={[
                        profile?.brokerName ?? '',
                        profile?.brokerCompany ?? '',
                        profile?.brokerAddress1 ?? '',
                        profile?.brokerAddress2 ?? '',
                        [profile?.brokerCity, profile?.brokerState, profile?.brokerPostcode].filter(Boolean).join(', '),
                        countryName(profile?.brokerCountry),
                        profile?.brokerPhone ?? '',
                        [
                          profile?.brokerId ? `Broker ID: ${profile.brokerId}` : '',
                          profile?.brokerLicense ? `License: ${profile.brokerLicense}` : '',
                        ]
                          .filter(Boolean)
                          .join(' · '),
                      ]}
                    />
                  )}
                </div>
                {profile?.incoterms || profile?.currency || profile?.reasonForExport ? (
                  <p className="mt-2.5 text-[11.5px] text-slate-500">
                    Defaults:{' '}
                    <span className="font-semibold text-slate-700">
                      {[profile.incoterms, profile.reasonForExport, profile.currency, profile.dutiesBillTo]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  </p>
                ) : null}
                {profile?.accountCarrier || profile?.accountNo ? (
                  <p className="mt-2.5 text-[11.5px] text-slate-500">
                    Account: <span className="font-semibold text-slate-700">{profile.accountCarrier} {profile.accountNo}</span>
                  </p>
                ) : null}
              </div>

              {/* Declared goods — from the order's own line items */}
              <div>
                <h4 className="mb-2 text-[12.5px] font-semibold text-slate-950">Declared goods ({lines.length})</h4>
                {lines.some((l) => !l.hsCode) ? (
                  <div className="mb-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[11.5px] text-amber-800">
                    <span className="font-semibold">
                      {lines.filter((l) => !l.hsCode).length} of {lines.length} items missing an HS code.
                    </span>{' '}
                    Customs requires an HS (tariff) code per item on the commercial invoice — clearance may be
                    delayed until the codes are added in the source system.
                  </div>
                ) : null}
                <div className="overflow-x-auto rounded-xl border border-slate-200">
                  <table className="w-full min-w-[440px] text-[12px] text-slate-700">
                    <thead className="border-b border-slate-200 bg-slate-50 text-left text-[9.5px] uppercase tracking-[0.1em] text-slate-500">
                      <tr>
                        <th className="px-2.5 py-2">Description</th>
                        <th className="px-2.5 py-2">HS code</th>
                        <th className="px-2.5 py-2">Origin</th>
                        <th className="px-2.5 py-2 text-right">Qty</th>
                        <th className="px-2.5 py-2 text-right">Value</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {lines.map((l) => (
                        <tr key={l.id}>
                          <td className="px-2.5 py-2 font-medium text-slate-800">{l.itemDescription || l.description || '—'}</td>
                          <td className="px-2.5 py-2 font-mono text-[11px]">{l.hsCode || '—'}</td>
                          <td className="px-2.5 py-2">{l.countryOfOrigin || '—'}</td>
                          <td className="px-2.5 py-2 text-right tabular-nums">{l.qtyShipped ?? '—'}</td>
                          <td className="px-2.5 py-2 text-right tabular-nums">{money(l.customsDeclValue ?? l.totalPrice)}</td>
                        </tr>
                      ))}
                      {!lines.length ? (
                        <tr><td colSpan={5} className="px-2.5 py-6 text-center text-slate-500">No line items on this order.</td></tr>
                      ) : null}
                    </tbody>
                    {lines.length ? (
                      <tfoot className="border-t-2 border-slate-200 bg-slate-50 text-[12px] font-semibold text-slate-900">
                        <tr>
                          <td className="px-2.5 py-2" colSpan={4}>Declared value</td>
                          <td className="px-2.5 py-2 text-right tabular-nums">{money(total)}</td>
                        </tr>
                      </tfoot>
                    ) : null}
                  </table>
                </div>
                <p className="mt-2 text-[11px] text-slate-400">
                  Goods come straight from the order — nothing to enter here.
                </p>
              </div>
            </div>
          )}
        </div>

        <div className="border-t border-slate-100 px-5 py-4">
          <button
            type="button"
            onClick={onClose}
            className="w-full rounded-2xl border border-slate-200 bg-white px-4 py-2.5 text-sm font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            Close
          </button>
        </div>
      </aside>
    </>
  )
}
