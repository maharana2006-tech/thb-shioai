import { useEffect, useMemo, useState } from 'react'
import { FiCheck, FiGlobe, FiSearch, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import {
  clientDestinationsService,
  type ClientDestinationRules,
} from '../../api/clientPolicyService'
import { COUNTRIES, REGIONS, type Region } from '../../utils/countries'

type Mode = 'NONE' | 'ALLOW' | 'DENY'

/**
 * Destinations tab — mode radios + a searchable, region-grouped country
 * picker. Mode NONE deletes every rule (ships anywhere); ALLOW / DENY does
 * a replace-PUT with the chosen country set.
 */
export default function ClientDestinationsTab({ clientCode }: { clientCode: string }) {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [mode, setMode] = useState<Mode>('NONE')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [search, setSearch] = useState('')

  const load = async () => {
    setLoading(true)
    try {
      const r = await clientDestinationsService.get(clientCode)
      const data = r.data as ClientDestinationRules | undefined
      const nextMode: Mode = data?.mode === 'ALLOW' ? 'ALLOW' : data?.mode === 'DENY' ? 'DENY' : 'NONE'
      setMode(nextMode)
      setSelected(new Set((data?.countries ?? []).map((c) => c.toUpperCase())))
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to load destination rules.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientCode])

  const filtered = useMemo(() => {
    const query = search.trim().toUpperCase()
    if (!query) return COUNTRIES
    return COUNTRIES.filter(
      (c) => c.code.toUpperCase().includes(query) || c.name.toUpperCase().includes(query),
    )
  }, [search])

  // Group filtered countries by region for the grid.
  const byRegion = useMemo(() => {
    const map = new Map<Region, typeof COUNTRIES>()
    for (const c of filtered) {
      const bucket = map.get(c.region) ?? []
      bucket.push(c)
      map.set(c.region, bucket)
    }
    return map
  }, [filtered])

  const toggle = (code: string) => {
    setSelected((cur) => {
      const next = new Set(cur)
      if (next.has(code)) next.delete(code)
      else next.add(code)
      return next
    })
  }

  const save = async () => {
    if (saving) return
    setSaving(true)
    try {
      if (mode === 'NONE') {
        await clientDestinationsService.clear(clientCode)
        notify.success(`Destinations cleared — ${clientCode} can ship anywhere.`)
      } else {
        if (selected.size === 0) {
          notify.error(`Pick at least one country, or switch to "Ship anywhere".`)
          return
        }
        await clientDestinationsService.replace(clientCode, {
          mode,
          countries: Array.from(selected).sort(),
        })
        notify.success(`Destination rules saved (${mode.toLowerCase()} · ${selected.size} countries).`)
      }
      await load()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to save destination rules.')
    } finally {
      setSaving(false)
    }
  }

  const anyCountriesNeeded = mode !== 'NONE'

  return (
    <div className="flex-1 overflow-y-auto px-5 py-4" role="tabpanel" id="client-editor-panel-destinations">
      <h4 className="text-[12.5px] font-semibold text-slate-950">Ship-to destinations</h4>
      <p className="text-[11px] leading-5 text-slate-500">
        Decide where {clientCode} may ship. ALLOW = only listed countries; DENY = block listed countries; unrestricted = ship anywhere.
      </p>

      {loading ? (
        <p className="mt-4 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
          Loading…
        </p>
      ) : (
        <>
          {/* Mode */}
          <div className="mt-3 grid gap-2 sm:grid-cols-3">
            {(['NONE', 'ALLOW', 'DENY'] as const).map((m) => (
              <label
                key={m}
                className={`inline-flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-[12px] font-semibold transition ${
                  mode === m
                    ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]'
                    : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                }`}
              >
                <input
                  type="radio"
                  name="dest-mode"
                  value={m}
                  checked={mode === m}
                  onChange={() => setMode(m)}
                  className="sr-only"
                />
                {m === 'NONE' ? 'Ship anywhere' : m === 'ALLOW' ? 'ALLOW list' : 'DENY list'}
              </label>
            ))}
          </div>

          {anyCountriesNeeded ? (
            <>
              {/* Search */}
              <div className="mt-3 flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-1.5">
                <FiSearch className="h-3.5 w-3.5 text-slate-400" />
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search country name or ISO-2 code…"
                  className="w-full bg-transparent text-[12px] text-slate-950 outline-none placeholder:text-slate-400"
                />
                {search ? (
                  <button
                    type="button"
                    onClick={() => setSearch('')}
                    aria-label="Clear search"
                    className="text-slate-400 hover:text-slate-600"
                  >
                    <FiX className="h-3.5 w-3.5" />
                  </button>
                ) : null}
              </div>

              {/* Country grid — grouped by region, filtered by search. */}
              <div className="mt-3 max-h-[45vh] overflow-y-auto rounded-xl border border-slate-200 bg-white p-2">
                {REGIONS.filter((r) => (byRegion.get(r)?.length ?? 0) > 0).map((region) => (
                  <div key={region} className="mb-2 last:mb-0">
                    <p className="mb-1 flex items-center gap-1 px-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      <FiGlobe className="h-3 w-3 text-emerald-600" />
                      {region}
                    </p>
                    <div className="grid grid-cols-1 gap-1 sm:grid-cols-2">
                      {byRegion.get(region)!.map((c) => {
                        const on = selected.has(c.code)
                        return (
                          <button
                            key={c.code}
                            type="button"
                            onClick={() => toggle(c.code)}
                            className={`flex items-center justify-between rounded-lg px-2 py-1.5 text-left text-[11.5px] transition ${
                              on
                                ? 'bg-[#412d15]/10 text-[#412d15]'
                                : 'text-slate-700 hover:bg-slate-100'
                            }`}
                          >
                            <span className="truncate">
                              <span className="font-mono text-[10.5px] font-semibold uppercase text-slate-500">{c.code}</span>
                              <span className="ml-2">{c.name}</span>
                            </span>
                            {on ? <FiCheck className="h-3.5 w-3.5" /> : null}
                          </button>
                        )
                      })}
                    </div>
                  </div>
                ))}
              </div>

              <p className="mt-2 text-[11px] text-slate-500">
                {selected.size} selected
              </p>
            </>
          ) : (
            <p className="mt-3 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              No restriction — {clientCode} may ship to any destination.
            </p>
          )}

          <div className="mt-4 flex items-center justify-end">
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving}
              className="rounded-xl bg-[#1f150c] px-4 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save destinations'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
