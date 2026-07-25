import { useEffect, useMemo, useState } from 'react'
import { FiAlertTriangle, FiCheck, FiPackage, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import {
  shippingConfigService,
  type PackagePreset,
  type ShipMethodRule,
  type ShippingServiceItem,
} from '../../api/shippingConfigService'
import { formatCarrierName } from '../../utils/carrierUtils'

/** Convert package max weight to lb for comparison against ShippingService.maxWeightLb. */
const toLb = (p: PackagePreset): number | null => {
  if (p.maxWeight == null) return null
  return p.weightUnit === 'KG' ? p.maxWeight * 2.20462 : p.maxWeight
}

/** Would this package fit under this service according to the ServicesPage
 *  compat predicate — same carrier or a custom (carrier-agnostic) box. */
const packageFits = (p: PackagePreset, carrier: string | null): boolean => {
  if (p.kind !== 'CARRIER') return true
  if (!carrier) return true
  return (p.carrier || '').toUpperCase() === carrier
}

/**
 * Drawer for editing the allowed-packages set on a single ship-method rule.
 * Filters the catalog to packages compatible with the rule's target service
 * carrier; each row shows its max weight; rows whose weight exceeds the
 * service cap are highlighted (warn, don't block).
 */
export default function RulePackagesDrawer({
  rule,
  service,
  initialPresetIds,
  onClose,
  onSaved,
}: {
  rule: ShipMethodRule
  service: ShippingServiceItem
  initialPresetIds: number[]
  onClose: () => void
  onSaved: (nextIds: number[]) => void
}) {
  const [presets, setPresets] = useState<PackagePreset[]>([])
  const [selected, setSelected] = useState<Set<number>>(new Set(initialPresetIds))
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let alive = true
    shippingConfigService.listPresets()
      .then((list) => { if (alive) setPresets(list) })
      .catch(() => { /* notify covers it */ })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  const carrier = useMemo(() => (service.carrier || '').toUpperCase(), [service])
  const cap = service.maxWeightLb ?? null

  const eligible = useMemo(
    () => presets.filter((p) => p.enabled && p.id != null && packageFits(p, carrier)),
    [presets, carrier],
  )

  const toggle = (id: number) =>
    setSelected((cur) => {
      const next = new Set(cur)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })

  const overWeightCount = useMemo(() => {
    if (cap == null) return 0
    let n = 0
    for (const id of selected) {
      const p = presets.find((x) => x.id === id)
      if (!p) continue
      const lb = toLb(p)
      if (lb != null && lb > cap) n++
    }
    return n
  }, [selected, presets, cap])

  const save = async () => {
    setSaving(true)
    try {
      const nextIds = Array.from(selected).sort((a, b) => a - b)
      const r = await shippingConfigService.saveRule({
        ...rule,
        allowedPresetIds: nextIds,
      })
      if (overWeightCount > 0) {
        notify.info(`${overWeightCount} package${overWeightCount === 1 ? '' : 's'} exceed the service's ${cap} lb cap — saved with a warning.`)
      } else {
        notify.success(r.message || 'Rule packages saved.')
      }
      onSaved(nextIds)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to save.')
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
        aria-labelledby="rule-pkg-title"
        className="fixed inset-y-0 right-0 z-[60] flex w-full max-w-[520px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <header className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div className="min-w-0 flex-1">
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Rule packages
            </p>
            <h3 id="rule-pkg-title" className="mt-1 truncate text-[15px] font-semibold text-slate-950">
              {rule.shipviaCd} → {formatCarrierName(service.carrier)} · {service.name}
            </h3>
            <p className="mt-1 text-[11.5px] leading-5 text-slate-500">
              {cap != null
                ? <>Service max: <span className="font-semibold text-slate-800">{cap} lb</span>. Filter: {formatCarrierName(carrier)} + carrier-agnostic customs.</>
                : <>Service has no weight cap set. Filter: {formatCarrierName(carrier)} + carrier-agnostic customs.</>}
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
          ) : eligible.length === 0 ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              No compatible packages for {formatCarrierName(carrier)}. Add carrier packages or custom boxes on the Packages page.
            </p>
          ) : (
            <div className="space-y-1.5">
              {eligible.map((p) => {
                const id = p.id!
                const on = selected.has(id)
                const lb = toLb(p)
                const overCap = cap != null && lb != null && lb > cap
                return (
                  <button
                    key={id}
                    type="button"
                    onClick={() => toggle(id)}
                    className={`flex w-full items-center gap-2.5 rounded-xl border px-3 py-2 text-left transition ${
                      on
                        ? overCap
                          ? 'border-amber-400 bg-amber-50'
                          : 'border-[#412d15] bg-[#412d15]/[0.06]'
                        : 'border-slate-200 bg-white hover:bg-slate-50'
                    }`}
                  >
                    <FiPackage className={`h-4 w-4 shrink-0 ${overCap ? 'text-amber-600' : on ? 'text-[#412d15]' : 'text-slate-500'}`} />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-[12px] font-semibold text-slate-900">
                        {p.name}
                        <span className="ml-1 font-normal text-slate-500">
                          · {p.kind}
                          {p.carrier ? ` · ${formatCarrierName(p.carrier)}` : ''}
                        </span>
                      </p>
                      <p className="text-[10.5px] text-slate-500">
                        {p.length && p.width && p.height
                          ? `${p.length}×${p.width}×${p.height} ${(p.dimUnit || 'in').toLowerCase()}`
                          : 'no dims'}
                        {p.maxWeight != null
                          ? ` · up to ${p.maxWeight} ${(p.weightUnit || 'lb').toLowerCase()}${lb != null && p.weightUnit === 'KG' ? ` (${Math.round(lb)} lb)` : ''}`
                          : ' · no max weight'}
                      </p>
                    </div>
                    {overCap ? (
                      <span
                        title={`Exceeds the service's ${cap} lb cap`}
                        className="inline-flex shrink-0 items-center gap-1 rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide text-amber-800"
                      >
                        <FiAlertTriangle className="h-3 w-3" />
                        Over cap
                      </span>
                    ) : null}
                    {on ? <FiCheck className="h-4 w-4 shrink-0 text-[#412d15]" /> : null}
                  </button>
                )
              })}
            </div>
          )}

          <p className="mt-3 text-[11px] text-slate-500">
            {selected.size} selected{selected.size === 0 ? ' (unrestricted)' : ''}
            {overWeightCount > 0 ? ` · ${overWeightCount} over cap` : ''}
          </p>
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
            {saving ? 'Saving…' : 'Save packages'}
          </button>
        </footer>
      </aside>
    </>
  )
}
