/**
 * C1 — per-tenant ship-from defaults. Backend fallback chain is:
 *   request sender → client warehouse → tenant_settings.shipper.* → platform default
 * This card writes the tenant_settings.shipper.* layer.
 *
 * Blank field = "no tenant override", falls through to the platform
 * value shown as placeholder. Same pattern as NdsFallbacksSection.
 */
import { useCallback, useEffect, useState } from 'react'
import { FiSave } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { clientService, type Client } from '../../api/clientService'
import {
  tenantSettingsService,
  SHIPPER_KEYS,
  SHIPPER_KEY_LABEL,
  type ShipperDefaultField,
  type ShipperKey,
} from '../../api/tenantSettingsService'

type FieldMap = Record<string, ShipperDefaultField>
const EMPTY_MAP: FieldMap = {}

const STORAGE_KEY = 'multiship_shipper_defaults_tenant'

function readLastTenant(): string {
  if (typeof window === 'undefined') return ''
  try { return window.localStorage.getItem(STORAGE_KEY) ?? '' }
  catch { return '' }
}
function writeLastTenant(value: string): void {
  if (typeof window === 'undefined') return
  try {
    if (value.trim()) window.localStorage.setItem(STORAGE_KEY, value)
    else window.localStorage.removeItem(STORAGE_KEY)
  } catch { /* silent */ }
}

