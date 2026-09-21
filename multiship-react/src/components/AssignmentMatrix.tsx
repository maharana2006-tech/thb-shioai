import { useEffect, useMemo, useRef, useState } from 'react'
import { FiCheck, FiPrinter, FiX } from 'react-icons/fi'
import {
  printerService,
  type PrintDocType,
  type Printer,
  type PrinterAssignment,
} from '../api/printerService'
import { notify } from '../utils/notify'

/**
 * PR-Printer-R6 — replaces the dropdown grid ("Where each client
 * prints") with a full matrix:
 *
 * <ul>
 *   <li>Rows = clients (+ Default row at top for the wildcard fallback)</li>
 *   <li>Columns = active printers, sorted alphabetically</li>
 *   <li>Cells show which doctypes route via that printer for that
 *       client — L (labels), I (commercial invoices), or both. Empty
 *       cell = no route via this printer.</li>
 *   <li>Click a cell → popover with two checkboxes. Save applies the
 *       diff via the existing assign / unassign endpoints (per docType).</li>
 * </ul>
 *
 * <p>Scannability > single-click savings: for large tenants (100+
 * clients × many printers) the matrix shows the entire routing at
 * a glance in a way the dropdown table can't.
 *
 * <p>Sticky first column keeps the client name in view during
 * horizontal scroll. Native {@code <button>} cells give keyboard +
 * screen-reader parity without drag-and-drop's a11y baggage.
 *
 * <p>Constraint: {@code COMMERCIAL_INVOICE} needs a PDF printer.
 * Its checkbox is disabled + tooltip'd on non-PDF columns.
 */
