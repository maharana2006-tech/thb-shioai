import { useEffect, useRef, useState } from 'react'
import { FiChevronDown, FiFileText, FiPrinter, FiSend } from 'react-icons/fi'
import type { OrderImportRow } from '../../api/orderImportService'
import { hasCommercialInvoice, liveOrdersOf } from '../../utils/batchLabels'
import { useDismissable } from '../../hooks/useDismissable'

type Docs = { labels: number[]; invoices: number[] }

/**
 * One Print ▾ for whole batches in Import history — the batch page's menu:
 * Download labels, Download invoices (only when there is an international
 * order with a live label) and Send to printer. The rows are read when the
 * menu opens, so the list itself stays light.
 */
export default function BatchPrintMenu({
  scope,
  buttonLabel,
  busy,
  disabledReason,
  loadRows,
  onPrint,
  onSend,
}: {
  /** What it prints, for people: "batch #128", "2 batches". */
  scope: string
  /** Text for the button (the selection bar); an icon button when omitted (a row). */
  buttonLabel?: string
  busy: boolean
  /** Shown but not usable, with this as the reason (e.g. nothing ticked has a label). */
  disabledReason?: string
  loadRows: () => Promise<OrderImportRow[]>
  onPrint: (orders: number[], docType: 'LABEL' | 'COMMERCIAL_INVOICE') => void
  onSend: (orders: number[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [docs, setDocs] = useState<Docs | null>(null)
  const [at, setAt] = useState<{ top?: number; bottom?: number; right: number } | null>(null)
  const btn = useRef<HTMLButtonElement>(null)
  const close = () => setOpen(false)
  const ref = useDismissable(open, close)

  // Pinned where the button is (the list scrolls in its own box): close rather than drift.
  useEffect(() => {
    if (!open) return
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [open])

  const toggle = () => {
    if (open) return close()
    const r = btn.current?.getBoundingClientRect()
    if (r) {
      const right = window.innerWidth - r.right
      // Open upward near the bottom of the window.
      setAt(r.bottom + 200 > window.innerHeight ? { bottom: window.innerHeight - r.top + 6, right } : { top: r.bottom + 6, right })
    }
    setDocs(null)
    setOpen(true)
    loadRows()
      .then((rows) => setDocs({ labels: liveOrdersOf(rows), invoices: liveOrdersOf(rows.filter(hasCommercialInvoice)) }))
      .catch(() => setDocs({ labels: [], invoices: [] }))
  }

  const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`
  const items = docs ? [
    { key: 'LABEL', label: 'Download labels', hint: `One print dialog, ${plural(docs.labels.length, 'label')}`,
      icon: <FiPrinter className="h-3.5 w-3.5 text-[#412d15]" />, run: () => onPrint(docs.labels, 'LABEL') },
    ...(docs.invoices.length > 0 ? [{ key: 'INVOICE', label: 'Download invoices',
      hint: `Commercial invoices — ${plural(docs.invoices.length, 'international order')}`,
      icon: <FiFileText className="h-3.5 w-3.5 text-sky-700" />, run: () => onPrint(docs.invoices, 'COMMERCIAL_INVOICE') }] : []),
    { key: 'SEND', label: 'Send to printer', hint: 'Straight to a network printer',
      icon: <FiSend className="h-3.5 w-3.5 text-emerald-600" />, run: () => onSend(docs.labels) },
  ] : []

  return (
    <div ref={ref} className="relative">
      <button
        ref={btn}
        type="button"
        onClick={toggle}
        disabled={busy || !!disabledReason}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={buttonLabel ? undefined : 'Print batch'}
        title={disabledReason ?? `Download labels or invoices of ${scope}, or send them to a printer`}
        className={buttonLabel
          ? 'inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#412d15] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40'
          : 'inline-flex items-center gap-0.5 rounded-xl border border-[#e3d9c4] bg-[#faf7f0] p-2 text-[#412d15] transition hover:border-[#cdbf9f] hover:bg-[#f0e9d8] disabled:opacity-50'}
      >
        {busy
          ? <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#e3d9c4] border-t-[#5a4526]" />
          : <FiPrinter className="h-3.5 w-3.5" />}
        {buttonLabel}
        <FiChevronDown className={`h-3 w-3 text-[#b6a684] transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
      </button>
      {open && at ? (
        <div role="menu" aria-label={`Print ${scope}`} style={{ position: 'fixed', ...at }}
          className="bulk-pop-in z-50 w-60 rounded-xl border border-[#e3d9c4] bg-white p-1 text-left shadow-[0_12px_32px_rgba(31,21,12,0.14)]">
          {docs === null ? (
            <p className="px-2.5 py-2 text-[11.5px] text-[#8a7a5c]">Reading {scope}…</p>
          ) : docs.labels.length === 0 ? (
            <p className="px-2.5 py-2 text-[11.5px] text-[#8a7a5c]">No live labels in {scope}.</p>
          ) : items.map((item) => (
            <button key={item.key} type="button" role="menuitem" onClick={() => { close(); item.run() }}
              className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition hover:bg-[#faf7f0]">
              <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-[#f4eede]" aria-hidden="true">{item.icon}</span>
              <span className="min-w-0">
                <span className="block text-[12px] font-semibold leading-tight text-[#1f150c]">{item.label}</span>
                <span className="block text-[10.5px] leading-tight text-[#a1906d]">{item.hint}</span>
              </span>
            </button>
          ))}
          <p className="border-t border-[#f2ecdf] px-2.5 pb-1 pt-1.5 text-[10.5px] text-[#a1906d]">For {scope.startsWith('batch #') ? `all of ${scope}` : `the ${scope} selected`}.</p>
        </div>
      ) : null}
    </div>
  )
}
