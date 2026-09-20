/**
 * F5-B2 — Carriers step in create mode (staged-draft list). Each row
 * is a carrier account that will POST to accountRefService.upsertAccount
 * right after the client is created. Draft fields mirror the API
 * payload exactly so the commit path in handleCreate is a straight
 * pass-through.
 *
 * <p>Not shown when the client already exists (edit / post-create) —
 * that path hands off to {@code <CarrierConnections embedded />}, the
 * full editor.
 *
 * <p>Extracted from ClientEditorPage. Owns its own add-form state
 * ({@code adding} / {@code f} / {@code touched}) and runs
 * {@code validateCarrierAccount} on every render. The
 * carrier-change effect clears a stale clearanceOption so a UPS-only
 * value can't ride on a FedEx account.
 */
import { useEffect, useMemo, useState } from 'react'
import { FiPlus, FiStar } from 'react-icons/fi'
import {
  carrierEnvironmentOptions,
  formatCarrierName,
  type CarrierEnvironment,
} from '../../utils/carrierUtils'
import {
  CARRIER_ACCOUNT_RULES,
  type CarrierAccountRule,
  type CarrierCode,
} from '../../utils/carrierFieldLimits'
import { SHIPPING_PURPOSES, clearanceOptionsForCarrier } from '../../utils/customsOptions'
import { validateCarrierAccount } from '../../validation/carrierAccountValidation'
import Select from '../workspace/Select'
import { inputBaseClass, inputErr, inputOk } from './_shared'
import type { CarrierAccountDraft } from './_types'

/**
 * Look up the account-number rule for a carrier code. Returns null when
 * the code isn't one of the utility's four known carriers (UPS / FEDEX /
 * USPS / DHL) — callers fall back to their pre-existing maxLength in
 * that case so a novel carrier code doesn't lock the input out.
 */
function accountRuleFor(code: string): CarrierAccountRule | null {
  const upper = code.toUpperCase()
  if (upper === 'UPS' || upper === 'FEDEX' || upper === 'USPS' || upper === 'DHL') {
    return CARRIER_ACCOUNT_RULES[upper as CarrierCode]
  }
  return null
}

const carrierOptions = [
  { code: 'UPS', label: 'UPS' },
  { code: 'FEDEX', label: 'FedEx' },
  { code: 'USPS', label: 'USPS' },
  { code: 'DHL', label: 'DHL Express' },
]

export interface CarrierDraftStepProps {
  drafts: CarrierAccountDraft[]
  addDraft: (d: Omit<CarrierAccountDraft, 'id'>) => void
  removeDraft: (id: number) => void
}

