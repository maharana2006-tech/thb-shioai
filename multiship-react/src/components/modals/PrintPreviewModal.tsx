import { useRef, useState } from 'react'
import { FiPrinter, FiX } from 'react-icons/fi'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import { useModalDismiss } from '../../hooks/useModalDismiss'
import { notify } from '../../utils/notify'
import PdfPagesPreview from '../PdfPagesPreview'

export type PrintDocKind = 'label' | 'invoice'

interface PrintPreviewModalProps {
  /** The printable PDF — main label page(s) at 4×6, or the commercial invoice. */
  blob: Blob
  kind: PrintDocKind
  orderNo: number
  onClose: () => void
}

/** Paper geometry per document: the label prints on 4×6 stock, the
 *  invoice on Letter. Drives both the @page rule and the image size so
 *  the browser's print dialog defaults to the right sheet. */
const PAPER: Record<PrintDocKind, { size: string; widthIn: number; heightIn: number; title: string }> = {
  label: { size: '4in 6in', widthIn: 4, heightIn: 6, title: 'Shipping label' },
  invoice: { size: 'letter', widthIn: 8.5, heightIn: 11, title: 'Commercial invoice' },
}

/**
 * In-app print preview. Shows the document's rendered pages and prints them
 * from a hidden same-origin HTML frame holding the page images, sized to the
 * physical sheet. Printing the PDF blob itself through an iframe is not
 * viable — Chrome denies script access to its PDF viewer, so print() throws
 * and the only recourse was opening a new tab.
 */
export default function PrintPreviewModal({ blob, kind, orderNo, onClose }: PrintPreviewModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const pagesRef = useRef<HTMLDivElement>(null)
  const [pageCount, setPageCount] = useState(0)
  const [printing, setPrinting] = useState(false)
  useFocusTrap(true, dialogRef)
  useModalDismiss(true, dialogRef, onClose)
  const paper = PAPER[kind]

  const print = () => {
    const canvases = [...(pagesRef.current?.querySelectorAll('canvas') ?? [])]
      .filter((c) => c.width > 300 || c.dataset.rendered === '1')
    if (!canvases.length) {
      notify.info('The pages are still rendering — try again in a moment.')
      return
    }
    setPrinting(true)
    const pages = canvases
      .map((c) => `<img src="${c.toDataURL('image/png')}" alt="">`)
      .join('')
    const html = `<!doctype html><html><head><meta charset="utf-8"><title>${paper.title} — order ${orderNo}</title>
<style>
  @page { size: ${paper.size}; margin: 0; }
  html, body { margin: 0; padding: 0; background: #fff; }
  img { display: block; width: ${paper.widthIn}in; height: ${paper.heightIn}in; object-fit: contain; page-break-after: always; break-after: page; }
  img:last-child { page-break-after: auto; break-after: auto; }
</style></head><body>${pages}</body></html>`
    const frame = document.createElement('iframe')
    frame.setAttribute('aria-hidden', 'true')
    frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;'
    frame.srcdoc = html
    frame.onload = () => {
      const win = frame.contentWindow
      const done = () => {
        setPrinting(false)
        window.setTimeout(() => frame.remove(), 1000)
      }
      if (!win) {
        done()
        return
      }
      win.addEventListener('afterprint', done, { once: true })
      // Let the data-URI images decode before the dialog snapshots the page.
      window.setTimeout(() => {
        try {
          win.focus()
          win.print()
        } catch {
          done()
          notify.error('The browser blocked printing — use Download PDF and print from your PDF viewer.')
        }
        // Browsers that never fire afterprint (dialog cancelled early, etc.)
        window.setTimeout(done, 60_000)
      }, 150)
    }
    document.body.appendChild(frame)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-sm print:hidden"
      role="dialog"
      aria-modal="true"
      aria-label={`Print ${paper.title.toLowerCase()} for order ${orderNo}`}
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex max-h-[92vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-[#412d15]">Print preview</p>
            <h3 className="mt-1 text-base font-semibold text-[#1f150c]">
              {paper.title} · order #{orderNo}
            </h3>
            <p className="mt-1 text-xs leading-5 text-slate-500">
              {kind === 'label'
                ? `Prints on ${paper.size.replace(' ', ' × ')} label stock — ${pageCount || '…'} sheet${pageCount === 1 ? '' : 's'}, one per package. This is exactly what the printer receives.`
                : `Prints on Letter — ${pageCount || '…'} page${pageCount === 1 ? '' : 's'}.`}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
            aria-label="Close"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div ref={pagesRef} className="min-h-0 flex-1 overflow-y-auto bg-slate-100/70 px-5 py-5">
          <PdfPagesPreview
            blob={blob}
            pageWidthPx={kind === 'label' ? 320 : 560}
            onLoaded={setPageCount}
            onError={() => {
              notify.error('Could not render the document for preview.')
              onClose()
            }}
          />
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[13px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
          >
            Close
          </button>
          <button
            type="button"
            onClick={print}
            disabled={printing || !pageCount}
            autoFocus
            className="inline-flex items-center gap-1.5 rounded-xl bg-slate-950 px-4 py-2 text-[13px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            <FiPrinter className="h-3.5 w-3.5" />
            {printing ? 'Printing…' : 'Print'}
          </button>
        </div>
      </div>
    </div>
  )
}
