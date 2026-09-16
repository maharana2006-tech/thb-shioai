import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiCheck, FiLock, FiSave } from 'react-icons/fi'
import {
  systemSettingsService,
  type SystemSetting,
  type UspsProviderReadiness,
} from '../api/systemSettingsService'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import UspsProviderReadinessTable from './settings/UspsProviderReadinessTable'

/**
 * Sprint 49 Tier 0 — admin surface for encrypted system secrets.
 *
 * <p>Renders each known setting from the backend registry (currently
 * just {@code openai.api-key}) with its masked preview and a plain
 * text input to rotate the value. Values are encrypted at rest via
 * AES-GCM; the input clears on save and only the masked value is ever
 * re-fetched.
 *
 * <p>USPS_DIRECT (PR-A) additions:
 *  - When the persisted {@code USPS_PROVIDER} is
 *    {@code PROVISIONING_USPS_DIRECT}, render the
 *    {@link UspsProviderReadinessTable} inline under the picker so the
 *    ops team sees the per-tenant checklist without leaving the page.
 *  - When the operator selects {@code USPS_DIRECT} in the picker, the
 *    save button becomes gated: we fetch the readiness DTO on selection
 *    and disable save with a tooltip when {@code overallReady=false}.
 *    Backend applies the same gate (HTTP 409 with the DTO), so this is
 *    UX polish, not the authoritative check.
 */
const USPS_PROVIDER_KEY = 'USPS_PROVIDER'
const USPS_DIRECT_VALUE = 'USPS_DIRECT'
const PROVISIONING_VALUE = 'PROVISIONING_USPS_DIRECT'

/** Whitelist of setting keys whose current values map to USPS provider modes.
 *  Anything else (e.g. the OpenAI API key) is treated as a plain SECRET. */
function isUspsProviderSetting(setting: SystemSetting): boolean {
  return setting.key === USPS_PROVIDER_KEY
}

