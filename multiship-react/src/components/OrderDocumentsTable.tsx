import { useEffect, useState } from 'react'
import { FiDownload, FiFileText, FiLoader, FiRefreshCw, FiX } from 'react-icons/fi'
import { Link } from 'react-router-dom'
import { orderService, type OrderDocumentRow } from '../api/orderService'
import { notify } from '../utils/notify'

/**
 * The unified Documents table — one row per labelled order carrying every
 * artifact that label generation produced: tracking number, label downloads
 * (PDF/ZPL), the commercial invoice (international orders), and the billing
 * statement (carrier cost / markup / billable). The "everything for this
 * shipment in one place" view.
 */
export default function OrderDocumentsTable() {
  const [rows, setRows] = useState<OrderDocumentRow[]>([])
  const [loading, setLoading] = useState(true)
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [statementRow, setStatementRow] = useState<OrderDocumentRow | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const res = await orderService.getDocuments(200)
      setRows(res.data ?? [])
    } catch (e) {
      notify.apiError(e, 'Could not load the documents table.')
    } finally {
      setLoading(false)
    }
  }
  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial fetch on mount; the sync setLoading(true) drives the spinner and cannot be derived at render
  useEffect(() => { void load() }, [])

  const saveBlob = (blob: Blob, filename: string) => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  }

  const downloadLabel = async (r: OrderDocumentRow) => {
    setBusyKey(`label-${r.orderNo}`)
    try {
      saveBlob(await orderService.getLabelPdf(r.orderNo), `label-${r.orderNo}.pdf`)
    } catch (e) {
      notify.apiError(e, 'Label PDF download failed.')
    } finally {
      setBusyKey(null)
    }
  }

  const downloadZpl = async (r: OrderDocumentRow) => {
    setBusyKey(`zpl-${r.orderNo}`)
    try {
      const zpl = await orderService.getLabelZpl(r.orderNo)
      saveBlob(new Blob([zpl], { type: 'text/plain' }), `label-${r.orderNo}.zpl`)
    } catch (e) {
      notify.apiError(e, 'ZPL download failed.')
    } finally {
      setBusyKey(null)
    }
  }

  const downloadInvoice = async (r: OrderDocumentRow) => {
    setBusyKey(`inv-${r.orderNo}`)
    try {
      saveBlob(await orderService.getCommercialInvoicePdf(r.orderNo), `commercial-invoice-${r.orderNo}.pdf`)
    } catch (e) {
      notify.apiError(e, 'Commercial invoice download failed.')
    } finally {
      setBusyKey(null)
    }
  }

  const money = (v: number | null | undefined, ccy?: string | null) =>
    v == null ? '—' : `${Number(v).toFixed(2)}${ccy ? ` ${ccy}` : ''}`

  const DOC_BTN =
    'inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[10px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40'

  return (
    <section className="rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#eee6d6] px-4 py-3">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#b6a684]">Shipment documents</p>
          <p className="text-[11.5px] text-[#8a7959]">
            Everything each label generation produced — tracking, label, invoice &amp; statement — in one table.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
        >
          <FiRefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} /> Refresh
        </button>
      </div>

      {loading ? (
        <p className="px-4 py-10 text-center text-[12px] text-[#8a7959]">Loading documents…</p>
      ) : rows.length === 0 ? (
        <p className="px-4 py-10 text-center text-[12px] text-[#8a7959]">
          No labels generated yet — generate one and its documents appear here.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-[11.5px]">
            <thead>
              <tr className="bg-[#faf7f0] text-left text-[9px] uppercase tracking-[0.1em] text-[#8a7959]">
                <th className="border-b border-[#e3d9c4] px-3 py-2 font-bold">Order</th>
                <th className="border-b border-[#e3d9c4] px-3 py-2 font-bold">Client</th>
                <th className="border-b border-[#e3d9c4] px-3 py-2 font-bold">Recipient</th>
                <th className="border-b border-[#e3d9c4] px-3 py-2 font-bold">Destination</th>
                <th className="border-b border-[#e3d9c4] px-3 py-2 font-bold">Carrier</th>
                <th className="border-b border-[#e3d9c4] px-3 py-2 font-bold">Tracking</th>
                <th className="border-b border-[#e3d9c4] px-3 py-2 text-right font-bold">Billed</th>
                <th className="border-b border-[#e3d9c4] px-3 py-2 font-bold">Documents</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.orderNo} className="hover:bg-[#faf7f0]/60">
                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-2">
                    <Link to={`/label/${r.orderNo}`} className="font-mono text-[11px] font-bold text-[#412d15] underline-offset-2 hover:underline">
                      #{r.orderNo}
                    </Link>
                    {r.packageCount && r.packageCount > 1 ? (
                      <span className="ml-1 text-[9.5px] text-[#b6a684]">×{r.packageCount} pkg</span>
                    ) : null}
                    {r.voided ? (
                      <span className="ml-1.5 rounded-full bg-slate-200 px-1.5 py-0.5 text-[8.5px] font-bold uppercase tracking-wide text-slate-600">Voided</span>
                    ) : null}
                  </td>
                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-2 font-mono text-[10.5px] text-[#5a4526]">{r.custNo ?? '—'}</td>
                  <td className="border-b border-[#f2ecdf] px-3 py-2 font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</td>
                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-2 text-[#5a4526]">
                    {r.city ?? '—'}{r.countryCode ? ` · ${r.countryCode}` : ''}
                  </td>
                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-2 font-mono text-[10.5px]">{r.carrier ?? '—'}</td>
                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-2 font-mono text-[10.5px] text-[#5a4526]">{r.trackingNumber ?? '—'}</td>
                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-2 text-right tabular-nums">
                    {r.voided ? (
                      <span title="Label voided — charge reversed" className="text-slate-400">
                        <s>{money(r.billableAmount, r.markupCurrency)}</s>
                      </span>
                    ) : (
                      money(r.billableAmount, r.markupCurrency)
                    )}
                  </td>
                  <td className="whitespace-nowrap border-b border-[#f2ecdf] px-3 py-2">
                    <span className="flex items-center gap-1">
                      <button type="button" onClick={() => void downloadLabel(r)} disabled={busyKey === `label-${r.orderNo}`} className={DOC_BTN} title="Download the 4x6 shipping label as PDF">
                        {busyKey === `label-${r.orderNo}` ? <FiLoader className="h-3 w-3 animate-spin" /> : <FiDownload className="h-3 w-3" />} Label
                      </button>
                      <button type="button" onClick={() => void downloadZpl(r)} disabled={busyKey === `zpl-${r.orderNo}`} className={DOC_BTN} title="Raw ZPL for thermal printers">
                        {busyKey === `zpl-${r.orderNo}` ? <FiLoader className="h-3 w-3 animate-spin" /> : <FiDownload className="h-3 w-3" />} ZPL
                      </button>
                      {r.hasInvoice ? (
                        <button type="button" onClick={() => void downloadInvoice(r)} disabled={busyKey === `inv-${r.orderNo}`} className={DOC_BTN} title="Commercial invoice PDF (customs document)">
                          {busyKey === `inv-${r.orderNo}` ? <FiLoader className="h-3 w-3 animate-spin" /> : <FiDownload className="h-3 w-3" />} Invoice
                        </button>
                      ) : (
                        <span className="px-1 text-[9.5px] text-[#cdbf9f]" title="Domestic shipment — no customs invoice">no invoice</span>
                      )}
                      <button type="button" onClick={() => setStatementRow(r)} className={DOC_BTN} title="Billing statement — carrier cost, markup, billable amount">
                        <FiFileText className="h-3 w-3" /> Statement
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* ── Billing-statement modal ── */}
      {statementRow ? (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Billing statement for order ${statementRow.orderNo}`}
          className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4"
          onClick={() => setStatementRow(null)}
        >
          <div
            className="w-full max-w-[420px] overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start justify-between border-b border-[#eee6d6] bg-[#faf7f0] px-5 py-3.5">
              <div>
                <p className="text-[9.5px] font-bold uppercase tracking-[0.18em] text-[#b6a684]">Billing statement</p>
                <p className="text-[14px] font-semibold text-[#1f150c]">Order #{statementRow.orderNo}</p>
              </div>
              <button
                type="button"
                onClick={() => setStatementRow(null)}
                aria-label="Close"
                className="rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#8a7959] transition hover:bg-[#faf7f0]"
              >
                <FiX className="h-3.5 w-3.5" />
              </button>
            </div>
            <div className="space-y-2.5 px-5 py-4 text-[12px]">
              {[
                ['Client', statementRow.custNo ?? '—'],
                ['Recipient', statementRow.recipientName ?? '—'],
                ['Carrier / Tracking', `${statementRow.carrier ?? '—'} · ${statementRow.trackingNumber ?? '—'}`],
                ['Billed to account', statementRow.accountNumber ?? '—'],
                ['Generated', statementRow.generatedAt ? new Date(statementRow.generatedAt).toLocaleString() : '—'],
              ].map(([k, v]) => (
                <div key={k} className="flex items-baseline justify-between gap-3">
                  <span className="text-[10.5px] font-semibold uppercase tracking-[0.06em] text-[#b6a684]">{k}</span>
                  <span className="text-right font-medium text-[#1f150c]">{v}</span>
                </div>
              ))}
              <div className="my-1 border-t border-dashed border-[#e3d9c4]" />
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-[10.5px] font-semibold uppercase tracking-[0.06em] text-[#b6a684]">Carrier cost</span>
                <span className="text-right tabular-nums">{money(statementRow.carrierAmount, statementRow.markupCurrency)}</span>
              </div>
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-[10.5px] font-semibold uppercase tracking-[0.06em] text-[#b6a684]">
                  Markup{statementRow.markupKind ? ` (${statementRow.markupKind.toLowerCase()}${statementRow.markupValue != null ? ` ${statementRow.markupValue}` : ''})` : ''}
                </span>
                <span className="text-right tabular-nums">
                  {statementRow.billableAmount != null && statementRow.carrierAmount != null
                    ? money(statementRow.billableAmount - statementRow.carrierAmount, statementRow.markupCurrency)
                    : '—'}
                </span>
              </div>
              <div className="flex items-baseline justify-between gap-3 rounded-xl bg-[#faf7f0] px-3 py-2">
                <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-[#5a4526]">Billable total</span>
                <span className="text-right text-[14px] font-bold tabular-nums text-[#1f150c]">
                  {money(statementRow.billableAmount, statementRow.markupCurrency)}
                </span>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  )
}
