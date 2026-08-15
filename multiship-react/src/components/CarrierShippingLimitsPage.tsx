import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { FiPlus, FiTrash2, FiEdit2, FiTruck, FiX, FiCheck, FiSlash } from 'react-icons/fi'
import {
  carrierShippingLimitService,
  type CarrierShippingLimit,
  type CarrierShippingLimitPayload,
} from '../api/carrierShippingLimitService'
import { notify } from '../utils/notify'
import { useFocusTrap } from '../hooks/useFocusTrap'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import IconButton from './ui/IconButton'

/**
 * Sprint 52 — admin CRUD for the `carrier_shipping_limit` catalog.
 * ADMIN-only page under `/settings/carrier-limits`; modelled directly
 * on {@link AdminUsersPage}. Every mutation invalidates the backend
 * resolver's in-memory cache so the change appears on the shipment-
 * create path immediately.
 *
 * Table + edit-modal + create-button + row-level active toggle +
 * delete-with-confirm. See CarrierLimitAdminController for the API
 * shape.
 */
export default function CarrierShippingLimitsPage() {
  const [rows, setRows] = useState<CarrierShippingLimit[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<CarrierShippingLimit | null>(null)
  const [creating, setCreating] = useState(false)
  const [filterCarrier, setFilterCarrier] = useState('')
  const [filterScope, setFilterScope] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await carrierShippingLimitService.list({ size: 200 })
      setRows(res.data ?? [])
    } catch (e) {
      notify.apiError(e, 'Failed to load carrier limits.')
    } finally {
      setLoading(false)
    }
  }, [])

  // eslint-disable-next-line react-hooks/set-state-in-effect -- initial fetch on mount; load() owns setLoading + setRows
  useEffect(() => { void load() }, [load])

  const outlet = useOutletContext<SettingsOutletContext | undefined>()
  useEffect(() => {
    // Refresh registration is optional — the page also renders standalone.
    if (!outlet?.registerRefresh) return
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const filtered = useMemo(() => {
    return rows.filter((r) => {
      if (filterCarrier && !r.carrierCode.toLowerCase().includes(filterCarrier.toLowerCase())) return false
      if (filterScope && r.scope !== filterScope) return false
      return true
    })
  }, [rows, filterCarrier, filterScope])

  const toggleActive = async (row: CarrierShippingLimit) => {
    const nextActive = !row.active
    try {
      await carrierShippingLimitService.update(row.id, {
        carrierCode: row.carrierCode,
        serviceCode: row.serviceCode,
        scope: row.scope,
        direction: row.direction,
        maxPackages: row.maxPackages,
        maxCommodities: row.maxCommodities,
        maxTotalWeightLb: row.maxTotalWeightLb,
        freeDeclaredValue: row.freeDeclaredValue,
        active: nextActive,
        notes: row.notes,
      })
      notify.success(nextActive
        ? `Activated ${row.carrierCode}/${row.serviceCode ?? '(default)'} row.`
        : `Deactivated ${row.carrierCode}/${row.serviceCode ?? '(default)'} row.`)
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to update row.')
    }
  }

  const remove = async (row: CarrierShippingLimit) => {
    const confirmed = await notify.confirm(
      `Delete ${row.carrierCode}/${row.serviceCode ?? '(default)'} / ${row.scope}? This can't be undone — use Deactivate instead if you want to keep it for history.`,
      { title: 'Delete carrier limit', confirmLabel: 'Delete', danger: true },
    )
    if (!confirmed) return
    try {
      await carrierShippingLimitService.remove(row.id)
      notify.success('Row deleted.')
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to delete row.')
    }
  }

  return (
    <div className="space-y-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <FiTruck className="h-4 w-4 text-slate-500" />
            Carrier Shipping Limits
          </h2>
          <p className="mt-1 text-[12.5px] text-slate-500">
            Per-carrier / per-service caps on packages, commodities, weight and free declared
            value. Sprint 52 direction-aware (FORWARD / RETURN); the shipment-create resolver
            picks the most specific match and falls back to a high default when unset. Every
            edit invalidates the 5-min in-memory cache so it takes effect on the next call.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setCreating(true)}
          className="inline-flex items-center gap-1.5 rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-800"
        >
          <FiPlus className="h-4 w-4" /> New row
        </button>
      </header>

      <section className="flex flex-wrap items-center gap-2 rounded-xl border border-slate-200 bg-white p-3">
        <input
          type="search"
          value={filterCarrier}
          onChange={(e) => setFilterCarrier(e.target.value)}
          placeholder="Filter by carrier (UPS, FEDEX...)"
          className="min-w-[220px] flex-1 rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
        />
        <select
          value={filterScope}
          onChange={(e) => setFilterScope(e.target.value)}
          className="rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
        >
          <option value="">All scopes</option>
          <option value="BOTH">BOTH</option>
          <option value="DOMESTIC">DOMESTIC</option>
          <option value="INTERNATIONAL">INTERNATIONAL</option>
        </select>
      </section>

      <section className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        <table className="min-w-full text-[13px]">
          <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2">Carrier</th>
              <th className="px-3 py-2">Service</th>
              <th className="px-3 py-2">Scope</th>
              <th className="px-3 py-2">Direction</th>
              <th className="px-3 py-2 text-right">Max pkgs</th>
              <th className="px-3 py-2 text-right">Max commodities</th>
              <th className="px-3 py-2 text-right">Max weight (lb)</th>
              <th className="px-3 py-2 text-right">Free declared $</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">Notes</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr><td colSpan={11} className="px-3 py-6 text-center text-slate-500">Loading…</td></tr>
            ) : filtered.length === 0 ? (
              <tr><td colSpan={11} className="px-3 py-6 text-center text-slate-500">No limit rows.</td></tr>
            ) : filtered.map((r) => (
              <tr key={r.id} className={r.active ? '' : 'bg-slate-50/50 text-slate-400'}>
                <td className="px-3 py-2 font-mono">{r.carrierCode}</td>
                <td className="px-3 py-2 font-mono">{r.serviceCode ?? <span className="text-slate-400">(default)</span>}</td>
                <td className="px-3 py-2">
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11.5px] font-semibold">
                    {r.scope}
                  </span>
                </td>
                <td className="px-3 py-2">{r.direction ?? <span className="text-slate-400">any</span>}</td>
                <td className="px-3 py-2 text-right font-mono">{r.maxPackages}</td>
                <td className="px-3 py-2 text-right font-mono">{r.maxCommodities ?? <span className="text-slate-400">—</span>}</td>
                <td className="px-3 py-2 text-right font-mono">{r.maxTotalWeightLb ?? <span className="text-slate-400">—</span>}</td>
                <td className="px-3 py-2 text-right font-mono">{r.freeDeclaredValue ?? <span className="text-slate-400">—</span>}</td>
                <td className="px-3 py-2">
                  {r.active ? (
                    <span className="inline-flex items-center gap-1 text-emerald-700">
                      <FiCheck className="h-3.5 w-3.5" /> Active
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1 text-red-600">
                      <FiSlash className="h-3.5 w-3.5" /> Inactive
                    </span>
                  )}
                </td>
                <td className="max-w-[200px] truncate px-3 py-2 text-slate-600" title={r.notes ?? ''}>
                  {r.notes ?? ''}
                </td>
                <td className="px-3 py-2 text-right whitespace-nowrap">
                  <button
                    onClick={() => setEditing(r)}
                    className="mr-1 rounded-md border border-slate-300 px-2 py-1 text-[12px] hover:bg-slate-50"
                    title="Edit row"
                  >
                    <FiEdit2 className="inline h-3.5 w-3.5" /> Edit
                  </button>
                  <button
                    onClick={() => toggleActive(r)}
                    className={`mr-1 rounded-md px-2 py-1 text-[12px] ${
                      r.active
                        ? 'border border-red-300 text-red-700 hover:bg-red-50'
                        : 'border border-emerald-300 text-emerald-700 hover:bg-emerald-50'
                    }`}
                    title={r.active ? 'Deactivate row' : 'Activate row'}
                  >
                    {r.active ? 'Deactivate' : 'Activate'}
                  </button>
                  <button
                    onClick={() => remove(r)}
                    className="rounded-md border border-red-300 px-2 py-1 text-[12px] text-red-700 hover:bg-red-50"
                    title="Delete row"
                  >
                    <FiTrash2 className="inline h-3.5 w-3.5" />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {(editing || creating) && (
        <CarrierShippingLimitEditorDialog
          row={editing}
          onClose={() => { setEditing(null); setCreating(false) }}
          onSaved={() => { setEditing(null); setCreating(false); void load() }}
        />
      )}
    </div>
  )
}

