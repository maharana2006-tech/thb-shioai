import { notify } from '../../../utils/notify'
import { FiZap, FiTruck, FiAlertTriangle, FiAlertCircle } from 'react-icons/fi'
import { canon } from '../../../utils/carrierCanon'
import { clearanceOptionsForCarrier } from '../../../utils/customsOptions'
import { isUpsDdpDisallowedForTerritory } from '../../../utils/usTerritoryServices'
import { accountNumberFormatError } from '../../../validation/carrierAccountValidation'
import { CountrySelect } from '../CountrySelect'
import { Field, SectionCard, inputCls } from '../_shared'


import type { NewShipmentModel } from '../useNewShipmentModel'

export function ServiceSection({ m }: { m: NewShipmentModel }) {
  const {
    accountNumber, accountsForCarrier, carrier, clearanceOption, dgBlock, dutiesAccount, errAt, importerProfile, incoterms, isInternational, recipient, recipientTerritory, recipientTerritoryEarly, recommendServiceAi, residentialConflict, residentialOrigin, sender, senderTerritoryEarly, serviceId, services, servicesForCarrier, setAccountNumber, setClearanceOption, setDgBlock, setDgWizardOpen, setDutiesAccount, setIncoterms, setRecipient, setResidentialOrigin, setServiceId, submitAttempted, svcBusy,
    billTo, thirdPartyAccount, thirdPartyPostalCode, thirdPartyCountry, setBillTo, setThirdPartyAccount, setThirdPartyPostalCode, setThirdPartyCountry, matchedAccount,
  } = m
  const carrierCode = canon(carrier)
  const isUsps = carrierCode === 'USPS'
  const thirdParty = billTo === 'THIRD_PARTY'
  const thirdPartyAccountError = !thirdParty ? undefined
    : !thirdPartyAccount.trim() ? (submitAttempted ? 'Enter the third party\'s account number.' : undefined)
    : accountNumberFormatError(carrierCode, thirdPartyAccount)
  const billToSegment = (value: 'SENDER' | 'THIRD_PARTY', label: string, disabled = false) => (
    <button
      type="button"
      role="radio"
      aria-checked={billTo === value}
      disabled={disabled}
      onClick={() => {
        setBillTo(value)
        // Start from the saved account's third-party defaults (Settings → Carrier accounts).
        if (value === 'THIRD_PARTY' && !thirdPartyAccount && matchedAccount?.thirdPartyAccount) {
          setThirdPartyAccount(matchedAccount.thirdPartyAccount)
          setThirdPartyPostalCode(matchedAccount.thirdPartyPostcode ?? '')
          setThirdPartyCountry(matchedAccount.thirdPartyCountry ?? '')
        }
      }}
      className={`flex-1 rounded-lg px-3 py-1.5 text-[12.5px] font-semibold transition disabled:cursor-not-allowed disabled:opacity-40 ${
        billTo === value ? 'bg-[#412d15] text-white shadow-sm' : 'text-[#5a4526] hover:bg-[#efe7d4]'
      }`}
    >
      {label}
    </button>
  )

  return (

              <SectionCard
                icon={<FiTruck className="h-3.5 w-3.5" />}
                id="sec-service"
                title="Account & service"
                badge={
                  <div className="flex flex-wrap items-center justify-end gap-2">
                    <button
                      type="button"
                      onClick={() => void recommendServiceAi()}
                      disabled={svcBusy}
                      title="Recommend a service & incoterm for this route"
                      className="inline-flex items-center gap-1 rounded-lg border border-dashed border-[#cdbf9f] bg-white px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.08em] text-[#5a4526] transition hover:border-[#412d15] hover:bg-[#faf7f0] disabled:opacity-50"
                    >
                      {svcBusy ? (
                        <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#412d15]" />
                      ) : (
                        <FiZap className="h-3 w-3" />
                      )}
                      Recommend
                    </button>
                    <span className="rounded-full bg-[#efe7d4] px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-[#5a4526]">
                      {(senderTerritoryEarly ?? sender.countryCode) || '—'} → {(recipientTerritoryEarly ?? recipient.countryCode) || '—'} · {isInternational ? 'Intl' : 'Domestic'}
                    </span>
                  </div>
                }
              >
                <div className="grid grid-cols-1 gap-3">
                  <Field label="Shipper account" required error={errAt('account')}>
                    <input
                      className={inputCls}
                      list="bill-to-accounts"
                      value={accountNumber}
                      onChange={(e) => setAccountNumber(e.target.value)}
                      placeholder="Account number"
                      autoComplete="off"
                    />
                    <datalist id="bill-to-accounts">
                      {accountsForCarrier.map((a) => (
                        <option key={a.id} value={a.accountNumber}>
                          {a.accountName || a.accountNumber}
                        </option>
                      ))}
                    </datalist>
                  </Field>
                  <Field label="Shipping charges billed to"
                         hint={isUsps ? 'USPS postage is prepaid, so the charges always go to the shipper account.' : undefined}>
                    <div role="radiogroup" aria-label="Shipping charges billed to"
                         className="flex gap-1 rounded-xl border border-[#e2d9c3] bg-[#faf7f0] p-1">
                      {billToSegment('SENDER', 'Sender (shipper account)')}
                      {billToSegment('THIRD_PARTY', 'Third party', isUsps)}
                    </div>
                  </Field>
                  {thirdParty ? (
                    <div className="grid grid-cols-1 gap-3 rounded-xl border border-dashed border-[#cdbf9f] bg-white p-3 sm:grid-cols-2">
                      <Field label="Third-party account number" required className="sm:col-span-2"
                             hint={`The ${carrierCode || 'carrier'} account that pays the shipping charges. It must allow third-party billing.`}
                             error={thirdPartyAccountError}>
                        <input className={inputCls} value={thirdPartyAccount}
                               onChange={(e) => setThirdPartyAccount(e.target.value)}
                               placeholder="Payer account number" autoComplete="off" />
                      </Field>
                      <Field label="Payer postal code" required={carrierCode === 'UPS'}
                             hint={carrierCode === 'UPS' ? 'UPS checks it against the account\'s billing address.' : undefined}
                             error={carrierCode === 'UPS' && submitAttempted && !thirdPartyPostalCode.trim() ? 'Required for UPS third-party billing.' : undefined}>
                        <input className={inputCls} value={thirdPartyPostalCode}
                               onChange={(e) => setThirdPartyPostalCode(e.target.value)}
                               placeholder="Postal code" autoComplete="off" />
                      </Field>
                      <Field label="Payer country" required={carrierCode === 'UPS'}
                             error={carrierCode === 'UPS' && submitAttempted && !thirdPartyCountry ? 'Required for UPS third-party billing.' : undefined}>
                        <CountrySelect value={thirdPartyCountry} onChange={setThirdPartyCountry} />
                      </Field>
                    </div>
                  ) : null}
                  <Field label="Service level">
                    <div className="flex items-center gap-1.5">
                      <select className={inputCls} value={serviceId} onChange={(e) => {
                        const nextId = e.target.value ? Number(e.target.value) : ''
                        setServiceId(nextId)
                        // Auto-manage the "Residential address" flag based
                        // on service choice. Two directions:
                        //
                        //   Picking GROUND_HOME_DELIVERY → auto-tick + mark
                        //   origin='auto'. FedEx refuses Home Delivery to
                        //   commercial addresses with an opaque error, so
                        //   the tick prevents the failure at submit. If
                        //   operator ticked it themselves before ('manual'),
                        //   leave the origin alone — no need to demote.
                        //
                        //   Leaving Home Delivery for another service →
                        //   IF the current tick is 'auto' (we set it),
                        //   untick and clear origin. IF 'manual' (operator
                        //   set it themselves), leave the tick alone —
                        //   respect operator intent, they may have picked
                        //   residential deliberately for surcharge reasons.
                        //
                        // Re-picking Home Delivery after an untick re-auto-
                        // ticks (per intended UX). Operator manually
                        // unticking after auto-tick stays unticked but
                        // Submit will be disabled with a warning banner
                        // (see the residentialConflict computed below).
                        const svcCode = nextId !== ''
                          ? services.find((s) => s.id === Number(nextId))?.serviceCode
                          : undefined
                        if (svcCode === 'GROUND_HOME_DELIVERY') {
                          if (!recipient.residential) {
                            setRecipient({ ...recipient, residential: true })
                            setResidentialOrigin('auto')
                            notify.info('Ticked "Residential address" — FedEx Home Delivery only delivers to homes.')
                          }
                          // Already ticked → keep whatever origin already is.
                        } else if (residentialOrigin === 'auto' && recipient.residential) {
                          // Switching AWAY from Home Delivery and we set
                          // the flag ourselves — revert. Manual ticks
                          // (origin='manual') stay put.
                          setRecipient({ ...recipient, residential: false })
                          setResidentialOrigin(undefined)
                          notify.info('Unticked "Residential address" — it was only ticked for FedEx Home Delivery.')
                        }
                      }}>
                        {servicesForCarrier.length === 0 ? <option value="">Carrier default</option> : null}
                        {servicesForCarrier.map((s) => (
                          <option key={s.id} value={s.id}>{s.name}</option>
                        ))}
                      </select>
                    </div>
                    {residentialConflict ? (
                      <div className="mt-1.5 flex items-start gap-1.5 rounded-lg border border-amber-300 bg-amber-50 px-2 py-1.5 text-[11px] text-amber-900">
                        <FiAlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
                        <span>
                          <span className="font-semibold">FedEx Home Delivery</span> requires the
                          recipient to be marked as <span className="font-semibold">residential</span>.
                          Tick the checkbox on the recipient block, or pick a different service —
                          Submit is disabled until this is fixed.
                        </span>
                      </div>
                    ) : null}
                  </Field>
                  <Field label="Dangerous goods">
                    <button
                      type="button"
                      onClick={() => setDgWizardOpen(true)}
                      className={`inline-flex w-full items-center justify-center gap-1.5 whitespace-nowrap rounded-lg border px-2.5 py-1.5 text-[11px] font-semibold transition ${
                        dgBlock
                          ? 'border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100'
                          : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
                      }`}
                    >
                      <FiAlertTriangle className="h-3 w-3" />
                      {dgBlock
                        ? `Hazmat attached · ${dgBlock.commodities.length} commodity(ies)`
                        : 'Declare dangerous goods'}
                    </button>
                    {dgBlock ? (
                      <button
                        type="button"
                        onClick={() => setDgBlock(null)}
                        className="ml-2 text-[10.5px] font-semibold text-rose-600 hover:underline"
                      >
                        Remove
                      </button>
                    ) : null}
                  </Field>
                  {isInternational ? (() => {
                    // 2026-09-09 — UPS won't accept DDP (sender-paid duties)
                    // to American Samoa or Northern Mariana Islands
                    // (wire error 121213 "billing option unavailable
                    // between the selected locations"). Hide DDP entirely
                    // on those lanes and show an inline hint so the
                    // operator picks DAP instead. GU is deliberately NOT
                    // in the deny set — UPS supports DDP to Guam per its
                    // Rate & Service Guide (larger territory, dedicated
                    // ground infrastructure).
                    const upsDdpBlocked = isUpsDdpDisallowedForTerritory(canon(carrier), recipientTerritory)
                    return (
                      <Field label="Incoterms" required
                             hint={upsDdpBlocked
                               ? `UPS doesn't offer DDP to ${recipientTerritory === 'AS' ? 'American Samoa' : 'Northern Mariana Islands'} — pick DAP or a different carrier.`
                               : undefined}
                             error={submitAttempted && !incoterms ? 'Required — pick an incoterm.' : errAt('incoterms')}>
                        <select className={inputCls} value={incoterms} onChange={(e) => setIncoterms(e.target.value)}>
                          <option value="">-- Select --</option>
                          {upsDdpBlocked ? null : (
                            <option value="DDP">DDP — sender pays duties</option>
                          )}
                          <option value="DAP">DAP — receiver pays duties</option>
                        </select>
                      </Field>
                    )
                  })() : null}
                  {/* F6-C — clearanceOption dropdown. Per-carrier
                      vocabulary (customsOptions.ts). Blank = backend
                      connector picks its own default. Hidden when the
                      carrier isn't recognised (no options). */}
                  {isInternational && clearanceOptionsForCarrier(canon(carrier)).length > 0 ? (
                    <Field label="Duties paid by"
                           // The old "Blank = carrier default" read as an
                           // unconditional RECIPIENT default; blank actually
                           // follows the incoterm (DDP → sender pays).
                           hint={clearanceOption ? undefined
                             : 'Blank = follows the incoterm (DDP → sender pays, DAP/DDU → recipient pays)'}
                           error={
                             // A label that says DDP while the consignee gets
                             // the duty bill is a promise the paperwork breaks
                             // at delivery — surface the contradiction here.
                             incoterms === 'DDP' && /RECIPIENT|RECEIVER/.test(clearanceOption.toUpperCase())
                               ? 'DDP means the sender pays duties, but this bills them to the recipient — the commercial invoice will say "Payable by consignee (DDP)". Pick sender-pays or change the incoterm unless this is intended.'
                               : undefined
                           }>
                      <select className={inputCls}
                              value={clearanceOption}
                              onChange={(e) => {
                                const v = e.target.value
                                setClearanceOption(v)
                                // Third party: start from the profile's duties account.
                                if (/THIRD/.test(v.toUpperCase()) && !dutiesAccount) setDutiesAccount(importerProfile?.dutiesAccount ?? '')
                              }}>
                        <option value="">Follow incoterm (DDP → sender pays, DAP/DDU → recipient pays)</option>
                        {clearanceOptionsForCarrier(canon(carrier)).map((o) => (
                          <option key={o.value} value={o.value}>{o.label}</option>
                        ))}
                      </select>
                    </Field>
                  ) : null}
                  {isInternational && /THIRD/.test(clearanceOption.toUpperCase()) ? (
                    <Field label="Duties payor account" required
                           hint={`The third party's ${canon(carrier) || 'carrier'} account that pays duties & taxes${importerProfile?.dutiesAccount ? ` (profile: ${importerProfile.dutiesAccount})` : ''}.`}
                           error={!dutiesAccount.trim() ? 'Required when duties are billed to a third party.' : undefined}>
                      <input className={inputCls} value={dutiesAccount}
                             onChange={(e) => setDutiesAccount(e.target.value)}
                             placeholder="Payer account number" />
                    </Field>
                  ) : null}
                </div>
              </SectionCard>

  )
}
