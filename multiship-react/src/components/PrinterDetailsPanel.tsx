import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  FiAlertTriangle, FiCheckCircle, FiClock, FiEdit2, FiPrinter, FiRadio, FiTrash2, FiX,
} from 'react-icons/fi'
import {
  CONNECTION_LABEL, PAPER_LABEL, printerService,
  type Printer, type PrinterAssignment, type PrinterTestHistoryEntry,
} from '../api/printerService'

/**
 * PR-Printer-R9 — right-slide side panel with the "big picture" of one
 * printer: metadata + last-test status + client usage + tags.
 *
 * <p>Trigger: click a printer's name in the Printers-tab table.
 * FE-only — reuses printers / assignments / tagsByPrinter state
 * already loaded on the page.
 *
 * <p>Deferred to a future R9.5 (needs a new backend table):
 * <ul>
 *   <li>Test-print history (last N attempts, not just the latest).</li>
 *   <li>Print queue depth (we don't track jobs in-flight).</li>
 * </ul>
 */
export default function PrinterDetailsPanel({
  printer,
  assignments,
  tags,
  testing,
  onClose,
  onEdit,
  onDelete,
  onTest,
}: {
  printer: Printer
  /** Full assignments list from the parent — we filter by printerId. */
  assignments: PrinterAssignment[]
  /** Tags for this printer (already loaded in parent's map). */
  tags: string[]
  /** Parent's testingIds.has(printer.id) — shows spinner while a
   *  test is in flight and disables the Retest button. */
  testing: boolean
  onClose: () => void
  onEdit: () => void
  onDelete: () => void
  onTest: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  // PR-R9.5b — test-history section. Fetched on open + refetched
  // whenever the Retest button flips `testing` from true → false
  // (a completed test just appended a row).
  const [history, setHistory] = useState<PrinterTestHistoryEntry[]>([])
  const [historyLoading, setHistoryLoading] = useState(true)

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true)
    try {
      const res = await printerService.listPrinterTestHistory(printer.id, 10)
      setHistory(res.data ?? [])
    } catch {
      // R9.5a endpoint may 404 in dev before backend deploys; render
      // empty state rather than a toast — the section is optional.
      setHistory([])
    } finally {
      setHistoryLoading(false)
    }
  }, [printer.id])

  useEffect(() => {
    let cancelled = false
    queueMicrotask(() => { if (!cancelled) void loadHistory() })
    return () => { cancelled = true }
  }, [loadHistory])

  // Refetch after a Retest completes. Guard on `testing` transitioning
  // to false — the parent's testingIds set removes on completion.
  const wasTesting = useRef(false)
  useEffect(() => {
    if (wasTesting.current && !testing) {
      void loadHistory()
    }
    wasTesting.current = testing
  }, [testing, loadHistory])

  // Close on Esc + click-outside.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onClick)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onClick)
    }
  }, [onClose])

  // Group assignments touching this printer by docType, with the
  // "Default" client rendered as an explicit tag rather than "null".
  const usage = useMemo(() => {
    const forPrinter = assignments.filter((a) => a.printerId === printer.id)
    const label = forPrinter.filter((a) => a.docType === 'LABEL')
    const invoice = forPrinter.filter((a) => a.docType === 'COMMERCIAL_INVOICE')
    const toRows = (rows: PrinterAssignment[]) => rows.map((r) => r.clientCode ?? '__DEFAULT__')
    return {
      labelClients: toRows(label),
      invoiceClients: toRows(invoice),
    }
  }, [assignments, printer.id])

  const status = !printer.active
    ? { icon: null, label: 'Switched off', tone: 'text-slate-500' }
    : printer.lastTestOk === true
      ? { icon: <FiCheckCircle className="h-3.5 w-3.5" />, label: 'Last test OK', tone: 'text-emerald-700' }
      : printer.lastTestOk === false
        ? { icon: <FiAlertTriangle className="h-3.5 w-3.5" />, label: 'Last test failed', tone: 'text-rose-700' }
        : { icon: null, label: 'Not tested', tone: 'text-slate-500' }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="printer-details-heading"
      className="fixed inset-0 z-40 flex justify-end bg-slate-950/30"
    >
      <div
        ref={ref}
        className="flex h-full w-full max-w-md flex-col overflow-y-auto bg-white shadow-[-30px_0_80px_rgba(15,23,42,0.15)]"
      >
        <header className="sticky top-0 z-10 flex items-start justify-between gap-3 border-b border-slate-100 bg-white px-5 py-4">
          <div className="min-w-0">
            <h3 id="printer-details-heading" className="flex items-center gap-2 text-[15px] font-semibold text-slate-950">
              <FiPrinter className="h-4 w-4 text-slate-500" />
              <span className="truncate">{printer.name}</span>
            </h3>
            {printer.location ? (
              <p className="mt-0.5 text-[12px] text-slate-500">{printer.location}</p>
            ) : null}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1 text-slate-500 hover:bg-slate-100"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <div className="flex-1 space-y-4 px-5 py-4">
          {/* Actions row */}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={onTest}
              disabled={testing}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-2.5 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {testing
                ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                : <FiRadio className="h-3.5 w-3.5" />}
              Retest
            </button>
            <button
              type="button"
              onClick={onEdit}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-2.5 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              <FiEdit2 className="h-3.5 w-3.5" />
              Edit
            </button>
            <button
              type="button"
              onClick={onDelete}
              className="inline-flex items-center gap-1.5 rounded-md border border-rose-300 bg-rose-50 px-2.5 py-1.5 text-[12.5px] font-semibold text-rose-700 hover:bg-rose-100"
            >
              <FiTrash2 className="h-3.5 w-3.5" />
              Delete
            </button>
          </div>

          {/* Metadata */}
          <section className="rounded-lg border border-slate-200 bg-slate-50 p-3">
            <h4 className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">Connection</h4>
            <dl className="mt-2 grid grid-cols-2 gap-y-1 text-[12.5px]">
              <dt className="text-slate-500">Type</dt>
              <dd className="text-slate-800">{CONNECTION_LABEL[printer.connection] ?? printer.connection}</dd>
              <dt className="text-slate-500">Address</dt>
              <dd className="font-mono text-slate-800">
                {printer.host}:{printer.port}{printer.connection === 'IPP' && printer.queuePath ? `/${printer.queuePath}` : ''}
              </dd>
              <dt className="text-slate-500">Format</dt>
              <dd className="text-slate-800">{printer.format}</dd>
              <dt className="text-slate-500">Paper</dt>
              <dd className="text-slate-800">{PAPER_LABEL[printer.paper] ?? printer.paper}</dd>
              <dt className="text-slate-500">Status</dt>
              <dd className={`inline-flex items-center gap-1 ${status.tone}`}>
                {status.icon}
                {status.label}
              </dd>
            </dl>
            {printer.lastTestMessage ? (
              <p className="mt-2 text-[11.5px] text-slate-600">
                <span className="font-semibold">Last test:</span> {printer.lastTestMessage}
              </p>
            ) : null}
          </section>

          {/* Usage */}
          <section className="rounded-lg border border-slate-200 p-3">
            <h4 className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">Client usage</h4>
            <div className="mt-2 grid grid-cols-1 gap-3 text-[12.5px]">
              <div>
                <span className="mb-1 flex items-center gap-1.5 text-slate-700">
                  <span className="rounded-full bg-sky-100 px-1.5 py-0.5 text-[10px] font-semibold text-sky-800">L</span>
                  Labels ({usage.labelClients.length})
                </span>
                <ClientList codes={usage.labelClients} />
              </div>
              <div>
                <span className="mb-1 flex items-center gap-1.5 text-slate-700">
                  <span className="rounded-full bg-violet-100 px-1.5 py-0.5 text-[10px] font-semibold text-violet-800">I</span>
                  Commercial invoices ({usage.invoiceClients.length})
                </span>
                <ClientList codes={usage.invoiceClients} />
              </div>
            </div>
            {usage.labelClients.length === 0 && usage.invoiceClients.length === 0 ? (
              <p className="mt-2 text-[11.5px] text-slate-500">
                No clients route through this printer yet. Assign it from the
                <span className="mx-1 font-semibold text-slate-700">Labels</span>
                or
                <span className="mx-1 font-semibold text-slate-700">Invoices</span>
                tab.
              </p>
            ) : null}
          </section>

          {/* Tags */}
          <section className="rounded-lg border border-slate-200 p-3">
            <div className="flex items-center justify-between">
              <h4 className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
                Tags ({tags.length})
              </h4>
              <button
                type="button"
                onClick={onEdit}
                className="text-[11px] font-semibold text-slate-500 underline hover:text-slate-800"
              >
                Edit
              </button>
            </div>
            {tags.length === 0 ? (
              <p className="mt-2 text-[11.5px] text-slate-500">No tags. Add some in the editor to group this printer.</p>
            ) : (
              <span className="mt-2 flex flex-wrap gap-1">
                {tags.map((t) => (
                  <span key={t} className="rounded-full bg-indigo-100 px-2 py-0.5 text-[10.5px] font-semibold text-indigo-800">
                    {t}
                  </span>
                ))}
              </span>
            )}
          </section>

          {/* PR-R9.5b — rolling test-print history (last 10). Refetches
              automatically when the Retest button completes. */}
          <section className="rounded-lg border border-slate-200 p-3">
            <h4 className="flex items-center gap-1.5 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
              <FiClock className="h-3 w-3" />
              Test history ({history.length})
            </h4>
            {historyLoading ? (
              <p className="mt-2 text-[11.5px] text-slate-500">Loading…</p>
            ) : history.length === 0 ? (
              <p className="mt-2 text-[11.5px] text-slate-500">
                No test attempts yet. Click <span className="font-semibold text-slate-700">Retest</span> above to make an entry.
              </p>
            ) : (
              <ol className="mt-2 space-y-1.5 text-[12px]">
                {history.map((h) => (
                  <li key={h.id} className="flex items-start justify-between gap-3 border-b border-slate-100 pb-1.5 last:border-b-0 last:pb-0">
                    <span className="flex min-w-0 items-start gap-1.5">
                      {h.ok ? (
                        <FiCheckCircle className="h-3.5 w-3.5 shrink-0 text-emerald-600" title="Sent OK" />
                      ) : (
                        <FiAlertTriangle className="h-3.5 w-3.5 shrink-0 text-rose-600" title="Test failed" />
                      )}
                      <span className="min-w-0">
                        <span className="block text-[11.5px] font-semibold text-slate-800" title={new Date(h.testedAt).toLocaleString()}>
                          {formatSince(h.testedAt)}
                          {h.testedBy ? <span className="ml-1 font-normal text-slate-500">by {h.testedBy}</span> : null}
                        </span>
                        {h.message ? (
                          <span className="block text-[11px] text-slate-500">{h.message}</span>
                        ) : null}
                      </span>
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </div>
      </div>
    </div>
  )
}

// PR-R9.5b — same formatSince helper the other panels use.
function formatSince(iso: string): string {
  const then = new Date(iso).getTime()
  if (!Number.isFinite(then)) return iso
  const ms = Date.now() - then
  if (ms < 60_000) return 'just now'
  const mins = Math.floor(ms / 60_000)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}

function ClientList({ codes }: { codes: string[] }) {
  if (codes.length === 0) {
    return <span className="text-[11.5px] italic text-slate-400">none</span>
  }
  return (
    <span className="flex flex-wrap gap-1">
      {codes.map((code) => (
        <span
          key={code}
          className={
            'rounded-md px-2 py-0.5 text-[11.5px] font-semibold '
            + (code === '__DEFAULT__'
              ? 'bg-amber-100 text-amber-800'
              : 'bg-slate-100 text-slate-700')
          }
          title={code === '__DEFAULT__' ? 'Default row: every client without its own routing' : undefined}
        >
          {code === '__DEFAULT__' ? 'Default' : code}
        </span>
      ))}
    </span>
  )
}
