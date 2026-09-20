/**
 * Slice-3 FE card for the tenant channel gate. Rendered on
 * {@code /settings/system} under the existing SystemSettingsPage
 * shell. Admin picks a tenant, picks D2C / B2B / Both from a 3-way
 * segmented control, hits Save — backend PUT persists to the
 * {@code tenant_settings} table (slice-1 PR #725) which the intake
 * guard (slice-2 PR #726) then honors.
 *
 * <p>UI patterns:
 *  - "Field with saved-source default" from project_sprint52_new_shipment_ux.md:
 *    the current stored value is highlighted; Save is only enabled when
 *    the picker differs from stored.
 *  - Unconfigured warning banner when {@code isConfigured=false} — a
 *    tenant in this state is currently REJECTING every external-API +
 *    WMS-pull request with 403 TENANT_CHANNEL_NOT_ENABLED (force-picking).
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { FiAlertTriangle, FiSave } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { clientService } from '../../api/clientService'
import type { Client } from '../../api/clientService'
import {
  tenantSettingsService,
  type ShippingChannel,
} from '../../api/tenantSettingsService'

type ChannelChoice = 'D2C' | 'B2B' | 'BOTH'

/** Serialise the segmented-control choice back to the wire array. */
function choiceToChannels(choice: ChannelChoice): ShippingChannel[] {
  if (choice === 'D2C') return ['D2C']
  if (choice === 'B2B') return ['B2B']
  return ['B2B', 'D2C']
}

/** Turn the stored (alphabetically-sorted) array back into a choice. */
function channelsToChoice(channels: ShippingChannel[]): ChannelChoice | null {
  if (channels.length === 0) return null
  const set = new Set(channels)
  if (set.has('D2C') && set.has('B2B')) return 'BOTH'
  if (set.has('D2C')) return 'D2C'
  if (set.has('B2B')) return 'B2B'
  return null
}

const STORAGE_KEY = 'multiship_system_channels_tenant'

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
  } catch { /* private mode / disabled storage — silently drop */ }
}

