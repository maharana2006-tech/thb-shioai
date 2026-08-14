import { useMemo, useState } from 'react'
import { FiCheck, FiX } from 'react-icons/fi'
import {
  countriesInRegion,
  REGIONS,
  regionOf,
  territoryLabel,
  territoryOf,
  type Region,
} from '../../utils/countries'

interface RegionCountryPickerProps {
  /** Selected ISO alpha-2 codes (always within ONE region). */
  value: string[]
  onChange: (codes: string[]) => void
  /** Codes already claimed by another profile — shown disabled with a hint. */
  disabledCodes?: Set<string>
  /**
   * BUSINESS (DDP) profiles: one importer registration covers exactly one
   * CUSTOMS TERRITORY (EU/EAEU/GCC/SACU or a single country), so the first
   * picked country pins the territory and everything outside it is disabled.
   * RECEIVER (DAP) profiles pass false — the whole region stays open.
   */
  territoryConstrained?: boolean
  /**
   * Zone mode (ship-method rules): the selection may span ANY mix of regions
   * and countries — no region lock, no clearing on switch. Regions become
   * bulk-select helpers, exactly the "shipping zone" pattern.
   */
  multiRegion?: boolean
}

type CheckState = 'checked' | 'indeterminate' | 'unchecked'

/** Purely visual tri-state box. */
function CheckVisual({ state, disabled }: { state: CheckState; disabled?: boolean }) {
  return (
    <span
      className={`grid h-4 w-4 shrink-0 place-items-center rounded-[5px] border transition ${
        state === 'unchecked' ? 'border-slate-300 bg-white' : 'border-[#412d15] bg-[#1f150c] text-[#f4eede]'
      } ${disabled ? 'opacity-40' : ''}`}
    >
      {state === 'checked' ? (
        <svg viewBox="0 0 12 12" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth="2.2">
          <path d="M2.5 6.5l2.5 2.5 4.5-5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      ) : state === 'indeterminate' ? (
        <span className="h-[2px] w-2 rounded-full bg-[#f4eede]" />
      ) : null}
    </span>
  )
}

/**
 * Destination picker with a PREDICTABLE flow:
 *   1. Region chips are pure NAVIGATION (browse a region's countries).
 *   2. Once anything is selected, other regions LOCK (no silent wiping) —
 *      "Clear" is the one explicit way to start over.
 *   3. "Select all" always targets exactly what is selectable: the whole
 *      region normally, or only the pinned customs territory for BUSINESS
 *      profiles (never a mixed invalid state).
 */
