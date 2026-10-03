/**
 * D1 — /settings/writeback-journal admin page.
 *
 * Paginated list of every external-system writeback dispatch
 * (PENDING / OK / SKIPPED / FAILED) with filter chips + a Retry action
 * on any row. Retry re-fires the stored payload through the dispatcher
 * (same path as the live dispatch) and a fresh row appears once the
 * async attempt completes.
 */
import { Fragment, useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  writebackJournalService,
  type WritebackJournalMode,
  type WritebackJournalPage,
  type WritebackJournalRow,
  type WritebackJournalStatus,
} from '../api/writebackJournalService'

const PAGE_SIZE = 50

const STATUS_BADGE: Record<WritebackJournalStatus, string> = {
  OK: 'bg-emerald-100 text-emerald-800',
  SKIPPED: 'bg-slate-100 text-slate-700',
  FAILED: 'bg-rose-100 text-rose-800',
  PENDING: 'bg-amber-100 text-amber-800',
}

export default function WritebackJournalPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [page, setPage] = useState<WritebackJournalPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [connectionName, setConnectionName] = useState('')
  const [status, setStatus] = useState<WritebackJournalStatus | ''>('')
  const [mode, setMode] = useState<WritebackJournalMode | ''>('')
  const [orderNo, setOrderNo] = useState('')
  const [pageIndex, setPageIndex] = useState(0)
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [retrying, setRetrying] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const parsedOrderNo = orderNo.trim() ? Number(orderNo.trim()) : ''
      const p = await writebackJournalService.list({
        connectionName: connectionName.trim() || undefined,
        status: status || undefined,
        mode: mode || undefined,
        orderNo: Number.isFinite(parsedOrderNo) ? parsedOrderNo : undefined,
        page: pageIndex,
        size: PAGE_SIZE,
      })
      setPage(p)
    } catch (err) {
      notify.apiError(err, 'Failed to load writeback journal.')
    } finally {
      setLoading(false)
    }
  }, [connectionName, status, mode, orderNo, pageIndex])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const retry = async (row: WritebackJournalRow) => {
    const label = `${row.mode} on ${row.connectionName}` + (row.orderNo != null ? ` (order ${row.orderNo})` : '')
    if (!(await notify.confirm(`Re-dispatch ${label}?`))) return
    setRetrying(row.id)
    try {
      await writebackJournalService.retry(row.id)
      notify.success('Retry queued — refreshing journal.')
      await load()
    } catch (err) {
      notify.apiError(err, 'Retry failed.')
      await load()
    } finally {
      setRetrying(null)
    }
  }

  const totalPages = page?.totalPages ?? 0
  const items = page?.items ?? []

  const resetFilters = () => {
    setConnectionName('')
    setStatus('')
    setMode('')
    setOrderNo('')
    setPageIndex(0)
  }

  return (
    <div className="space-y-3">
      <section className="rounded-xl border border-slate-200 bg-white p-3 shadow-sm">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
          <label className="text-[12px] font-semibold text-slate-700">
            Connection
            <input
              value={connectionName}
              onChange={(e) => { setConnectionName(e.target.value); setPageIndex(0) }}
              placeholder="nds-default"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]"
            />
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Status
            <select
              value={status}
              onChange={(e) => { setStatus(e.target.value as WritebackJournalStatus | ''); setPageIndex(0) }}
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]"
            >
              <option value="">Any</option>
              <option value="OK">OK</option>
              <option value="SKIPPED">SKIPPED</option>
              <option value="FAILED">FAILED</option>
              <option value="PENDING">PENDING</option>
            </select>
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Mode
            <select
              value={mode}
              onChange={(e) => { setMode(e.target.value as WritebackJournalMode | ''); setPageIndex(0) }}
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]"
            >
              <option value="">Any</option>
              <option value="GENERATE">GENERATE</option>
              <option value="CLEAR">CLEAR</option>
            </select>
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Order no.
            <input
              value={orderNo}
              onChange={(e) => { setOrderNo(e.target.value); setPageIndex(0) }}
              placeholder="12345"
              inputMode="numeric"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]"
            />
          </label>
        </div>
        <div className="mt-2 flex items-center justify-between text-[11px] text-slate-500">
          <div>
            {page ? (
              <>Showing {items.length} of {page.totalElements} · page {page.page + 1} / {totalPages || 1}</>
            ) : (
              'Loading…'
            )}
          </div>
          <button
            type="button"
            onClick={resetFilters}
            className="rounded border border-slate-300 px-2 py-1 hover:bg-slate-50"
          >
            Reset filters
          </button>
        </div>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
        {loading ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
        ) : items.length === 0 ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">No journal rows match those filters.</div>
        ) : (
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-slate-500">
                <th className="px-3 py-2 font-semibold">When</th>
                <th className="px-3 py-2 font-semibold">Connection</th>
                <th className="px-3 py-2 font-semibold">Mode</th>
                <th className="px-3 py-2 font-semibold">Order</th>
                <th className="px-3 py-2 font-semibold">Status</th>
                <th className="px-3 py-2 font-semibold">Latency</th>
                <th className="px-3 py-2 text-right font-semibold">Actions</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => {
                const expanded = expandedId === row.id
                const badge = STATUS_BADGE[row.status] ?? 'bg-slate-100 text-slate-700'
                return (
                  <Fragment key={row.id}>
                    <tr
                      onClick={() => setExpandedId(expanded ? null : row.id)}
                      className={`cursor-pointer border-t border-slate-100 hover:bg-slate-50 ${expanded ? 'bg-slate-50' : ''}`}
                    >
                      <td className="px-3 py-2 text-slate-600">{new Date(row.createdAt).toLocaleString()}</td>
                      <td className="px-3 py-2 font-mono text-[12px] text-slate-700">
                        {row.connectionName}
                        {row.systemType && (
                          <span className="ml-1 rounded bg-slate-100 px-1 text-[10px] text-slate-600">{row.systemType}</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-slate-700">
                        {row.mode}
                        {row.retryOfId != null && (
                          <span className="ml-1 rounded bg-amber-100 px-1 text-[10px] text-amber-800"
                                title={`Retry of #${row.retryOfId} · attempt ${row.attemptNumber}`}>
                            retry #{row.attemptNumber}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 tabular-nums text-slate-700">{row.orderNo ?? '—'}</td>
                      <td className="px-3 py-2">
                        <span className={`rounded px-2 py-0.5 ${badge}`}>{row.status}</span>
                      </td>
                      <td className="px-3 py-2 text-right tabular-nums text-slate-500">
                        {row.latencyMs != null ? `${row.latencyMs} ms` : '—'}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <button
                          type="button"
                          onClick={(e) => { e.stopPropagation(); void retry(row) }}
                          disabled={retrying === row.id}
                          className="rounded border border-slate-300 px-2 py-1 text-[12px] hover:bg-white disabled:opacity-60"
                        >
                          {retrying === row.id ? 'Retrying…' : 'Retry'}
                        </button>
                      </td>
                    </tr>
                    {expanded && (
                      <tr className="bg-slate-50">
                        <td colSpan={7} className="px-3 py-3">
                          <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
                            <KV k="Client" v={row.clientCode} />
                            <KV k="Tracking" v={row.trackingNumber} />
                            <KV k="Source" v={row.source} />
                            <KV k="Channel" v={row.channel} />
                            <KV k="Ack status" v={row.ackStatus} />
                            <KV k="Attempt" v={String(row.attemptNumber)} />
                            {row.ackDetail && (
                              <div className="md:col-span-2">
                                <div className="text-[11px] uppercase tracking-wide text-slate-500">Ack detail</div>
                                <pre className="whitespace-pre-wrap text-[12px] text-slate-800">{row.ackDetail}</pre>
                              </div>
                            )}
                            {row.errorMessage && (
                              <div className="md:col-span-2">
                                <div className="text-[11px] uppercase tracking-wide text-rose-600">Error</div>
                                <pre className="whitespace-pre-wrap text-[12px] text-rose-800">{row.errorMessage}</pre>
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
          <button
            type="button"
            disabled={pageIndex === 0 || loading}
            onClick={() => setPageIndex(Math.max(0, pageIndex - 1))}
            className="rounded border border-slate-300 px-3 py-1 hover:bg-slate-50 disabled:opacity-40"
          >
            ← Prev
          </button>
          <span className="text-slate-600">Page {page.page + 1} of {totalPages}</span>
          <button
            type="button"
            disabled={pageIndex + 1 >= totalPages || loading}
            onClick={() => setPageIndex(pageIndex + 1)}
            className="rounded border border-slate-300 px-3 py-1 hover:bg-slate-50 disabled:opacity-40"
          >
            Next →
          </button>
        </div>
      )}
    </div>
  )
}

function KV({ k, v }: { k: string; v: string | null }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wide text-slate-500">{k}</div>
      <div className="text-[13px] text-slate-800">{v ?? '—'}</div>
    </div>
  )
}
