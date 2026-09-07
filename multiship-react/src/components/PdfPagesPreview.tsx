import { useEffect, useRef, useState } from 'react'
import * as pdfjsLib from 'pdfjs-dist'
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl

/** Render scale relative to the PDF's 72dpi points — 2× keeps barcodes crisp
 *  on retina screens without ballooning canvas memory on 4-page carrier docs. */
const RENDER_SCALE = 2

interface PdfPagesPreviewProps {
  /** The PDF to render — every page, stacked, in document order. */
  blob: Blob
  /** Caption for page 1 (e.g. "Main label — this is what prints"). */
  firstPageCaption?: string
  /** Fires once when the document is parsed (page count known). */
  onLoaded?: (pageCount: number) => void
  /** Fires when the PDF can't be parsed/rendered — caller falls back. */
  onError?: (err: unknown) => void
  /** CSS width of each rendered page. */
  pageWidthPx?: number
}

/**
 * Stacks every page of a PDF as canvases so the printable document — the
 * carrier's own label pages, or the ZPL rendered by zebrash — is what the
 * operator sees on screen, not an HTML approximation of it.
 *
 * Two effects on purpose: parsing sets the document into state, which mounts
 * one canvas per page; the render effect then runs after that commit with
 * the refs populated. No animation-frame polling — rAF never fires in a
 * background tab, which left every page blank until the tab was fronted.
 */
export default function PdfPagesPreview({
  blob,
  firstPageCaption,
  onLoaded,
  onError,
  pageWidthPx = 430,
}: PdfPagesPreviewProps) {
  const [doc, setDoc] = useState<pdfjsLib.PDFDocumentProxy | null>(null)
  const canvasRefs = useRef<(HTMLCanvasElement | null)[]>([])
  // Latest-callback refs so inline parent callbacks don't restart the render
  // loop on every parent render (written in an effect, not during render).
  const onLoadedRef = useRef(onLoaded)
  const onErrorRef = useRef(onError)
  useEffect(() => {
    onLoadedRef.current = onLoaded
    onErrorRef.current = onError
  })

  // Parse: blob → document.
  useEffect(() => {
    let cancelled = false
    let loaded: pdfjsLib.PDFDocumentProxy | null = null
    ;(async () => {
      try {
        const data = new Uint8Array(await blob.arrayBuffer())
        loaded = await pdfjsLib.getDocument({ data }).promise
        if (cancelled) {
          void loaded.destroy()
          return
        }
        setDoc(loaded)
        onLoadedRef.current?.(loaded.numPages)
      } catch (err) {
        if (!cancelled) onErrorRef.current?.(err)
      }
    })()
    return () => {
      cancelled = true
      void loaded?.destroy()
      setDoc(null)
    }
  }, [blob])

  // Render: document → canvases (mounted by the commit that set `doc`).
  useEffect(() => {
    if (!doc) return
    let cancelled = false
    const pdf = doc
    ;(async () => {
      try {
        // Pages render concurrently — sequential rendering left the
        // carrier's copy pages blank for seconds after page 1 appeared.
        await Promise.all(
          Array.from({ length: pdf.numPages }, async (_, idx) => {
            const page = await pdf.getPage(idx + 1)
            const canvas = canvasRefs.current[idx]
            if (!canvas || cancelled) return
            const viewport = page.getViewport({ scale: RENDER_SCALE })
            canvas.width = viewport.width
            canvas.height = viewport.height
            const ctx = canvas.getContext('2d')
            if (!ctx) return
            await page.render({ canvasContext: ctx, viewport }).promise
            if (!cancelled) canvas.dataset.rendered = '1'
          }),
        )
      } catch (err) {
        if (!cancelled) onErrorRef.current?.(err)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [doc])

  if (!doc) return null
  const pageCount = doc.numPages

  return (
    <div className="flex flex-col items-center gap-5" data-testid="printable-pages-preview">
      {Array.from({ length: pageCount }, (_, i) => (
        <figure key={i} className="m-0 flex flex-col items-center gap-1.5">
          <figcaption className="text-[10.5px] font-semibold uppercase tracking-[0.12em] text-slate-500">
            Page {i + 1} of {pageCount}
            {i === 0 && firstPageCaption ? <span className="ml-2 rounded-full bg-slate-900 px-2 py-0.5 text-[9.5px] normal-case tracking-normal text-white">{firstPageCaption}</span> : null}
          </figcaption>
          <canvas
            ref={(el) => { canvasRefs.current[i] = el }}
            className="block border border-slate-300 bg-white shadow-xl"
            style={{ width: pageWidthPx, height: 'auto' }}
            aria-label={`Printable label page ${i + 1} of ${pageCount}`}
          />
        </figure>
      ))}
    </div>
  )
}
