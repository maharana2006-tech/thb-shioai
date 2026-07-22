import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useOutletContext } from 'react-router-dom'
import toast from 'react-hot-toast'
import { FiDownloadCloud, FiEdit3, FiGlobe, FiPlus, FiStar, FiTrash2, FiTruck, FiX } from 'react-icons/fi'
import { dimWeightOf, oversizeOf, shippingConfigService, type PackagePreset } from '../api/shippingConfigService'
import { countryName } from '../utils/countries'
import { useAppSession } from '../hooks/useAppSession'
import { canManageCarriers, normalizeRole } from '../utils/roles'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import Select from './workspace/Select'
import TablePagination from './workspace/TablePagination'

const inputClass =
  'w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10'

const blankPreset = (): PackagePreset => ({
  name: '', kind: 'CUSTOM', dimUnit: 'IN', weightUnit: 'LB', enabled: true,
})

function Field({ label, required, children }: { label: string; required?: boolean; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
        {label}
        {required ? <span className="ml-0.5 text-rose-500">*</span> : null}
      </span>
      {children}
    </label>
  )
}

/**
 * The preset's own box, drawn to its true proportions as an isometric line
 * sketch — a tall carton reads tall, a mailer reads flat. Presets without
 * dimensions (carrier letters/paks) render as a thin slab.
 */
function IsoBox({ length, width, height }: { length?: number | null; width?: number | null; height?: number | null }) {
  const hasDims = Boolean(length && width && height)
  const L = hasDims ? (length as number) : 1
  const W = hasDims ? (width as number) : 0.72
  const H = hasDims ? (height as number) : 0.08
  const m = Math.max(L, W, H)
  const S = 30
  const lx = (L / m) * S
  const wx = (W / m) * S
  const up = (H / m) * S

  // isometric axes: +l → right/down, +w → left/down, +h → up
  const a: [number, number] = [0, 0]
  const b: [number, number] = [0.866 * lx, 0.5 * lx]
  const c: [number, number] = [-0.866 * wx, 0.5 * wx]
  const d: [number, number] = [0.866 * lx - 0.866 * wx, 0.5 * lx + 0.5 * wx]
  const lift = ([x, y]: [number, number]): [number, number] => [x, y - up]
  const [a2, b2, c2, d2] = [a, b, c, d].map(lift)

  const xs = [a, b, c, d, a2, b2, c2, d2].map((p) => p[0])
  const ys = [a, b, c, d, a2, b2, c2, d2].map((p) => p[1])
  const ox = 32 - (Math.min(...xs) + Math.max(...xs)) / 2
  const oy = 32 - (Math.min(...ys) + Math.max(...ys)) / 2

  const pt = ([x, y]: [number, number]) => `${(x + ox).toFixed(1)} ${(y + oy).toFixed(1)}`

  return (
    <svg viewBox="0 0 64 64" className="h-14 w-14 shrink-0 text-[#412d15]" aria-hidden="true">
      <g fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round" strokeLinecap="round">
        {/* visible silhouette */}
        <path d={`M ${pt(c)} L ${pt(a)} L ${pt(b)} L ${pt(b2)} L ${pt(d2)} L ${pt(c2)} Z`} />
        {/* inner edges: front vertical + top-face front corner */}
        <path d={`M ${pt(a)} L ${pt(a2)} M ${pt(c2)} L ${pt(a2)} L ${pt(b2)}`} />
        {/* tape seam across the top face */}
        <path d={`M ${pt(a2)} L ${pt(d2)}`} strokeDasharray="2.5 3" strokeWidth="1.1" opacity="0.55" />
      </g>
    </svg>
  )
}

