import { useEffect, useState } from 'react'
import { FiInfo, FiTag } from 'react-icons/fi'
import {
  customFieldService,
  type CustomFieldDefinition,
} from '../../api/customFieldService'
import Select from '../workspace/Select'

/**
 * Sprint 43 — dynamic custom-fields panel. Loads the tenant's
 * applicable definitions and renders type-aware inputs; parent owns
 * the value state and submit path.
 *
 * A blank tenantId still shows platform-wide fields (definitions with
 * tenantId=null); when both are missing, the panel renders nothing so
 * the form doesn't sprout an empty section.
 */
export default function CustomFieldsSection({
  tenantId,
  values,
  onChange,
  compact = false,
}: {
  tenantId: string | null | undefined
  values: Record<string, string>
  onChange: (values: Record<string, string>) => void
  compact?: boolean
}) {
  const [defs, setDefs] = useState<CustomFieldDefinition[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    customFieldService
      .applicable(tenantId ?? undefined)
      .then((d) => {
        if (!cancelled) setDefs(d)
      })
      .catch(() => setDefs([]))
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [tenantId])

  if (!loading && defs.length === 0) return null

  const setValue = (key: string, v: string) => {
    const next = { ...values }
    if (v.trim()) next[key] = v
    else delete next[key]
    onChange(next)
  }

  return (
    <div
      className={
        compact
          ? 'rounded-xl border border-[#e3d9c4] bg-white p-4'
          : 'rounded-2xl border border-[#e3d9c4] bg-white p-5 shadow-sm'
      }
    >
      <div className="mb-3 flex items-start justify-between gap-3">
        <div className="flex items-center gap-2">
          <FiTag className="text-[#8a7959]" />
          <h3 className="text-[13px] font-semibold text-[#1f150c]">Custom fields</h3>
        </div>
        <span className="inline-flex items-center gap-1 text-[11px] text-[#8a7959]">
          <FiInfo /> Tenant-defined metadata
        </span>
      </div>

      {loading ? (
        <p className="text-[12px] text-[#8a7959]">Loading fields…</p>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {defs.map((d) => (
            <FieldInput
              key={d.fieldKey}
              def={d}
              value={values[d.fieldKey] ?? ''}
              onChange={(v) => setValue(d.fieldKey, v)}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function FieldInput({
  def,
  value,
  onChange,
}: {
  def: CustomFieldDefinition
  value: string
  onChange: (v: string) => void
}) {
  const options =
    def.fieldType === 'SELECT'
      ? (def.selectOptions ?? '')
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean)
      : []

  return (
    <div>
      <label className="mb-1 flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
        {def.label}
        {def.required ? <span className="text-rose-500">*</span> : null}
      </label>
      {def.fieldType === 'SELECT' ? (
        <Select value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">— select —</option>
          {options.map((o) => (
            <option key={o} value={o}>
              {o}
            </option>
          ))}
        </Select>
      ) : (
        <input
          type={def.fieldType === 'NUMBER' ? 'number' : def.fieldType === 'DATE' ? 'date' : 'text'}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]"
        />
      )}
    </div>
  )
}
