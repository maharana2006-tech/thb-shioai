/**
 * F5-B2 — Mapping step in create mode (staged-draft list). Rules
 * commit to shippingConfigService.saveRule after the client is
 * persisted. Only the minimum fields (order ship-via + carrier
 * service) are captured here; the post-create Mapping tab is where
 * destinations / warehouses / packages get layered on top.
 *
 * <p>Extracted from ClientEditorPage. Reads staged CarrierDraftStep
 * output via the {@code carrierDrafts} prop so the "allowed carriers"
 * set includes the client's not-yet-persisted accounts.
 */
import { useMemo, useState } from 'react'
import { FiPlus } from 'react-icons/fi'
import type { CarrierAccountRef } from '../../api/accountRefService'
import type { ShippingServiceItem } from '../../api/shippingConfigService'
import { formatCarrierName } from '../../utils/carrierUtils'
import { FIELD_LIMITS, validateCode } from '../../utils/clientValidation'
import { computeAllowedCarriers, platformAccounts as platformAccountsFrom } from '../../utils/allowedCarriers'
import Select from '../workspace/Select'
import { inputBaseClass, inputErr, inputOk } from './_shared'
import type { CarrierAccountDraft, MappingRuleDraft } from './_types'

export interface MappingDraftStepProps {
  drafts: MappingRuleDraft[]
  services: ShippingServiceItem[]
  addDraft: (d: Omit<MappingRuleDraft, 'id'>) => void
  removeDraft: (id: number) => void
  /** Staged carrier accounts from the Carriers step. In create mode nothing
   *  is persisted yet, so this is the source for "the client's carriers". */
  carrierDrafts: CarrierAccountDraft[]
  /** All carrier accounts fetched from the server — used to surface the
   *  platform-account picker (client-owned entries here are irrelevant in
   *  create mode; only platform rows are used). */
  accounts: CarrierAccountRef[]
}

