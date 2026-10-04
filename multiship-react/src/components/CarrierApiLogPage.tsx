/**
 * V114 — /settings/carrier-api-log admin page. Durable per-request
 * carrier API log. Row click expands the full request + response bodies.
 */
import { Fragment, useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  carrierApiLogService,
  type CarrierApiLogPage,
  type CarrierApiLogRow,
  type CarrierApiLogSummary,
} from '../api/carrierApiLogService'

const PAGE_SIZE = 50

export default function CarrierApiLogPageView() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [page, setPage] = useState<CarrierApiLogPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [carrier, setCarrier] = useState('')
  const [orderNo, setOrderNo] = useState('')
  const [tracking, setTracking] = useState('')
  const [pageIndex, setPageIndex] = useState(0)
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [expandedRow, setExpandedRow] = useState<CarrierApiLogRow | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const parsedOrderNo = orderNo.trim() ? Number(orderNo.trim()) : ''
      setPage(await carrierApiLogService.list({
        carrier: carrier.trim() || undefined,
        orderNo: Number.isFinite(parsedOrderNo) ? parsedOrderNo : undefined,
        tracking: tracking.trim() || undefined,
        page: pageIndex,
        size: PAGE_SIZE,
      }))
    } catch (err) {
      notify.apiError(err, 'Failed to load carrier API log.')
    } finally {
      setLoading(false)
    }
  }, [carrier, orderNo, tracking, pageIndex])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const expand = async (row: CarrierApiLogSummary) => {
    if (expandedId === row.id) {
      setExpandedId(null); setExpandedRow(null); return
    }
    setExpandedId(row.id)
    try {
      setExpandedRow(await carrierApiLogService.getFull(row.id))
    } catch (err) {
      notify.apiError(err, 'Could not load full row.')
      setExpandedId(null)
    }
  }

  const totalPages = page?.totalPages ?? 0
  const items = page?.items ?? []

  return (
    <div className="space-y-3">
      <section className="rounded-xl border border-slate-200 bg-white p-3 shadow-sm">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <label className="text-[12px] font-semibold text-slate-700">
            Carrier
            <input value={carrier} onChange={(e) => { setCarrier(e.target.value); setPageIndex(0) }}
                   placeholder="FEDEX"
                   className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]" />
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Order no.
            <input value={orderNo} onChange={(e) => { setOrderNo(e.target.value); setPageIndex(0) }}
                   placeholder="12345" inputMode="numeric"
                   className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]" />
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Tracking
            <input value={tracking} onChange={(e) => { setTracking(e.target.value); setPageIndex(0) }}
                   placeholder="1Z999…"
                   className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]" />
          </label>
        </div>
        <div className="mt-2 text-[11px] text-slate-500">
          {page ? <>Showing {items.length} of {page.totalElements} · page {page.page + 1} / {totalPages || 1}</> : 'Loading…'}
        </div>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
        {loading ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
        ) : items.length === 0 ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">
            No API-log rows match those filters.
          </div>
        ) : (
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-slate-500">
                <th className="px-3 py-2 font-semibold">When</th>
                <th className="px-3 py-2 font-semibold">Carrier</th>
                <th className="px-3 py-2 font-semibold">Method</th>
                <th className="px-3 py-2 font-semibold">URL</th>
                <th className="px-3 py-2 font-semibold">Status</th>
                <th className="px-3 py-2 font-semibold">Order</th>
                <th className="px-3 py-2 font-semibold">Latency</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => {
                const expanded = expandedId === row.id
                return (
                  <Fragment key={row.id}>
                    <tr onClick={() => void expand(row)}
                        className={`cursor-pointer border-t border-slate-100 hover:bg-slate-50 ${expanded ? 'bg-slate-50' : ''}`}>
                      <td className="px-3 py-2 text-slate-600">{new Date(row.createdAt).toLocaleString()}</td>
                      <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.carrier}</td>
                      <td className="px-3 py-2 text-slate-700">{row.method}</td>
                      <td className="px-3 py-2 truncate text-slate-700" title={row.url}>{row.url}</td>
                      <td className="px-3 py-2">
                        {row.statusCode == null ? '—' : row.statusCode >= 400 ? (
                          <span className="rounded bg-rose-100 px-2 py-0.5 text-rose-800">{row.statusCode}</span>
                        ) : (
                          <span className="rounded bg-emerald-100 px-2 py-0.5 text-emerald-800">{row.statusCode}</span>
                        )}
                      </td>
                      <td className="px-3 py-2 tabular-nums text-slate-700">{row.orderNo ?? '—'}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-slate-500">
                        {row.latencyMs != null ? `${row.latencyMs} ms` : '—'}
                      </td>
                    </tr>
                    {expanded && expandedRow && (
                      <tr className="bg-slate-50">
                        <td colSpan={7} className="px-3 py-3">
                          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                            <div>
                              <div className="text-[11px] uppercase tracking-wide text-slate-500">Request body</div>
                              <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-all text-[11px] text-slate-800">
                                {expandedRow.requestBody ?? '—'}
                              </pre>
                            </div>
                            <div>
                              <div className="text-[11px] uppercase tracking-wide text-slate-500">Response body</div>
                              <pre className="max-h-96 overflow-auto whitespace-pre-wrap break-all text-[11px] text-slate-800">
                                {expandedRow.responseBody ?? '—'}
                              </pre>
                            </div>
                            {expandedRow.errorMessage && (
                              <div className="md:col-span-2">
                                <div className="text-[11px] uppercase tracking-wide text-rose-600">Error</div>
                                <pre className="whitespace-pre-wrap text-[12px] text-rose-800">{expandedRow.errorMessage}</pre>
                              </div>
                            )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        )}
      </section>

      {page && totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 text-[12px]">
          <button type="button" disabled={pageIndex === 0 || loading}
                  onClick={() => setPageIndex(Math.max(0, pageIndex - 1))}
                  className="rounded border border-slate-300 px-3 py-1 hover:bg-slate-50 disabled:opacity-40">← Prev</button>
          <span className="text-slate-600">Page {page.page + 1} of {totalPages}</span>
          <button type="button" disabled={pageIndex + 1 >= totalPages || loading}
                  onClick={() => setPageIndex(pageIndex + 1)}
                  className="rounded border border-slate-300 px-3 py-1 hover:bg-slate-50 disabled:opacity-40">Next →</button>
        </div>
      )}
    </div>
  )
}
