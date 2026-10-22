import { useState } from 'react'
import { FiCheck, FiCopy } from 'react-icons/fi'
import type { LabelArtifact, PackageArtifact } from '../../api/labelService'

/**
 * Facts about the active package, all from stored columns. The format
 * badge is data (the `source` column), never an inference from the ZPL.
 */
export function LabelMeta({ artifact, pkg }: { artifact: LabelArtifact; pkg: PackageArtifact }) {
  const native = pkg.source === 'CARRIER_NATIVE'
  const total = artifact.packages.length
  const [copied, setCopied] = useState(false)

  const copyTracking = async () => {
    if (!pkg.tracking) return
    try {
      await navigator.clipboard.writeText(pkg.tracking)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard blocked: the number is plain text on screen, selectable by hand.
    }
  }

  const row = 'flex items-center justify-between gap-3 border-b border-dashed border-[#e3d9c4] py-2 last:border-b-0'
  const key = 'font-mono text-[9.5px] font-bold uppercase tracking-[0.14em] text-[#a1906d]'

  return (
    <section className="rounded-2xl border border-[#e3d9c4] bg-white px-4 py-3" aria-label="Label details">
      <h2 className="mb-1 font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">Label</h2>
      <div className={row}>
        <span className={key}>Format</span>
        {native ? (
          <span className="rounded-full bg-emerald-50 px-2 py-0.5 font-mono text-[11px] font-semibold text-emerald-800 ring-1 ring-emerald-200">
            Native ZPL
          </span>
        ) : (
          <span
            className="rounded-full bg-amber-50 px-2 py-0.5 font-mono text-[11px] font-semibold text-amber-900 ring-1 ring-amber-200"
            title={`The carrier returned a ${pkg.format} image rather than ZPL. Copy ZPL gives a ^GFA bitmap wrap of it: it prints the same label, but is not native thermal markup.`}
          >
            Image label ({pkg.format})
          </span>
        )}
      </div>
      <div className={row}>
        <span className={key}>Carrier</span>
        <span className="text-[13px] font-semibold text-[#1f150c]">{artifact.carrier ?? '—'}</span>
      </div>
      {total > 1 && (
        <div className={row}>
          <span className={key}>Package</span>
          <span className="font-mono text-[12px] tabular-nums text-[#1f150c]">
            {pkg.seq} of {total}
          </span>
        </div>
      )}
      <div className={row}>
        <span className={key}>Tracking</span>
        <span className="flex min-w-0 items-center gap-1.5">
          <span className="truncate font-mono text-[12px] tabular-nums text-[#1f150c]" title={pkg.tracking ?? undefined}>
            {pkg.tracking ?? '—'}
          </span>
          {pkg.tracking && (
            <button
              type="button"
              onClick={() => void copyTracking()}
              aria-label="Copy tracking number"
              title="Copy tracking number"
              className="rounded-md p-1 text-[#a1906d] transition hover:bg-[#f0e9d8] hover:text-[#1f150c]"
            >
              {copied ? <FiCheck className="h-3.5 w-3.5 text-emerald-600" /> : <FiCopy className="h-3.5 w-3.5" />}
            </button>
          )}
        </span>
      </div>
      <div className={row}>
        <span className={key}>Size</span>
        <span className="font-mono text-[12px] tabular-nums text-[#1f150c]">4 × 6 in</span>
      </div>
    </section>
  )
}
