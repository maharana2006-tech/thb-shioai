import { useEffect, useMemo, useState } from 'react'
import { FiGlobe, FiHome, FiX } from 'react-icons/fi'
import { COUNTRIES, countryName } from '../../utils/countries'
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
  /**
   * ISO alpha-2 country code that defines "domestic" for this mapping — from
   * the client's ship-from address. When null (e.g. "Any client" mappings),
   * the modal shows its own inline Ship-from picker so the user can pick a
   * fallback for this session.
   */
  domesticCountry?: string | null
}

type Tab = 'domestic' | 'international'

const FALLBACK_ORIGINS = ['US', 'GB', 'DE', 'FR', 'NL', 'IT', 'ES', 'IN', 'CN', 'JP', 'AU', 'CA', 'MX', 'BR', 'SG']

const isDomesticOnly = (codes: string[], domestic: string): boolean =>
  codes.length === 1 && codes[0].toUpperCase() === domestic.toUpperCase()

/**
 * Destination-zone editor — shared modal for picking any mix of regions and
 * countries. Splits into Domestic (pin the single ship-from country) and
 * International (any mix except that country) tabs. When the caller can't
 * provide a domestic country (Any-client mappings), the modal shows an
 * inline Ship-from picker so the user picks one for the session. Empty
 * selection means "anywhere / no restriction".
 */
export default function ZoneEditorModal({
  open,
  codes,
  onCodesChange,
  onSave,
  onClose,
  subject,
  saveLabel = 'Save zone',
  domesticCountry,
}: ZoneEditorModalProps) {
  const providedDomestic = domesticCountry ? domesticCountry.toUpperCase() : null

  // When there's no client-provided domestic, the user picks one inline. We
  // default to the first fallback origin (US) so tabs are immediately useful.
  const [pickedOrigin, setPickedOrigin] = useState<string>('US')
  const effectiveDomestic = providedDomestic ?? pickedOrigin

  const originOptions = useMemo(() => {
    const merged = new Set<string>([...FALLBACK_ORIGINS, pickedOrigin])
    return [...merged].sort((a, b) => countryName(a).localeCompare(countryName(b)))
  }, [pickedOrigin])

  const [tab, setTab] = useState<Tab>(() =>
    isDomesticOnly(codes, effectiveDomestic) ? 'domestic' : 'international',
  )

  // Re-sync tab whenever the modal opens so a closed-then-reopened row lands
  // on the tab that matches its saved data.
  useEffect(() => {
    if (!open) return
    // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot tab init when modal opens; deriving from props at render time would flicker
    setTab(isDomesticOnly(codes, effectiveDomestic) ? 'domestic' : 'international')
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  if (!open) return null

  const switchTab = (next: Tab) => {
    if (next === tab) return
    // Reset codes on tab switch so the two lists never carry stray selections.
    if (next === 'domestic') {
      onCodesChange(effectiveDomestic ? [effectiveDomestic] : [])
    } else {
      onCodesChange(codes.filter((c) => c.toUpperCase() !== effectiveDomestic))
    }
    setTab(next)
  }

  const changePickedOrigin = (next: string) => {
    setPickedOrigin(next.toUpperCase())
    // If we're already in Domestic mode, re-pin the newly-picked country.
    if (tab === 'domestic') {
      onCodesChange([next.toUpperCase()])
    } else {
      // Ensure the newly-picked domestic country is stripped from International.
      onCodesChange(codes.filter((c) => c.toUpperCase() !== next.toUpperCase()))
    }
  }

  const domesticSelected = codes.some((c) => c.toUpperCase() === effectiveDomestic)
  const disabledForIntl = new Set<string>([effectiveDomestic])
  const domesticName = countryName(effectiveDomestic)

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
          where the parcel is going.{' '}
          <span className="font-semibold text-slate-700">Empty = anywhere.</span>
        </p>

        {/* Inline Ship-from picker — only when the caller can't supply the
            domestic country (typically Any-client mappings). */}
        {!providedDomestic ? (
          <label
            title="No specific client is set on this mapping — pick a Ship-from country to define what 'Domestic' means."
            className="mt-3 flex items-center gap-1.5 rounded-xl border border-slate-200 bg-slate-50/60 pl-3 pr-1.5 py-1.5"
          >
            <FiGlobe className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Ship from <span className="text-slate-300">· fallback for Any-client</span>
            </span>
            <select
              value={pickedOrigin}
              onChange={(e) => changePickedOrigin(e.target.value)}
              className="cursor-pointer bg-transparent py-1 pr-1 text-[12.5px] font-semibold text-[#1f150c] outline-none"
            >
              {originOptions.map((code) => (
                <option key={code} value={code}>
                  {countryName(code)} ({code})
                </option>
              ))}
            </select>
          </label>
        ) : null}

        <div className="mt-3 flex gap-1 rounded-xl border border-slate-200 bg-slate-50 p-1" role="tablist">
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'domestic'}
            onClick={() => switchTab('domestic')}
            className={`inline-flex flex-1 items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-[12.5px] font-semibold transition ${
              tab === 'domestic'
                ? 'bg-white text-[#1f150c] shadow-sm'
                : 'text-slate-500 hover:text-slate-800'
            }`}
          >
            <FiHome className="h-3.5 w-3.5" />
            Domestic
            <span className="text-[10.5px] font-medium text-slate-400">({effectiveDomestic})</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={tab === 'international'}
            onClick={() => switchTab('international')}
            className={`inline-flex flex-1 items-center justify-center gap-1.5 rounded-lg px-3 py-2 text-[12.5px] font-semibold transition ${
              tab === 'international'
                ? 'bg-white text-[#1f150c] shadow-sm'
                : 'text-slate-500 hover:text-slate-800'
            }`}
          >
            <FiGlobe className="h-3.5 w-3.5" />
            International
          </button>
        </div>

        <div className="mt-3">
          {tab === 'domestic' ? (
            <label
              className={`flex cursor-pointer items-center gap-3 rounded-2xl border p-4 transition ${
                domesticSelected
                  ? 'border-[#412d15] bg-[#412d15]/[0.04]'
                  : 'border-slate-200 bg-white hover:border-slate-300'
              }`}
            >
              <input
                type="checkbox"
                checked={domesticSelected}
                onChange={(e) => onCodesChange(e.target.checked ? [effectiveDomestic] : [])}
                className="h-4 w-4 accent-[#412d15]"
              />
              <div className="min-w-0 flex-1">
                <p className="text-[13.5px] font-semibold text-slate-950">
                  {domesticName} <span className="font-mono text-[11px] text-slate-400">({effectiveDomestic})</span>
                </p>
                <p className="text-[11.5px] text-slate-500">
                  Ship to the origin country only. Domestic mode pins this single country.
                </p>
              </div>
            </label>
          ) : (
            <RegionCountryPicker
              value={codes}
              onChange={onCodesChange}
              multiRegion
              disabledCodes={disabledForIntl}
            />
          )}
        </div>

        {tab === 'international' ? (
          <p className="mt-2 text-[10.5px] text-slate-400">
            {domesticName} is the domestic country for this mapping — hidden from international mode.
          </p>
        ) : null}

        <div className="mt-4 flex items-center justify-between gap-2 border-t border-slate-100 pt-4">
          <p className="text-[11.5px] font-semibold text-slate-500">
            {codes.length
              ? tab === 'domestic'
                ? `Domestic — ${countryName(codes[0])}`
                : `${codes.length} countr${codes.length === 1 ? 'y' : 'ies'} in this zone`
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

// Keep the country table bundled even if it gets tree-shaken from the picker.
void COUNTRIES