/** Numbered spec-sheet section: stop-node index + mono-caps title. */
function SpecSection({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <section>
      <p className="mb-2 flex items-center gap-2">
        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-[#412d15]/30 font-mono text-[9px] font-bold text-[#412d15]">
          {n}
        </span>
        <span className="font-mono text-[9.5px] font-bold uppercase tracking-[0.2em] text-slate-400">{title}</span>
      </p>
      <div className="grid grid-cols-2 gap-3">{children}</div>
    </section>
  )
}

/** The universal carton stencil markings: this way up · fragile · keep dry. */
function CartonMarks() {
  return (
    <span className="flex items-center gap-2 text-slate-300" aria-hidden="true">
      <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
        <path d="M5 13V5m0 0L3 7.2M5 5l2 2.2M11 13V5m0 0L9 7.2M11 5l2 2.2M3 14.5h10" />
      </svg>
      <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
        <path d="M4 2.5h8l-1 4a3 3 0 0 1-6 0l-1-4ZM8 9.5v4m-2.5 0h5" />
      </svg>
      <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
        <path d="M2.5 8a5.5 5.5 0 0 1 11 0H2.5ZM8 8v4.5a1.5 1.5 0 0 1-3 0M8 2.5V4" />
      </svg>
    </span>
  )
}

/**
 * Packages — reusable package presets: carrier packaging (UPS Letter,
 * FedEx Pak) or the warehouse's own boxes with dimensions. The DEFAULT preset
 * feeds every label: package type + dimensions (+ tare weight on top of the
 * order's weight).
 */
export default function PackagesPage() {
  const { role } = useAppSession()
  // Sync pulls the carrier's live packaging catalog — requires the admin-only
  // carrier credentials on the backend, so gate the button to admins.
  const canSync = canManageCarriers(normalizeRole(role))
  const [presets, setPresets] = useState<PackagePreset[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<PackagePreset | null>(null)
  const [saving, setSaving] = useState(false)
  /** Ship-from origin for carrier packaging (USPS Flat Rate is US-only, etc.). */
  const [origin, setOrigin] = useState('US')
  /** Carrier filter: 'ALL' | 'CUSTOM' | a carrier code (UPS/FEDEX/USPS…). */
  const [carrierFilter, setCarrierFilter] = useState('ALL')
  const [syncing, setSyncing] = useState(false)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setPresets(await shippingConfigService.listPresets())
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to load packages.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const save = async () => {
    if (!editing) return
    if (!editing.name.trim()) {
      toast.error('Give the package a name.')
      return
    }
    if (editing.kind === 'CUSTOM' && (!editing.length || !editing.width || !editing.height)) {
      toast.error('A custom box needs length, width and height.')
      return
    }
    if (editing.kind === 'CARRIER' && !editing.carrierPackageCode?.trim()) {
      toast.error("Carrier packaging needs the carrier's package code.")
      return
    }
    setSaving(true)
    try {
      await shippingConfigService.savePreset(editing)
      toast.success(`Package '${editing.name.trim()}' saved.`)
      setEditing(null)
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to save the package.')
    } finally {
      setSaving(false)
    }
  }

  const makeDefault = async (p: PackagePreset) => {
    if (!p.id) return
    try {
      await shippingConfigService.setDefaultPreset(p.id)
      toast.success(`'${p.name}' is now the default package.`)
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to set the default.')
    }
  }

  const remove = async (p: PackagePreset) => {
    if (!p.id) return
    if (!window.confirm(`Delete package '${p.name}'?`)) return
    try {
      await shippingConfigService.deletePreset(p.id)
      toast.success(`Package '${p.name}' removed.`)
      void load()
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Failed to delete the package.')
    }
  }

  const dims = (p: PackagePreset) =>
    p.length && p.width && p.height ? `${p.length} × ${p.width} × ${p.height} ${p.dimUnit.toLowerCase()}` : '—'

  const set = <K extends keyof PackagePreset>(key: K, value: PackagePreset[K]) =>
    setEditing((cur) => (cur ? { ...cur, [key]: value } : cur))

  const num = (v: string) => (v === '' ? null : Number(v))

  // Carriers present in the catalog (+ a Custom option when any hand-made box exists).
  const carrierOptions = useMemo(() => {
    const carriers = new Set<string>()
    let hasCustom = false
    presets.forEach((p) => {
      if (p.kind === 'CARRIER' && p.carrier) carriers.add(p.carrier.toUpperCase())
      else hasCustom = true
    })
    return { carriers: [...carriers].sort(), hasCustom }
  }, [presets])

  // Custom boxes are universal; carrier packaging is per ship-from origin.
  // The carrier filter narrows to one carrier's packaging (or just custom boxes).
  const visiblePresets = useMemo(
    () =>
      presets.filter((p) => {
        const originOk =
          p.kind !== 'CARRIER' || (p.originCountry ?? 'US').toUpperCase() === origin.toUpperCase()
        if (!originOk) return false
        if (carrierFilter === 'ALL') return true
        if (carrierFilter === 'CUSTOM') return p.kind !== 'CARRIER'
        return p.kind === 'CARRIER' && (p.carrier ?? '').toUpperCase() === carrierFilter
      }),
    [presets, origin, carrierFilter],
  )
  const totalPages = Math.max(1, Math.ceil(visiblePresets.length / pageSize))
  const safePage = Math.min(page, totalPages)
  const pagedPresets = useMemo(
    () => visiblePresets.slice((safePage - 1) * pageSize, safePage * pageSize),
    [visiblePresets, safePage, pageSize],
  )
  // Snap back to page 1 whenever the filtered set changes (origin/carrier switch, sync…).
  useEffect(() => {
    setPage(1)
  }, [origin, carrierFilter, visiblePresets.length])
  const originOptions = useMemo(() => {
    const base = ['US', 'GB', 'DE', 'FR', 'NL', 'IT', 'ES', 'IN', 'CN', 'JP', 'AU', 'CA', 'MX', 'BR', 'SG']
    const merged = new Set<string>([...base, ...presets.map((p) => (p.originCountry || '').toUpperCase()).filter(Boolean), origin])
    return [...merged].sort((a, b) => countryName(a).localeCompare(countryName(b)))
  }, [presets, origin])

  const syncCarrierPackaging = async () => {
    // Sync only the carrier the user is filtering on; when they're looking at
    // ALL or CUSTOM, fall back to sweeping every supported carrier.
    const targets =
      carrierFilter && carrierFilter !== 'ALL' && carrierFilter !== 'CUSTOM'
        ? [carrierFilter]
        : ['UPS', 'FEDEX', 'USPS']

    setSyncing(true)
    try {
      let added = 0
      let updated = 0
      const skipped: Array<{ carrier: string; message: string }> = []
      // Continue past per-carrier failures so one unverified carrier doesn't
      // hide the result of another (or surface a misleading error for the
      // carrier the user actually picked).
      for (const c of targets) {
        try {
          const res = await shippingConfigService.syncPackages(c, origin)
          added += res.data?.added ?? 0
          updated += res.data?.updated ?? 0
        } catch (e) {
          skipped.push({ carrier: c, message: e instanceof Error ? e.message : 'Sync failed.' })
        }
      }

      if (added || updated) {
        toast.success(`Carrier packaging · ${countryName(origin)}: ${added} new, ${updated} refreshed.`)
      }
      for (const s of skipped) {
        toast.error(`${s.carrier}: ${s.message}`)
      }
      if (!added && !updated && !skipped.length) {
        toast.success(`Carrier packaging · ${countryName(origin)}: nothing to sync.`)
      }
      await load()
    } finally {
      setSyncing(false)
    }
  }

  return (
    <div className="space-y-4 pb-16">
      {/* Page-specific actions bar — origin/carrier filters + sync + add. Refresh is global. */}
      <div className="flex flex-wrap items-center gap-2">
        <label
          title="Origin — carrier packaging is shown for this ship-from country. Your custom boxes always show."
          className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white pl-3 pr-1.5 py-1.5"
        >
          <FiGlobe className="h-3.5 w-3.5 shrink-0 text-slate-400" />
          <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
            Ship from <span className="text-slate-300">· origin</span>
          </span>
          <select
            value={origin}
            onChange={(e) => setOrigin(e.target.value)}
            className="cursor-pointer bg-transparent py-1 pr-1 text-[12.5px] font-semibold text-[#1f150c] outline-none"
          >
            {originOptions.map((code) => (
              <option key={code} value={code}>
                {countryName(code)} ({code})
              </option>
            ))}
          </select>
        </label>
        <label
          title="Filter packages by carrier (or show only your custom boxes)."
          className="flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white pl-3 pr-1.5 py-1.5"
        >
          <FiTruck className="h-3.5 w-3.5 shrink-0 text-slate-400" />
          <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Carrier</span>
          <select
            value={carrierFilter}
            onChange={(e) => setCarrierFilter(e.target.value)}
            className="cursor-pointer bg-transparent py-1 pr-1 text-[12.5px] font-semibold text-[#1f150c] outline-none"
          >
            <option value="ALL">All carriers</option>
            {carrierOptions.carriers.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
            {carrierOptions.hasCustom ? <option value="CUSTOM">Custom boxes</option> : null}
          </select>
        </label>
        {canSync ? (
          <button
            type="button"
            onClick={() => void syncCarrierPackaging()}
            disabled={syncing}
            title={
              carrierFilter && carrierFilter !== 'ALL' && carrierFilter !== 'CUSTOM'
                ? `Pull ${carrierFilter} predefined packaging for ${countryName(origin)}`
                : `Pull UPS/FedEx/USPS predefined packaging for ${countryName(origin)}`
            }
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-slate-600 transition hover:bg-slate-50 disabled:opacity-50"
          >
            <FiDownloadCloud className={`h-3.5 w-3.5 ${syncing ? 'animate-pulse' : ''}`} />
            {syncing ? 'Syncing…' : 'Sync carrier packaging'}
          </button>
        ) : null}
        <button
          type="button"
          onClick={() => setEditing(blankPreset())}
          className="ml-auto inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15]"
        >
          <FiPlus className="h-3.5 w-3.5" /> Add Package
        </button>
      </div>

      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {loading && !presets.length ? (
          <p className="col-span-3 py-10 text-center text-sm text-slate-500">Loading packages…</p>
        ) : (
          pagedPresets.map((p) => (
            <div
              key={p.id}
              className={`group relative rounded-2xl border bg-white shadow-sm transition hover:shadow-md ${
                p.default ? 'border-[#412d15]/40 ring-1 ring-[#412d15]/15' : 'border-slate-200'
              } ${p.enabled ? '' : 'opacity-60'}`}
            >
              {/* stamp-style default mark */}
              {p.default ? (
                <span
                  className="pointer-events-none absolute right-3 top-3 rotate-[6deg] rounded border-2 border-amber-400/60 px-1.5 py-0.5 text-[8.5px] font-black uppercase tracking-[0.22em] text-amber-600/80"
                  title="Default package — used when no linked package fits"
                >
                  Default
                </span>
              ) : null}

              {/* plate header: the box itself, drawn to its true proportions */}
              <div className="flex items-center gap-3 px-4 pt-4">
                <IsoBox length={p.length} width={p.width} height={p.height} />
                <div className="min-w-0 flex-1">
                  <p className="truncate pr-14 text-[13.5px] font-semibold text-slate-950">{p.name}</p>
                  <p className="mt-0.5 font-mono text-[9.5px] uppercase tracking-[0.14em] text-slate-400">
                    {p.carrierPackageCode || 'YOUR_PACKAGING'}
                  </p>
                  <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                    <span
                      className={`rounded-full px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ring-1 ${
                        p.kind === 'CARRIER' ? 'bg-sky-50 text-sky-700 ring-sky-100' : 'bg-emerald-50 text-emerald-700 ring-emerald-100'
                      }`}
                    >
                      {p.kind === 'CARRIER' ? 'Carrier packaging' : 'Custom box'}
                    </span>
                    {p.carrier ? (
                      <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-slate-600">
                        <FiTruck className="h-2.5 w-2.5" /> {p.carrier}
                      </span>
                    ) : null}
                    {p.kind === 'CARRIER' && p.scope && p.scope !== 'BOTH' ? (
                      <span
                        className={`rounded-full px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ring-1 ${
                          p.scope === 'DOMESTIC'
                            ? 'bg-emerald-50 text-emerald-700 ring-emerald-100'
                            : 'bg-sky-50 text-sky-700 ring-sky-100'
                        }`}
                      >
                        {p.scope === 'DOMESTIC' ? '⌂ Domestic' : '⊕ Intl'}
                      </span>
                    ) : null}
                    {p.flatRate ? (
                      <span className="rounded-full bg-violet-50 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-violet-700 ring-1 ring-violet-100">
                        Flat rate
                      </span>
                    ) : null}
                    {(() => {
                      const o = oversizeOf(p)
                      if (!o || o.status === 'OK') return null
                      return o.status === 'SURCHARGE' ? (
                        <span
                          title={`Length + girth ${o.lengthPlusGirth}" — Large Package surcharge ($220+, 90 lb min billable)`}
                          className="rounded-full bg-amber-50 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-amber-700 ring-1 ring-amber-200"
                        >
                          ⚠ Oversize
                        </span>
                      ) : (
                        <span
                          title={`Length + girth ${o.lengthPlusGirth}" — exceeds parcel limits (165" L+G / 108" length): freight only`}
                          className="rounded-full bg-rose-50 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-rose-700 ring-1 ring-rose-200"
                        >
                          ✕ Over max
                        </span>
                      )
                    })()}
                  </div>
                </div>
              </div>

              {/* spec sheet */}
              <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 border-t border-dashed border-slate-200 px-4 pt-3 text-[12px]">
                <div>
                  <dt className="font-mono text-[8.5px] font-bold uppercase tracking-[0.16em] text-slate-400">Dimensions</dt>
                  <dd className="font-medium tabular-nums text-slate-800">{dims(p)}</dd>
                </div>
                <div>
                  <dt className="font-mono text-[8.5px] font-bold uppercase tracking-[0.16em] text-slate-400">Max weight</dt>
                  <dd className="font-medium tabular-nums text-slate-800">{p.maxWeight ? `${p.maxWeight} ${p.weightUnit.toLowerCase()}` : '—'}</dd>
                </div>
                <div>
                  <dt className="font-mono text-[8.5px] font-bold uppercase tracking-[0.16em] text-slate-400">Tare weight</dt>
                  <dd className="font-medium tabular-nums text-slate-800">{p.tareWeight ? `${p.tareWeight} ${p.weightUnit.toLowerCase()}` : '—'}</dd>
                </div>
                <div>
                  <dt className="font-mono text-[8.5px] font-bold uppercase tracking-[0.16em] text-slate-400" title="Carriers bill max(actual, L×W×H ÷ 139) — dims rounded up">
                    DIM weight
                  </dt>
                  <dd className="font-medium tabular-nums text-slate-800">
                    {(() => {
                      const d = dimWeightOf(p)
                      return d != null ? `${d} ${p.weightUnit.toLowerCase()}` : p.flatRate ? 'n/a (flat)' : '—'
                    })()}
                  </dd>
                </div>
                <div>
                  <dt className="font-mono text-[8.5px] font-bold uppercase tracking-[0.16em] text-slate-400">Box cost</dt>
                  <dd className="font-medium tabular-nums text-slate-800">{p.boxCost != null ? `$${p.boxCost}` : '—'}</dd>
                </div>
                <div>
                  <dt className="font-mono text-[8.5px] font-bold uppercase tracking-[0.16em] text-slate-400">Pick priority</dt>
                  <dd className="font-medium tabular-nums text-slate-800">{p.sortOrder ?? '—'}</dd>
                </div>
              </dl>

              {/* stencil footer: carton markings + actions */}
              <div className="mt-3 flex items-center justify-between gap-2 border-t border-dashed border-slate-200 px-4 py-2.5">
                <CartonMarks />
                <div className="flex items-center gap-1.5">
                  {!p.default ? (
                    <button
                      type="button"
                      onClick={() => void makeDefault(p)}
                      className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-600 transition hover:border-amber-300 hover:text-amber-700"
                    >
                      <FiStar className="h-3 w-3" /> Make default
                    </button>
                  ) : null}
                  <button
                    type="button"
                    onClick={() => setEditing({ ...p })}
                    className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
                  >
                    <FiEdit3 className="h-3 w-3" /> Edit
                  </button>
                  <button
                    type="button"
                    onClick={() => void remove(p)}
                    aria-label={`Delete ${p.name}`}
                    className="rounded-lg border border-rose-200 bg-white p-1.5 text-rose-600 transition hover:bg-rose-50"
                  >
                    <FiTrash2 className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            </div>
          ))
        )}
        {!loading && !presets.length ? (
          <p className="col-span-3 py-10 text-center text-sm text-slate-500">No packages yet — click "Add Package".</p>
        ) : null}
      </section>

      {visiblePresets.length > pageSize ? (
        <TablePagination
          page={safePage}
          pageSize={pageSize}
          totalPages={totalPages}
          onPageChange={setPage}
          onPageSizeChange={(size) => {
            setPageSize(size)
            setPage(1)
          }}
        />
      ) : null}

      {/* add/edit modal */}
      {editing ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={() => setEditing(null)}
        >
          <div
            className="w-full max-w-xl overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
            onClick={(e) => e.stopPropagation()}
          >
            {/* document band */}
            <div className="flex items-center justify-between gap-3 bg-[#1f150c] px-5 py-3">
              <p className="text-[10px] font-black uppercase tracking-[0.22em] text-[#e1dcc9]">Package spec sheet</p>
              <div className="flex items-center gap-2">
                <span className="rounded bg-[#e1dcc9]/15 px-2 py-0.5 font-mono text-[9px] font-bold uppercase tracking-[0.16em] text-[#e1dcc9]">
                  {editing.id ? 'Revision' : 'New entry'}
                </span>
                <button
                  type="button"
                  onClick={() => setEditing(null)}
                  className="rounded-lg p-1.5 text-[#e1dcc9]/60 transition hover:bg-white/10 hover:text-[#e1dcc9]"
                  aria-label="Close"
                >
                  <FiX className="h-4 w-4" />
                </button>
              </div>
            </div>

            {/* live preview: the box takes shape as you type */}
            <div className="flex items-center gap-4 border-b border-dashed border-slate-300 bg-[#faf9f7] px-5 py-3">
              <IsoBox length={editing.length} width={editing.width} height={editing.height} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-[13.5px] font-semibold text-slate-950">
                  {editing.name.trim() || 'Unnamed package'}
                </p>
                <p className="mt-0.5 font-mono text-[9.5px] uppercase tracking-[0.14em] text-slate-400">
                  {editing.carrierPackageCode || 'YOUR_PACKAGING'}
                </p>
              </div>
              {(() => {
                const d = dimWeightOf(editing)
                const o = oversizeOf(editing)
                return (
                  <div className="shrink-0 text-right">
                    <p className="font-mono text-[9px] font-bold uppercase tracking-[0.16em] text-slate-400">
                      Dim wt {d != null ? `${d} ${editing.weightUnit.toLowerCase()}` : editing.flatRate ? 'n/a' : '—'}
                      {o ? ` · L+G ${o.lengthPlusGirth}"` : ''}
                    </p>
                    <p className="mt-1 flex justify-end gap-1.5">
                      {editing.flatRate ? (
                        <span className="rounded-full bg-violet-50 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wide text-violet-700 ring-1 ring-violet-100">
                          Flat rate
                        </span>
                      ) : null}
                      {o && o.status === 'SURCHARGE' ? (
                        <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wide text-amber-700 ring-1 ring-amber-200">
                          ⚠ Oversize
                        </span>
                      ) : o && o.status === 'OVER_MAX' ? (
                        <span className="rounded-full bg-rose-50 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wide text-rose-700 ring-1 ring-rose-200">
                          ✕ Over max
                        </span>
                      ) : (
                        <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[9px] font-bold uppercase tracking-wide text-emerald-700 ring-1 ring-emerald-100">
                          Within limits
                        </span>
                      )}
                    </p>
                  </div>
                )
              })()}
            </div>

            {/* numbered spec sections */}
            <div className="max-h-[56vh] space-y-5 overflow-y-auto px-5 py-4">
              <SpecSection n={1} title="Identity">
                <Field label="Name" required>
                  <input value={editing.name} onChange={(e) => set('name', e.target.value)} placeholder="e.g. Small Box" className={inputClass} />
                </Field>
                <Field label="Type" required>
                  <Select value={editing.kind} onChange={(e) => set('kind', e.target.value as 'CARRIER' | 'CUSTOM')}>
                    <option value="CUSTOM">Custom box</option>
                    <option value="CARRIER">Carrier packaging</option>
                  </Select>
                </Field>
                {editing.kind === 'CARRIER' ? (
                  <>
                    <Field label="Carrier package code" required>
                      <input
                        value={editing.carrierPackageCode ?? ''}
                        onChange={(e) => set('carrierPackageCode', e.target.value)}
                        placeholder="e.g. 01 (UPS Letter), FEDEX_PAK"
                        className={inputClass}
                      />
                    </Field>
                    <Field label="Carrier (optional restriction)">
                      <Select value={editing.carrier ?? ''} onChange={(e) => set('carrier', e.target.value || null)}>
                        <option value="">Any carrier</option>
                        <option value="UPS">UPS</option>
                        <option value="FEDEX">FEDEX</option>
                        <option value="USPS">USPS</option>
                      </Select>
                    </Field>
                  </>
                ) : null}
              </SpecSection>

              <SpecSection n={2} title="Dimensions">
                {editing.kind === 'CUSTOM' ? (
                  <>
                    <Field label={`Length (${editing.dimUnit.toLowerCase()})`} required>
                      <input type="number" min="0" step="0.1" value={editing.length ?? ''} onChange={(e) => set('length', num(e.target.value))} className={inputClass} />
                    </Field>
                    <Field label={`Width (${editing.dimUnit.toLowerCase()})`} required>
                      <input type="number" min="0" step="0.1" value={editing.width ?? ''} onChange={(e) => set('width', num(e.target.value))} className={inputClass} />
                    </Field>
                    <Field label={`Height (${editing.dimUnit.toLowerCase()})`} required>
                      <input type="number" min="0" step="0.1" value={editing.height ?? ''} onChange={(e) => set('height', num(e.target.value))} className={inputClass} />
                    </Field>
                    <Field label="Dimension unit">
                      <Select value={editing.dimUnit} onChange={(e) => set('dimUnit', e.target.value)}>
                        <option value="IN">Inches</option>
                        <option value="CM">Centimeters</option>
                      </Select>
                    </Field>
                    <div className="col-span-2">
                      <Field label={`Internal L × W × H (${editing.dimUnit.toLowerCase()}, usable space)`}>
                        <div className="flex gap-1.5">
                          <input type="number" min="0" step="0.1" value={editing.internalLength ?? ''} onChange={(e) => set('internalLength', num(e.target.value))} placeholder="L" className={inputClass} aria-label="Internal length" />
                          <input type="number" min="0" step="0.1" value={editing.internalWidth ?? ''} onChange={(e) => set('internalWidth', num(e.target.value))} placeholder="W" className={inputClass} aria-label="Internal width" />
                          <input type="number" min="0" step="0.1" value={editing.internalHeight ?? ''} onChange={(e) => set('internalHeight', num(e.target.value))} placeholder="H" className={inputClass} aria-label="Internal height" />
                        </div>
                      </Field>
                    </div>
                  </>
                ) : (
                  <p className="col-span-2 rounded-xl border border-dashed border-slate-200 bg-slate-50/60 px-3 py-2.5 text-[11.5px] text-slate-500">
                    Dimensions come from the carrier's packaging spec — nothing to enter here.
                  </p>
                )}
              </SpecSection>

              <SpecSection n={3} title="Weights & rating">
                <Field label={`Max weight (${editing.weightUnit.toLowerCase()})`}>
                  <input type="number" min="0" step="0.1" value={editing.maxWeight ?? ''} onChange={(e) => set('maxWeight', num(e.target.value))} className={inputClass} />
                </Field>
                <Field label={`Tare weight (${editing.weightUnit.toLowerCase()})`}>
                  <input type="number" min="0" step="0.1" value={editing.tareWeight ?? ''} onChange={(e) => set('tareWeight', num(e.target.value))} className={inputClass} />
                </Field>
                <Field label="Weight unit">
                  <Select value={editing.weightUnit} onChange={(e) => set('weightUnit', e.target.value)}>
                    <option value="LB">Pounds</option>
                    <option value="KG">Kilograms</option>
                  </Select>
                </Field>
                <Field label="Box cost ($)">
                  <input type="number" min="0" step="0.01" value={editing.boxCost ?? ''} onChange={(e) => set('boxCost', num(e.target.value))} className={inputClass} />
                </Field>
                {editing.kind === 'CARRIER' ? (
                  <Field label="Flat rate">
                    <Select value={editing.flatRate ? 'YES' : 'NO'} onChange={(e) => set('flatRate', e.target.value === 'YES')}>
                      <option value="NO">No — normal rating</option>
                      <option value="YES">Yes — fixed price, no DIM weight</option>
                    </Select>
                  </Field>
                ) : null}
                <Field label="Pick priority (lower = preferred)">
                  <input type="number" min="0" step="1" value={editing.sortOrder ?? ''} onChange={(e) => set('sortOrder', num(e.target.value))} className={inputClass} />
                </Field>
                <Field label="Status">
                  <Select value={editing.enabled ? 'ON' : 'OFF'} onChange={(e) => set('enabled', e.target.value === 'ON')}>
                    <option value="ON">Enabled</option>
                    <option value="OFF">Disabled</option>
                  </Select>
                </Field>
              </SpecSection>
            </div>

            {/* footer */}
            <div className="flex items-center justify-end gap-2 border-t border-dashed border-slate-300 px-5 py-4">
              <button
                type="button"
                onClick={() => setEditing(null)}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void save()}
                disabled={saving}
                className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                {saving ? 'Saving…' : editing.id ? 'Save changes' : 'Register package'}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