interface EditorProps {
  row: CarrierShippingLimit | null
  onClose: () => void
  onSaved: () => void
}

/**
 * Shared create / edit dialog. When {@code row} is null the dialog
 * submits POST /admin/carrier-shipping-limits; otherwise it PUTs to
 * the same id and preserves everything the operator didn't touch.
 */
function CarrierShippingLimitEditorDialog({ row, onClose, onSaved }: EditorProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, containerRef)

  const [carrierCode, setCarrierCode] = useState(row?.carrierCode ?? '')
  const [serviceCode, setServiceCode] = useState(row?.serviceCode ?? '')
  const [scope, setScope] = useState<'BOTH' | 'DOMESTIC' | 'INTERNATIONAL'>(row?.scope ?? 'DOMESTIC')
  const [direction, setDirection] = useState<'FORWARD' | 'RETURN' | ''>(row?.direction ?? '')
  const [maxPackages, setMaxPackages] = useState<string>(row?.maxPackages != null ? String(row.maxPackages) : '')
  const [maxCommodities, setMaxCommodities] = useState<string>(row?.maxCommodities != null ? String(row.maxCommodities) : '')
  const [maxTotalWeightLb, setMaxTotalWeightLb] = useState<string>(row?.maxTotalWeightLb != null ? String(row.maxTotalWeightLb) : '')
  const [freeDeclaredValue, setFreeDeclaredValue] = useState<string>(row?.freeDeclaredValue != null ? String(row.freeDeclaredValue) : '')
  const [active, setActive] = useState<boolean>(row?.active ?? true)
  const [notes, setNotes] = useState(row?.notes ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !saving) onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose, saving])

  /** Simple client-side validation — the backend re-validates on POST/PUT. */
  const validate = (): string | null => {
    if (!carrierCode.trim()) return 'Carrier code is required.'
    if (carrierCode.length > 32) return 'Carrier code must be 32 chars or fewer.'
    if (!scope) return 'Scope is required.'
    const pkgs = Number(maxPackages)
    if (!Number.isInteger(pkgs) || pkgs < 1 || pkgs > 9999) {
      return 'Max packages must be an integer between 1 and 9999.'
    }
    if (maxCommodities.trim()) {
      const c = Number(maxCommodities)
      if (!Number.isInteger(c) || c < 1 || c > 9999) {
        return 'Max commodities must be an integer between 1 and 9999.'
      }
    }
    if (maxTotalWeightLb.trim()) {
      const w = Number(maxTotalWeightLb)
      if (Number.isNaN(w) || w <= 0) return 'Max weight must be > 0 lb.'
    }
    if (freeDeclaredValue.trim()) {
      const f = Number(freeDeclaredValue)
      if (Number.isNaN(f) || f < 0) return 'Free declared value must be >= 0.'
    }
    if (notes.length > 500) return 'Notes must be 500 chars or fewer.'
    return null
  }

  const submit = async () => {
    const err = validate()
    if (err) {
      setError(err)
      return
    }
    setError(null)
    setSaving(true)
    const payload: CarrierShippingLimitPayload = {
      carrierCode: carrierCode.trim().toUpperCase(),
      serviceCode: serviceCode.trim() || null,
      scope,
      direction: direction || null,
      maxPackages: Number(maxPackages),
      maxCommodities: maxCommodities.trim() ? Number(maxCommodities) : null,
      maxTotalWeightLb: maxTotalWeightLb.trim() ? Number(maxTotalWeightLb) : null,
      freeDeclaredValue: freeDeclaredValue.trim() ? Number(freeDeclaredValue) : null,
      active,
      notes: notes.trim() || null,
    }
    try {
      if (row) {
        await carrierShippingLimitService.update(row.id, payload)
        notify.success(`Updated ${payload.carrierCode}/${payload.serviceCode ?? '(default)'}.`)
      } else {
        await carrierShippingLimitService.create(payload)
        notify.success(`Created ${payload.carrierCode}/${payload.serviceCode ?? '(default)'}.`)
      }
      onSaved()
    } catch (e) {
      notify.apiError(e, row ? 'Failed to update row.' : 'Failed to create row.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4"
         role="dialog" aria-modal="true" aria-labelledby="csl-dialog-title">
      <div ref={containerRef} className="w-full max-w-2xl rounded-xl border border-slate-200 bg-white p-4 shadow-xl">
        <header className="mb-3 flex items-center justify-between">
          <h3 id="csl-dialog-title" className="text-[15px] font-semibold text-slate-900">
            {row ? 'Edit carrier limit' : 'New carrier limit'}
          </h3>
          <IconButton
            onClick={onClose}
            label="Close editor"
            icon={<FiX className="h-4 w-4" />}
            className="rounded p-1 text-slate-500 hover:bg-slate-100"
          />
        </header>

        <div className="grid grid-cols-2 gap-3">
          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Carrier code *</span>
            <input
              value={carrierCode}
              onChange={(e) => setCarrierCode(e.target.value)}
              placeholder="UPS"
              maxLength={32}
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Service code (blank = carrier default)</span>
            <input
              value={serviceCode}
              onChange={(e) => setServiceCode(e.target.value)}
              placeholder="UPS_GROUND"
              maxLength={60}
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            />
          </label>

          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Scope *</span>
            <select
              value={scope}
              onChange={(e) => setScope(e.target.value as 'BOTH' | 'DOMESTIC' | 'INTERNATIONAL')}
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            >
              <option value="DOMESTIC">DOMESTIC</option>
              <option value="INTERNATIONAL">INTERNATIONAL</option>
              <option value="BOTH">BOTH</option>
            </select>
          </label>
          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Direction (blank = matches any)</span>
            <select
              value={direction}
              onChange={(e) => setDirection(e.target.value as 'FORWARD' | 'RETURN' | '')}
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            >
              <option value="">— any —</option>
              <option value="FORWARD">FORWARD</option>
              <option value="RETURN">RETURN</option>
            </select>
          </label>

          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Max packages * (1..9999)</span>
            <input
              type="number" min={1} max={9999} step={1}
              value={maxPackages}
              onChange={(e) => setMaxPackages(e.target.value)}
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Max commodities (1..9999)</span>
            <input
              type="number" min={1} max={9999} step={1}
              value={maxCommodities}
              onChange={(e) => setMaxCommodities(e.target.value)}
              placeholder="blank = 999 fallback"
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            />
          </label>

          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Max total weight (lb)</span>
            <input
              type="number" min={0} step={0.01}
              value={maxTotalWeightLb}
              onChange={(e) => setMaxTotalWeightLb(e.target.value)}
              placeholder="blank = no cap"
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            />
          </label>
          <label className="block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Free declared value ($)</span>
            <input
              type="number" min={0} step={0.01}
              value={freeDeclaredValue}
              onChange={(e) => setFreeDeclaredValue(e.target.value)}
              placeholder="blank = no free tier"
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            />
          </label>

          <label className="col-span-2 flex items-center gap-2">
            <input
              type="checkbox"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
            />
            <span className="text-[12.5px] font-medium text-slate-700">Active</span>
          </label>

          <label className="col-span-2 block">
            <span className="mb-1 block text-[12.5px] font-medium text-slate-700">Notes (max 500)</span>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              rows={2}
              maxLength={500}
              className="w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
            />
          </label>
        </div>

        {error && (
          <p className="mt-3 rounded-md border border-red-200 bg-red-50 px-2 py-1.5 text-[12.5px] text-red-700">
            {error}
          </p>
        )}

        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={onClose}
            disabled={saving}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-[13px] hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={saving}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-[13px] text-white hover:bg-slate-800 disabled:opacity-60"
          >
            {saving ? 'Saving…' : row ? 'Save' : 'Create'}
          </button>
        </div>
      </div>
    </div>
  )
}
