/**
 * Audit X1/X2/X4 — per-tenant NDS prefill fallbacks: default recipient
 * phone (when NDS returns something unusable), default notify email
 * (when OE_SEND_TO is empty), default package weight in lb (when
 * TB_BILLABLE_CONTAINERS.WEIGHT ≤ 0), and the policy for what to do
 * when weight is missing (DEFAULT | BLOCK).
 *
 * <p>Backed by tenant_settings via the generic KV endpoints on
 * TenantSettingsController. Unset keys fall through to platform
 * defaults (`+1 616 772 3513` / `support@thbred.com` / `1.0` /
 * `DEFAULT`) on the backend — leaving a field blank here means
 * "use the platform default".
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { FiSave } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { clientService } from '../../api/clientService'
import type { Client } from '../../api/clientService'
import { tenantSettingsService } from '../../api/tenantSettingsService'

const KEY_PHONE = 'nds.fallback_phone'
const KEY_EMAIL = 'nds.fallback_notify_email'
const KEY_WEIGHT = 'nds.default_weight_lb'
const KEY_POLICY = 'nds.on_missing_weight'

type Policy = 'DEFAULT' | 'BLOCK'

interface Values {
  phone: string
  email: string
  weight: string
  policy: Policy
}

const EMPTY: Values = { phone: '', email: '', weight: '', policy: 'DEFAULT' }

const STORAGE_KEY = 'multiship_nds_fallbacks_tenant'

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

export default function NdsFallbacksSection() {
  const [clients, setClients] = useState<Client[]>([])
  const [tenantCode, setTenantCode] = useState<string>(() => readLastTenant())
  const [stored, setStored] = useState<Values>(EMPTY)
  const [draft, setDraft] = useState<Values>(EMPTY)
  const [loading, setLoading] = useState<boolean>(false)
  const [saving, setSaving] = useState<boolean>(false)

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const res = await clientService.listClients({ page: 0, size: 100, sortBy: 'code' })
        if (cancelled) return
        setClients(res.data?.content ?? [])
      } catch (e) {
        if (!cancelled) notify.apiError(e, 'Failed to load clients for the tenant picker.')
      }
    })()
    return () => { cancelled = true }
  }, [])

  const load = useCallback(async (code: string) => {
    if (!code) { setStored(EMPTY); setDraft(EMPTY); return }
    setLoading(true)
    try {
      const [phone, email, weight, policyRaw] = await Promise.all([
        tenantSettingsService.getKv(code, KEY_PHONE),
        tenantSettingsService.getKv(code, KEY_EMAIL),
        tenantSettingsService.getKv(code, KEY_WEIGHT),
        tenantSettingsService.getKv(code, KEY_POLICY),
      ])
      const policy: Policy = policyRaw === 'BLOCK' ? 'BLOCK' : 'DEFAULT'
      const values: Values = {
        phone: phone ?? '',
        email: email ?? '',
        weight: weight ?? '',
        policy,
      }
      setStored(values)
      setDraft(values)
    } catch (e) {
      notify.apiError(e, 'Failed to load NDS fallbacks for this tenant.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load(tenantCode) }, [load, tenantCode])

  const dirty = useMemo(() => (
    draft.phone !== stored.phone
    || draft.email !== stored.email
    || draft.weight !== stored.weight
    || draft.policy !== stored.policy
  ), [draft, stored])

  const save = async () => {
    if (!tenantCode || !dirty) return
    setSaving(true)
    try {
      const jobs: Promise<unknown>[] = []
      if (draft.phone !== stored.phone) jobs.push(tenantSettingsService.putKv(tenantCode, KEY_PHONE, draft.phone.trim()))
      if (draft.email !== stored.email) jobs.push(tenantSettingsService.putKv(tenantCode, KEY_EMAIL, draft.email.trim()))
      if (draft.weight !== stored.weight) jobs.push(tenantSettingsService.putKv(tenantCode, KEY_WEIGHT, draft.weight.trim()))
      if (draft.policy !== stored.policy) jobs.push(tenantSettingsService.putKv(tenantCode, KEY_POLICY, draft.policy))
      await Promise.all(jobs)
      setStored(draft)
      notify.success('NDS fallbacks saved.')
    } catch (e) {
      notify.apiError(e, 'Failed to save NDS fallbacks.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
      <header className="mb-3">
        <h3 className="text-[14px] font-semibold text-slate-900">NDS fallbacks</h3>
        <p className="mt-0.5 text-[12px] text-slate-500">
          Per-tenant defaults for the .X / .Y scan prefill. Blank = use the platform default.
        </p>
      </header>

      <div className="mb-3">
        <label className="block text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">Tenant</label>
        <select
          value={tenantCode}
          onChange={(e) => { const v = e.target.value; setTenantCode(v); writeLastTenant(v) }}
          className="mt-1 w-full rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-[13px]"
        >
          <option value="">— pick a tenant —</option>
          {clients.map(c => (
            <option key={c.clientCode} value={c.clientCode}>{c.clientCode}{c.name ? ` — ${c.name}` : ''}</option>
          ))}
        </select>
      </div>

      {tenantCode ? (
        <div className={loading ? 'opacity-60 pointer-events-none space-y-3' : 'space-y-3'}>
          <Field
            label="Default recipient phone"
            hint={`Platform default: +1 616 772 3513`}
            value={draft.phone}
            onChange={(v) => setDraft(d => ({ ...d, phone: v }))}
            placeholder="+1 555 123 4567"
          />
          <Field
            label="Default notify email"
            hint={`Platform default: support@thbred.com`}
            value={draft.email}
            onChange={(v) => setDraft(d => ({ ...d, email: v }))}
            placeholder="ops@yourcompany.com"
            type="email"
          />
          <Field
            label="Default package weight (lb) when NDS is missing"
            hint={`Platform default: 1.0`}
            value={draft.weight}
            onChange={(v) => setDraft(d => ({ ...d, weight: v }))}
            placeholder="1.0"
            type="number"
          />
          <div>
            <label className="block text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
              When weight is missing
            </label>
            <div className="mt-1 inline-flex rounded-lg border border-slate-300 bg-slate-50 p-0.5">
              {(['DEFAULT', 'BLOCK'] as Policy[]).map(p => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setDraft(d => ({ ...d, policy: p }))}
                  className={`rounded-md px-3 py-1 text-[12.5px] font-semibold transition ${
                    draft.policy === p
                      ? 'bg-white text-slate-900 shadow-sm ring-1 ring-slate-200'
                      : 'text-slate-500 hover:text-slate-800'
                  }`}
                >
                  {p === 'DEFAULT' ? 'Use default (warn)' : 'Block the order'}
                </button>
              ))}
            </div>
          </div>

          <div className="pt-1">
            <button
              type="button"
              onClick={() => void save()}
              disabled={!dirty || saving || loading}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-900 bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {saving
                ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-500 border-t-white" />
                : <FiSave className="h-3.5 w-3.5" />}
              Save
            </button>
          </div>
        </div>
      ) : (
        <p className="text-[12.5px] text-slate-500">Pick a tenant to view its NDS fallbacks.</p>
      )}
    </section>
  )
}

interface FieldProps {
  label: string
  hint?: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: string
}

function Field({ label, hint, value, onChange, placeholder, type = 'text' }: FieldProps) {
  return (
    <div>
      <label className="block text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="mt-1 w-full rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-[13px] outline-none focus:border-slate-500"
      />
      {hint ? <p className="mt-0.5 text-[11px] text-slate-500">{hint}</p> : null}
    </div>
  )
}
