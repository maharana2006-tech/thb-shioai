import { useEffect, useState } from 'react'
import { FiX } from 'react-icons/fi'
import { orderService } from '../../api/orderService'
import { printPdfBlob } from '../../utils/printPdf'
import { notify } from '../../utils/notify'

/**
 * One order's label PDF in a modal. The PDF is fetched and shown from an
 * object URL: the API answers with X-Frame-Options: DENY and
 * Content-Disposition: attachment, so pointing an iframe straight at it
 * shows nothing.
 */
export default function LabelPreviewModal({ orderNo, onClose }: { orderNo: number; onClose: () => void }) {
  const [blob, setBlob] = useState<Blob | null>(null)
  const [src, setSrc] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let url: string | null = null
    let gone = false
    orderService.getLabelPdf(orderNo)
      .then((b) => {
        if (gone) return
        url = URL.createObjectURL(b)
        setBlob(b)
        setSrc(url)
      })
      .catch((e) => {
        if (gone) return
        setError(e instanceof Error ? e.message : 'Could not load the label.')
        notify.apiError(e, 'Could not load the label.')
      })
    return () => {
      gone = true
      if (url) URL.revokeObjectURL(url)
    }
  }, [orderNo])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Label of order ${orderNo}`}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={onClose}
    >
      <div className="relative w-full max-w-4xl rounded-lg bg-white shadow-lg" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-[#e3d9c4] px-6 py-4">
          <h2 className="text-lg font-semibold text-[#1f150c]">Order #{orderNo} - Label Preview</h2>
          <button type="button" onClick={onClose} className="rounded-full p-1 text-[#6b5c42] hover:bg-[#f2ecdf]" title="Close" aria-label="Close">
            <FiX className="h-5 w-5" />
          </button>
        </div>

        <div className="h-[70vh] overflow-auto bg-[#f9f6f0]">
          {src ? (
            <iframe src={src} className="h-full w-full border-0" title={`Label for order ${orderNo}`} />
          ) : (
            <p className="py-16 text-center text-sm text-[#6b5c42]">{error ?? 'Loading the label…'}</p>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-[#e3d9c4] px-6 py-3">
          <button
            type="button"
            onClick={() => window.open(`/api/v1/orders/${orderNo}/label/pdf`, '_blank')}
            className="inline-flex items-center gap-1 rounded-lg px-3 py-2 text-[12px] font-semibold text-[#1f150c] hover:bg-[#f2ecdf]"
          >
            📥 Download
          </button>
          <button
            type="button"
            disabled={!blob}
            onClick={() => { if (blob) printPdfBlob(blob) }}
            className="inline-flex items-center gap-1 rounded-lg px-3 py-2 text-[12px] font-semibold text-[#1f150c] hover:bg-[#f2ecdf] disabled:opacity-40"
          >
            🖨️ Print
          </button>
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-3 py-2 text-[12px] font-semibold text-white hover:bg-[#412d15]"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