export default function AssignmentMatrix({
  clients,
  printers,
  assignments,
  onChanged,
  docType,
}: {
  /** Uppercased client codes to render as rows (in order). `null` = Default. */
  clients: Array<string | null>
  /** Active printers only. Deactivated printers can't accept new assignments;
   *  legacy assignments to them are still shown via the switched-off state. */
  printers: Printer[]
  /** Full assignments list from the backend. */
  assignments: PrinterAssignment[]
  /** Called after any save so the parent can refetch. */
  onChanged: () => void | Promise<void>
  /** PR-Printer-R7b — when provided, the popover shows only that
   *  doctype's checkbox and the cell badges filter accordingly. Omit
   *  to render the mixed L+I cell (R6 behaviour). */
  docType?: PrintDocType
}) {
  const [popover, setPopover] = useState<{ client: string | null; printerId: number } | null>(null)
  const [saving, setSaving] = useState(false)

  // Alphabetical printer order for stable column layout.
  const sortedPrinters = useMemo(
    () => [...printers].sort((a, b) => a.name.localeCompare(b.name)),
    [printers],
  )

  // Fast lookup: `${client}|${printerId}|${docType}` → assignment.
  // Speeds cell rendering from O(clients × printers × assignments) to O(1).
  const byCell = useMemo(() => {
    const m = new Map<string, PrinterAssignment>()
    for (const a of assignments) {
      const key = `${(a.clientCode ?? '__DEFAULT__').toUpperCase()}|${a.printerId}|${a.docType}`
      m.set(key, a)
    }
    return m
  }, [assignments])

  const has = (client: string | null, printerId: number, docType: PrintDocType) =>
    byCell.has(`${(client ?? '__DEFAULT__').toUpperCase()}|${printerId}|${docType}`)
  const cellAssignment = (client: string | null, printerId: number, docType: PrintDocType) =>
    byCell.get(`${(client ?? '__DEFAULT__').toUpperCase()}|${printerId}|${docType}`)

  const handleSave = async (
    client: string | null,
    printerId: number,
    nextLabels: boolean,
    nextInvoice: boolean,
  ) => {
    if (saving) return
    setSaving(true)
    try {
      const labelHad = has(client, printerId, 'LABEL')
      const invoiceHad = has(client, printerId, 'COMMERCIAL_INVOICE')
      // Sequence: unassign-first so a client that flipped LABEL from
      // printer A to printer B doesn't briefly have both.
      // (Backend allows one assignment per (client, docType), so this
      // is more UX-consistency than backend-correctness.)
      if (labelHad && !nextLabels) {
        const cur = cellAssignment(client, printerId, 'LABEL')
        if (cur) await printerService.unassign(cur.id)
      }
      if (invoiceHad && !nextInvoice) {
        const cur = cellAssignment(client, printerId, 'COMMERCIAL_INVOICE')
        if (cur) await printerService.unassign(cur.id)
      }
      if (!labelHad && nextLabels) {
        await printerService.assign(client, 'LABEL', printerId)
      }
      if (!invoiceHad && nextInvoice) {
        await printerService.assign(client, 'COMMERCIAL_INVOICE', printerId)
      }
      await onChanged()
      setPopover(null)
    } catch (err) {
      notify.apiError(err, 'Could not save the routing change')
    } finally {
      setSaving(false)
    }
  }

  if (sortedPrinters.length === 0) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-6 text-center text-[13px] text-slate-500">
        No active printers to route to. Register a printer on the
        <span className="mx-1 font-semibold text-slate-700">Printers</span>
        tab first.
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
      <table className="min-w-full text-[13px]">
        <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
          <tr>
            <th className="sticky left-0 z-10 bg-slate-50 px-3 py-2 shadow-[8px_0_8px_-8px_rgba(15,23,42,0.15)]">
              Client
            </th>
            {sortedPrinters.map((p) => (
              <th key={p.id} className="px-3 py-2 whitespace-nowrap">
                <span className="flex items-center gap-1.5 text-slate-700">
                  <FiPrinter className="h-3.5 w-3.5 text-slate-400" />
                  {p.name}
                </span>
                <span className="mt-0.5 block text-[10px] font-normal normal-case text-slate-400">
                  {p.format} · {p.host}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {clients.map((client) => (
            <tr key={client ?? 'default'}>
              <td className="sticky left-0 z-10 bg-white px-3 py-2.5 shadow-[8px_0_8px_-8px_rgba(15,23,42,0.15)]">
                {client === null ? (
                  <span>
                    <span className="block font-semibold text-slate-900">Default</span>
                    <span className="block text-[11.5px] text-slate-500">Fallback for clients without their own routing</span>
                  </span>
                ) : (
                  <span className="font-semibold text-slate-900">{client}</span>
                )}
              </td>
              {sortedPrinters.map((p) => {
                const rawHasLabel = has(client, p.id, 'LABEL')
                const rawHasInvoice = has(client, p.id, 'COMMERCIAL_INVOICE')
                // PR-R7b — when docType is set, hide the other doctype's
                // badge but keep it visible in the popover so an admin
                // can still add it without switching tabs.
                const hasLabel = docType === 'COMMERCIAL_INVOICE' ? false : rawHasLabel
                const hasInvoice = docType === 'LABEL' ? false : rawHasInvoice
                const active = hasLabel || hasInvoice
                const isPopoverOpen = popover?.client === client && popover?.printerId === p.id
                return (
                  <td key={p.id} className="relative px-3 py-2.5">
                    <button
                      type="button"
                      onClick={() => setPopover(isPopoverOpen ? null : { client, printerId: p.id })}
                      aria-label={`Routing for ${client ?? 'Default'} → ${p.name}`}
                      aria-expanded={isPopoverOpen}
                      className={
                        'inline-flex min-w-[52px] items-center justify-center gap-1 rounded-md border px-2 py-1 text-[11px] font-semibold transition-colors '
                        + (active
                          ? 'border-slate-300 bg-slate-50 text-slate-700 hover:bg-slate-100'
                          : 'border-dashed border-slate-200 bg-white text-slate-400 hover:border-slate-300 hover:text-slate-600')
                      }
                    >
                      {hasLabel ? (
                        <span className="rounded-full bg-sky-100 px-1.5 py-0.5 text-sky-800" title="Labels">L</span>
                      ) : null}
                      {hasInvoice ? (
                        <span className="rounded-full bg-violet-100 px-1.5 py-0.5 text-violet-800" title="Commercial invoices">I</span>
                      ) : null}
                      {!active ? <span className="text-[10px] font-normal">—</span> : null}
                    </button>

                    {isPopoverOpen ? (
                      <CellPopover
                        clientCode={client}
                        printer={p}
                        hasLabel={rawHasLabel}
                        hasInvoice={rawHasInvoice}
                        docType={docType}
                        saving={saving}
                        onClose={() => setPopover(null)}
                        onSave={(l, i) => void handleSave(client, p.id, l, i)}
                      />
                    ) : null}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Popover anchored under the cell button. Click-outside closes it. */
function CellPopover({
  clientCode,
  printer,
  hasLabel,
  hasInvoice,
  docType,
  saving,
  onClose,
  onSave,
}: {
  clientCode: string | null
  printer: Printer
  hasLabel: boolean
  hasInvoice: boolean
  /** PR-R7b — scope the popover to a single doctype. undefined = both. */
  docType?: PrintDocType
  saving: boolean
  onClose: () => void
  onSave: (labels: boolean, invoice: boolean) => void
}) {
  const [labels, setLabels] = useState(hasLabel)
  const [invoice, setInvoice] = useState(hasInvoice)
  const ref = useRef<HTMLDivElement>(null)
  const invoiceAllowed = printer.format !== 'ZPL'
  const dirty = labels !== hasLabel || invoice !== hasInvoice

  // Click-outside close. mousedown fires before button click so the
  // outer table's other cell-buttons work as toggle-open on the next
  // popover without a phantom close-and-reopen.
  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose()
    }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [onClose])

  return (
    <div
      ref={ref}
      role="dialog"
      aria-label={`Routing for ${clientCode ?? 'Default'} → ${printer.name}`}
      className="absolute left-0 top-full z-20 mt-1 w-64 rounded-lg border border-slate-200 bg-white p-3 shadow-lg"
    >
      <p className="text-[11.5px] font-semibold text-slate-500">
        Route which docs from <span className="text-slate-800">{clientCode ?? 'Default'}</span> to <span className="text-slate-800">{printer.name}</span>?
      </p>
      {docType === undefined || docType === 'LABEL' ? (
        <label className="mt-3 flex items-center gap-2 text-[12.5px] text-slate-800">
          <input
            type="checkbox"
            checked={labels}
            onChange={(e) => setLabels(e.target.checked)}
            disabled={saving}
          />
          <span className="rounded-full bg-sky-100 px-1.5 py-0.5 text-[10px] font-semibold text-sky-800">L</span>
          Labels
        </label>
      ) : null}
      {docType === undefined || docType === 'COMMERCIAL_INVOICE' ? (
        <label
          className={
            'mt-2 flex items-center gap-2 text-[12.5px] '
            + (invoiceAllowed ? 'text-slate-800' : 'text-slate-400')
          }
          title={invoiceAllowed ? undefined : 'Commercial invoices need a PDF printer. This one prints ZPL.'}
        >
          <input
            type="checkbox"
            checked={invoice}
            onChange={(e) => setInvoice(e.target.checked)}
            disabled={saving || !invoiceAllowed}
          />
          <span className="rounded-full bg-violet-100 px-1.5 py-0.5 text-[10px] font-semibold text-violet-800">I</span>
          Commercial invoices
          {!invoiceAllowed ? <span className="ml-auto text-[10px]">PDF only</span> : null}
        </label>
      ) : null}
      <div className="mt-3 flex items-center justify-end gap-1.5 border-t border-slate-100 pt-2">
        <button
          type="button"
          onClick={onClose}
          disabled={saving}
          className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11.5px] font-semibold text-slate-600 hover:bg-slate-50 disabled:opacity-50"
        >
          <FiX className="h-3 w-3" />
          Cancel
        </button>
        <button
          type="button"
          onClick={() => onSave(labels, invoice)}
          disabled={saving || !dirty}
          className="inline-flex items-center gap-1 rounded-md bg-slate-900 px-2 py-1 text-[11.5px] font-semibold text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving
            ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
            : <FiCheck className="h-3 w-3" />}
          Save
        </button>
      </div>
    </div>
  )
}
