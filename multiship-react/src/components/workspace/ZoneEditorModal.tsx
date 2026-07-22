import { FiGlobe, FiX } from 'react-icons/fi'
import RegionCountryPicker from './RegionCountryPicker'

export interface ZoneEditorModalProps {
  open: boolean
  codes: string[]
  onCodesChange: (codes: string[]) => void
  onSave: () => void | Promise<void>
  onClose: () => void
  /** Optional subject line (e.g. rule identifier) rendered inside the description. */
  subject?: React.ReactNode
  /** Save button label. Defaults to "Save zone". */
  saveLabel?: string
}

/**
 * Destination-zone editor — a shared modal that lets the caller pick any mix
 * of regions and countries. Empty selection means "anywhere / no restriction".
 * Fully controlled: the caller owns the codes state so the picker can round-trip
 * cleanly with backend representations (COUNTRIES/ANY/etc.).
 */
export default function ZoneEditorModal({
  open,
  codes,
  onCodesChange,
  onSave,
  onClose,
  subject,
  saveLabel = 'Save zone',
}: ZoneEditorModalProps) {
  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <div
        className="w-full max-w-2xl rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-1 flex items-start justify-between">
          <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
            <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-sky-50 text-sky-700">
              <FiGlobe className="h-4 w-4" />
            </span>
            Ships to — destination zone
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
            aria-label="Close"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>
        <p className="text-[12px] text-slate-500">
          {subject ? <>{subject} — </> : null}
          where the parcel is going — pick any mix of destination regions and countries.{' '}
          <span className="font-semibold text-slate-700">Empty = anywhere.</span>
        </p>

        <div className="mt-3">
          <RegionCountryPicker value={codes} onChange={onCodesChange} multiRegion />
        </div>

        <div className="mt-4 flex items-center justify-between gap-2 border-t border-slate-100 pt-4">
          <p className="text-[11.5px] font-semibold text-slate-500">
            {codes.length
              ? `${codes.length} countr${codes.length === 1 ? 'y' : 'ies'} in this zone`
              : 'Anywhere (no restriction)'}
          </p>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void onSave()}
              className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15]"
            >
              {saveLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