export default function SystemSettingsPage() {
  const [items, setItems] = useState<SystemSetting[]>([])
  const [loading, setLoading] = useState(true)
  const [inputs, setInputs] = useState<Record<string, string>>({})
  const [savingKey, setSavingKey] = useState<string | null>(null)
  /**
   * USPS readiness cached from the pre-save gate. Refreshed:
   *  - Automatically when the operator picks USPS_DIRECT in the picker.
   *  - Manually via the readiness table's Refresh button (bubbles up
   *    through {@code onLoaded}).
   * Null means "not fetched yet" — the save button is optimistically
   * enabled and any 409 from the backend surfaces via notify.error.
   */
  const [uspsReadiness, setUspsReadiness] =
    useState<UspsProviderReadiness | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await systemSettingsService.list()
      setItems(Array.isArray(res) ? res : [])
    } catch (e) {
      notify.apiError(e, 'Failed to load system settings.')
    } finally {
      setLoading(false)
    }
  }, [])

  // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on mount; load() sets loading + settings state
  useEffect(() => { void load() }, [load])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const uspsProviderSetting = useMemo(
    () => items.find((s) => s.key === USPS_PROVIDER_KEY) ?? null,
    [items],
  )
  const persistedUspsProvider = useMemo(
    () =>
      (uspsProviderSetting?.currentValue
        ?? uspsProviderSetting?.defaultValue
        ?? '') as string,
    [uspsProviderSetting],
  )
  const uspsProviderPickerValue = inputs[USPS_PROVIDER_KEY] ?? ''
  const uspsPickerIsUspsDirect = uspsProviderPickerValue === USPS_DIRECT_VALUE

  /**
   * Fetch the readiness snapshot when the operator picks
   * {@code USPS_DIRECT} but only if the persisted value isn't already
   * {@code USPS_DIRECT} — we don't need to gate a no-op save.
   */
  useEffect(() => {
    if (!uspsPickerIsUspsDirect) return
    if (persistedUspsProvider === USPS_DIRECT_VALUE) return
    let cancelled = false
    systemSettingsService
      .getUspsProviderReadiness()
      .then((r) => {
        if (cancelled) return
        setUspsReadiness(r?.data ?? null)
      })
      .catch(() => {
        // Non-fatal — leave button enabled and let the server-side gate
        // reject if the DTO isn't reachable. The table itself surfaces
        // errors via notify.apiError.
      })
    return () => {
      cancelled = true
    }
  }, [uspsPickerIsUspsDirect, persistedUspsProvider])

  /** Human-readable tooltip listing whichever readiness dimensions failed. */
  const uspsBlockingReason = useMemo<string | null>(() => {
    if (!uspsPickerIsUspsDirect) return null
    if (!uspsReadiness) return null
    if (uspsReadiness.overallReady) return null
    const parts: string[] = []
    if (!uspsReadiness.platformCreds.clientIdSet) parts.push('USPS platform CLIENT_ID')
    if (!uspsReadiness.platformCreds.clientSecretSet) parts.push('USPS platform CLIENT_SECRET')
    if (uspsReadiness.pendingAccounts.length) {
      const tenants = uspsReadiness.pendingAccounts.slice(0, 3).map((r) => r.tenantCode).join(', ')
      const more =
        uspsReadiness.pendingAccounts.length > 3
          ? ` (+${uspsReadiness.pendingAccounts.length - 3} more)`
          : ''
      parts.push(`${uspsReadiness.pendingAccounts.length} USPS account(s) pending: ${tenants}${more}`)
    }
    return parts.length
      ? `Not ready to switch to USPS_DIRECT: ${parts.join('; ')}.`
      : 'Not ready to switch to USPS_DIRECT.'
  }, [uspsPickerIsUspsDirect, uspsReadiness])

  const save = async (key: string) => {
    const value = (inputs[key] ?? '').trim()
    if (!value) {
      notify.error('Enter a value to save.')
      return
    }
    setSavingKey(key)
    try {
      await systemSettingsService.update(key, value)
      notify.success('Setting updated.')
      setInputs((prev) => ({ ...prev, [key]: '' }))
      // Refresh cached readiness after a USPS_PROVIDER save so the
      // readiness table (still mounted while transitioning through
      // PROVISIONING) reflects the new baseline.
      if (key === USPS_PROVIDER_KEY) setUspsReadiness(null)
      void load()
    } catch (e) {
      // Server-side gate authoritative: a 409 on USPS_PROVIDER=USPS_DIRECT
      // includes the readiness DTO. Surface the reason inline so the
      // operator sees the same detail the pre-save tooltip would show.
      if (key === USPS_PROVIDER_KEY && value === USPS_DIRECT_VALUE) {
        const anyErr = e as { status?: number; payload?: { data?: UspsProviderReadiness } | null }
        const dto = anyErr?.payload?.data
        if (anyErr?.status === 409 && dto) {
          setUspsReadiness(dto)
          const pending = dto.pendingAccounts.length
          notify.error(
            pending
              ? `USPS_DIRECT rejected — ${pending} USPS account(s) still missing fields.`
              : 'USPS_DIRECT rejected — USPS platform credentials are not set.',
          )
        } else {
          notify.apiError(e, 'Failed to update the setting.')
        }
      } else {
        notify.apiError(e, 'Failed to update the setting.')
      }
    } finally {
      setSavingKey(null)
    }
  }

  return (
    <div className="space-y-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <FiLock className="h-4 w-4 text-slate-500" />
            System settings
          </h2>
          <p className="mt-1 text-[12.5px] text-slate-500">
            Admin-managed secrets stored encrypted at rest (AES-GCM). Values overlay the
            corresponding environment variables so you can rotate a key without a redeploy.
          </p>
        </div>
      </header>

      {loading ? (
        <div className="rounded-xl border border-slate-200 bg-white p-6 text-[13px] text-slate-500">
          Loading…
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-xl border border-slate-200 bg-white p-6 text-[13px] text-slate-500">
          No settings registered.
        </div>
      ) : (
        <div className="space-y-4">
          {items.map((item) => {
            const inputValue = inputs[item.key] ?? ''
            const isSaving = savingKey === item.key
            const isChoice = item.kind === 'CHOICE' && Array.isArray(item.options) && item.options.length > 0
            const effectiveChoice =
              inputs[item.key] || item.currentValue || item.defaultValue || ''
            const isUspsProvider = isUspsProviderSetting(item)
            const pickedUspsDirect =
              isUspsProvider && uspsProviderPickerValue === USPS_DIRECT_VALUE
            const uspsDirectBlocked =
              pickedUspsDirect
              && !!uspsReadiness
              && uspsReadiness.overallReady === false
            return (
              <section
                key={item.key}
                className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <p className="font-mono text-[13px] font-semibold text-slate-900">
                      {item.key}
                    </p>
                    <p className="mt-0.5 text-[12px] text-slate-500">
                      {item.description}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2 text-[12px]">
                    {isChoice ? (
                      <>
                        <FiCheck className="h-3.5 w-3.5 text-emerald-600" />
                        <span className="font-mono text-slate-700">
                          {item.currentValue ?? item.defaultValue ?? '—'}
                        </span>
                      </>
                    ) : item.hasValue ? (
                      <>
                        <FiCheck className="h-3.5 w-3.5 text-emerald-600" />
                        <span className="font-mono text-slate-600">{item.maskedValue}</span>
                      </>
                    ) : (
                      <span className="text-slate-400">not set</span>
                    )}
                  </div>
                </div>

                {isChoice ? (
                  <div className="mt-3 flex flex-wrap items-center gap-2">
                    <div className="inline-flex rounded-lg border border-slate-300 bg-slate-50 p-0.5">
                      {(item.options ?? []).map((opt) => {
                        const selected = effectiveChoice === opt
                        return (
                          <button
                            key={opt}
                            type="button"
                            onClick={() =>
                              setInputs((prev) => ({ ...prev, [item.key]: opt }))
                            }
                            className={`rounded-md px-3 py-1 text-[12.5px] font-semibold transition ${
                              selected
                                ? 'bg-white text-slate-900 shadow-sm ring-1 ring-slate-200'
                                : 'text-slate-500 hover:text-slate-800'
                            }`}
                          >
                            {opt}
                          </button>
                        )
                      })}
                    </div>
                    <button
                      type="button"
                      onClick={() => void save(item.key)}
                      disabled={
                        isSaving ||
                        !inputValue.trim() ||
                        inputValue.trim() === (item.currentValue ?? '') ||
                        uspsDirectBlocked
                      }
                      title={uspsDirectBlocked ? (uspsBlockingReason ?? undefined) : undefined}
                      aria-describedby={
                        uspsDirectBlocked ? 'usps-direct-blocked-tooltip' : undefined
                      }
                      className="inline-flex items-center gap-1.5 rounded-lg border border-slate-900 bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      {isSaving ? (
                        <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-500 border-t-white" />
                      ) : (
                        <FiSave className="h-3.5 w-3.5" />
                      )}
                      Apply
                    </button>
                    {uspsDirectBlocked && uspsBlockingReason ? (
                      <p
                        id="usps-direct-blocked-tooltip"
                        role="status"
                        className="w-full text-[11.5px] text-rose-700"
                      >
                        {uspsBlockingReason}
                      </p>
                    ) : null}
                  </div>
                ) : (
                  <div className="mt-3 flex gap-2">
                    <input
                      type="password"
                      autoComplete="new-password"
                      placeholder={item.hasValue ? 'Replace stored value' : 'Enter new value'}
                      className="flex-1 rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-[13px] outline-none focus:border-slate-500"
                      value={inputValue}
                      onChange={(e) =>
                        setInputs((prev) => ({ ...prev, [item.key]: e.target.value }))
                      }
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') void save(item.key)
                      }}
                    />
                    <button
                      type="button"
                      onClick={() => void save(item.key)}
                      disabled={isSaving || !inputValue.trim()}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-slate-900 bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      {isSaving ? (
                        <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-500 border-t-white" />
                      ) : (
                        <FiSave className="h-3.5 w-3.5" />
                      )}
                      Save
                    </button>
                  </div>
                )}

                {/* USPS_DIRECT provisioning readiness — rendered inline under
                    the USPS_PROVIDER selector when the persisted value is
                    PROVISIONING_USPS_DIRECT. Shows the per-tenant fill-in
                    checklist so ops can drive it to 0/0 before flipping to
                    USPS_DIRECT. */}
                {isUspsProvider && persistedUspsProvider === PROVISIONING_VALUE ? (
                  <UspsProviderReadinessTable onLoaded={setUspsReadiness} />
                ) : null}
              </section>
            )
          })}
        </div>
      )}

      <p className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-[11.5px] text-amber-800">
        <strong>Deployment prerequisite:</strong> the backend needs the{' '}
        <code className="rounded bg-amber-100 px-1 py-0.5 font-mono text-[11px]">SECRETS_ENCRYPTION_KEY</code>{' '}
        environment variable set to a base64-encoded 32-byte random value. Without it, save
        will fail with a "not configured" error.
      </p>
    </div>
  )
}
