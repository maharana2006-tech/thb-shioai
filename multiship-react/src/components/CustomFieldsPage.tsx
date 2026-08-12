import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { FiChevronDown, FiChevronUp, FiEdit3, FiPlus, FiSave, FiTrash2, FiX } from 'react-icons/fi'
import { notify } from '../utils/notify'
import { clientService, type Client } from '../api/clientService'
import {
  customFieldService,
  type CustomFieldDefinition,
  type CustomFieldType,
} from '../api/customFieldService'
import Select from './workspace/Select'
import type { SettingsOutletContext } from './layout/SettingsLayout'

const PLATFORM = '__PLATFORM__'

/**
 * Sprint 43 — Custom Fields settings page. Per-tenant definitions
 * (plus platform-wide) with a Type/Required/Position editor. Applied
 * fields surface on the New Shipment form and the order detail.
 */
export default function CustomFieldsPage() {
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  const [clients, setClients] = useState<Client[]>([])
  const [tenantId, setTenantId] = useState(PLATFORM)
  const [defs, setDefs] = useState<CustomFieldDefinition[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<CustomFieldDefinition | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const effectiveTenantId = tenantId === PLATFORM ? null : tenantId

  useEffect(() => {
    clientService
      .listClients({ size: 200, status: 'ACTIVE', sortBy: 'code' })
      .then((r) => setClients(r.data?.content ?? []))
      .catch(() => setClients([]))
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setDefs(await customFieldService.list(effectiveTenantId))
    } catch (err) {
      notify.apiError(err, 'Failed to load definitions.')
    } finally {
      setLoading(false)
    }
  }, [effectiveTenantId])

  useEffect(() => {
    load()
  }, [load, reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const move = async (def: CustomFieldDefinition, delta: -1 | 1) => {
    const nextPos = Math.max(0, (def.position ?? 100) + delta * 10)
    await customFieldService.save({ ...def, position: nextPos })
    refresh()
  }

  const del = async (def: CustomFieldDefinition) => {
    if (!def.id) return
    if (!window.confirm(`Delete definition "${def.label}"? Historic values remain on orders but no new values can be captured.`)) return
    await customFieldService.remove(def.id)
    notify.success('Definition deleted.')
    refresh()
  }

  const tenantOptions = useMemo(
    () => [
      { value: PLATFORM, label: 'Platform-wide (all tenants)' },
      ...clients.map((c) => ({ value: c.clientCode, label: `${c.clientCode} — ${c.name}` })),
    ],
    [clients],
  )

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-[260px]">
          <label className="mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
            Tenant scope
          </label>
          <Select value={tenantId} onChange={(e) => setTenantId(e.target.value || PLATFORM)}>
            {tenantOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </Select>
        </div>
        <button
          type="button"
          onClick={() =>
            setEditing({
              tenantId: effectiveTenantId,
              fieldKey: '',
              label: '',
              fieldType: 'TEXT',
              required: false,
              position: (defs[defs.length - 1]?.position ?? 100) + 10,
              active: true,
            })
          }
          className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black"
        >
          <FiPlus /> New field
        </button>
      </div>

      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-[12.5px]">
          <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">Order</th>
              <th className="px-4 py-2 text-left">Key</th>
              <th className="px-4 py-2 text-left">Label</th>
              <th className="px-4 py-2 text-left">Type</th>
              <th className="px-4 py-2 text-left">Required</th>
              <th className="px-4 py-2 text-left">Options</th>
              <th className="px-4 py-2 text-left">Active</th>
              <th className="px-4 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr>
                <td colSpan={8} className="px-4 py-6 text-center text-slate-400">
                  Loading definitions…
                </td>
              </tr>
            ) : defs.length === 0 ? (
              <tr>
                <td colSpan={8} className="px-4 py-6 text-center text-slate-400">
                  No custom fields defined for this scope yet.
                </td>
              </tr>
            ) : (
              defs.map((d, i) => (
                <tr key={d.id ?? d.fieldKey} className="hover:bg-slate-50">
                  <td className="px-4 py-2 align-middle">
                    <div className="inline-flex items-center gap-1">
                      <button
                        type="button"
                        disabled={i === 0}
                        onClick={() => move(d, -1)}
                        className="text-slate-400 hover:text-slate-700 disabled:opacity-30"
                        title="Move up"
                      >
                        <FiChevronUp />
                      </button>
                      <button
                        type="button"
                        disabled={i === defs.length - 1}
                        onClick={() => move(d, 1)}
                        className="text-slate-400 hover:text-slate-700 disabled:opacity-30"
                        title="Move down"
                      >
                        <FiChevronDown />
                      </button>
                      <span className="ml-1 text-[11px] tabular-nums text-slate-400">{d.position}</span>
                    </div>
                  </td>
                  <td className="px-4 py-2 font-mono text-[12px] text-slate-800">{d.fieldKey}</td>
                  <td className="px-4 py-2 text-slate-800">{d.label}</td>
                  <td className="px-4 py-2">
                    <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-bold text-slate-600">
                      {d.fieldType}
                    </span>
                  </td>
                  <td className="px-4 py-2">{d.required ? 'Required' : '—'}</td>
                  <td className="px-4 py-2 text-slate-500">{d.selectOptions ?? '—'}</td>
                  <td className="px-4 py-2">
                    {d.active === false ? (
                      <span className="text-rose-600">Inactive</span>
                    ) : (
                      <span className="text-emerald-700">Active</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <div className="inline-flex items-center gap-1">
                      <button
                        type="button"
                        onClick={() => setEditing({ ...d })}
                        className="rounded p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-800"
                        title="Edit"
                      >
                        <FiEdit3 />
                      </button>
                      <button
                        type="button"
                        onClick={() => del(d)}
                        className="rounded p-1 text-rose-500 hover:bg-rose-50"
                        title="Delete"
                      >
                        <FiTrash2 />
                      </button>
                    </div>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {editing ? (
        <CustomFieldEditorModal
          def={editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            refresh()
          }}
        />
      ) : null}
    </div>
  )
}

function CustomFieldEditorModal({
  def,
  onClose,
  onSaved,
}: {
  def: CustomFieldDefinition
  onClose: () => void
  onSaved: () => void
}) {
  const [draft, setDraft] = useState<CustomFieldDefinition>(def)
  const [saving, setSaving] = useState(false)
  const firstInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    firstInputRef.current?.focus()
  }, [])

  const update = <K extends keyof CustomFieldDefinition>(k: K, v: CustomFieldDefinition[K]) =>
    setDraft((d) => ({ ...d, [k]: v }))

  const save = async () => {
    if (!draft.fieldKey?.trim() || !draft.label?.trim()) {
      notify.error('Key and label are both required.')
      return
    }
    if (draft.fieldType === 'SELECT' && !draft.selectOptions?.trim()) {
      notify.error('SELECT fields need comma-separated options.')
      return
    }
    setSaving(true)
    try {
      await customFieldService.save(draft)
      notify.success('Definition saved.')
      onSaved()
    } catch (err) {
      notify.apiError(err, 'Failed to save.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-md rounded-2xl bg-white p-5 shadow-lg">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-base font-semibold text-slate-900">
            {draft.id ? 'Edit custom field' : 'New custom field'}
          </h3>
          <button type="button" onClick={onClose} className="text-slate-400 hover:text-slate-800">
            <FiX />
          </button>
        </div>

        <div className="mb-3 grid grid-cols-2 gap-3">
          <div>
            <label className={label}>Key</label>
            <input
              ref={firstInputRef}
              type="text"
              value={draft.fieldKey}
              onChange={(e) => update('fieldKey', e.target.value.trim())}
              placeholder="po_number"
              className={input}
            />
            <p className="mt-1 text-[11px] text-slate-400">snake_case. Immutable once orders reference it.</p>
          </div>
          <div>
            <label className={label}>Label</label>
            <input
              type="text"
              value={draft.label}
              onChange={(e) => update('label', e.target.value)}
              placeholder="PO Number"
              className={input}
            />
          </div>
        </div>

        <div className="mb-3 grid grid-cols-2 gap-3">
          <div>
            <label className={label}>Type</label>
            <Select
              value={draft.fieldType}
              onChange={(e) => update('fieldType', e.target.value as CustomFieldType)}
            >
              <option value="TEXT">Text</option>
              <option value="NUMBER">Number</option>
              <option value="DATE">Date (YYYY-MM-DD)</option>
              <option value="SELECT">Select</option>
            </Select>
          </div>
          <div>
            <label className={label}>Position</label>
            <input
              type="number"
              min={0}
              value={draft.position ?? 100}
              onChange={(e) => update('position', Number(e.target.value))}
              className={input}
            />
          </div>
        </div>

        {draft.fieldType === 'SELECT' ? (
          <div className="mb-3">
            <label className={label}>Options (comma-separated)</label>
            <input
              type="text"
              value={draft.selectOptions ?? ''}
              onChange={(e) => update('selectOptions', e.target.value)}
              placeholder="S, M, L, XL"
              className={input}
            />
          </div>
        ) : null}

        <div className="mb-4 flex items-center gap-4 text-[13px] text-slate-700">
          <label className="inline-flex items-center gap-2">
            <input
              type="checkbox"
              checked={draft.required ?? false}
              onChange={(e) => update('required', e.target.checked)}
            />
            Required on new shipments
          </label>
          <label className="inline-flex items-center gap-2">
            <input
              type="checkbox"
              checked={draft.active !== false}
              onChange={(e) => update('active', e.target.checked)}
            />
            Active
          </label>
        </div>

        <div className="flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={save}
            disabled={saving}
            className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50"
          >
            <FiSave /> Save
          </button>
        </div>
      </div>
    </div>
  )
}

const label = 'mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500'
const input =
  'w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]'
