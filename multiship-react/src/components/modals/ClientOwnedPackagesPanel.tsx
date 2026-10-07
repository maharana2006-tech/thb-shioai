import { useCallback, useEffect, useState } from 'react'
import { FiEdit3, FiPlus, FiTrash2, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import {
  shippingConfigService,
  type PackagePreset,
} from '../../api/shippingConfigService'
import Select from '../workspace/Select'

/**
 * Section 1 of the client-editor Packages tab: the client's OWN packages
 * (owner_type=CLIENT). These are auto-allowed for the owner (Phase-5d
 * resolver cascade), so they don't need a ClientAllowedPackage row.
 *
 * Inline create/edit form kept minimal — full dim overrides live on the
 * platform Packages page for PLATFORM presets; here we only manage the
 * fields a client-owned box realistically needs.
 */
export default function ClientOwnedPackagesPanel({ clientCode }: { clientCode: string }) {
  const [presets, setPresets] = useState<PackagePreset[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<PackagePreset | null>(null)
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const list = await shippingConfigService.listPresets()
      setPresets(list.filter((p) =>
        (p.ownerType || '').toUpperCase() === 'CLIENT'
        && (p.ownerClientCode || '').toUpperCase() === clientCode.toUpperCase(),
      ))
    } catch (error) {
      notify.apiError(error, 'Failed to load client packages.')
    } finally {
      setLoading(false)
    }
  }, [clientCode])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on client change; load() sets loading + list state
    void load()
  }, [load])

  const startCreate = () => {
    setEditing({
      name: '',
      kind: 'CUSTOM',
      ownerType: 'CLIENT',
      ownerClientCode: clientCode,
      dimUnit: 'IN',
      weightUnit: 'LB',
      enabled: true,
    })
  }

  const startEdit = (p: PackagePreset) => setEditing({ ...p })

  const save = async () => {
    if (!editing || !editing.name.trim()) {
      notify.error('Give the package a name.')
      return
    }
    if (editing.kind === 'CUSTOM' && (!editing.length || !editing.width || !editing.height)) {
      notify.error('A custom box needs length, width and height.')
      return
    }
    setSaving(true)
    try {
      await shippingConfigService.savePreset({
        ...editing,
        // Always CLIENT-owned by this client, regardless of form state.
        ownerType: 'CLIENT',
        ownerClientCode: clientCode,
      })
      notify.success(`Package '${editing.name}' saved.`)
      setEditing(null)
      await load()
    } catch (error) {
      notify.apiError(error, 'Failed to save package.')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (p: PackagePreset) => {
    if (!p.id) return
    if (!(await notify.confirm(`Delete package '${p.name}'?`, {
      title: 'Delete package', confirmLabel: 'Delete', danger: true,
    }))) return
    try {
      await shippingConfigService.deletePreset(p.id)
      notify.success('Package deleted.')
      await load()
    } catch (error) {
      notify.apiError(error, 'Failed to delete package.')
    }
  }

  return (
    <div className="rounded-2xl border border-slate-200 bg-slate-50/50 p-4">
      <div className="flex items-center justify-between">
        <div>
          <h4 className="text-[12.5px] font-semibold text-slate-950">Client-owned packages</h4>
          <p className="text-[11px] leading-5 text-slate-500">
            Private boxes for {clientCode} — automatically usable at label time (no allowlist row required).
          </p>
        </div>
        {!editing ? (
          <button
            type="button"
            onClick={startCreate}
            className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiPlus className="h-3 w-3" />
            New package
          </button>
        ) : null}
      </div>

      {editing ? (
        <EditorForm
          preset={editing}
          onChange={setEditing}
          onCancel={() => setEditing(null)}
          onSave={() => void save()}
          saving={saving}
        />
      ) : null}

      <div className="mt-3 space-y-1.5">
        {loading ? (
          <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
            Loading…
          </p>
        ) : presets.length === 0 ? (
          <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
            No client-owned packages yet.
          </p>
        ) : (
          presets.map((p) => (
            <div key={p.id} className="flex items-center gap-2.5 rounded-xl border border-slate-200 bg-white px-3 py-2">
              <div className="min-w-0 flex-1">
                <p className="truncate text-[12px] font-semibold text-slate-800">
                  {p.name}
                  <span className="ml-1 font-normal text-slate-500">· {p.kind}</span>
                </p>
                <p className="text-[10.5px] text-slate-500">
                  {p.length && p.width && p.height
                    ? `${p.length}×${p.width}×${p.height} ${p.dimUnit?.toLowerCase() ?? 'in'}`
                    : 'no dims'}
                  {p.maxWeight != null ? ` · up to ${p.maxWeight} ${p.weightUnit?.toLowerCase() ?? 'lb'}` : ''}
                  {p.carrier ? ` · ${p.carrier}` : ''}
                </p>
              </div>
              <button
                type="button"
                onClick={() => startEdit(p)}
                aria-label={`Edit ${p.name}`}
                className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
              >
                <FiEdit3 className="h-3.5 w-3.5" />
              </button>
              <button
                type="button"
                onClick={() => void remove(p)}
                aria-label={`Delete ${p.name}`}
                className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600"
              >
                <FiTrash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          ))
        )}
      </div>
    </div>
  )
}

function EditorForm({
  preset,
  onChange,
  onCancel,
  onSave,
  saving,
}: {
  preset: PackagePreset
  onChange: (p: PackagePreset) => void
  onCancel: () => void
  onSave: () => void
  saving: boolean
}) {
  const set = <K extends keyof PackagePreset>(key: K, value: PackagePreset[K]) =>
    onChange({ ...preset, [key]: value })

  return (
    <div className="mt-3 rounded-xl border border-slate-200 bg-white p-3">
      <div className="flex items-center justify-between">
        <h5 className="text-[12px] font-semibold text-slate-950">
          {preset.id ? `Edit ${preset.name}` : 'New client package'}
        </h5>
        <button
          type="button"
          onClick={onCancel}
          aria-label="Close"
          className="rounded-lg border border-transparent p-1.5 text-slate-400 transition hover:border-slate-200 hover:text-slate-600"
        >
          <FiX className="h-3.5 w-3.5" />
        </button>
      </div>
      <div className="mt-2 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <FieldRow label="Name" required>
          <input
            value={preset.name}
            onChange={(e) => set('name', e.target.value)}
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
          />
        </FieldRow>
        <FieldRow label="Kind">
          <Select value={preset.kind} onChange={(e) => set('kind', e.target.value as 'CARRIER' | 'CUSTOM')}>
            <option value="CUSTOM">CUSTOM — your own box</option>
            <option value="CARRIER">CARRIER — carrier-defined</option>
          </Select>
        </FieldRow>
        <FieldRow label="Carrier">
          <Select value={preset.carrier ?? ''} onChange={(e) => set('carrier', e.target.value || null)}>
            <option value="">Any</option>
            <option value="UPS">UPS</option>
            <option value="FEDEX">FedEx</option>
            <option value="USPS">USPS</option>
          </Select>
        </FieldRow>
        <FieldRow label="Carrier package code">
          <input
            value={preset.carrierPackageCode ?? ''}
            onChange={(e) => set('carrierPackageCode', e.target.value || null)}
            placeholder="only for CARRIER kind"
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
          />
        </FieldRow>
        <FieldRow label="Length">
          <input
            type="number"
            step="0.01"
            value={preset.length ?? ''}
            onChange={(e) => set('length', e.target.value ? Number(e.target.value) : null)}
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
          />
        </FieldRow>
        <FieldRow label="Width">
          <input
            type="number"
            step="0.01"
            value={preset.width ?? ''}
            onChange={(e) => set('width', e.target.value ? Number(e.target.value) : null)}
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
          />
        </FieldRow>
        <FieldRow label="Height">
          <input
            type="number"
            step="0.01"
            value={preset.height ?? ''}
            onChange={(e) => set('height', e.target.value ? Number(e.target.value) : null)}
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
          />
        </FieldRow>
        <FieldRow label="Dim unit">
          <Select value={preset.dimUnit ?? 'IN'} onChange={(e) => set('dimUnit', e.target.value)}>
            <option value="IN">IN</option>
            <option value="CM">CM</option>
          </Select>
        </FieldRow>
        <FieldRow label="Max weight">
          <input
            type="number"
            step="0.01"
            value={preset.maxWeight ?? ''}
            onChange={(e) => set('maxWeight', e.target.value ? Number(e.target.value) : null)}
            className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
          />
        </FieldRow>
        <FieldRow label="Weight unit">
          <Select value={preset.weightUnit ?? 'LB'} onChange={(e) => set('weightUnit', e.target.value)}>
            <option value="LB">LB</option>
            <option value="KG">KG</option>
          </Select>
        </FieldRow>
      </div>
      <div className="mt-3 flex items-center justify-end gap-2">
        <button
          type="button"
          onClick={onCancel}
          className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-100"
        >
          Cancel
        </button>
        <button
          type="button"
          onClick={onSave}
          disabled={saving}
          className="rounded-xl bg-[#1f150c] px-4 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
    </div>
  )
}

function FieldRow({ label, required, children }: { label: string; required?: boolean; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
        {label}
        {required ? <span className="ml-1 text-rose-500">*</span> : null}
      </label>
      {children}
    </div>
  )
}
