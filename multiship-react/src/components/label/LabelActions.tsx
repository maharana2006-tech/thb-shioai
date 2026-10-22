import { useEffect, useRef, useState, type ReactNode } from 'react'
import { FiActivity, FiCopy, FiDownload, FiExternalLink, FiFileText, FiPrinter } from 'react-icons/fi'
import { labelService, type LabelArtifact } from '../../api/labelService'
import { orderService } from '../../api/orderService'
import { notify } from '../../utils/notify'
import TrackingTimelineModal from '../tracking/TrackingTimelineModal'

/**
 * Every action here hands the operator the same bytes the preview was
 * rendered from. Copy ZPL falls back to a selectable textarea when the
 * browser refuses clipboard access, instead of only showing an error.
 * Tracking and the commercial invoice sit here too, so the page reaches
 * parity with the document page it replaced.
 */
export function LabelActions({ artifact, activeSeq }: { artifact: LabelArtifact; activeSeq: number }) {
  const [busy, setBusy] = useState<'zpl' | 'pdf' | 'invoice' | null>(null)
  const [fallbackZpl, setFallbackZpl] = useState<string | null>(null)
  const [trackingOpen, setTrackingOpen] = useState(false)
  const fallbackRef = useRef<HTMLTextAreaElement>(null)
  const inFlight = useRef(false)
  const total = artifact.packages.length
  const { orderNo } = artifact
  const pkg = artifact.packages.find((p) => p.seq === activeSeq) ?? artifact.packages[0]

  useEffect(() => {
    if (fallbackZpl !== null) fallbackRef.current?.select()
  }, [fallbackZpl])

  const fetchZpl = async (): Promise<string | null> => {
    if (inFlight.current) return null
    inFlight.current = true
    setBusy('zpl')
    try {
      return await labelService.packageZpl(orderNo, activeSeq)
    } catch (err) {
      void notify.apiError(err, "Couldn't fetch the ZPL — retry, or download the PDF.")
      return null
    } finally {
      inFlight.current = false
      setBusy(null)
    }
  }

  const copyZpl = async () => {
    const zpl = await fetchZpl()
    if (!zpl) return
    try {
      await navigator.clipboard.writeText(zpl)
      setFallbackZpl(null)
      void notify.success('ZPL copied — paste it into labelary.com/viewer or send it to a Zebra.')
    } catch {
      // Clipboard refused (permissions, older desktop apps): expose the text
      // selected so one keystroke copies it.
      setFallbackZpl(zpl)
      void notify.info('Clipboard is blocked here — the ZPL is selected below, press Ctrl/Cmd+C to copy it.')
    }
  }

  const downloadBlob = (blob: Blob, filename: string) => {
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    link.click()
    URL.revokeObjectURL(url)
  }

  const downloadZpl = async () => {
    const zpl = await fetchZpl()
    if (!zpl) return
    const name = total > 1 ? `label-${orderNo}-pkg${activeSeq}of${total}.zpl` : `label-${orderNo}.zpl`
    downloadBlob(new Blob([zpl], { type: 'text/plain' }), name)
    void notify.success(`${name} downloaded — send it straight to a Zebra printer.`)
  }

  const downloadPdf = async () => {
    setBusy('pdf')
    try {
      downloadBlob(await labelService.getPdf(orderNo, undefined), `label-${orderNo}.pdf`)
    } catch (err) {
      void notify.apiError(err, "Couldn't fetch the PDF.")
    } finally {
      setBusy(null)
    }
  }

  // Opens the printable PDF (one 4×6 page per package, cropped rasters) for
  // the browser's print dialog — the same bytes the printer queue sends.
  const printLabel = () => {
    window.open(labelService.pdfUrl(orderNo), '_blank', 'noopener')
  }

  const openInvoice = async () => {
    setBusy('invoice')
    try {
      const blob = await orderService.getCommercialInvoicePdf(orderNo)
      window.open(URL.createObjectURL(blob), '_blank', 'noopener')
    } catch (err) {
      void notify.apiError(err, 'This order has no commercial invoice — it is international-only.')
    } finally {
      setBusy(null)
    }
  }

  const btn =
    'flex w-full items-center gap-2.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-left text-[13px] font-medium text-[#1f150c] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:border-[#e3d9c4] disabled:hover:bg-white focus-visible:outline focus-visible:outline-2 focus-visible:outline-[#1f150c]'
  const icon = 'h-4 w-4 shrink-0 text-[#a1906d]'
  /** Right-aligned hint; aria-hidden so the button's name stays its label. */
  const hint = (text: ReactNode) => (
    <span aria-hidden className="ml-auto font-mono text-[10px] text-[#b6a684]">
      {text}
    </span>
  )
  const h2 = 'mb-2 font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]'
  const pkgHint = total > 1 ? `pkg ${activeSeq}` : null

  return (
    <>
      <section className="rounded-2xl border border-[#e3d9c4] bg-white px-4 py-3" aria-label="Label actions">
        <h2 className={h2}>Actions</h2>
        <div className="grid gap-2">
          <button
            type="button"
            onClick={printLabel}
            className="flex w-full items-center gap-2.5 rounded-xl bg-[#1f150c] px-3 py-2.5 text-left text-[13px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#1f150c]"
          >
            <FiPrinter className="h-4 w-4 shrink-0 text-[#cdbf9f]" />
            Print label
            <span aria-hidden className="ml-auto font-mono text-[10px] text-[#b6a684]">
              {total > 1 ? `${total} pages` : '4 × 6'}
            </span>
          </button>
          <button type="button" onClick={() => void copyZpl()} disabled={busy !== null} className={btn}>
            <FiCopy className={icon} />
            {busy === 'zpl' ? 'Fetching…' : 'Copy ZPL'}
            {hint(pkgHint ?? 'clipboard')}
          </button>
          <button type="button" onClick={() => void downloadZpl()} disabled={busy !== null} className={btn}>
            <FiDownload className={icon} />
            Download .zpl
            {hint(pkgHint ?? '.zpl')}
          </button>
          <button type="button" onClick={() => void downloadPdf()} disabled={busy !== null} className={btn}>
            <FiDownload className={icon} />
            {busy === 'pdf' ? 'Fetching…' : 'Download PDF'}
            {hint(total > 1 ? `${total} pages` : '.pdf')}
          </button>
        </div>

        {fallbackZpl !== null && (
          <div className="mt-3">
            <label htmlFor="label-zpl-fallback" className="mb-1 block text-[11.5px] text-[#6b5c42]">
              ZPL (selected — press Ctrl/Cmd+C)
            </label>
            <textarea
              id="label-zpl-fallback"
              ref={fallbackRef}
              readOnly
              value={fallbackZpl}
              rows={6}
              className="w-full rounded-lg border border-[#e3d9c4] bg-[#faf7f0] p-2 font-mono text-[11px] text-[#1f150c]"
            />
          </div>
        )}
      </section>

      <section className="rounded-2xl border border-[#e3d9c4] bg-white px-4 py-3" aria-label="Tracking and documents">
        <h2 className={h2}>Tracking &amp; documents</h2>
        <div className="grid gap-2">
          <button type="button" onClick={() => setTrackingOpen(true)} className={btn}>
            <FiActivity className={icon} />
            Track live
            {hint('timeline')}
          </button>
          {pkg?.trackingUrl ? (
            <a href={pkg.trackingUrl} target="_blank" rel="noopener noreferrer" className={btn}>
              <FiExternalLink className={icon} />
              Track on {artifact.carrier ?? 'carrier'} site
              {hint('opens new tab')}
            </a>
          ) : (
            <button type="button" disabled className={btn} title="The carrier returned no tracking link for this package.">
              <FiExternalLink className={icon} />
              Track on carrier site
            </button>
          )}
          <button type="button" onClick={() => void openInvoice()} disabled={busy !== null} className={btn}>
            <FiFileText className={icon} />
            {busy === 'invoice' ? 'Fetching…' : 'Commercial invoice'}
            {hint('international')}
          </button>
        </div>
      </section>

      {trackingOpen && <TrackingTimelineModal orderNo={orderNo} onClose={() => setTrackingOpen(false)} />}
    </>
  )
}
