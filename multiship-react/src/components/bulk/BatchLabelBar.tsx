import { useState } from 'react'
import { FiFileText, FiPrinter, FiSend, FiSlash, FiX } from 'react-icons/fi'
import type { OrderImportRow } from '../../api/orderImportService'
import { orderImportService } from '../../api/orderImportService'
import { orderService } from '../../api/orderService'
import { notify } from '../../utils/notify'
import { printPdfBlob } from '../../utils/printPdf'
import { BTN_GHOST_SM } from '../ui/buttons'
import { liveOrdersOf } from '../../utils/batchLabels'
import SendToPrinterDialog from '../workspace/SendToPrinterDialog'

const MAX_PRINT = 500

/**
 * The batch page's label actions — print labels, print commercial invoices,
 * send to a network printer, void — for the ticked rows, or for every live
 * label in the batch when nothing is ticked. The same print and send paths
 * the Orders page uses; void runs on the server and reports each carrier's
 * own answer.
 */
export default function BatchLabelBar({
  batchId,
  rows,
  picked,
  onPickAllLive,
  onClearPick,
  onChanged,
  onPrinted,
  canWrite,
  canManagePrinters,
  locked,
  onOpenPrinterSettings,
}: {
  batchId: number
  rows: OrderImportRow[]
  /** Ticked row numbers. */
  picked: number[]
  onPickAllLive: () => void
  onClearPick: () => void
  /** Labels were voided — reload the rows. */
  onChanged: () => void
  /** Something was printed or sent — reload so the rows say so. */
  onPrinted?: () => void
  canWrite: boolean
  canManagePrinters: boolean
  /** Generating, or in Trash — no voiding. */
  locked: boolean
  onOpenPrinterSettings: () => void
}) {
  const [printing, setPrinting] = useState<'LABEL' | 'COMMERCIAL_INVOICE' | null>(null)
  const [sendOpen, setSendOpen] = useState(false)
  const [voiding, setVoiding] = useState(false)

  const allLive = liveOrdersOf(rows)
  const pickedSet = new Set(picked)
  const pickedLive = liveOrdersOf(rows.filter((r) => pickedSet.has(r.rowNumber)))
  const scoped = picked.length > 0
  const target = scoped ? pickedLive : allLive
  const n = target.length
  const scopeLabel = scoped ? `${n} selected` : `all ${n}`

  if (allLive.length === 0) return null

  const print = async (docType: 'LABEL' | 'COMMERCIAL_INVOICE') => {
    if (n === 0 || printing) return
    if (n > MAX_PRINT) {
      notify.info({ title: 'Too many to print at once', body: `Print at most ${MAX_PRINT} orders at a time — tick fewer rows.` })
      return
    }
    setPrinting(docType)
    try {
      const res = await orderService.printDocuments(target, docType)
      printPdfBlob(res.blob)
      onPrinted?.()
      const what = docType === 'LABEL' ? 'label' : 'commercial invoice'
      notify.success(`Opening ${res.included} ${what}${res.included === 1 ? '' : 's'} in the print dialog`
        + (res.skipped > 0 ? ` · ${res.skipped} skipped (${docType === 'LABEL' ? 'no label' : 'domestic — no invoice'})` : '') + '.')
    } catch (e) {
      const status = (e as { status?: number }).status
      const message = e instanceof Error ? e.message : 'Print failed.'
      if (status === 422) notify.info({ title: 'Nothing to print', body: message })
      else notify.error({ title: 'Print failed', body: message })
    } finally {
      setPrinting(null)
    }
  }

  const voidLabels = async () => {
    if (n === 0 || voiding) return
    const ok = await notify.confirm(
      `The carrier${n === 1 ? '' : 's'} will cancel ${n === 1 ? 'this label' : `these ${n} labels`} — the order${n === 1 ? '' : 's'} can't ship on ${n === 1 ? 'it' : 'them'} any more. This can't be undone.`,
      { title: `Void ${n} label${n === 1 ? '' : 's'}?`, confirmLabel: `Void ${n} label${n === 1 ? '' : 's'}`, cancelLabel: 'Keep them' },
    )
    if (!ok) return
    setVoiding(true)
    try {
      const rowNumbers = scoped ? picked : []
      const res = (await orderImportService.voidBatchLabels(batchId, rowNumbers)).data
      if (!res || res.orders.length === 0) {
        notify.info('There were no live labels left to void.')
      } else if (res.refused === 0) {
        notify.success(`${res.voided} label${res.voided === 1 ? '' : 's'} voided.`)
      } else {
        const refusals = res.orders.filter((o) => !o.voided).slice(0, 3)
          .map((o) => `#${o.orderNo}: ${o.message}`).join(' · ')
        notify.info({
          title: `${res.voided} voided, ${res.refused} refused by the carrier`,
          body: refusals + (res.refused > 3 ? ` · and ${res.refused - 3} more` : ''),
        })
      }
      onClearPick()
      onChanged()
    } catch (e) {
      notify.apiError(e, 'Could not void the labels.')
    } finally {
      setVoiding(false)
    }
  }

  const spin = <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#e3d9c4] border-t-[#5a4526]" />

  return (
    <div
      data-testid="batch-label-bar"
      className="mb-2 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2"
    >
      <span className="flex flex-wrap items-center gap-2 text-[11.5px] text-[#6b5c42]">
        {scoped ? (
          <>
            <span className="font-semibold text-[#1f150c]">{picked.length} row{picked.length === 1 ? '' : 's'} ticked</span>
            <span>· {n} order{n === 1 ? '' : 's'} with a live label</span>
            <button type="button" onClick={onClearPick} className="inline-flex items-center gap-0.5 font-semibold text-[#5a4526] hover:underline">
              <FiX className="h-3 w-3" /> Clear
            </button>
          </>
        ) : (
          <>
            <span><span className="font-semibold text-[#1f150c]">{allLive.length}</span> live label{allLive.length === 1 ? '' : 's'} in this batch</span>
            <button type="button" onClick={onPickAllLive} className="font-semibold text-[#5a4526] hover:underline">Tick them all</button>
            <span className="text-[#b6a684]">· or tick rows to act on just those</span>
          </>
        )}
      </span>
      <span className="flex flex-wrap items-center gap-1.5">
        <button type="button" className={BTN_GHOST_SM} disabled={n === 0 || !!printing} onClick={() => void print('LABEL')}
          title={`Print the labels of ${scopeLabel} in one print dialog`}>
          {printing === 'LABEL' ? spin : <FiPrinter className="h-3.5 w-3.5 text-[#412d15]" />} Print labels
        </button>
        <button type="button" className={BTN_GHOST_SM} disabled={n === 0 || !!printing} onClick={() => void print('COMMERCIAL_INVOICE')}
          title={`Print the commercial invoices of ${scopeLabel} (international orders)`}>
          {printing === 'COMMERCIAL_INVOICE' ? spin : <FiFileText className="h-3.5 w-3.5 text-sky-700" />} Print invoices
        </button>
        <button type="button" className={BTN_GHOST_SM} disabled={n === 0} onClick={() => setSendOpen(true)}
          title={`Send ${scopeLabel} to a network printer`}>
          <FiSend className="h-3.5 w-3.5 text-emerald-600" /> Send to printer
        </button>
        {canWrite ? (
          <button type="button" disabled={n === 0 || voiding || locked} onClick={() => void voidLabels()}
            title={locked ? 'Not while the batch is generating or in Trash' : `Void the labels of ${scopeLabel} with their carriers`}
            className="inline-flex items-center gap-1 rounded-lg border border-rose-200 bg-white px-2 py-1 text-[11px] font-semibold text-rose-700 transition hover:border-rose-300 hover:bg-rose-50 disabled:cursor-not-allowed disabled:opacity-40">
            {voiding ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-rose-100 border-t-rose-600" /> : <FiSlash className="h-3.5 w-3.5" />}
            Void {scoped ? `${n}` : 'all'}
          </button>
        ) : null}
      </span>
      {sendOpen ? (
        <SendToPrinterDialog
          orderNumbers={target}
          canManagePrinters={canManagePrinters}
          onClose={() => { setSendOpen(false); onPrinted?.() }}
          onOpenSettings={() => { setSendOpen(false); onOpenPrinterSettings() }}
        />
      ) : null}
    </div>
  )
}
