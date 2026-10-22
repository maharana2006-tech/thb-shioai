import { useNavigate } from 'react-router-dom'
import { FiAlertTriangle, FiArrowLeft } from 'react-icons/fi'
import type { LabelArtifact } from '../../api/labelService'

/**
 * Order identity and state on one row, so the label below gets the
 * viewport. The sandbox warning is a pill here, not a banner.
 */
export function LabelHeader({ artifact }: { artifact: LabelArtifact }) {
  const navigate = useNavigate()
  const generated = artifact.generatedAt ? new Date(artifact.generatedAt) : null
  const sandbox = (artifact.environment ?? '').toUpperCase() === 'SANDBOX'
  const pkgCount = artifact.packages.length
  const generatedLabel = generated?.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

  return (
    <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-[#e3d9c4] pb-3">
      <button
        type="button"
        onClick={() => navigate(-1)}
        aria-label="Back"
        title="Back"
        className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] focus-visible:outline focus-visible:outline-2 focus-visible:outline-[#1f150c]"
      >
        <FiArrowLeft className="h-4 w-4" />
      </button>
      <h1 className="text-[20px] font-semibold leading-none tracking-tight text-[#1f150c]">Order #{artifact.orderNo}</h1>
      <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-[12.5px] text-[#6b5c42]">
        <span className="rounded-md bg-[#f0e9d8] px-1.5 py-0.5 font-mono text-[11px] font-semibold text-[#412d15]">
          {artifact.carrier ?? 'Carrier unknown'}
        </span>
        <span>
          {pkgCount} {pkgCount === 1 ? 'package' : 'packages'}
        </span>
        {generatedLabel && (
          <>
            <span aria-hidden className="text-[#cdbf9f]">·</span>
            <span>generated {generatedLabel}</span>
          </>
        )}
      </p>
      <div className="ml-auto flex flex-wrap items-center gap-2">
        <span
          className={
            artifact.state === 'GENERATED'
              ? 'inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-0.5 font-mono text-[11px] font-semibold text-emerald-800 ring-1 ring-emerald-200'
              : 'inline-flex items-center gap-1.5 rounded-full bg-[#f0e9d8] px-2.5 py-0.5 font-mono text-[11px] font-semibold text-[#5a4526] ring-1 ring-[#e3d9c4]'
          }
        >
          <span
            aria-hidden
            className={`h-1.5 w-1.5 rounded-full ${artifact.state === 'GENERATED' ? 'bg-emerald-500' : 'bg-[#b6a684]'}`}
          />
          {artifact.state}
        </span>
        {sandbox && (
          <span
            className="inline-flex items-center gap-1.5 rounded-full bg-amber-50 px-2.5 py-0.5 font-mono text-[11px] font-semibold text-amber-900 ring-1 ring-amber-200"
            title="Generated on a sandbox carrier account: barcodes are placeholders. Real barcodes ship only when the account environment is PRODUCTION."
          >
            <FiAlertTriangle className="h-3 w-3 text-amber-600" />
            SANDBOX
          </span>
        )}
      </div>
    </header>
  )
}
