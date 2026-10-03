/**
 * A4.4 — /settings/notification-delivery-log admin page.
 *
 * Paginated list of every outbound-email dispatch (SENT + FAILED) with
 * filter chips + a Retry action on any row. Retry re-sends the stored
 * subject + body as-is (no re-render) and creates a new log row linked
 * to the original via retryOfId.
 */
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  notificationDeliveryLogService,
  type DeliveryStatus,
  type NotificationDeliveryLogPage,
  type NotificationDeliveryLogRow,
} from '../api/notificationDeliveryLogService'

const PAGE_SIZE = 50

export default function NotificationDeliveryLogPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [page, setPage] = useState<NotificationDeliveryLogPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [templateKey, setTemplateKey] = useState('')
  const [status, setStatus] = useState<DeliveryStatus | ''>('')
  const [recipient, setRecipient] = useState('')
  const [pageIndex, setPageIndex] = useState(0)
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [retrying, setRetrying] = useState<number | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const p = await notificationDeliveryLogService.list({
        templateKey: templateKey.trim() || undefined,
        status: status || undefined,
        recipient: recipient.trim() || undefined,
        page: pageIndex,
        size: PAGE_SIZE,
      })
      setPage(p)
    } catch (err) {
      notify.apiError(err, 'Failed to load delivery log.')
    } finally {
      setLoading(false)
    }
  }, [templateKey, status, recipient, pageIndex])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const retry = async (row: NotificationDeliveryLogRow) => {
    if (!(await notify.confirm(`Retry send to ${row.recipient}?`))) return
    setRetrying(row.id)
    try {
      await notificationDeliveryLogService.retry(row.id)
      notify.success('Retry attempted — refreshing log.')
      await load()
    } catch (err) {
      notify.apiError(err, 'Retry failed.')
      await load()  // still refresh — a FAILED row was logged
    } finally {
      setRetrying(null)
    }
  }

  const totalPages = page?.totalPages ?? 0
  const items = page?.items ?? []

  const distinctTemplateKeys = useMemo(() => {
    const set = new Set<string>()
    items.forEach((r) => { if (r.templateKey) set.add(r.templateKey) })
    return Array.from(set).sort()
  }, [items])

  const resetFilters = () => {
    setTemplateKey('')
    setStatus('')
    setRecipient('')
    setPageIndex(0)
  }

  return (
    <div className="space-y-3">
      {/* Filter bar */}
      <section className="rounded-xl border border-slate-200 bg-white p-3 shadow-sm">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
          <label className="text-[12px] font-semibold text-slate-700">
            Template key
            <input
              value={templateKey}
              onChange={(e) => { setTemplateKey(e.target.value); setPageIndex(0) }}
              placeholder="AUTH.VERIFY_EMAIL"
              list="ndl-template-keys"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]"
            />
            <datalist id="ndl-template-keys">
              {distinctTemplateKeys.map((k) => <option key={k} value={k} />)}
            </datalist>
          </label>
          <label className="text-[12px] font-semibold text-slate-700">
            Status
            <select
              value={status}
              onChange={(e) => { setStatus(e.target.value as DeliveryStatus | ''); setPageIndex(0) }}
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]"
            >
              <option value="">Any</option>
              <option value="SENT">SENT</option>
              <option value="FAILED">FAILED</option>
            </select>
          </label>
          <label className="text-[12px] font-semibold text-slate-700 sm:col-span-2">
            Recipient contains
            <input
              value={recipient}
              onChange={(e) => { setRecipient(e.target.value); setPageIndex(0) }}
              placeholder="alice@"
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

      {/* Table */}
      <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
        {loading ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
        ) : items.length === 0 ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">No delivery rows match those filters.</div>
        ) : (
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-slate-500">
                <th className="px-3 py-2 font-semibold">When</th>
                <th className="px-3 py-2 font-semibold">Template</th>
                <th className="px-3 py-2 font-semibold">Recipient</th>
                <th className="px-3 py-2 font-semibold">Status</th>
                <th className="px-3 py-2 font-semibold">Provider</th>
                <th className="px-3 py-2 font-semibold">Latency</th>
                <th className="px-3 py-2 text-right font-semibold">Actions</th>
              </tr>
            </thead>
            <tbody>
              {items.map((row) => {
                const expanded = expandedId === row.id
                return (
                  <Fragment key={row.id}>
                    <tr
                      onClick={() => setExpandedId(expanded ? null : row.id)}
                      className={`cursor-pointer border-t border-slate-100 hover:bg-slate-50 ${
                        expanded ? 'bg-slate-50' : ''
                      }`}
                    >
                      <td className="px-3 py-2 text-slate-600">
                        {new Date(row.sentAt).toLocaleString()}
                      </td>
                      <td className="px-3 py-2 font-mono text-[12px] text-slate-700">
                        {row.templateKey ?? '—'}
                        {row.retryOfId != null && (
                          <span className="ml-1 rounded bg-amber-100 px-1 text-[10px] text-amber-800"
                                title={`Retry of #${row.retryOfId}`}>
                            retry
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-slate-700">{row.recipient}</td>
                      <td className="px-3 py-2">
                        {row.status === 'SENT' ? (
                          <span className="rounded bg-emerald-100 px-2 py-0.5 text-emerald-800">SENT</span>
                        ) : (
                          <span className="rounded bg-rose-100 px-2 py-0.5 text-rose-800">FAILED</span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-slate-600">{row.providerKind ?? '—'}</td>
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
                            <div>
                              <div className="text-[11px] uppercase tracking-wide text-slate-500">Subject</div>
                              <div className="font-semibold text-slate-800">{row.subject}</div>
                            </div>
                            <div>
                              <div className="text-[11px] uppercase tracking-wide text-slate-500">Body</div>
                              <pre className="whitespace-pre-wrap text-[13px] text-slate-800">{row.body}</pre>
                            </div>
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

      {/* Pagination */}
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
