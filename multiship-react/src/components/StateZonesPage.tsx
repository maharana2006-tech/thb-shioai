/**
 * G2 — /settings/state-zones. Admin surface over the state_zone table
 * that drives DST-aware offsets for carrier timestamps. One row per
 * (country, state) → IANA zone. Writes trigger a server-side cache
 * reload so FedEx pickups pick up the change without a restart.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useModalDismiss } from '../hooks/useModalDismiss'
import { useOutletContext } from 'react-router-dom'
import { FiEdit3, FiPlus, FiTrash2 } from 'react-icons/fi'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import { serverFieldError } from '../utils/serverFieldErrors'
import { stateZoneService, type StateZone } from '../api/stateZoneService'

type EditorState =
  | { mode: 'closed' }
  | { mode: 'add' }
  | { mode: 'edit'; row: StateZone }

export default function StateZonesPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<StateZone[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [editor, setEditor] = useState<EditorState>({ mode: 'closed' })

  const load = useCallback(async () => {
    setLoading(true)
    try { setRows(await stateZoneService.list()) }
    catch (err) { notify.apiError(err, 'Failed to load state zones.') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return rows
    return rows.filter((r) =>
      r.countryCode.toLowerCase().includes(q) ||
      r.stateCode.toLowerCase().includes(q) ||
      r.zoneId.toLowerCase().includes(q) ||
      (r.displayName ?? '').toLowerCase().includes(q),
    )
  }, [rows, query])

  const onDelete = async (row: StateZone) => {
    const ok = await notify.confirm(
      `Delete ${row.countryCode}/${row.stateCode} → ${row.zoneId}?\n\n`
        + 'This row no longer drives offsets for carrier timestamps. '
        + 'Pickups from this state fall back to naive local wall-clock.',
      { danger: true, confirmLabel: 'Delete', cancelLabel: 'Keep' },
    )
    if (!ok) return
    try {
      await stateZoneService.remove(row.countryCode, row.stateCode)
      notify.success(`Deleted ${row.countryCode}/${row.stateCode}.`)
      void load()
    } catch (err) {
      notify.apiError(err, 'Delete failed.')
    }
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 px-4 py-3">
        <div>
          <h2 className="text-[14px] font-semibold text-slate-800">State → zone</h2>
          <p className="mt-0.5 text-[12px] text-slate-500">
            Country + state → IANA zone (<code className="text-[11px]">America/New_York</code>, …).
            Carriers that need DST-aware offsets on timestamps read from here;
            an unmapped shipper falls back to naive-local wall-clock.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Filter…"
            className="w-48 rounded-lg border border-slate-300 px-3 py-1.5 text-[12.5px] outline-none focus:border-slate-500"
          />
          <button
            type="button"
            onClick={() => setEditor({ mode: 'add' })}
            className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add row
          </button>
        </div>
      </header>

      {loading ? (
        <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
      ) : filtered.length === 0 ? (
        <div className="px-4 py-6 text-center text-[13px] text-slate-500">
          {rows.length === 0 ? 'No rows seeded.' : 'Nothing matches the filter.'}
        </div>
      ) : (
        <table className="w-full text-[13px]">
          <thead>
            <tr className="text-left text-slate-500">
              <th className="px-3 py-2 font-semibold">Country</th>
              <th className="px-3 py-2 font-semibold">State</th>
              <th className="px-3 py-2 font-semibold">Zone id</th>
              <th className="px-3 py-2 font-semibold">Display name</th>
              <th className="px-3 py-2 text-right font-semibold">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((row) => (
              <tr key={`${row.countryCode}:${row.stateCode}`} className="border-t border-slate-100">
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.countryCode}</td>
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.stateCode}</td>
                <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.zoneId}</td>
                <td className="px-3 py-2 text-slate-700">{row.displayName ?? '—'}</td>
                <td className="px-3 py-2 text-right">
                  <div className="inline-flex items-center gap-1.5">
                    <button
                      type="button"
                      onClick={() => setEditor({ mode: 'edit', row })}
                      aria-label={`Edit ${row.stateCode}`}
                      className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2 py-1 text-[11px] font-semibold text-slate-600 hover:bg-slate-50"
                    >
                      <FiEdit3 className="h-3 w-3" /> Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => void onDelete(row)}
                      aria-label={`Delete ${row.stateCode}`}
                      className="inline-flex items-center gap-1 rounded-md border border-rose-200 bg-white px-2 py-1 text-[11px] font-semibold text-rose-700 hover:bg-rose-50"
                    >
                      <FiTrash2 className="h-3 w-3" /> Delete
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {editor.mode !== 'closed' ? (
        <StateZoneEditor
          initial={editor.mode === 'edit' ? editor.row : null}
          onClose={() => setEditor({ mode: 'closed' })}
          onSaved={() => { setEditor({ mode: 'closed' }); void load() }}
        />
      ) : null}
    </section>
  )
}

/** Add-or-edit modal. Country+state are read-only on edit (they're the PK). */
function StateZoneEditor({
  initial, onClose, onSaved,
}: {
  initial: StateZone | null
  onClose: () => void
  onSaved: () => void
}) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const requestClose = useModalDismiss(true, dialogRef, onClose)
  const isEdit = initial !== null
  const [countryCode, setCountryCode] = useState(initial?.countryCode ?? 'US')
  const [stateCode, setStateCode] = useState(initial?.stateCode ?? '')
  const [zoneId, setZoneId] = useState(initial?.zoneId ?? '')
  const [displayName, setDisplayName] = useState(initial?.displayName ?? '')
  const [saving, setSaving] = useState(false)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const clearErr = (k: string) => setFieldErrors((cur) => (cur[k] ? {} : cur))
  const fieldErr = (k: string) => fieldErrors[k]
    ? <p role="alert" className="mt-1 text-[11.5px] font-normal text-rose-700">{fieldErrors[k]}</p>
    : null
  const firstInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => { firstInputRef.current?.focus() }, [])

  const canSave =
    countryCode.trim().length === 2 &&
    stateCode.trim().length > 0 &&
    zoneId.trim().length > 0 &&
    !saving

  const save = async () => {
    if (!canSave) return
    setSaving(true)
    try {
      await stateZoneService.upsert(
        countryCode.trim().toUpperCase(),
        stateCode.trim().toUpperCase(),
        { zoneId: zoneId.trim(), displayName: displayName.trim() || null },
      )
      notify.success(`${isEdit ? 'Updated' : 'Added'} ${countryCode.trim().toUpperCase()}/${stateCode.trim().toUpperCase()}.`)
      onSaved()
    } catch (err) {
      const fe = serverFieldError(err)
      if (fe) setFieldErrors({ [['countryCode', 'stateCode', 'zoneId', 'displayName'].includes(fe.field) ? fe.field : '_form']: fe.message })
      else notify.apiError(err, 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div ref={dialogRef}
      role="dialog"
      aria-modal="true"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="w-full max-w-md rounded-xl bg-white p-5 shadow-xl">
        <h3 className="text-[14px] font-semibold text-slate-800">
          {isEdit ? `Edit ${initial.countryCode}/${initial.stateCode}` : 'Add state zone'}
        </h3>
        <p className="mt-0.5 text-[12px] text-slate-500">
          Zone id must be a valid IANA zone (e.g. <code>America/New_York</code>).
          Server validates on save.
        </p>
        {fieldErr('_form')}
        <div className="mt-4 grid grid-cols-2 gap-3">
          <label className="text-[11px] font-semibold text-slate-700">
            Country code
            <input
              ref={firstInputRef}
              value={countryCode}
              onChange={(e) => { setCountryCode(e.target.value.toUpperCase()); clearErr('countryCode') }}
              maxLength={2}
              readOnly={isEdit}
              aria-invalid={fieldErrors.countryCode ? true : undefined}
              className={`mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-mono text-[13px] uppercase ${isEdit ? 'bg-slate-50 text-slate-500' : ''}`}
            />
            {fieldErr('countryCode')}
          </label>
          <label className="text-[11px] font-semibold text-slate-700">
            State code
            <input
              value={stateCode}
              onChange={(e) => { setStateCode(e.target.value.toUpperCase()); clearErr('stateCode') }}
              maxLength={10}
              readOnly={isEdit}
              aria-invalid={fieldErrors.stateCode ? true : undefined}
              className={`mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-mono text-[13px] uppercase ${isEdit ? 'bg-slate-50 text-slate-500' : ''}`}
            />
            {fieldErr('stateCode')}
          </label>
        </div>
        <label className="mt-3 block text-[11px] font-semibold text-slate-700">
          IANA zone id
          <input
            value={zoneId}
            onChange={(e) => { setZoneId(e.target.value); clearErr('zoneId') }}
            placeholder="America/New_York"
            aria-invalid={fieldErrors.zoneId ? true : undefined}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 font-mono text-[13px]"
          />
          {fieldErr('zoneId')}
        </label>
        <label className="mt-3 block text-[11px] font-semibold text-slate-700">
          Display name (optional)
          <input
            value={displayName}
            onChange={(e) => { setDisplayName(e.target.value); clearErr('displayName') }}
            placeholder="Michigan"
            aria-invalid={fieldErrors.displayName ? true : undefined}
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-[13px]"
          />
          {fieldErr('displayName')}
        </label>
        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={() => void requestClose()}
            className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={!canSave}
            className={`rounded-xl px-5 py-2 text-[13px] font-semibold transition ${
              canSave ? 'bg-[#1f150c] text-white hover:bg-[#412d15]' : 'cursor-not-allowed bg-slate-200 text-slate-500'
            }`}
          >
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Add row'}
          </button>
        </div>
      </div>
    </div>
  )
}
