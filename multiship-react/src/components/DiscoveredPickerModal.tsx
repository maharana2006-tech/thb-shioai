import { relativeTime } from '../utils/relativeTime'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { FiCheckSquare, FiPrinter, FiRefreshCw, FiSquare, FiX } from 'react-icons/fi'
import {
  printerService,
  type Printer,
  type PrinterConnection,
  type PrinterDiscovered,
  type PrinterFormat,
  type PrinterInput,
  type PrinterPaper,
} from '../api/printerService'
import { notify } from '../utils/notify'

/**
 * PR-Printer-P3 — bulk-add drawer for the LAN scan flow.
 *
 * <p>Fetches {@code /tenants/{t}/printers/discovered/latest} and shows
 * every discovered row as a selectable table entry. Rows whose
 * {@code host:port} already matches an existing {@link Printer} are
 * disabled so the admin can't create duplicates by accident.
 *
 * <p>Bulk-add fires one POST per selection (no dedicated batch endpoint
 * on the backend — reuses {@code POST /api/v1/printers} for parity with
 * the manual "Add printer" flow). Errors don't abort the batch: each
 * failure is counted, and the toast summarises added vs failed at the
 * end so a bad row doesn't cost the admin the whole batch.
 */
export default function DiscoveredPickerModal({
  tenantCode,
  existingPrinters,
  onClose,
  onImported,
}: {
  tenantCode: string
  existingPrinters: Printer[]
  onClose: () => void
  onImported: () => void | Promise<void>
}) {
  const [rows, setRows] = useState<PrinterDiscovered[]>([])
  const [loading, setLoading] = useState(true)
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [importing, setImporting] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await printerService.latestDiscovered(tenantCode)
      setRows(res.data ?? [])
    } catch (err) {
      notify.apiError(err, 'Could not load discovered printers')
    } finally {
      setLoading(false)
    }
  }, [tenantCode])

  useEffect(() => {
    let cancelled = false
    queueMicrotask(() => { if (!cancelled) void load() })
    return () => { cancelled = true }
  }, [load])

  // O(1) duplicate-lookup — host+port pair identifies a printer in the
  // registered list; multiple entries on same host with different ports
  // (e.g. 631 IPP + 9100 raw) are legitimately distinct printers.
  const existingKeys = useMemo(
    () => new Set(existingPrinters.map((p) => `${p.host}:${p.port}`)),
    [existingPrinters],
  )

  const eligible = useMemo(
    () => rows.filter((r) => !existingKeys.has(`${r.host}:${r.port}`)),
    [rows, existingKeys],
  )

  const allEligibleSelected = eligible.length > 0
    && eligible.every((r) => selected.has(r.id))

  const toggle = (id: number) => {
    setSelected((cur) => {
      const next = new Set(cur)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const toggleAll = () => {
    setSelected(() => allEligibleSelected ? new Set() : new Set(eligible.map((r) => r.id)))
  }

  const submit = async () => {
    if (importing || selected.size === 0) return
    const toImport = rows.filter((r) => selected.has(r.id))
    setImporting(true)
    let added = 0
    const failures: string[] = []
    for (const row of toImport) {
      try {
        await printerService.create(toPrinterInput(row))
        added++
      } catch (err) {
        failures.push(`${row.host}:${row.port} — ${err instanceof Error ? err.message : 'failed'}`)
      }
    }
    setImporting(false)

    if (added > 0) {
      notify.success({
        title: `Added ${added} printer${added === 1 ? '' : 's'}`,
        body: failures.length > 0
          ? `${failures.length} row${failures.length === 1 ? '' : 's'} failed — see below.`
          : 'They appear in the Printers table above.',
      })
    }
    if (failures.length > 0) {
      notify.error({
        title: `${failures.length} row${failures.length === 1 ? '' : 's'} did not import`,
        body: failures.slice(0, 3).join('\n') + (failures.length > 3 ? `\n…and ${failures.length - 3} more` : ''),
        durationMs: 15_000,
      })
    }

    if (added > 0) {
      await onImported()
      onClose()
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="printer-picker-heading"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      onClick={importing ? undefined : onClose}
    >
      <div
        className="flex max-h-[90vh] w-full max-w-3xl flex-col rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-3">
          <div>
            <h3 id="printer-picker-heading" className="flex items-center gap-2 text-[15px] font-semibold text-slate-950">
              <FiPrinter className="h-4 w-4 text-slate-500" />
              Add printers from scan
            </h3>
            <p className="mt-0.5 text-[12px] text-slate-500">
              These are the printers your LAN scanner found. Pick the ones you want to register.
              Rows already in the table above are disabled.
            </p>
          </div>
          <button
            type="button"
            onClick={() => void load()}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-2.5 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50"
            aria-label="Refresh discovered list"
          >
            <FiRefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded-md p-1 text-slate-500 hover:bg-slate-100"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-auto">
          {loading ? (
            <div className="px-5 py-10 text-center text-[12.5px] text-slate-500">Loading…</div>
          ) : rows.length === 0 ? (
            <div className="px-5 py-10 text-center text-[12.5px] text-slate-500">
              Nothing here yet. Click <span className="font-semibold">Scan for printers</span> above,
              wait about 10 seconds, then reopen this list.
            </div>
          ) : (
            <table className="min-w-full text-[13px]">
              <thead className="sticky top-0 bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-3 py-2">
                    <button
                      type="button"
                      onClick={toggleAll}
                      disabled={eligible.length === 0}
                      aria-label={allEligibleSelected ? 'Clear selection' : 'Select all new'}
                      className="inline-flex items-center text-slate-500 hover:text-slate-800 disabled:opacity-40"
                    >
                      {allEligibleSelected
                        ? <FiCheckSquare className="h-4 w-4" />
                        : <FiSquare className="h-4 w-4" />}
                    </button>
                  </th>
                  <th className="px-3 py-2">Printer</th>
                  <th className="px-3 py-2">Address</th>
                  <th className="px-3 py-2">Kind</th>
                  <th className="px-3 py-2">Scanner</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map((r) => {
                  const key = `${r.host}:${r.port}`
                  const duplicate = existingKeys.has(key)
                  const isSelected = selected.has(r.id)
                  return (
                    <tr key={r.id} className={duplicate ? 'opacity-50' : ''}>
                      <td className="px-3 py-2.5">
                        <input
                          type="checkbox"
                          checked={isSelected}
                          disabled={duplicate || importing}
                          onChange={() => toggle(r.id)}
                          aria-label={`Select ${key}`}
                        />
                      </td>
                      <td className="px-3 py-2.5">
                        <span className="block font-semibold text-slate-900">
                          {r.name?.trim() || fallbackName(r)}
                        </span>
                        {r.location ? (
                          <span className="block text-[11.5px] text-slate-500">{r.location}</span>
                        ) : null}
                        {duplicate ? (
                          <span className="mt-0.5 inline-block rounded-full bg-amber-100 px-2 py-0.5 text-[10.5px] font-semibold uppercase tracking-wide text-amber-800">
                            already registered
                          </span>
                        ) : null}
                      </td>
                      <td className="px-3 py-2.5 font-mono text-[12px] text-slate-700">
                        {r.host}:{r.port}
                        {r.queuePath ? <span className="text-slate-400">/{r.queuePath}</span> : null}
                      </td>
                      <td className="px-3 py-2.5">
                        <span className="mr-1 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700">
                          {r.connectionGuess ?? 'unknown'}
                        </span>
                        {r.formatGuess ? (
                          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700">
                            {r.formatGuess}
                          </span>
                        ) : null}
                      </td>
                      <td className="px-3 py-2.5 text-[12px] text-slate-500">
                        <span className="block font-semibold text-slate-700">{r.agentId}</span>
                        <span className="block text-[11px] text-slate-400">{relativeTime(r.discoveredAt)}</span>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
        </div>

        <div className="flex items-center justify-between gap-3 border-t border-slate-100 px-5 py-3">
          <span className="text-[12px] text-slate-500">
            {selected.size === 0
              ? 'Nothing selected'
              : `${selected.size} printer${selected.size === 1 ? '' : 's'} selected`}
            {eligible.length > 0 && rows.length !== eligible.length ? (
              <span className="ml-1 text-slate-400">
                ({rows.length - eligible.length} already registered)
              </span>
            ) : null}
          </span>
          <span className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              disabled={importing}
              className="inline-flex items-center rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void submit()}
              disabled={importing || selected.size === 0}
              className="inline-flex items-center gap-1.5 rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {importing ? 'Adding…' : `Add ${selected.size || ''} selected`.trim()}
            </button>
          </span>
        </div>
      </div>
    </div>
  )
}

// --- helpers -------------------------------------------------------------

function toPrinterInput(row: PrinterDiscovered): PrinterInput {
  const connection: PrinterConnection = (row.connectionGuess ?? 'RAW_9100') as PrinterConnection
  const format: PrinterFormat = (row.formatGuess ?? (connection === 'IPP' ? 'PDF' : 'ZPL')) as PrinterFormat
  const paper: PrinterPaper = (row.paperGuess ?? (format === 'ZPL' ? 'LABEL_4X6' : 'LETTER')) as PrinterPaper
  return {
    name: row.name?.trim() || fallbackName(row),
    location: row.location,
    connection,
    host: row.host,
    port: row.port,
    queuePath: row.queuePath,
    format,
    paper,
    active: true,
  }
}

function fallbackName(row: PrinterDiscovered): string {
  // Auto-name unnamed printers by connection + host tail so the admin
  // sees "RAW_9100 · 192.168.1.42" rather than a blank cell before edit.
  const kind = row.connectionGuess ?? 'Printer'
  return `${kind} · ${row.host}`
}

