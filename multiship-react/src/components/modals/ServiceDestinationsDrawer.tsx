import { useEffect, useMemo, useState } from 'react'
import { FiCheck, FiGlobe, FiSearch, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { clientAllowedServiceDestinationsService } from '../../api/clientAllowedServiceDestinationsService'
import { COUNTRIES, REGIONS, type Region } from '../../utils/countries'

/**
 * Modal-drawer for the destination gate on a single client-allowed-service
 * row. Uses replace-PUT: pick the destination countries the client may use
 * this service for; empty set = unrestricted.
 */
export default function ServiceDestinationsDrawer({
  clientCode,
  serviceId,
  serviceLabel,
  onClose,
  onSaved,
}: {
  clientCode: string
  serviceId: number
  /** e.g. "UPS · 03 — Ground". Shown in the header. */
  serviceLabel: string
  onClose: () => void
  onSaved: () => void
}) {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [search, setSearch] = useState('')

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const r = await clientAllowedServiceDestinationsService.get(clientCode, serviceId)
        if (!alive) return
        setSelected(new Set((r.data?.countries ?? []).map((c) => c.toUpperCase())))
      } catch (error) {
        if (!alive) return
        notify.error(error instanceof Error ? error.message : 'Failed to load destinations.')
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => { alive = false }
  }, [clientCode, serviceId])

  const filtered = useMemo(() => {
    const q = search.trim().toUpperCase()
    if (!q) return COUNTRIES
    return COUNTRIES.filter((c) => c.code.toUpperCase().includes(q) || c.name.toUpperCase().includes(q))
  }, [search])

  const byRegion = useMemo(() => {
    const m = new Map<Region, typeof COUNTRIES>()
    for (const c of filtered) m.set(c.region, [...(m.get(c.region) ?? []), c])
    return m
  }, [filtered])

  const toggle = (code: string) =>
    setSelected((cur) => {
      const next = new Set(cur)
      next.has(code) ? next.delete(code) : next.add(code)
      return next
    })

  const save = async () => {
    setSaving(true)
    try {
      if (selected.size === 0) {
        await clientAllowedServiceDestinationsService.clear(clientCode, serviceId)
        notify.success('Destination gate cleared — service is unrestricted.')
      } else {
        await clientAllowedServiceDestinationsService.replace(clientCode, serviceId, {
          countries: Array.from(selected).sort(),
        })
        notify.success(`Destination gate saved (${selected.size} countries).`)
      }
      onSaved()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to save destinations.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-[55] bg-slate-950/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby="svc-dest-title"
        className="fixed inset-y-0 right-0 z-[60] flex w-full max-w-[520px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <header className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Destination gate
            </p>
            <h3 id="svc-dest-title" className="mt-1 text-[15px] font-semibold text-slate-950">
              {serviceLabel}
            </h3>
            <p className="mt-1 text-[11.5px] leading-5 text-slate-500">
              Countries {clientCode} may ship on this service. Empty = ship anywhere.
            </p>
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              Loading…
            </p>
          ) : (
            <>
              <div className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-1.5">
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

              <div className="mt-3 max-h-[55vh] overflow-y-auto rounded-xl border border-slate-200 bg-white p-2">
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
                {selected.size} selected {selected.size === 0 ? '(unrestricted)' : null}
              </p>
            </>
          )}
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-slate-100 bg-slate-50/60 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-100"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving}
            className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save destinations'}
          </button>
        </footer>
      </aside>
    </>
  )
}
