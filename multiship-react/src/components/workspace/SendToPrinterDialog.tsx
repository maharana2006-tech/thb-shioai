import { useEffect, useState } from 'react'
import { FiAlertTriangle, FiCheckCircle, FiSend, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { printerService, type PrintDocType, type Printer, type SendToPrinterResult } from '../../api/printerService'

const MAX_ORDERS = 500

/**
 * "Send to printer" from the Orders selection bar. By default every order goes
 * to the printer its client is assigned in Settings → Printers; the operator
 * can instead send the whole selection to one printer.
 */
export default function SendToPrinterDialog({
  orderNumbers,
  canManagePrinters,
  onClose,
  onOpenSettings,
}: {
  orderNumbers: number[]
  canManagePrinters: boolean
  onClose: () => void
  onOpenSettings: () => void
}) {
  const [docType, setDocType] = useState<PrintDocType>('LABEL')
  const [printers, setPrinters] = useState<Printer[] | null>(null)
  const [destination, setDestination] = useState<string>('client')
  const [sending, setSending] = useState(false)
  const [result, setResult] = useState<SendToPrinterResult | null>(null)

  useEffect(() => {
    let alive = true
    printerService.list()
      .then((res) => { if (alive) setPrinters((res.data ?? []).filter((p) => p.active)) })
      .catch(() => { if (alive) setPrinters([]) })
    return () => { alive = false }
  }, [])

  const choices = (printers ?? []).filter((p) => docType === 'LABEL' || p.format === 'PDF')
  // A printer picked for labels may not print invoices; fall back to client routing.
  const target = destination !== 'client' && choices.some((p) => String(p.id) === destination) ? destination : 'client'

  const tooMany = orderNumbers.length > MAX_ORDERS

  const send = async () => {
    setSending(true)
    setResult(null)
    try {
      const res = await printerService.sendToPrinter(orderNumbers, docType, target === 'client' ? null : Number(target))
      const data = res.data
      setResult(data ?? null)
      if (data && data.sent > 0 && data.printers.every((p) => p.ok)) notify.success(res.message ?? 'Sent to printer.')
    } catch (e) {
      notify.apiError(e, 'Could not send to the printer.')
    } finally {
      setSending(false)
    }
  }

  const what = docType === 'LABEL' ? 'labels' : 'commercial invoices'
  const radio = (value: PrintDocType, label: string) => (
    <label className={`flex flex-1 cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-[13px] font-semibold ${
      docType === value ? 'border-[#5a4526] bg-[#faf7f0] text-[#3b2d18]' : 'border-[#e3d9c4] text-slate-600'}`}>
      <input type="radio" name="send-doc-type" checked={docType === value} onChange={() => { setDocType(value); setResult(null) }} />
      {label}
    </label>
  )

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-label="Send to printer"
        className="w-full max-w-[520px] rounded-2xl bg-white shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-[#efe7d6] px-5 py-3">
          <h3 className="text-[15px] font-semibold text-slate-900">
            Send {orderNumbers.length.toLocaleString()} order{orderNumbers.length === 1 ? '' : 's'} to printer
          </h3>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-md p-1 text-slate-500 hover:bg-slate-100">
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-4 px-5 py-4">
          <div className="flex gap-2">
            {radio('LABEL', 'Labels')}
            {radio('COMMERCIAL_INVOICE', 'Commercial invoices')}
          </div>

          <label className="block">
            <span className="mb-1 block text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">Print on</span>
            <select value={target} onChange={(e) => { setDestination(e.target.value); setResult(null) }}
              disabled={printers === null}
              className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-2 text-[13px]">
              <option value="client">Each client&rsquo;s assigned printer</option>
              {choices.map((p) => (
                <option key={p.id} value={String(p.id)}>
                  {p.name}{p.location ? ` — ${p.location}` : ''} · {p.format}
                </option>
              ))}
            </select>
            {printers !== null && printers.length === 0 ? (
              <span className="mt-1.5 block text-[12px] text-amber-700">
                No printers are set up yet.{' '}
                {canManagePrinters
                  ? <button type="button" onClick={onOpenSettings} className="font-semibold underline">Add one in Settings → Printers</button>
                  : 'Ask an admin to add one in Settings → Printers.'}
              </span>
            ) : docType === 'COMMERCIAL_INVOICE' ? (
              <span className="mt-1.5 block text-[12px] text-slate-500">Invoices print on PDF printers only; domestic orders have no invoice and are skipped.</span>
            ) : (
              <span className="mt-1.5 block text-[12px] text-slate-500">Orders without a label yet are skipped.</span>
            )}
          </label>

          {tooMany ? (
            <p className="rounded-lg bg-rose-50 px-3 py-2 text-[12.5px] text-rose-800">
              Send at most {MAX_ORDERS} orders at a time — {orderNumbers.length.toLocaleString()} are selected.
            </p>
          ) : null}

          {result ? <ResultSummary result={result} what={what} /> : null}
        </div>

        <div className="flex justify-end gap-2 border-t border-[#efe7d6] px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]">
            {result ? 'Done' : 'Cancel'}
          </button>
          <button type="button" onClick={() => void send()}
            disabled={sending || tooMany || orderNumbers.length === 0 || !printers || printers.length === 0}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#3b2d18] px-3 py-2 text-[13px] font-semibold text-white hover:bg-[#5a4526] disabled:opacity-50">
            {sending
              ? <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white" />
              : <FiSend className="h-3.5 w-3.5" />}
            {sending ? 'Sending…' : result ? 'Send again' : `Send ${what}`}
          </button>
        </div>
      </div>
    </div>
  )
}

function ResultSummary({ result, what }: { result: SendToPrinterResult; what: string }) {
  const orderList = (nos: number[]) => nos.slice(0, 8).map((n) => `#${n}`).join(', ') + (nos.length > 8 ? ` and ${nos.length - 8} more` : '')
  return (
    <div className="space-y-2 rounded-lg border border-[#efe7d6] bg-[#fcfaf5] px-3 py-2.5 text-[12.5px]">
      {result.printers.length === 0 ? (
        <p className="font-semibold text-slate-700">Nothing was sent.</p>
      ) : result.printers.map((p) => (
        <p key={p.printerId} className={`flex items-start gap-1.5 ${p.ok ? 'text-emerald-800' : 'text-rose-800'}`}>
          {p.ok ? <FiCheckCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" /> : <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />}
          <span>
            <span className="font-semibold">{p.printer}</span>
            {p.ok ? ` — ${p.documents} ${what} sent` : ` — ${p.message}`}
          </span>
        </p>
      ))}
      {result.unassigned.length > 0 ? (
        <p className="text-amber-800">
          <span className="font-semibold">No printer assigned</span> for {result.unassigned.length} order{result.unassigned.length === 1 ? '' : 's'} ({orderList(result.unassigned)}). Assign their client a printer or set a Default in Settings → Printers.
        </p>
      ) : null}
      {result.wrongFormat.length > 0 ? (
        <p className="text-amber-800">
          <span className="font-semibold">Assigned printer can&rsquo;t print this</span> for {orderList(result.wrongFormat)}.
        </p>
      ) : null}
      {result.skipped.length > 0 ? (
        <p className="text-slate-600">
          Skipped {result.skipped.length}: {orderList(result.skipped)} (nothing to print or not visible to you).
        </p>
      ) : null}
    </div>
  )
}