export default function SystemChannelSection() {
  const [clients, setClients] = useState<Client[]>([])
  const [tenantCode, setTenantCode] = useState<string>(() => readLastTenant())
  const [stored, setStored] = useState<ChannelChoice | null>(null)
  const [isConfigured, setIsConfigured] = useState<boolean>(false)
  const [picker, setPicker] = useState<ChannelChoice>('BOTH')
  const [loading, setLoading] = useState<boolean>(false)
  const [saving, setSaving] = useState<boolean>(false)

  // ─────────────────────── client list load once ──────────────────
  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        // First page is fine for the picker; a tenant with 100+ clients
        // is rare and the operator can type-search on the browser side.
        const res = await clientService.listClients({ page: 0, size: 100, sortBy: 'code' })
        if (cancelled) return
        setClients(res.data?.content ?? [])
      } catch (e) {
        if (!cancelled) notify.apiError(e, 'Could not load the client list.')
      }
    })()
    return () => { cancelled = true }
  }, [])

  // ─────────────────────── tenant setting fetch ───────────────────
  const load = useCallback(async (code: string) => {
    if (!code.trim()) {
      setStored(null)
      setIsConfigured(false)
      setPicker('BOTH')
      return
    }
    setLoading(true)
    try {
      const dto = await tenantSettingsService.getEnabledChannels(code)
      const choice = channelsToChoice(dto?.enabledChannels ?? [])
      setStored(choice)
      setIsConfigured(Boolean(dto?.isConfigured))
      // Picker seeds from stored (or falls back to BOTH so the operator
      // can Save immediately without picking twice).
      setPicker(choice ?? 'BOTH')
    } catch (e) {
      notify.apiError(e, 'Could not load channel settings for this tenant.')
    } finally {
      setLoading(false)
    }
  }, [])

  // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on tenant change; load() sets stored/isConfigured/picker state
  useEffect(() => { void load(tenantCode) }, [tenantCode, load])

  const dirty = useMemo(() => stored !== picker, [stored, picker])

  const onTenantChange = (next: string) => {
    setTenantCode(next)
    writeLastTenant(next)
  }

  const onSave = async () => {
    if (!tenantCode.trim() || !dirty) return
    setSaving(true)
    try {
      const dto = await tenantSettingsService.setEnabledChannels(
        tenantCode, choiceToChannels(picker),
      )
      const choice = channelsToChoice(dto?.enabledChannels ?? [])
      setStored(choice)
      setIsConfigured(Boolean(dto?.isConfigured))
      if (choice) setPicker(choice)
      notify.success(`Channels saved for ${tenantCode.toUpperCase()}.`)
    } catch (e) {
      notify.apiError(e, 'Could not save channel settings.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <header className="mb-3">
        <h3 className="text-[15px] font-semibold text-slate-950">Order channels</h3>
        <p className="mt-0.5 text-[12.5px] text-slate-500">
          Which order types the tenant accepts on the external API + WMS pull.
          Unconfigured tenants reject all intake with 403
          <span className="ml-1 rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[10.5px] font-semibold text-slate-700">
            TENANT_CHANNEL_NOT_ENABLED
          </span>
          .
        </p>
      </header>

      {/* Tenant picker */}
      <label className="block">
        <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">
          Tenant
        </span>
        <select
          value={tenantCode}
          onChange={(e) => onTenantChange(e.target.value)}
          className="w-full max-w-md rounded-lg border border-slate-300 bg-white px-3 py-2 text-[13px] text-slate-900 outline-none focus:border-slate-400 focus:ring-2 focus:ring-slate-200"
        >
          <option value="">— pick a tenant —</option>
          {clients.map((c) => (
            <option key={c.clientCode} value={c.clientCode}>
              {c.clientCode}{c.name ? ` — ${c.name}` : ''}
            </option>
          ))}
        </select>
      </label>

      {/* Unconfigured warning */}
      {tenantCode.trim() && !loading && !isConfigured ? (
        <div className="mt-3 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2">
          <FiAlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden />
          <div className="text-[12.5px] text-amber-900">
            <p className="font-semibold">
              This tenant hasn't picked its order channels yet.
            </p>
            <p className="mt-0.5">
              Until you Save a choice below, every external API POST + WMS pull for{' '}
              <span className="font-mono font-semibold">{tenantCode.toUpperCase()}</span>{' '}
              returns 403 TENANT_CHANNEL_NOT_ENABLED.
            </p>
          </div>
        </div>
      ) : null}

      {/* 3-way segmented control */}
      {tenantCode.trim() ? (
        <div className="mt-4">
          <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">
            Enabled channels
          </span>
          <div
            role="radiogroup"
            aria-label="Enabled shipping channels"
            className="inline-flex overflow-hidden rounded-lg border border-slate-300"
          >
            {(
              [
                { value: 'D2C' as const, label: 'D2C only', hint: 'Consumer orders' },
                { value: 'B2B' as const, label: 'B2B only', hint: 'Business orders' },
                { value: 'BOTH' as const, label: 'Both', hint: 'D2C + B2B' },
              ]
            ).map((opt, idx) => {
              const active = picker === opt.value
              const isStored = stored === opt.value
              return (
                <button
                  key={opt.value}
                  type="button"
                  role="radio"
                  aria-checked={active}
                  disabled={loading || saving}
                  onClick={() => setPicker(opt.value)}
                  className={[
                    'inline-flex flex-col items-center px-4 py-2 text-[13px] font-semibold transition',
                    idx > 0 ? 'border-l border-slate-300' : '',
                    active
                      ? 'bg-slate-900 text-white'
                      : 'bg-white text-slate-700 hover:bg-slate-50',
                  ].join(' ')}
                >
                  <span className="flex items-center gap-1.5">
                    {opt.label}
                    {isStored ? (
                      <span
                        title="Currently saved for this tenant"
                        className={[
                          'rounded-full px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.06em]',
                          active
                            ? 'bg-white/20 text-white'
                            : 'bg-emerald-100 text-emerald-700',
                        ].join(' ')}
                      >
                        SAVED
                      </span>
                    ) : null}
                  </span>
                  <span
                    className={[
                      'mt-0.5 text-[10.5px] font-normal normal-case tracking-normal',
                      active ? 'text-white/70' : 'text-slate-400',
                    ].join(' ')}
                  >
                    {opt.hint}
                  </span>
                </button>
              )
            })}
          </div>
        </div>
      ) : null}

      {/* Save */}
      {tenantCode.trim() ? (
        <div className="mt-4 flex items-center gap-2">
          <button
            type="button"
            onClick={() => void onSave()}
            disabled={!dirty || saving || loading}
            className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
          >
            <FiSave className="h-3.5 w-3.5" aria-hidden />
            {saving ? 'Saving…' : 'Save'}
          </button>
          {!dirty && stored ? (
            <span className="text-[11.5px] text-slate-500">
              No changes to save.
            </span>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}
