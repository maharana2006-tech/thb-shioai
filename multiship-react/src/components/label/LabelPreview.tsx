import { useState } from 'react'
import { FiCheckCircle } from 'react-icons/fi'
import { labelService, type LabelArtifact } from '../../api/labelService'

/**
 * The one preview. An <img> of /labels/{n}/packages/{seq}/preview.png,
 * which the server renders from the exact bytes Copy ZPL returns (the
 * ETag is their SHA-256). There is no facsimile branch to fall into.
 *
 * "PKG n of m" is a tab and a CSS overlay here, not pixels burned into
 * the image, so the image under it stays byte-identical to the printable.
 */
export function LabelPreview({
  artifact,
  activeSeq,
  onSelectSeq,
}: {
  artifact: LabelArtifact
  activeSeq: number
  onSelectSeq: (seq: number) => void
}) {
  const [fit, setFit] = useState<'fit' | 'actual'>('fit')
  const [broken, setBroken] = useState(false)
  // The server rasterises on demand (a few seconds for an image label), so
  // the sheet shows a placeholder until the PNG arrives instead of a blank canvas.
  const [loadedSrc, setLoadedSrc] = useState<string | null>(null)
  const total = artifact.packages.length
  const src = labelService.previewPngUrl(artifact.orderNo, activeSeq)
  const loading = !broken && loadedSrc !== src

  const seg = (on: boolean) =>
    on
      ? 'rounded-full bg-[#1f150c] px-3 py-1 font-mono text-[11px] font-semibold text-[#f4eede]'
      : 'rounded-full px-3 py-1 font-mono text-[11px] font-semibold text-[#6b5c42] transition hover:bg-[#f0e9d8] hover:text-[#1f150c]'

  return (
    <section
      className="flex min-w-0 flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white"
      aria-label="Label preview"
    >
      <div className="flex flex-wrap items-center gap-2 border-b border-[#e3d9c4] px-4 py-2.5">
        {total > 1 ? (
          <div className="inline-flex rounded-full border border-[#e3d9c4] bg-[#faf7f0] p-0.5" role="group" aria-label="Package">
            {artifact.packages.map((p) => (
              <button
                key={p.seq}
                type="button"
                onClick={() => onSelectSeq(p.seq)}
                aria-pressed={p.seq === activeSeq}
                className={seg(p.seq === activeSeq)}
              >
                PKG {p.seq} of {total}
              </button>
            ))}
          </div>
        ) : (
          <span className="font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">Preview</span>
        )}
        <div className="ml-auto inline-flex rounded-full border border-[#e3d9c4] bg-[#faf7f0] p-0.5" role="group" aria-label="Zoom">
          <button type="button" onClick={() => setFit('fit')} aria-pressed={fit === 'fit'} className={seg(fit === 'fit')}>
            Fit
          </button>
          <button type="button" onClick={() => setFit('actual')} aria-pressed={fit === 'actual'} className={seg(fit === 'actual')}>
            Actual size
          </button>
        </div>
      </div>

      {/* The light table: a darker cream ground so the white 4×6 reads as a
          physical sheet, with the printer's own 812×1218 geometry. */}
      {/* Fit: the sheet is sized to the viewport height (header, toolbar and
          footer subtracted) so the whole label is visible without scrolling. */}
      <div
        className={`flex justify-center bg-[#f0e9d8] p-5 [background-image:radial-gradient(#dccfb0_0.8px,transparent_0.8px)] [background-size:14px_14px] ${
          fit === 'actual' ? 'overflow-auto' : ''
        }`}
      >
        <div className={`relative ${fit === 'fit' ? 'max-w-full' : 'w-[812px] shrink-0'}`}>
          {broken ? (
            <div
              role="alert"
              className="flex aspect-[4/6] items-center justify-center rounded border border-dashed border-rose-300 bg-white p-6 text-center text-sm text-rose-700"
            >
              The preview could not be rendered. The ZPL is still available in Actions; retry, or download the PDF.
            </div>
          ) : (
            <>
              {loading && (
                <div
                  aria-hidden
                  className="flex aspect-[4/6] h-[calc(100dvh-320px)] max-h-[600px] animate-pulse items-center justify-center bg-white font-mono text-[11px] text-[#a1906d] shadow-[0_18px_50px_rgba(31,21,12,0.22)]"
                >
                  Rendering preview…
                </div>
              )}
              <img
                key={src}
                src={src}
                alt={`Shipping label for order ${artifact.orderNo}, package ${activeSeq} of ${total}`}
                className={`block bg-white shadow-[0_18px_50px_rgba(31,21,12,0.22),0_1px_0_rgba(31,21,12,0.35)] ${
                  fit === 'fit' ? 'h-auto max-h-[calc(100dvh-320px)] w-auto max-w-full' : 'h-auto w-[812px] max-w-none'
                } ${loading ? 'aspect-[4/6] opacity-0' : ''}`}
                onLoad={() => setLoadedSrc(src)}
                onError={() => setBroken(true)}
              />
            </>
          )}
          {total > 1 && !broken && (
            <span className="pointer-events-none absolute right-2 top-2 rounded border border-[#1f150c] bg-white px-2 py-0.5 font-mono text-[11px] font-semibold text-[#1f150c]">
              PKG {activeSeq} OF {total}
            </span>
          )}
        </div>
      </div>

      <p className="flex flex-wrap items-center justify-between gap-2 border-t border-[#e3d9c4] bg-[#faf7f0] px-4 py-2 font-mono text-[10.5px] text-[#a1906d]">
        <span className="inline-flex items-center gap-1.5">
          <FiCheckCircle className="h-3 w-3 text-emerald-600" />
          Rendered from the exact ZPL that Copy and Print use
        </span>
        <span className="tabular-nums">4 × 6 in · 8 dpmm</span>
      </p>
    </section>
  )
}