export default function RegionCountryPicker({
  value,
  onChange,
  disabledCodes,
  territoryConstrained,
  multiRegion,
}: RegionCountryPickerProps) {
  const selected = useMemo(() => new Set(value.map((c) => c.toUpperCase())), [value])
  const claimed = disabledCodes ?? new Set<string>()

  // Single-region mode: the region follows the current selection. Zone mode
  // (multiRegion): browsing is always free and the selection can span regions.
  const selectionRegion = value.length ? regionOf(value[0]) : null
  const [browseRegion, setBrowseRegion] = useState<Region | null>(null)
  const activeRegion = multiRegion ? (browseRegion ?? selectionRegion) : (selectionRegion ?? browseRegion)

  /** Regions that contain at least one selected country (zone-mode ✓ marks). */
  const regionsWithSelection = useMemo(() => {
    const s = new Set<Region>()
    value.forEach((c) => s.add(regionOf(c)))
    return s
  }, [value])

  // BUSINESS: the first selected country pins the customs territory.
  const activeTerritory = territoryConstrained && value.length ? territoryOf(value[0]) : null

  /** Why a given country can't be picked right now (null = pickable). */
  const blockReason = (code: string): string | null => {
    if (claimed.has(code)) return 'covered by another profile'
    if (activeTerritory && territoryOf(code) !== activeTerritory)
      return `outside ${territoryLabel(activeTerritory)} — needs its own profile`
    return null
  }

  const commit = (codes: Set<string>) => onChange([...codes])

  const toggleCountry = (code: string) => {
    if (blockReason(code)) return
    const next = new Set(selected)
    if (next.has(code)) next.delete(code)
    else next.add(code)
    commit(next)
  }

  // "Select all" base: every pickable country in the active region — the whole
  // region normally, only the pinned territory when constrained. Never mixes.
  const selectAllBase = activeRegion
    ? countriesInRegion(activeRegion)
        .map((c) => c.code)
        .filter((c) => !blockReason(c))
    : []
  const onCount = selectAllBase.filter((c) => selected.has(c)).length
  const allState: CheckState =
    !selectAllBase.length || onCount === 0 ? 'unchecked' : onCount === selectAllBase.length ? 'checked' : 'indeterminate'

  const toggleAll = () => {
    const next = new Set(selected)
    if (allState === 'checked') selectAllBase.forEach((c) => next.delete(c))
    else selectAllBase.forEach((c) => next.add(c))
    commit(next)
  }

  const selectAllLabel = activeTerritory
    ? `Select all of ${territoryLabel(activeTerritory)}`
    : `Select all ${activeRegion ?? ''}`

  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
      {/* region navigation — locked while a selection exists */}
      <div className="border-b border-slate-100 bg-slate-50/60 px-3 py-2.5">
        <div className="flex flex-wrap items-center gap-1.5">
          {REGIONS.map((r) => {
            const active = activeRegion === r
            const locked = !multiRegion && !!selectionRegion && selectionRegion !== r
            const hasSelection = multiRegion ? regionsWithSelection.has(r) : selectionRegion === r
            return (
              <button
                key={r}
                type="button"
                onClick={() => !locked && setBrowseRegion(r)}
                disabled={locked}
                title={locked ? 'Clear the selection to switch region' : r}
                className={`inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-[11.5px] font-semibold transition ${
                  active
                    ? 'bg-[#1f150c] text-[#f4eede] shadow-sm'
                    : locked
                      ? 'cursor-not-allowed border border-slate-200 bg-white text-slate-300'
                      : 'border border-slate-200 bg-white text-slate-600 hover:border-[#412d15]/40 hover:text-[#412d15]'
                }`}
              >
                {hasSelection ? <FiCheck className="h-3 w-3" /> : null}
                {r}
              </button>
            )
          })}
          {value.length ? (
            <button
              type="button"
              onClick={() => commit(new Set())}
              className="ml-auto inline-flex items-center gap-1 rounded-lg border border-rose-200 bg-white px-2 py-1.5 text-[11px] font-semibold text-rose-600 transition hover:bg-rose-50"
            >
              <FiX className="h-3 w-3" /> Clear ({value.length})
            </button>
          ) : null}
        </div>
        <p className="mt-1.5 text-[10.5px] text-slate-400">
          {multiRegion
            ? 'Build the destination zone: mix regions and countries freely — "Select all" grabs a whole region.'
            : !value.length
              ? territoryConstrained
                ? 'Browse a region, then pick a country — the first pick sets the customs territory.'
                : 'Browse a region and tick its countries. A profile covers one region.'
              : activeTerritory
                ? <>Locked to <span className="font-semibold text-[#412d15]">{territoryLabel(activeTerritory)}</span> — one importer registration covers one customs territory. Use Clear to start over.</>
                : <>Selecting in <span className="font-semibold text-[#412d15]">{selectionRegion}</span> — use Clear to switch region.</>}
        </p>
      </div>

      {/* countries of the active region */}
      {activeRegion ? (
        <div className="p-2.5">
          {/* In constrained mode "Select all" only appears once the first pick
              has pinned the territory — otherwise it would bulk-select a
              region and immediately invalidate most of it. */}
          {selectAllBase.length > 1 && (!territoryConstrained || activeTerritory) ? (
            <button
              type="button"
              onClick={toggleAll}
              className="mb-1 flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left transition hover:bg-slate-50"
            >
              <CheckVisual state={allState} />
              <span className="text-[12.5px] font-semibold text-slate-800">{selectAllLabel}</span>
              <span className="text-[11px] font-medium tabular-nums text-slate-400">
                {onCount}/{selectAllBase.length}
              </span>
            </button>
          ) : null}
          <div className="ml-3 grid max-h-[190px] grid-cols-2 gap-x-2 overflow-y-auto border-l border-slate-100 pl-3 sm:grid-cols-3">
            {countriesInRegion(activeRegion).map((c) => {
              const reason = blockReason(c.code)
              const on = selected.has(c.code)
              return (
                <button
                  key={c.code}
                  type="button"
                  onClick={() => toggleCountry(c.code)}
                  disabled={!!reason}
                  title={reason ? `${c.name} — ${reason}` : c.name}
                  className={`flex items-center gap-2 rounded-md px-1.5 py-1 text-left transition ${
                    reason ? 'cursor-not-allowed opacity-40' : 'hover:bg-slate-50'
                  }`}
                >
                  <CheckVisual state={on ? 'checked' : 'unchecked'} disabled={!!reason} />
                  <span className="min-w-0 flex-1 truncate text-[12px] text-slate-700">
                    <span className="font-semibold text-slate-900">{c.code}</span>{' '}
                    <span className="text-slate-500">{c.name}</span>
                    {c.eu ? (
                      <span className="ml-1 rounded bg-sky-100 px-1 py-px align-middle text-[8.5px] font-bold text-sky-700">EU</span>
                    ) : null}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      ) : (
        <p className="px-4 py-6 text-center text-[12px] text-slate-400">Pick a region above to see its countries.</p>
      )}
    </div>
  )
}