export function CarrierDraftStep({
  drafts,
  addDraft,
  removeDraft,
}: CarrierDraftStepProps) {
  const [adding, setAdding] = useState(false)
  const [f, setF] = useState<Omit<CarrierAccountDraft, 'id'>>({
    carrierCode: 'UPS',
    accountNumber: '',
    accountName: '',
    clientId: '',
    clientSecret: '',
    environment: 'SANDBOX' as CarrierEnvironment,
    clientDefault: drafts.length === 0,
    shippingPurpose: '',
    clearanceOption: '',
    thirdPartyAccount: '',
    thirdPartyName: '',
    thirdPartyAddress1: '',
    thirdPartyCity: '',
    thirdPartyState: '',
    thirdPartyPostcode: '',
    thirdPartyCountry: '',
  })

  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const markTouched = (k: string) => setTouched((cur) => ({ ...cur, [k]: true }))

  // Domain-specific carrier-account validation (per-carrier account-number
  // format + credential sanity — trims, no embedded whitespace, no <>). Same
  // validator the standalone Carrier Accounts drawer uses, so both stay in sync.
  const errors = useMemo(
    () =>
      validateCarrierAccount(
        {
          carrierCode: f.carrierCode,
          accountType: 'platform', // staged for THIS client — no separate client picker
          accountNumber: f.accountNumber,
          accountName: f.accountName,
          clientId: f.clientId,
          clientSecret: f.clientSecret,
          customerNo: '',
          environment: f.environment,
        },
        {
          isEdit: false,
          rotating: false,
          labels: { accountNumberLabel: 'Account number', idLabel: 'Client ID', secretLabel: 'Client secret' },
        },
      ),
    [f.carrierCode, f.accountNumber, f.accountName, f.clientId, f.clientSecret, f.environment],
  )
  const err = (k: 'accountNumber' | 'clientId' | 'clientSecret'): string | undefined =>
    touched[k] ? errors[k] : undefined
  const canSave = Object.keys(errors).length === 0

  const save = () => {
    if (!canSave) {
      // Surface every error even for fields the operator hasn't blurred yet.
      setTouched({ accountNumber: true, clientId: true, clientSecret: true, accountName: true })
      return
    }
    addDraft({
      ...f,
      accountNumber: f.accountNumber.trim(),
      accountName: f.accountName.trim(),
      clientId: f.clientId.trim(),
      clientSecret: f.clientSecret.trim(),
    })
    setF({
      carrierCode: 'UPS',
      accountNumber: '',
      accountName: '',
      clientId: '',
      clientSecret: '',
      environment: 'SANDBOX',
      clientDefault: false,
      shippingPurpose: '',
      clearanceOption: '',
      thirdPartyAccount: '',
      thirdPartyName: '',
      thirdPartyAddress1: '',
      thirdPartyCity: '',
      thirdPartyState: '',
      thirdPartyPostcode: '',
      thirdPartyCountry: '',
    })
    setTouched({})
    setAdding(false)
  }

  /** Only render the third-party sub-panel when the operator has picked
   *  THIRD_PARTY as clearance — SENDER/RECIPIENT/etc. don't need extra data. */
  const isThirdParty = f.clearanceOption === 'THIRD_PARTY'

  // Clearance options depend on the carrier — clear a stale pick when the
  // operator flips carrier mid-form so we can't submit a UPS-only value on
  // a FedEx account.
  useEffect(() => {
    if (!f.clearanceOption) return
    const ok = clearanceOptionsForCarrier(f.carrierCode).some((o) => o.value === f.clearanceOption)
    // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale clearance option on carrier change; guards submit-time invalid combos, cannot be derived at render
    if (!ok) setF((cur) => ({ ...cur, clearanceOption: '' }))
    // eslint-disable-next-line react-hooks/exhaustive-deps -- f.clearanceOption intentionally omitted; only carrier-change should re-evaluate. Including it would clear the field on any operator edit.
  }, [f.carrierCode])

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h4 className="text-[12.5px] font-semibold text-slate-950">Carrier accounts (draft)</h4>
          <p className="text-[11px] leading-5 text-slate-500">
            Staged in memory — they'll be saved to this client the moment you click Create client
            on the last step. Add as many as you need; you can also add more later.
          </p>
        </div>
        {!adding ? (
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add carrier account
          </button>
        ) : null}
      </div>

      {adding ? (
        <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-3">
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Carrier *</span>
              <Select value={f.carrierCode} onChange={(e) => setF((c) => ({ ...c, carrierCode: e.target.value }))}>
                {carrierOptions.map((option) => (
                  <option key={option.code} value={option.code}>{option.label}</option>
                ))}
              </Select>
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Account number *</span>
              {(() => {
                const rule = accountRuleFor(f.carrierCode)
                return (
                  <>
                    <input
                      value={f.accountNumber}
                      onChange={(e) => setF((c) => ({ ...c, accountNumber: e.target.value }))}
                      onBlur={() => markTouched('accountNumber')}
                      maxLength={rule?.maxLength ?? 100}
                      pattern={rule?.pattern}
                      placeholder={rule?.placeholder}
                      autoComplete="off"
                      aria-invalid={err('accountNumber') ? true : undefined}
                      className={`${inputBaseClass} ${err('accountNumber') ? inputErr : inputOk}`}
                    />
                    {err('accountNumber') ? (
                      <span className="mt-0.5 block text-[10.5px] font-semibold leading-4 text-rose-600">{err('accountNumber')}</span>
                    ) : rule ? (
                      <span className="mt-0.5 block text-[10.5px] leading-4 text-slate-500">{rule.helper}</span>
                    ) : null}
                  </>
                )
              })()}
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Environment</span>
              <Select
                value={f.environment}
                onChange={(e) => setF((c) => ({ ...c, environment: e.target.value as CarrierEnvironment }))}
              >
                {carrierEnvironmentOptions.map((o) => <option key={o} value={o}>{o}</option>)}
              </Select>
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Client ID *</span>
              <input
                value={f.clientId}
                onChange={(e) => setF((c) => ({ ...c, clientId: e.target.value }))}
                onBlur={() => markTouched('clientId')}
                maxLength={255}
                autoComplete="off"
                spellCheck={false}
                aria-invalid={err('clientId') ? true : undefined}
                className={`${inputBaseClass} ${err('clientId') ? inputErr : inputOk}`}
              />
              {err('clientId') ? (
                <span className="mt-0.5 block text-[10.5px] font-semibold leading-4 text-rose-600">{err('clientId')}</span>
              ) : null}
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Client Secret *</span>
              <input
                type="password"
                value={f.clientSecret}
                onChange={(e) => setF((c) => ({ ...c, clientSecret: e.target.value }))}
                onBlur={() => markTouched('clientSecret')}
                maxLength={255}
                autoComplete="new-password"
                aria-invalid={err('clientSecret') ? true : undefined}
                className={`${inputBaseClass} ${err('clientSecret') ? inputErr : inputOk}`}
              />
              {err('clientSecret') ? (
                <span className="mt-0.5 block text-[10.5px] font-semibold leading-4 text-rose-600">{err('clientSecret')}</span>
              ) : null}
            </label>
            <label className="flex items-end gap-2 pb-2 text-[12px] font-semibold text-slate-700">
              <input
                type="checkbox"
                checked={f.clientDefault}
                onChange={(e) => setF((c) => ({ ...c, clientDefault: e.target.checked }))}
                className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
              />
              Default account
            </label>
          </div>

          {/* International-shipment defaults — both optional. Applied by the
              carrier only when a shipment on this account has an
              international destination. */}
          <div className="mt-3 rounded-xl border border-slate-100 bg-white px-3 py-2.5">
            <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              International defaults <span className="ml-1 rounded-full bg-slate-100 px-1.5 py-0.5 text-[9px] font-bold text-slate-500">optional</span>
            </p>
            <p className="mt-0.5 text-[10.5px] leading-4 text-slate-500">
              Applied to international shipments only. Blank = the carrier's default.
            </p>
            <div className="mt-2 grid grid-cols-2 gap-2">
              <label className="block">
                <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Shipping purpose</span>
                <Select
                  value={f.shippingPurpose}
                  onChange={(e) => setF((c) => ({ ...c, shippingPurpose: e.target.value }))}
                >
                  <option value="">— carrier default —</option>
                  {SHIPPING_PURPOSES.map((p) => (
                    <option key={p.value} value={p.value}>{p.label}</option>
                  ))}
                </Select>
              </label>
              <label className="block">
                <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Customs clearance</span>
                <Select
                  value={f.clearanceOption}
                  onChange={(e) => setF((c) => ({ ...c, clearanceOption: e.target.value }))}
                  disabled={clearanceOptionsForCarrier(f.carrierCode).length === 0}
                >
                  <option value="">— carrier default —</option>
                  {clearanceOptionsForCarrier(f.carrierCode).map((o) => (
                    <option key={o.value} value={o.value}>{o.label}</option>
                  ))}
                </Select>
              </label>
            </div>

            {/* Third-party billing address — only when clearance = THIRD_PARTY.
                Every field is an account-level DEFAULT; per-shipment overrides
                land on the Shipment row (follow-up). */}
            {isThirdParty ? (
              <div className="mt-2 rounded-xl border border-amber-200 bg-amber-50/40 p-3">
                <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-amber-800">
                  Third-party billing (default) <span className="ml-1 rounded-full bg-white px-1.5 py-0.5 text-[9px] font-bold text-amber-700">per-shipment override</span>
                </p>
                <p className="mt-0.5 text-[10.5px] leading-4 text-amber-900/70">
                  Applied when nobody overrides at label time. The account # is the crucial field; address optional.
                </p>
                <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
                  <label className="block">
                    <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Third-party account #</span>
                    <input
                      value={f.thirdPartyAccount}
                      onChange={(e) => setF((c) => ({ ...c, thirdPartyAccount: e.target.value }))}
                      maxLength={100}
                      autoComplete="off"
                      className={`${inputBaseClass} ${inputOk}`}
                    />
                  </label>
                  <label className="block">
                    <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Party name</span>
                    <input
                      value={f.thirdPartyName}
                      onChange={(e) => setF((c) => ({ ...c, thirdPartyName: e.target.value }))}
                      maxLength={255}
                      className={`${inputBaseClass} ${inputOk}`}
                    />
                  </label>
                  <label className="block sm:col-span-2">
                    <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Address line 1</span>
                    <input
                      value={f.thirdPartyAddress1}
                      onChange={(e) => setF((c) => ({ ...c, thirdPartyAddress1: e.target.value }))}
                      maxLength={255}
                      autoComplete="address-line1"
                      className={`${inputBaseClass} ${inputOk}`}
                    />
                  </label>
                  <label className="block">
                    <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">City</span>
                    <input
                      value={f.thirdPartyCity}
                      onChange={(e) => setF((c) => ({ ...c, thirdPartyCity: e.target.value }))}
                      maxLength={100}
                      autoComplete="address-level2"
                      className={`${inputBaseClass} ${inputOk}`}
                    />
                  </label>
                  <label className="block">
                    <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">State / region</span>
                    <input
                      value={f.thirdPartyState}
                      onChange={(e) => setF((c) => ({ ...c, thirdPartyState: e.target.value }))}
                      maxLength={50}
                      autoComplete="address-level1"
                      className={`${inputBaseClass} ${inputOk}`}
                    />
                  </label>
                  <label className="block">
                    <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Postal code</span>
                    <input
                      value={f.thirdPartyPostcode}
                      onChange={(e) => setF((c) => ({ ...c, thirdPartyPostcode: e.target.value }))}
                      maxLength={20}
                      autoComplete="postal-code"
                      className={`${inputBaseClass} ${inputOk}`}
                    />
                  </label>
                  <label className="block">
                    <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Country (ISO-2)</span>
                    <input
                      value={f.thirdPartyCountry}
                      onChange={(e) => setF((c) => ({ ...c, thirdPartyCountry: e.target.value.toUpperCase() }))}
                      maxLength={2}
                      className={`${inputBaseClass} ${inputOk} uppercase`}
                      placeholder="US"
                    />
                  </label>
                </div>
              </div>
            ) : null}
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
          <p className="text-[12px] font-semibold text-slate-800">No carrier accounts staged yet</p>
          <p className="mx-auto mt-1 max-w-md text-[11px] leading-4 text-slate-500">
            Click Add carrier account above to stage one. You can add multiple; all get created
            when you click Create client.
          </p>
        </div>
      ) : null}

      {drafts.length > 0 ? (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <ul className="divide-y divide-slate-100">
            {drafts.map((d) => (
              <li key={d.id} className="flex items-center gap-3 px-3 py-2">
                <div className="flex-1 min-w-0">
                  <p className="truncate text-[12px] font-semibold text-slate-800">
                    {formatCarrierName(d.carrierCode)} · {d.accountNumber}
                    {d.clientDefault ? (
                      <span className="ml-1.5 inline-flex items-center gap-1 rounded-full bg-[#412d15]/10 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-[#412d15]">
                        <FiStar className="h-2.5 w-2.5" /> default
                      </span>
                    ) : null}
                  </p>
                  <p className="text-[10.5px] text-slate-500">
                    {d.environment} · client ID {d.clientId.slice(0, 10)}{d.clientId.length > 10 ? '…' : ''} · secret hidden
                    {(d.shippingPurpose || d.clearanceOption) ? (
                      <span className="ml-1 text-slate-400">
                        · intl:
                        {d.shippingPurpose ? ` ${d.shippingPurpose.toLowerCase().replace(/_/g, ' ')}` : ' carrier-default purpose'}
                        {' /'}
                        {d.clearanceOption ? ` ${d.clearanceOption}` : ' carrier-default clearance'}
                      </span>
                    ) : null}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => removeDraft(d.id)}
                  aria-label={`Remove ${d.accountNumber}`}
                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600"
                >
                  <FiPlus className="h-3.5 w-3.5 rotate-45" />
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}