export function MappingDraftStep({
  drafts,
  services,
  addDraft,
  removeDraft,
  carrierDrafts,
  accounts,
}: MappingDraftStepProps) {
  const [adding, setAdding] = useState(false)
  const [shipviaCd, setShipviaCd] = useState('')
  const [serviceId, setServiceId] = useState('')
  /** Optional platform account picked to seed / extend the allowed carrier
   *  set — same UX as ClientShippingMappingTab. */
  const [platformAccountId, setPlatformAccountId] = useState<number | null>(null)

  const svcById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services])
  const platformAccountList = useMemo(() => platformAccountsFrom(accounts), [accounts])
  /** In create mode we drive the "client's carriers" set from the staged
   *  drafts — the client has no persisted accounts yet. */
  const allowed = useMemo(
    () => computeAllowedCarriers({
      clientCarrierDrafts: carrierDrafts.map((d) => ({ carrierCode: d.carrierCode })),
      accounts,
      platformAccountId,
    }),
    [carrierDrafts, accounts, platformAccountId],
  )
  const filteredServices = useMemo(
    () => services.filter((s) => {
      if (!s.enabled) return false
      if (allowed.carriers.size === 0) return false
      return allowed.carriers.has((s.carrier || '').toUpperCase())
    }),
    [services, allowed.carriers],
  )
  // Drop a stale serviceId at render time when the filter no longer includes it.
  const effectiveServiceId = useMemo(() => {
    if (!serviceId) return ''
    return filteredServices.some((s) => String(s.id) === serviceId) ? serviceId : ''
  }, [serviceId, filteredServices])

  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const markTouched = (k: string) => setTouched((cur) => ({ ...cur, [k]: true }))

  // Per-field validation. Order Ship Via is a code (same [A-Z0-9_-] rules as
  // the client/warehouse codes); the platform account is required only when
  // the client has no carriers of its own; a carrier service must be picked.
  const errors = useMemo(() => {
    const platformRequired = !allowed.hasClientCarriers
    return {
      shipviaCd: validateCode(shipviaCd, 'Order Ship Via'),
      platformAccount:
        platformRequired && platformAccountId == null
          ? 'Pick a platform account to source the carrier.'
          : null,
      serviceId: !effectiveServiceId ? 'Pick a carrier service.' : null,
    }
  }, [shipviaCd, allowed.hasClientCarriers, platformAccountId, effectiveServiceId])
  const err = (k: 'shipviaCd' | 'platformAccount' | 'serviceId'): string | null =>
    touched[k] ? errors[k] : null
  const canSave = Object.values(errors).every((e) => !e)

  const save = () => {
    if (!canSave) {
      setTouched({ shipviaCd: true, platformAccount: true, serviceId: true })
      return
    }
    addDraft({ shipviaCd: shipviaCd.trim().toUpperCase(), serviceId: Number(effectiveServiceId) })
    setShipviaCd('')
    setServiceId('')
    setTouched({})
    setAdding(false)
  }

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h4 className="text-[12.5px] font-semibold text-slate-950">Shipping service mapping (draft)</h4>
          <p className="text-[11px] leading-5 text-slate-500">
            Staged in memory — each rule will POST after Create client. Fields captured here are
            minimal (ship via + service); come back to this step in edit mode to add destinations,
            warehouse restrictions, and package allowlists per rule.
          </p>
        </div>
        {!adding ? (
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add mapping
          </button>
        ) : null}
      </div>

      {adding ? (
        <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Order Ship Via *</span>
              <input
                value={shipviaCd}
                onChange={(e) => setShipviaCd(e.target.value.toUpperCase())}
                onBlur={() => markTouched('shipviaCd')}
                placeholder="e.g. P80"
                maxLength={FIELD_LIMITS.clientCode}
                aria-invalid={err('shipviaCd') ? true : undefined}
                className={`${inputBaseClass} ${err('shipviaCd') ? inputErr : inputOk} font-mono`}
              />
              {err('shipviaCd') ? (
                <span className="mt-0.5 block text-[10.5px] font-semibold leading-4 text-rose-600">{err('shipviaCd')}</span>
              ) : null}
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                Platform carrier account
                <span className="ml-1 normal-case font-normal tracking-normal text-slate-400">
                  · {allowed.hasClientCarriers ? 'optional' : 'required'}
                </span>
              </span>
              <Select
                value={platformAccountId == null ? '' : String(platformAccountId)}
                onChange={(e) => {
                  const raw = e.target.value
                  setPlatformAccountId(raw ? Number(raw) : null)
                  setServiceId('')
                }}
                onBlur={() => markTouched('platformAccount')}
                title={
                  allowed.hasClientCarriers
                    ? "Optional — pick to also allow this platform account's carrier."
                    : 'Add a carrier account to the client, or pick a platform account here to see Ship Via options.'
                }
              >
                <option value="">
                  {platformAccountList.length === 0
                    ? 'No platform accounts'
                    : allowed.hasClientCarriers
                      ? '+ platform account (optional)'
                      : 'Pick to filter Ship Via'}
                </option>
                {platformAccountList.map((a) => (
                  <option key={a.id} value={a.id}>
                    {formatCarrierName(a.carrierCode)} · {a.accountNumber}
                  </option>
                ))}
              </Select>
              {err('platformAccount') ? (
                <span className="mt-0.5 block text-[10.5px] font-semibold leading-4 text-rose-600">{err('platformAccount')}</span>
              ) : null}
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Carrier Ship Via *</span>
              <Select value={effectiveServiceId} onChange={(e) => setServiceId(e.target.value)} onBlur={() => markTouched('serviceId')}>
                <option value="">
                  {allowed.carriers.size === 0
                    ? 'Add a carrier account first —'
                    : filteredServices.length === 0
                      ? 'No services for the allowed carrier(s) —'
                      : 'Pick a carrier service…'}
                </option>
                {filteredServices.map((s) => (
                  <option key={s.id} value={s.id}>
                    {formatCarrierName(s.carrier)} — {s.name}
                  </option>
                ))}
              </Select>
              {err('serviceId') ? (
                <span className="mt-0.5 block text-[10.5px] font-semibold leading-4 text-rose-600">{err('serviceId')}</span>
              ) : null}
            </label>
          </div>
          <div className="mt-3 flex items-center justify-end gap-2 border-t border-slate-100 pt-3">
            <button
              type="button"
              onClick={() => { setTouched({}); setAdding(false) }}
              className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-100"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={save}
              aria-disabled={!canSave}
              title={!canSave ? 'Fix the highlighted fields to continue' : undefined}
              className={`rounded-xl px-4 py-1.5 text-[12px] font-semibold text-white transition ${
                canSave ? 'bg-[#1f150c] hover:bg-[#412d15]' : 'bg-slate-300'
              }`}
            >
              Add to list
            </button>
          </div>
        </div>
      ) : null}

      {!adding && drafts.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 px-5 py-6 text-center">
          <p className="text-[12px] font-semibold text-slate-800">No mappings staged yet</p>
          <p className="mx-auto mt-1 max-w-md text-[11px] leading-4 text-slate-500">
            A mapping routes an order's ship-via code (e.g. <span className="font-mono">P80</span>) to a
            carrier service. Click Add mapping above to stage one.
          </p>
        </div>
      ) : null}

      {drafts.length > 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <ul className="divide-y divide-slate-100">
            {drafts.map((d) => {
              const svc = svcById.get(d.serviceId)
              return (
                <li key={d.id} className="flex items-center gap-3 px-3 py-2">
                  <span className="rounded-lg bg-[#1f150c] px-2.5 py-1 font-mono text-[11.5px] font-bold text-[#e1dcc9]">
                    {d.shipviaCd}
                  </span>
                  <div className="flex-1 min-w-0">
                    <p className="truncate text-[12px] font-semibold text-slate-800">
                      {svc ? `${formatCarrierName(svc.carrier)} — ${svc.name}` : `Service #${d.serviceId}`}
                    </p>
                    <p className="text-[10.5px] text-slate-500">
                      Scope + warehouse + packages: set after client creation.
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => removeDraft(d.id)}
                    aria-label={`Remove ${d.shipviaCd}`}
                    className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600"
                  >
                    <FiPlus className="h-3.5 w-3.5 rotate-45" />
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      ) : null}
    </div>
  )
}