export default function ShipperDefaultsSection() {
  const [clients, setClients] = useState<Client[]>([])
  const [tenantCode, setTenantCode] = useState<string>(() => readLastTenant())
  const [stored, setStored] = useState<FieldMap>(EMPTY_MAP)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState<boolean>(false)
  const [saving, setSaving] = useState<boolean>(false)

  // Tenant picker source.
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        // BUG-147 R7 — the tenant picker was showing deleted/inactive
        // tenants, so an operator could pick a defunct one and write
        // shipper defaults into a dead row (never resolved by any
        // active shipment). Hard-filter to ACTIVE here; the status
        // param narrows server-side so the page=100 bucket isn't
        // wasted on tombstones either.
        const res = await clientService.listClients({ page: 0, size: 100, sortBy: 'code', status: 'ACTIVE' })
        if (cancelled) return
        setClients((res.data?.content ?? []).filter((c) => c.status === 'ACTIVE'))
      } catch (e) {
        if (!cancelled) notify.apiError(e, 'Failed to load clients for the tenant picker.')
      }
    })()
    return () => { cancelled = true }
  }, [])

  const load = useCallback(async (code: string) => {
    if (!code) { setStored(EMPTY_MAP); setDraft({}); return }
    setLoading(true)
    try {
      const map = await tenantSettingsService.getShipperDefaults(code)
      setStored(map)
      const draftInit: Record<string, string> = {}
      for (const key of SHIPPER_KEYS) {
        const f = map[key]
        // Only pre-fill the input with the tenant override; a "platform
        // default" state should show as blank + placeholder so the
        // operator sees at a glance what's tenant-specific.
        draftInit[key] = f?.isTenantOverride ? (f.resolved ?? '') : ''
      }
      setDraft(draftInit)
    } catch (e) {
      notify.apiError(e, 'Failed to load shipper defaults.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load(tenantCode) }, [load, tenantCode])

  const onTenantChange = (v: string) => {
    setTenantCode(v)
    writeLastTenant(v)
  }

  const setField = (key: ShipperKey, value: string) =>
    setDraft((d) => ({ ...d, [key]: value }))

  const save = async () => {
    if (!tenantCode.trim()) {
      notify.error('Pick a tenant first.')
      return
    }
    setSaving(true)
    try {
      const payload: Record<string, string> = {}
      for (const key of SHIPPER_KEYS) {
        payload[key] = (draft[key] ?? '').trim()
      }
      const next = await tenantSettingsService.putShipperDefaults(tenantCode, payload)
      setStored(next)
      notify.success('Ship-from defaults saved.')
    } catch (e) {
      notify.apiError(e, 'Failed to save shipper defaults.')
    } finally {
      setSaving(false)
    }
  }

  const anyOverride = SHIPPER_KEYS.some((k) => stored[k]?.isTenantOverride)
  // BUG-001 F-005: the chip used to show "Using platform default"
  // regardless of whether the platform actually had defaults. After
  // 7a906fbc blanked carrier.shipper.* and flipped strict-mode on,
  // tier 4 is empty — the chip's statement is a lie. Three-state:
  // "Tenant configured" | "Using platform default" (only when the
  // platform really has values) | "No platform default configured"
  // (amber, warns admins strict-mode will reject this tenant).
  const anyPlatformValue = SHIPPER_KEYS.some(
    (k) => (stored[k]?.platformValue ?? '').trim() !== '',
  )
  const chipLabel = anyOverride
    ? 'Tenant configured'
    : anyPlatformValue ? 'Using platform default' : 'No platform default configured'
  const chipClass = anyOverride
    ? 'bg-emerald-100 text-emerald-800'
    : anyPlatformValue ? 'bg-slate-100 text-slate-600' : 'bg-amber-100 text-amber-800'

  return (
    <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
      <header className="border-b border-slate-200 px-4 py-3">
        <div className="flex items-center justify-between gap-3">
          <div>
            <h2 className="text-[14px] font-semibold text-slate-800">Ship-from defaults</h2>
            <p className="mt-0.5 text-[12px] text-slate-500">
              Fallback shipper stamped on labels when the request has no sender and no warehouse
              resolves. {anyPlatformValue
                ? 'Blank field ⇒ uses the platform default (shown as placeholder).'
                : 'No platform default is configured — tenants without an override here will be rejected by strict-mode at ship time.'}
            </p>
          </div>
          <span className={`rounded px-2 py-0.5 text-[11px] font-semibold ${chipClass}`}>
            {chipLabel}
          </span>
        </div>
      </header>

      <div className="grid grid-cols-1 gap-4 px-4 py-4 md:grid-cols-[16rem_1fr]">
        {/* Tenant picker */}
        <div>
          <label className="text-[12px] font-semibold text-slate-700">
            Tenant
            <select
              value={tenantCode}
              onChange={(e) => onTenantChange(e.target.value)}
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-[13px]"
            >
              <option value="">— pick a client —</option>
              {clients.map((c) => (
                <option key={c.clientCode} value={c.clientCode}>
                  {c.clientCode} — {c.name}
                </option>
              ))}
            </select>
          </label>
        </div>

        {/* Form */}
        <div className="space-y-2">
          {loading ? (
            <div className="text-[12px] text-slate-500">Loading…</div>
          ) : !tenantCode.trim() ? (
            <div className="text-[12px] text-slate-500">Pick a tenant to view or edit their ship-from defaults.</div>
          ) : (
            <>
              {SHIPPER_KEYS.map((key) => {
                const field = stored[key]
                const placeholder = field?.platformValue ?? ''
                return (
                  <label
                    key={key}
                    className="grid grid-cols-[10rem_1fr] items-center gap-3 text-[12px] font-semibold text-slate-700"
                  >
                    <span>
                      {SHIPPER_KEY_LABEL[key]}
                      {field?.isTenantOverride && (
                        <span className="ml-1 rounded bg-emerald-100 px-1 text-[10px] text-emerald-800">
                          override
                        </span>
                      )}
                    </span>
                    <input
                      value={draft[key] ?? ''}
                      onChange={(e) => setField(key, e.target.value)}
                      placeholder={placeholder ? `default: ${placeholder}` : ''}
                      className="rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]"
                    />
                  </label>
                )
              })}
              <div className="flex justify-end pt-1">
                <button
                  type="button"
                  onClick={() => void save()}
                  disabled={saving}
                  className="inline-flex items-center gap-1 rounded-lg bg-slate-900 px-4 py-2 text-[12px] font-semibold text-white hover:bg-slate-700 disabled:opacity-60"
                >
                  <FiSave className="h-3.5 w-3.5" />
                  {saving ? 'Saving…' : 'Save shipper defaults'}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </section>
  )
}
