/**
 * V113 — /settings/alerts-history admin page. Durable record of fired
 * alerts (USPS fallback, Stamps SERA, future). Mirrors WritebackJournalPage.
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  alertHistoryService,
  type AlertHistoryPage,
  type AlertHistoryRow,
} from '../api/alertHistoryService'

const PAGE_SIZE = 50

export default function AlertHistoryPageView() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [page, setPage] = useState<AlertHistoryPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [source, setSource] = useState('')
  const [tenantCode, setTenantCode] = useState('')
  const [orderNo, setOrderNo] = useState('')
  const [pageIndex, setPageIndex] = useState(0)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const parsedOrderNo = orderNo.trim() ? Number(orderNo.trim()) : ''
      setPage(await alertHistoryService.list({
        source: source.trim() || undefined,
        tenantCode: tenantCode.trim() || undefined,
        orderNo: Number.isFinite(parsedOrderNo) ? parsedOrderNo : undefined,
        page: pageIndex,
        size: PAGE_SIZE,
      }))
    } catch (err) {
      notify.apiError(err, 'Failed to load alert history.')
    } finally {
      setLoading(false)
    }
  }, [source, tenantCode, orderNo, pageIndex])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const totalPages = page?.totalPages ?? 0
  const items: AlertHistoryRow[] = page?.items ?? []

  return (
    <div className="space-y-3">
      <section className="rounded-xl border border-slate-200 bg-white p-3 shadow-sm">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <label className="text-[12px] font-semibold text-slate-700">
            Source
            <input value={source} onChange={(e) => { setSource(e.target.value); setPageIndex(0) }}
                   placeholder="USPS_DIRECT"
                   className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]" />
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Tenant code
            <input value={tenantCode} onChange={(e) => { setTenantCode(e.target.value); setPageIndex(0) }}
                   placeholder="ACME"
                   className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]" />
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Order no.
            <input value={orderNo} onChange={(e) => { setOrderNo(e.target.value); setPageIndex(0) }}
                   placeholder="12345" inputMode="numeric"
                   className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]" />
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
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">No alerts match those filters.</div>
        ) : (
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-slate-500">
                <th className="px-3 py-2 font-semibold">Fired</th>
                <th className="px-3 py-2 font-semibold">Source</th>
                <th className="px-3 py-2 font-semibold">Tenant</th>
                <th className="px-3 py-2 font-semibold">Order</th>
                <th className="px-3 py-2 font-semibold">Reason</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => (
                <tr key={row.id} className="border-t border-slate-100 hover:bg-slate-50">
                  <td className="px-3 py-2 text-slate-600">{new Date(row.firedAt).toLocaleString()}</td>
                  <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.source}</td>
                  <td className="px-3 py-2 text-slate-700">{row.tenantCode ?? '—'}</td>
                  <td className="px-3 py-2 tabular-nums text-slate-700">{row.targetOrderNo ?? '—'}</td>
                  <td className="px-3 py-2 text-slate-700">{row.reason ?? '—'}</td>
                </tr>
              ))}
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
