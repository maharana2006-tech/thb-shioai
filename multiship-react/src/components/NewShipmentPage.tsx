import { memo, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { useNavigate } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiZap, FiArrowRight, FiArrowLeft, FiTruck, FiPackage, FiMapPin, FiHome, FiUsers, FiFileText, FiPlus, FiTrash2, FiRotateCcw, FiGlobe, FiEdit3, FiCheckCircle, FiAlertTriangle, FiSearch, FiX } from 'react-icons/fi'
import { ApiError } from '../api/apiClient'
import {
  orderService,
  type ManualShipmentAddress,
  type ManualShipmentItem,
  type ManualShipmentPayload,
} from '../api/orderService'
import { accountRefService, type CarrierAccountRef } from '../api/accountRefService'
import { clientService, type Client } from '../api/clientService'
import { customsProfileService, type CustomsProfile } from '../api/customsProfileService'
import { shippingConfigService, type ShippingServiceItem, type PackagePreset } from '../api/shippingConfigService'
import { addressValidationService, type AddressValidationResponse } from '../api/addressValidationService'
import { recipientBookService, type SavedRecipient } from '../api/recipientBookService'
import { clientWarehouseService, type ClientWarehouse } from '../api/warehouseService'
import {
  clientAllowedPackagesService,
  clientAllowedServicesService,
} from '../api/clientCatalogService'
import {
  clientDestinationsService,
  type ClientDestinationRules,
} from '../api/clientPolicyService'
import { aiService, type ShipmentWarning } from '../api/aiService'
import ShipmentPartiesOverrideModal, { type Party } from './modals/ShipmentPartiesOverrideModal'
import CustomFieldsSection from './shared/CustomFieldsSection'
import { customFieldService } from '../api/customFieldService'
import CustomsWizard from './shipment/CustomsWizard'
import HsCodeCombobox from './shipment/HsCodeCombobox'
import RatePickerModal from './shipment/RatePickerModal'
import type { RateOption, RateShopRequest } from '../api/rateShopService'
import DangerousGoodsWizard from './shipment/DangerousGoodsWizard'
import type { DangerousGoodsBlock } from '../api/dgService'
import LandedCostModal from './shipment/LandedCostModal'
import type { LandedCostRequest } from '../api/landedCostService'
import type { CustomsItem, OrderCustomsPayload } from '../api/customsService'
import { parseIntlValidationMessage } from '../utils/intlValidationErrors'
import { useFormik, getIn } from 'formik'
import { shipmentSchema, type ShipmentFormValues } from '../validation/yup/shipmentSchema'
import { dialCodeFor, postalPlaceholderFor } from '../utils/countryFormats'
import { STATE_CODE_OPTIONS } from '../utils/stateCodes'
import { decorateWithStateWarning } from '../utils/addressWarnings'

/** Canonicalise a carrier code (ERP aliases → UPS/FEDEX/USPS). */
const canon = (c?: string | null) => {
  const v = (c || '').trim().toUpperCase()
  if (v === 'P80') return 'UPS'
  if (v === 'F77') return 'FEDEX'
  if (v === 'L01') return 'USPS'
  return v
}

const COUNTRIES: [string, string][] = [
  ['US', 'United States'], ['CA', 'Canada'], ['MX', 'Mexico'], ['GB', 'United Kingdom'],
  ['IE', 'Ireland'], ['FR', 'France'], ['DE', 'Germany'], ['NL', 'Netherlands'], ['BE', 'Belgium'],
  ['LU', 'Luxembourg'], ['IT', 'Italy'], ['ES', 'Spain'], ['PT', 'Portugal'], ['CH', 'Switzerland'],
  ['AT', 'Austria'], ['DK', 'Denmark'], ['SE', 'Sweden'], ['NO', 'Norway'], ['FI', 'Finland'],
  ['PL', 'Poland'], ['CZ', 'Czechia'], ['HU', 'Hungary'], ['RO', 'Romania'], ['GR', 'Greece'],
  ['IN', 'India'], ['CN', 'China'], ['HK', 'Hong Kong'], ['JP', 'Japan'], ['KR', 'South Korea'],
  ['SG', 'Singapore'], ['MY', 'Malaysia'], ['TH', 'Thailand'], ['VN', 'Vietnam'], ['ID', 'Indonesia'],
  ['PH', 'Philippines'], ['AE', 'United Arab Emirates'], ['SA', 'Saudi Arabia'], ['IL', 'Israel'],
  ['TR', 'Turkey'], ['ZA', 'South Africa'], ['NG', 'Nigeria'], ['EG', 'Egypt'], ['KE', 'Kenya'],
  ['AU', 'Australia'], ['NZ', 'New Zealand'], ['BR', 'Brazil'], ['AR', 'Argentina'], ['CL', 'Chile'],
  ['CO', 'Colombia'], ['PE', 'Peru'],
]
const COUNTRY_NAME: Record<string, string> = Object.fromEntries(COUNTRIES)

/** Reason-for-export choices, shown at the top of the shipment. */
const EXPORT_REASONS = ['SALE', 'GIFT', 'SAMPLE', 'RETURN', 'REPAIR', 'PERSONAL']
const CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'INR', 'AUD', 'SGD', 'JPY', 'CNY', 'AED']

/** Persist the last-used value for a field so it prefills next time (sticky default). */
const readSticky = (key: string, fallback: string) => {
  try {
    return localStorage.getItem(key) || fallback
  } catch {
    return fallback
  }
}
const writeSticky = (key: string, value: string) => {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* ignore storage errors */
  }
}

const CARRIER_LABEL: Record<string, string> = { UPS: 'UPS', FEDEX: 'FedEx', USPS: 'USPS' }

const blankAddress = (): ManualShipmentAddress => ({
  name: '', company: '', phone: '', email: '',
  addressLine1: '', addressLine2: '', city: '', state: '', postalCode: '', countryCode: 'US',
})

/** A sensible default ship-from so operators don't retype the warehouse each time. */
const defaultSender = (): ManualShipmentAddress => ({
  name: 'MultiShip Fulfillment', company: 'MultiShip', phone: '2125550100', email: '',
  addressLine1: '350 5th Ave', addressLine2: '', city: 'New York', state: 'NY', postalCode: '10118', countryCode: 'US',
})

const inputCls =
  'w-full rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] text-[#1f150c] outline-none transition placeholder:text-[#b6a684] focus:border-[#cdbf9f] focus:ring-4 focus:ring-[#f4eede] disabled:cursor-not-allowed disabled:bg-[#faf7f0] disabled:text-[#8a7959]'

function Field({ label, required, hint, error, title, children, className = '' }: { label: string; required?: boolean; hint?: string; error?: string | false | null; title?: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block space-y-1 ${className}`} title={title}>
      <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
        {label}
        {required ? <span className="text-rose-500"> *</span> : null}
      </span>
      {children}
      {error ? (
        <span className="ms-field-error mt-1 block text-[10.5px] font-semibold normal-case tracking-normal text-rose-600">{error}</span>
      ) : hint ? (
        <span className="mt-1 block text-[10.5px] normal-case tracking-normal text-slate-400">{hint}</span>
      ) : null}
    </label>
  )
}

/** Espresso section shell used across the page. */
function SectionCard({ icon, title, badge, note, className = '', wrapHeader = false, children }: { icon: ReactNode; title: string; badge?: ReactNode; note?: ReactNode; className?: string; wrapHeader?: boolean; children: ReactNode }) {
  return (
    <section className={`rounded-2xl border border-slate-200 bg-white p-5 shadow-sm ${className}`}>
      <div className={`flex min-h-[38px] items-center justify-between gap-2 border-b border-dashed border-[#e3d9c4] pb-2 ${wrapHeader ? 'flex-wrap' : ''}`}>
        <div className="flex items-center gap-2">
          <span className="text-[#8a7959]">{icon}</span>
          <h3 className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">{title}</h3>
        </div>
        {badge}
      </div>
      {note ? <p className="mt-2 text-[11px] text-[#8a7959]">{note}</p> : null}
      <div className="mt-3">{children}</div>
    </section>
  )
}

/** Searchable country dropdown — type to filter, click to select. */
function CountrySelect({ value, onChange }: { value: string; onChange: (code: string) => void }) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const selectedName = COUNTRY_NAME[value] || value || ''
  const q = query.trim().toLowerCase()
  const matches = q
    ? COUNTRIES.filter(([code, name]) => name.toLowerCase().includes(q) || code.toLowerCase().includes(q))
    : COUNTRIES

  return (
    <div className="relative">
      <div className="relative">
        <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-[#b6a684]" />
        <input
          className={`${inputCls} pl-8`}
          value={open ? query : selectedName ? `${selectedName} (${value})` : ''}
          placeholder="Search country…"
          onFocus={() => {
            setOpen(true)
            setQuery('')
          }}
          onChange={(e) => {
            setOpen(true)
            setQuery(e.target.value)
          }}
          onBlur={() => setTimeout(() => setOpen(false), 120)}
          autoComplete="off"
        />
      </div>
      {open ? (
        <ul className="absolute z-40 mt-1 max-h-56 w-full overflow-auto rounded-xl border border-[#e3d9c4] bg-white py-1 shadow-lg">
          {matches.length === 0 ? (
            <li className="px-3 py-2 text-[12px] text-[#b6a684]">No match</li>
          ) : (
            matches.map(([code, name]) => (
              <li key={code}>
                <button
                  type="button"
                  onMouseDown={(e) => {
                    e.preventDefault()
                    onChange(code)
                    setOpen(false)
                    setQuery('')
                  }}
                  className={`flex w-full items-center justify-between px-3 py-1.5 text-left text-[12.5px] hover:bg-[#faf7f0] ${
                    code === value ? 'font-semibold text-[#1f150c]' : 'text-[#5a4526]'
                  }`}
                >
                  <span>{name}</span>
                  <span className="font-mono text-[10px] text-[#b6a684]">{code}</span>
                </button>
              </li>
            ))
          )}
        </ul>
      ) : null}
    </div>
  )
}

type AddressErrors = Partial<Record<keyof ManualShipmentAddress, string>>

function AddressBlock({
  value,
  onChange,
  withEmail,
  hideLine3,
  extraAction,
  errors,
}: {
  value: ManualShipmentAddress
  onChange: (patch: Partial<ManualShipmentAddress>) => void
  withEmail?: boolean
  hideLine3?: boolean
  /** Extra control (e.g. address-book search) placed on the same line as "Paste & autofill with AI". */
  extraAction?: ReactNode
  /** Per-field validation messages (only passed after a submit attempt). */
  errors?: AddressErrors
}) {
  const [pasteOpen, setPasteOpen] = useState(false)
  const [pasteText, setPasteText] = useState('')
  const [parsing, setParsing] = useState(false)

  const runParse = async () => {
    if (!pasteText.trim()) return
    setParsing(true)
    try {
      const parsed = await aiService.parseAddress(pasteText)
      // Only apply the fields the model actually found — never clobber with blanks.
      const patch: Partial<ManualShipmentAddress> = {}
      const keys: (keyof ManualShipmentAddress)[] = [
        'name', 'company', 'phone', 'email', 'addressLine1', 'addressLine2',
        'city', 'state', 'postalCode', 'countryCode',
      ]
      let filled = 0
      for (const k of keys) {
        const v = (parsed as Record<string, unknown>)[k]
        if (typeof v === 'string' && v.trim()) {
          // Every key in `keys` is a string-valued field, but TS widens patch[k]
          // to the full value union — assign through a string-keyed view.
          ;(patch as Record<string, string>)[k] = v.trim()
          filled += 1
        }
      }
      if (!filled) {
        notify.error('Could not find an address in that text.')
        return
      }
      onChange(patch)
      notify.success(`Autofilled ${filled} field${filled === 1 ? '' : 's'} — please review.`)
      setPasteOpen(false)
      setPasteText('')
    } catch (err) {
      notify.apiError(err, 'AI autofill failed. Enter the address manually.')
    } finally {
      setParsing(false)
    }
  }

  return (
    <>
      <div className="mb-3">
        {pasteOpen ? (
          <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0] p-2.5">
            <textarea
              className={`${inputCls} min-h-[64px] resize-y`}
              value={pasteText}
              onChange={(e) => setPasteText(e.target.value)}
              placeholder="Paste an address, email signature, or order text — e.g. “Jane Doe, Acme Inc, 123 Market St Suite 400, Buffalo NY 14201, +1 212 555 0100”"
              autoFocus
            />
            <div className="mt-2 flex items-center gap-2">
              <button
                type="button"
                onClick={() => void runParse()}
                disabled={parsing || !pasteText.trim()}
                className="inline-flex items-center gap-1.5 rounded-lg bg-[#412d15] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#1f150c] disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiZap className="h-3.5 w-3.5" />
                {parsing ? 'Reading…' : 'Autofill fields'}
              </button>
              <button
                type="button"
                onClick={() => { setPasteOpen(false); setPasteText('') }}
                className="rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
              >
                Cancel
              </button>
            </div>
          </div>
        ) : (
          <div className="flex items-center gap-2">
            {extraAction ? <div className="min-w-0 flex-1">{extraAction}</div> : null}
            <button
              type="button"
              onClick={() => setPasteOpen(true)}
              className="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-dashed border-[#cdbf9f] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#412d15] hover:bg-[#faf7f0]"
            >
              <FiZap className="h-3.5 w-3.5" />
              Paste &amp; autofill with AI
            </button>
          </div>
        )}
      </div>
      <div className="grid grid-cols-2 gap-3">
      <Field label="Full name" required error={errors?.name} className="col-span-2 sm:col-span-1">
        <input className={inputCls} value={value.name} onChange={(e) => onChange({ name: e.target.value })} placeholder="Jane Doe" />
      </Field>
      <Field label="Company" error={errors?.company} className="col-span-2 sm:col-span-1">
        <input className={inputCls} value={value.company} onChange={(e) => onChange({ company: e.target.value })} placeholder="Acme Inc." />
      </Field>
      <Field label="Address line 1" required error={errors?.addressLine1} className="col-span-2">
        <input className={inputCls} value={value.addressLine1} onChange={(e) => onChange({ addressLine1: e.target.value })} placeholder="123 Market St" />
      </Field>
      <Field label="Address line 2" error={errors?.addressLine2} className="col-span-2">
        <input className={inputCls} value={value.addressLine2} onChange={(e) => onChange({ addressLine2: e.target.value })} placeholder="Suite 400" />
      </Field>
      {!hideLine3 && (value.addressLine2 || value.addressLine3) ? (
        <Field
          label="Address line 3"
          title="Needed for some JP / CN / IN addresses that span three street lines"
          className="col-span-2"
        >
          <input
            className={inputCls}
            value={value.addressLine3 ?? ''}
            onChange={(e) => onChange({ addressLine3: e.target.value })}
            placeholder="Chiyoda-ku, Nihonbashi"
          />
        </Field>
      ) : null}
      <div className="col-span-2 grid grid-cols-3 gap-3">
        <Field label="City" required error={errors?.city}>
          <input className={inputCls} value={value.city} onChange={(e) => onChange({ city: e.target.value })} placeholder="Buffalo" />
        </Field>
        <Field label="State / region" error={errors?.state}>
          {/* Sprint 51 — for countries where carriers demand a real code
              (US / CA / AU), render a dropdown so operators can't type
              'Delaware' and get downstream rate/label rejection. Free-text
              input stays for every other country. */}
          {(() => {
            const options = STATE_CODE_OPTIONS[(value.countryCode || '').toUpperCase()]
            return options ? (
              <select
                className={inputCls}
                value={value.state}
                onChange={(e) => onChange({ state: e.target.value })}
              >
                <option value="">Select…</option>
                {options.map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.code} — {s.label}
                  </option>
                ))}
              </select>
            ) : (
              <input
                className={inputCls}
                value={value.state}
                onChange={(e) => onChange({ state: e.target.value })}
                placeholder="NY"
              />
            )
          })()}
        </Field>
        <Field label="Postal code" required error={errors?.postalCode}>
          <input className={inputCls} value={value.postalCode} onChange={(e) => onChange({ postalCode: e.target.value })} placeholder={postalPlaceholderFor(value.countryCode)} />
        </Field>
      </div>
      <div className="col-span-2 grid grid-cols-3 gap-3">
        <Field label="Country" required error={errors?.countryCode}>
          <CountrySelect
            value={value.countryCode}
            onChange={(code) => {
              // Picking a country auto-fills its dial code, but never clobbers a
              // dial code the user typed that doesn't match the previous country.
              const nextDial = dialCodeFor(code)
              const current = (value.phoneCountryCode ?? '').trim()
              const wasAutoFilled = !current || current === dialCodeFor(value.countryCode)
              onChange({
                countryCode: code,
                ...(nextDial && wasAutoFilled ? { phoneCountryCode: nextDial } : {}),
              })
            }}
          />
        </Field>
        <Field label="Phone country code" error={errors?.phoneCountryCode} title="ISO dial code without the plus — auto-filled from the country; e.g. 1 for US, 44 for GB, 91 for IN">
          <input
            className={inputCls}
            value={value.phoneCountryCode ?? ''}
            onChange={(e) => onChange({ phoneCountryCode: e.target.value.replace(/[^\d]/g, '') })}
            placeholder={dialCodeFor(value.countryCode) || '44'}
            inputMode="numeric"
            maxLength={4}
          />
        </Field>
        <Field label="Phone" error={errors?.phone}>
          <input className={inputCls} value={value.phone} onChange={(e) => onChange({ phone: e.target.value })} placeholder="2125550100" />
        </Field>
      </div>
      <div className="col-span-2 grid grid-cols-1 gap-3 sm:grid-cols-2 sm:items-end">
        {withEmail ? (
          <Field label="Email" error={errors?.email}>
            <input className={inputCls} value={value.email} onChange={(e) => onChange({ email: e.target.value })} placeholder="jane@acme.com" />
          </Field>
        ) : null}
        <label
          className={`mt-1 flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[12px] font-semibold text-slate-700 ${withEmail ? '' : 'sm:col-span-2'}`}
          title="UPS + FedEx charge a residential surcharge on international to homes"
        >
          <input
            type="checkbox"
            checked={Boolean(value.residential)}
            onChange={(e) => onChange({ residential: e.target.checked })}
            className="h-4 w-4 shrink-0 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
          />
          <span>Residential address</span>
        </label>
      </div>
      </div>
    </>
  )
}

const CUSTOM_PKG = 'CUSTOM'

/** Join non-empty parts with a separator (skips blanks). */
const joinParts = (parts: (string | null | undefined)[], sep = ', ') =>
  parts.map((p) => (p || '').trim()).filter(Boolean).join(sep)

export default function NewShipmentPage() {
  const navigate = useNavigate()

  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [packages, setPackages] = useState<PackagePreset[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [profiles, setProfiles] = useState<CustomsProfile[]>([])
  // Per-shipment importer/broker override (null = use the client's saved profile).
  const [override, setOverride] = useState<{ importer: Party; broker: Party } | null>(null)
  const [overrideEditorOpen, setOverrideEditorOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)

  const [sender, setSender] = useState<ManualShipmentAddress>(defaultSender())
  const [recipient, setRecipient] = useState<ManualShipmentAddress>(blankAddress())
  // SHIPMENT = outbound (you → customer); RETURN = reverse (customer → you).
  const [mode, setMode] = useState<'SHIPMENT' | 'RETURN'>('SHIPMENT')
  const isReturn = mode === 'RETURN'

  const [carrier, setCarrier] = useState('')
  const [accountNumber, setAccountNumber] = useState('') // bill-to account, manually editable
  const [serviceId, setServiceId] = useState<number | ''>('')
  const [incoterms, setIncoterms] = useState('DDP') // DAP | DDP — who pays duties/taxes
  const [packageChoice, setPackageChoice] = useState<string>('') // preset id as string, or CUSTOM_PKG
  const [length, setLength] = useState('')
  const [width, setWidth] = useState('')
  const [height, setHeight] = useState('')
  const [dimUnit, setDimUnit] = useState<'IN' | 'CM'>('IN')
  const [weight, setWeight] = useState('')
  const [weightUnit, setWeightUnit] = useState<'LB' | 'KG'>('LB')
  const [declaredValue, setDeclaredValue] = useState('')
  const [clientCode, setClientCode] = useState('')
  // Sprint 35 — signature at delivery + insured value beyond the
  // carrier's free tier. Signature is a per-shipment enum; insured
  // value is a separate money amount from declared/customs value.
  const [signatureOption, setSignatureOption] = useState<'NONE' | 'INDIRECT' | 'DIRECT' | 'ADULT'>('NONE')
  const [insuredValue, setInsuredValue] = useState('')
  // Sprint 22 — rate picker: opens the RatePickerModal with current form
  // state; on select we populate carrier + serviceId + accountNumber.
  const [ratePickerOpen, setRatePickerOpen] = useState(false)
  // Sprint 27 — dangerous goods declaration. Null on non-hazmat shipments;
  // populated by DangerousGoodsWizard and threaded into the manual
  // shipment payload as dangerousGoods.
  const [dgBlock, setDgBlock] = useState<DangerousGoodsBlock | null>(null)
  const [dgWizardOpen, setDgWizardOpen] = useState(false)
  // Sprint 32 — landed cost modal. Opens on demand for cross-border
  // shipments; backend routes to UPS Landed Cost / FedEx EDT / DHL
  // Duties + Taxes based on the carrier the operator picked.
  const [landedCostOpen, setLandedCostOpen] = useState(false)
  // Sprint 29 — multi-package. The first box uses the existing top-level
  // weight/dims/packaging fields; extraPackages holds boxes 2..N. When
  // non-empty the payload emits packages[] (backend keys off effective-
  // Packages()); when empty the existing single-package payload is sent.
  type ExtraPackage = {
    weight: string
    length: string
    width: string
    height: string
    packageType: string  // may be empty → falls back to shipment-level
    declaredValue: string
  }
  const blankExtraPackage = (): ExtraPackage => ({
    weight: '', length: '', width: '', height: '',
    packageType: '', declaredValue: '',
  })
  const [extraPackages, setExtraPackages] = useState<ExtraPackage[]>([])

  // Sprint 43 — custom field values keyed by fieldKey.
  const [customFieldValues, setCustomFieldValues] = useState<Record<string, string>>({})

  // 3PL guardrails: client's attached warehouses, allowlists, and destination
  // rules. Fetched when clientCode changes; drive the warehouse picker,
  // filtered dropdowns, and the ship-to warning banner.
  const [clientWarehouses, setClientWarehouses] = useState<ClientWarehouse[]>([])
  const [warehouseCode, setWarehouseCode] = useState('')
  /** Set of service ids on the client's allowlist. null = client has no
   *  allowlist yet, treat as unrestricted so shipments still ship. */
  const [allowedServiceIds, setAllowedServiceIds] = useState<Set<number> | null>(null)
  const [allowedPackageIds, setAllowedPackageIds] = useState<Set<number> | null>(null)
  /** Client's default from ClientAllowedService.isDefault — used to preselect
   *  the picker on client change rather than falling back to sort-order first. */
  const [defaultServiceId, setDefaultServiceId] = useState<number | null>(null)
  const [defaultPackagePresetId, setDefaultPackagePresetId] = useState<number | null>(null)
  const [destRules, setDestRules] = useState<ClientDestinationRules | null>(null)

  // Recipient address validation result (from the Validate button).
  const [recipientCheck, setRecipientCheck] = useState<{ valid: boolean; issues: string[] } | null>(null)
  // Sprint 31 — carrier-side address validation result (from the Validate with carrier button).
  const [carrierAddressResult, setCarrierAddressResult] = useState<AddressValidationResponse | null>(null)
  const [carrierValidating, setCarrierValidating] = useState(false)
  // Sprint 38 — saved recipients: address-book search + save UI.
  const [recipientSearch, setRecipientSearch] = useState('')
  const [recipientSuggestions, setRecipientSuggestions] = useState<SavedRecipient[]>([])
  const [recipientDropdownOpen, setRecipientDropdownOpen] = useState(false)

  // AI-assist per-section busy flags + pre-ship review result.
  const [pkgBusy, setPkgBusy] = useState(false)
  const [svcBusy, setSvcBusy] = useState(false)
  const [reviewBusy, setReviewBusy] = useState(false)
  const [reviewWarnings, setReviewWarnings] = useState<ShipmentWarning[] | null>(null)

  // International commercial-invoice line items (shown only for cross-border lanes).
  // Sprint 48 B11 — each item can be assigned to a physical package (boxSeq).
  // When every item is assigned, backend derives per-package declared value
  // from sum(unitValue × quantity in that box). null boxSeq = unassigned
  // (falls into box 1 by default; treated as legacy single-box CI).
  type ItemRow = NewShipmentItemRow
  const blankItem = (): ItemRow => ({ description: '', sku: '', hsCode: '', countryOfOrigin: '', quantity: '1', unitValue: '', boxSeq: '' })
  const [items, setItems] = useState<ItemRow[]>([blankItem()])
  // Reason of export + currency are sticky — they prefill from the last shipment.
  const [reasonForExport, setReasonForExport] = useState(() => readSticky('ms:lastReason', 'SALE'))
  const [currency, setCurrency] = useState(() => readSticky('ms:lastCurrency', 'USD'))

  // Optional guided-wizard for the customs section. Off by default; users
  // click "Open guided wizard" to enter a 4-step flow that maps onto the
  // same state as the inline form. Closing without Save preserves the
  // inline form's current values.
  const [wizardOpen, setWizardOpen] = useState(false)

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const [accs, catalog, presets, clientPage] = await Promise.all([
          accountRefService.listAccounts(),
          shippingConfigService.catalog(),
          shippingConfigService.listPresets(),
          clientService.listClients({ status: 'ACTIVE', size: 200 }),
        ])
        if (!alive) return
        setAccounts(accs.filter((a) => a.active))
        setServices(catalog.services.filter((s) => s.enabled))
        setPackages(presets)
        setClients(clientPage.data?.content ?? [])
      } catch (e) {
        notify.apiError(e, 'Failed to load shipment options.')
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => {
      alive = false
    }
  }, [])

  // Only offer carriers you can actually ship with: an active account AND a live service.
  const carrierOptions = useMemo(() => {
    const acctCarriers = new Set(accounts.map((a) => canon(a.carrierCode)))
    const svcCarriers = new Set(services.map((s) => canon(s.carrier)))
    return [...acctCarriers].filter((c) => svcCarriers.has(c)).sort()
  }, [accounts, services])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot default carrier pick when options first populate; deriving at render would fight explicit user picks
    if (!carrier && carrierOptions.length) setCarrier(carrierOptions[0])
  }, [carrierOptions, carrier])

  // The selected client's importer/broker profiles (per destination-country set).
  useEffect(() => {
    if (!clientCode) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale profiles when client is cleared; async fetch below repopulates for a non-empty client
      setProfiles([])
      return
    }
    let alive = true
    customsProfileService
      .list(clientCode)
      .then((ps) => alive && setProfiles(ps))
      .catch(() => alive && setProfiles([]))
    return () => {
      alive = false
    }
  }, [clientCode])

  // Client-scoped 3PL config: warehouses, service/package allowlists, ship-to
  // rules. Load in one round-trip; the page silently degrades on failure so a
  // half-configured client still ships.
  useEffect(() => {
    if (!clientCode) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- reset client-scoped config when no client is picked; the async fetch below repopulates for a non-empty client
      setClientWarehouses([])
      setWarehouseCode('')
      setAllowedServiceIds(null)
      setAllowedPackageIds(null)
      setDefaultServiceId(null)
      setDefaultPackagePresetId(null)
      setDestRules(null)
      return
    }
    let alive = true
    ;(async () => {
      try {
        const [whResp, svcResp, pkgResp, destResp] = await Promise.all([
          clientWarehouseService.listForClient(clientCode),
          clientAllowedServicesService.listForClient(clientCode),
          clientAllowedPackagesService.listForClient(clientCode),
          clientDestinationsService.get(clientCode),
        ])
        if (!alive) return
        const warehouses = whResp.data ?? []
        setClientWarehouses(warehouses)
        // Auto-pick the default; else first available. Empty = keep whatever
        // sender address applyClient already produced.
        const def = warehouses.find((w) => w.isDefault) || warehouses[0] || null
        setWarehouseCode(def?.warehouse?.code ?? '')
        // Empty allowlists mean "not yet configured" — treat as unrestricted so
        // shipping still works. Phase 4 flips this to strict once the backend
        // resolver rejects on empty.
        const svcs = svcResp.data ?? []
        setAllowedServiceIds(svcs.length ? new Set(svcs.map((s) => s.serviceId)) : null)
        setDefaultServiceId(svcs.find((s) => s.isDefault)?.serviceId ?? null)
        const pkgs = pkgResp.data ?? []
        setAllowedPackageIds(pkgs.length ? new Set(pkgs.map((p) => p.presetId)) : null)
        setDefaultPackagePresetId(pkgs.find((p) => p.isDefault)?.presetId ?? null)
        setDestRules(destResp.data ?? null)
      } catch {
        // Silent degrade: same behaviour as before the 3PL settings existed.
      }
    })()
    return () => {
      alive = false
    }
  }, [clientCode])

  // When the picked warehouse changes, overwrite the sender block with the
  // warehouse's address. Only overwrites fields the warehouse actually has, so
  // manual edits on unfilled columns aren't clobbered.
  useEffect(() => {
    if (!warehouseCode) return
    const cw = clientWarehouses.find((w) => w.warehouse?.code === warehouseCode)
    const a = cw?.warehouse?.address
    if (!a) return
    // eslint-disable-next-line react-hooks/set-state-in-effect -- overwrite sender block with warehouse address when picker changes; depends on prior sender state to preserve unfilled fields, not derivable at render
    setSender((cur) => ({
      ...cur,
      ...(a.name ? { name: a.name } : {}),
      ...(a.line1 ? { addressLine1: a.line1 } : {}),
      ...(a.line2 != null ? { addressLine2: a.line2 || '' } : {}),
      ...(a.city ? { city: a.city } : {}),
      ...(a.state ? { state: a.state } : {}),
      ...(a.zip ? { postalCode: a.zip } : {}),
      ...(a.country ? { countryCode: a.country } : {}),
      ...(a.phone ? { phone: a.phone } : {}),
    }))
  }, [warehouseCode, clientWarehouses])

  // Route: does this shipment cross a customs border? Drives which services/packages apply.
  const EU = new Set([
    'AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU', 'IE', 'IT',
    'LV', 'LT', 'LU', 'MT', 'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE',
  ])
  const sameTerritory = (a: string, b: string) => a === b || (EU.has(a) && EU.has(b))
  const isInternational =
    !!sender.countryCode &&
    !!recipient.countryCode &&
    !sameTerritory(sender.countryCode.toUpperCase(), recipient.countryCode.toUpperCase())
  const neededScope: 'DOMESTIC' | 'INTERNATIONAL' = isInternational ? 'INTERNATIONAL' : 'DOMESTIC'
  const scopeFits = (scope?: string | null) => !scope || scope === 'BOTH' || scope === neededScope

  const originMatch = (o?: string | null) => (o ?? 'US').toUpperCase() === (sender.countryCode || 'US').toUpperCase()

  // Sprint 22 — build a rate-shop request from the current form state.
  // Postal codes + weight are the minimum required set; the picker disables
  // its trigger button when they're missing.
  const rateShopRequest = useMemo<RateShopRequest>(() => ({
    shipment: {
      carrierCode: carrier || undefined,
      accountNumber: accountNumber || undefined,
      packageType: packageChoice || undefined,
      weight: Number(weight) || 0,
      weightUnit,
      length: length ? Number(length) : undefined,
      width: width ? Number(width) : undefined,
      height: height ? Number(height) : undefined,
      dimUnit,
      shipperPostalCode: sender.postalCode || '',
      shipperCountryCode: sender.countryCode || 'US',
      shipperCity: sender.city || undefined,
      shipperState: sender.state || undefined,
      shipperName: sender.name || undefined,
      shipperAddressLine1: sender.addressLine1 || undefined,
      recipientPostalCode: recipient.postalCode || '',
      recipientCountryCode: recipient.countryCode || 'US',
      recipientCity: recipient.city || undefined,
      recipientState: recipient.state || undefined,
      recipientName: recipient.name || undefined,
      recipientAddressLine1: recipient.addressLine1 || undefined,
      recipientResidential: recipient.residential,
      declaredValue: declaredValue ? Number(declaredValue) : undefined,
    },
    customerNo: clientCode || null,
    // No carriers whitelist — let the backend fan out to every configured
    // carrier so the picker can compare across the tenant's full inventory.
  }), [carrier, accountNumber, packageChoice, weight, weightUnit, length, width, height, dimUnit,
        sender, recipient, declaredValue, clientCode])

  const canOpenRatePicker = Boolean(
    rateShopRequest.shipment.weight > 0
    && rateShopRequest.shipment.shipperPostalCode
    && rateShopRequest.shipment.recipientPostalCode,
  )

  /**
   * Sprint 32 — landed cost request built from the current form. Requires
   * a picked carrier, a cross-border lane, and a customs block (declared
   * value + items) — USPS returns NOT_SUPPORTED regardless, and domestic
   * lanes come back with source=NOT_SUPPORTED from every carrier.
   */
  const landedCostRequest = useMemo<LandedCostRequest>(() => ({
    carrierCode: carrier || '',
    customerNo: clientCode || null,
    shipment: {
      ...rateShopRequest.shipment,
      declaredValueCurrency: currency,
    },
  }), [carrier, clientCode, rateShopRequest.shipment, currency])

  const canEstimateLandedCost = Boolean(
    canOpenRatePicker
    && carrier
    && isInternational
    && Number(declaredValue) > 0,
  )

  /**
   * Handle a picker selection: switch the carrier, look up the numeric
   * serviceId from the catalog, and pick a matching account. Ignores the
   * option's currency + price — those are informational for the operator;
   * the actual bill still comes from the carrier's own rate engine at
   * label time.
   */
  const handleRateSelected = (option: RateOption) => {
    const nextCarrier = canon(option.carrierCode)
    if (nextCarrier && nextCarrier !== carrier) setCarrier(nextCarrier)
    // Look up the numeric serviceId that matches the carrier's service code.
    // If the tenant hasn't loaded that service into their catalog yet we
    // clear the picker so they know to add it — a stale value would silently
    // ship on the wrong service.
    const match = services.find(
      (s) => canon(s.carrier) === nextCarrier && s.serviceCode === option.serviceCode,
    )
    setServiceId(match?.id ?? '')
    // Prefer the account associated with the credentials that produced this
    // quote; fall back to the current selection if we can't identify it.
    const preferredAccount = accounts.find(
      (a) => canon(a.carrierCode) === nextCarrier
        && (!clientCode || (a.customerNo || '').toUpperCase() === clientCode.toUpperCase()),
    )
    if (preferredAccount?.accountNumber) setAccountNumber(preferredAccount.accountNumber)
    notify.success(
      `Picked ${option.carrierCode} ${option.serviceName ?? option.serviceCode}${
        match ? '' : ' — service not in your catalog; add it before generating a label.'
      }`,
    )
  }
  const accountsForCarrier = useMemo(() => {
    const onCarrier = accounts.filter((a) => canon(a.carrierCode) === carrier)
    if (!clientCode) return onCarrier
    const own = onCarrier.filter((a) => (a.customerNo || '').toUpperCase() === clientCode.toUpperCase())
    return own.length ? own : onCarrier
  }, [accounts, carrier, clientCode])
  // Services offered on THIS route: right carrier, ship-from country, and
  // domestic/international scope — then further filtered to the client's
  // allowlist when one exists.
  const servicesForCarrier = useMemo(
    () =>
      services
        .filter((s) => canon(s.carrier) === carrier && originMatch(s.originCountry) && scopeFits(s.scope))
        .filter((s) => !allowedServiceIds || allowedServiceIds.has(s.id)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [services, carrier, sender.countryCode, neededScope, allowedServiceIds],
  )
  const packagesForCarrier = useMemo(
    () =>
      packages
        .filter((p) => p.kind === 'CARRIER' && canon(p.carrier) === carrier && originMatch(p.originCountry) && scopeFits(p.scope))
        .filter((p) => !allowedPackageIds || p.id == null || allowedPackageIds.has(p.id)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [packages, carrier, sender.countryCode, neededScope, allowedPackageIds],
  )
  const customBoxes = useMemo(
    () =>
      packages
        .filter((p) => p.kind !== 'CARRIER')
        .filter((p) => !allowedPackageIds || p.id == null || allowedPackageIds.has(p.id)),
    [packages, allowedPackageIds],
  )

  // Ship-to gate. mode=null => no rule, everything allowed. ALLOW list =>
  // country must be listed. DENY list => country must NOT be listed. Shown as
  // an inline warning; submit is not blocked here — Phase 4 backend enforces
  // the hard 422 SHIP_TO_DENIED.
  const destAllowed = useMemo(() => {
    const country = (recipient.countryCode || '').toUpperCase()
    if (!destRules || !destRules.mode || !country) return true
    const listed = destRules.countries.some((c) => (c || '').toUpperCase() === country)
    return destRules.mode === 'ALLOW' ? listed : !listed
  }, [destRules, recipient.countryCode])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- re-validate account/service/package selections when carrier/client/route changes; depends on prior state to preserve user picks that are still valid, not derivable at render
    setAccountNumber((cur) =>
      accountsForCarrier.some((a) => (a.accountNumber || '').toLowerCase() === cur.trim().toLowerCase())
        ? cur
        : accountsForCarrier[0]?.accountNumber ?? cur,
    )
    // Phase 5f — prefer the client's default from ClientAllowedService.isDefault
    // over "first available in filtered list". Falls back to first when the
    // client has no default configured or the default isn't in the current
    // carrier/scope filter.
    setServiceId((cur) => {
      if (servicesForCarrier.some((s) => s.id === cur)) return cur
      if (defaultServiceId != null && servicesForCarrier.some((s) => s.id === defaultServiceId)) {
        return defaultServiceId
      }
      return servicesForCarrier[0]?.id ?? ''
    })
    setPackageChoice((cur) => {
      if (cur === CUSTOM_PKG || packagesForCarrier.some((p) => String(p.id) === cur)) return cur
      if (
        defaultPackagePresetId != null
        && packagesForCarrier.some((p) => p.id === defaultPackagePresetId)
      ) {
        return String(defaultPackagePresetId)
      }
      return packagesForCarrier[0]?.id != null
        ? String(packagesForCarrier[0]?.id)
        : CUSTOM_PKG
    })
    // Re-validate account/service/package whenever the carrier, client, or route changes.
  }, [carrier, clientCode, accountsForCarrier, servicesForCarrier, packagesForCarrier, defaultServiceId, defaultPackagePresetId])

  /**
   * Select a client: fill YOUR address on the correct side and auto-pick its
   * default carrier + account. For a shipment your address is the origin (sender);
   * for a return it's the destination (recipient), taken from the return address.
   */
  const applyClient = (code: string) => {
    setClientCode(code)
    const client = clients.find((c) => c.clientCode === code)
    if (!client) return
    const yourAddr = isReturn ? client.returnAddress ?? client.shipFrom : client.shipFrom
    // Always reset to defaultSender() before overlay — otherwise switching
    // from Client A (with shipFrom) to Client B (without) would leave A's
    // address in place. The subsequent warehouse-change effect re-overlays
    // if the newly-picked client has warehouses attached.
    const base = defaultSender()
    const merged: ManualShipmentAddress = yourAddr
      ? {
          name: client.name || base.name,
          company: client.name || base.company,
          phone: client.phone || base.phone,
          email: client.email || base.email,
          addressLine1: yourAddr.line1 || base.addressLine1,
          addressLine2: yourAddr.line2 || '',
          city: yourAddr.city || base.city,
          state: yourAddr.state || base.state,
          postalCode: yourAddr.zip || base.postalCode,
          countryCode: yourAddr.country || base.countryCode,
        }
      : base
    if (isReturn) setRecipient(merged)
    else setSender(merged)
    const accts = (client.carrierAccounts || []).filter((a) => a.active)
    const def = accts.find((a) => a.clientDefault) || accts[0]
    if (def) {
      const canonical = canon(def.carrierCode)
      if (carrierOptions.includes(canonical)) {
        setCarrier(canonical)
        setAccountNumber(def.accountNumber || '')
      } else {
        // Client's default account is for a carrier this workspace hasn't
        // connected. Silently skipping would leave the operator wondering
        // why the picker didn't pre-fill; toast so it's obvious what to do.
        notify.info(
          `${client.name || client.clientCode}'s default carrier ${canonical} isn't connected in this workspace — pick a carrier manually.`,
        )
      }
    }
  }

  /** Toggle Shipment ⇆ Return. A return is the swapped shipment, so flip the two parties. */
  const switchMode = (next: 'SHIPMENT' | 'RETURN') => {
    if (next === mode) return
    setSender(recipient)
    setRecipient(sender)
    setReasonForExport(next === 'RETURN' ? 'RETURN' : 'SALE')
    setMode(next)
  }

  const isCustomPkg = packageChoice === CUSTOM_PKG

  // ── Yup + Formik validation ────────────────────────────────────────────────
  // The page keeps its own useState for each field (needed by AI autofill, the
  // rate picker, warehouse resolution, etc.), so Formik is wired as a pure
  // validation layer: we mirror the live state into `formValues`, feed it to
  // useFormik via enableReinitialize, and read back `formik.errors`. Messages
  // only render once the user has attempted "Generate label" (submitAttempted).
  const [submitAttempted, setSubmitAttempted] = useState(false)
  // Non-blocking validation toast (replaces the old error modal). Field-level
  // red messages under each input remain the primary guidance.
  const [toast, setToast] = useState<{ id: number; title: string; body: string } | null>(null)
  const showToast = (body: string, title = 'A few details need fixing') =>
    setToast({ id: Date.now(), title, body })
  useEffect(() => {
    if (!toast) return
    const t = window.setTimeout(() => setToast(null), 6000)
    return () => window.clearTimeout(t)
  }, [toast])
  /** Smooth-scroll the first inline error message into view after a failed submit. */
  const scrollToFirstError = () => {
    window.setTimeout(() => {
      const el = document.querySelector('.ms-field-error')
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }, 60)
  }
  const pickAddr = (a: ManualShipmentAddress) => ({
    name: a.name ?? '',
    company: a.company ?? '',
    addressLine1: a.addressLine1 ?? '',
    addressLine2: a.addressLine2 ?? '',
    city: a.city ?? '',
    state: a.state ?? '',
    postalCode: a.postalCode ?? '',
    countryCode: a.countryCode ?? '',
    phone: a.phone ?? '',
    phoneCountryCode: a.phoneCountryCode ?? '',
    email: a.email ?? '',
  })
  const formValues = useMemo(
    () => ({
      isInternational,
      isCustomPackage: isCustomPkg,
      carrier,
      account: accountNumber,
      incoterms,
      reasonForExport,
      currency,
      sender: pickAddr(sender),
      recipient: pickAddr(recipient),
      weight,
      declaredValue,
      insuredValue,
      length,
      width,
      height,
      items: items.map((it) => ({
        description: it.description,
        sku: it.sku,
        hsCode: it.hsCode,
        countryOfOrigin: it.countryOfOrigin,
        quantity: it.quantity,
        unitValue: it.unitValue,
      })),
    }),
    [isInternational, isCustomPkg, carrier, accountNumber, incoterms, reasonForExport,
      currency, sender, recipient, weight, declaredValue, insuredValue, length, width, height, items],
  )
  const formik = useFormik<ShipmentFormValues>({
    initialValues: formValues as unknown as ShipmentFormValues,
    enableReinitialize: true,
    validationSchema: shipmentSchema,
    onSubmit: () => {},
  })
  /** Field error at `path` — only surfaced after a submit attempt. */
  const errAt = (path: string): string | undefined =>
    submitAttempted ? (getIn(formik.errors, path) as string | undefined) : undefined
  /** Whole address-block error map for AddressBlock (sender / recipient). */
  const addrErrors = (prefix: 'sender' | 'recipient'): AddressErrors | undefined => {
    if (!submitAttempted) return undefined
    return getIn(formik.errors, prefix) as AddressErrors | undefined
  }
  /** Per-item error map (description, quantity, unitValue, …). */
  const itemErrAt = (index: number, field: string): string | undefined =>
    submitAttempted ? (getIn(formik.errors, `items[${index}].${field}`) as string | undefined) : undefined

  // Importer/broker resolve from the client's profile covering the destination country.
  const destCountry = (recipient.countryCode || '').toUpperCase()
  const importerProfile = useMemo(
    () =>
      isInternational && clientCode
        ? profiles.find((p) => (p.countries || []).some((c) => (c || '').toUpperCase() === destCountry)) ?? null
        : null,
    [profiles, isInternational, clientCode, destCountry],
  )

  /** Map a saved profile into the flat importer/broker shape (label-document keys). */
  const partiesFromProfile = (p: CustomsProfile): { importer: Party; broker: Party } => ({
    importer: {
      type: p.importerType || 'BUSINESS',
      name: p.importerName || '', contact: p.importerContact || '', phone: p.importerPhone || '',
      addressLine1: p.importerAddress1 || '', addressLine2: p.importerAddress2 || '',
      city: p.importerCity || '', state: p.importerState || '', postalCode: p.importerPostcode || '',
      countryCode: p.importerCountry || '', taxId: p.importerTaxId || '', taxIdType: p.importerTaxIdType || '',
      eori: p.importerEori || '', ioss: p.importerIoss || '', companyReg: p.importerCompanyReg || '',
      iec: p.importerIec || '', gstin: p.importerGstin || '',
    },
    broker: {
      name: p.brokerName || '', company: p.brokerCompany || '', countryCode: p.brokerCountry || '',
      addressLine1: p.brokerAddress1 || '', addressLine2: p.brokerAddress2 || '', phone: p.brokerPhone || '',
      city: p.brokerCity || '', state: p.brokerState || '', postalCode: p.brokerPostcode || '',
      brokerId: p.brokerId || '', license: p.brokerLicense || '',
    },
  })

  // What the card shows: the override wins; otherwise the resolved client profile.
  const activeParties = override ?? (importerProfile ? partiesFromProfile(importerProfile) : null)
  const vImp = activeParties?.importer
  const vBrk = activeParties?.broker
  const editorSeed = activeParties ?? { importer: { type: 'BUSINESS', countryCode: destCountry }, broker: {} }

  // A new destination or client re-resolves — drop any override tied to the old one.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale importer/broker override on destination/client change; must fire post-render, not derivable
    setOverride(null)
  }, [clientCode, destCountry])

  // Sticky defaults: remember the last reason/currency for the next shipment.
  useEffect(() => writeSticky('ms:lastReason', reasonForExport), [reasonForExport])
  useEffect(() => writeSticky('ms:lastCurrency', currency), [currency])

  // A changed recipient invalidates a previous validation result.
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- invalidate stale validation on recipient edit; forces re-validation before submit, cannot be derived at render
    setRecipientCheck(null)
  }, [recipient.addressLine1, recipient.city, recipient.state, recipient.postalCode, recipient.countryCode])

  /**
   * Sprint 31 — validate the ship-to address against the SELECTED carrier's
   * own database (UPS AVS / FedEx AV / DHL address-validate / SWSIM
   * CleanseAddress). Complements the platform-side check by confirming the
   * carrier can actually deliver the parcel + returning residential/commercial
   * classification.
   */
  const validateRecipientWithCarrier = async () => {
    if (!carrier) {
      notify.error('Pick a carrier first — validation is carrier-specific.')
      return
    }
    setCarrierValidating(true)
    try {
      const res = await addressValidationService.validate({
        carrierCode: carrier,
        customerNo: clientCode.trim() || null,
        name: recipient.name || undefined,
        addressLine1: recipient.addressLine1,
        addressLine2: recipient.addressLine2 || undefined,
        addressLine3: recipient.addressLine3 || undefined,
        city: recipient.city,
        state: recipient.state || undefined,
        postalCode: recipient.postalCode,
        countryCode: recipient.countryCode,
      })
      const d = res.data
      // Sprint 51 polish — FedEx/UPS/DHL Address Validation is lenient about
      // state names (accepts "Delaware" and reports Matched=true), but the
      // subsequent Rate / Ship APIs need a 2-letter code. Detect the
      // mismatch client-side and append it to the banner's warnings so the
      // green banner doesn't give false confidence.
      const decorated = decorateWithStateWarning(d ?? null, recipient.countryCode, recipient.state)
      setCarrierAddressResult(decorated)
      if (decorated?.valid) {
        notify.success(`${carrier}: ${decorated.matchLevel} match.`)
      } else if (decorated) {
        notify.error(`${carrier}: ${decorated.message}`)
      }
    } catch (e) {
      notify.apiError(e, 'Carrier address validation failed.')
    } finally {
      setCarrierValidating(false)
    }
  }

  /**
   * Sprint 38 — search the address book. Fires on every input change
   * (debounce-free — the endpoint caps at 25 hits and returns fast).
   * Empty / < 2 chars → clear suggestions.
   */
  const runRecipientSearch = async (q: string) => {
    setRecipientSearch(q)
    if (!q || q.trim().length < 2) {
      setRecipientSuggestions([])
      setRecipientDropdownOpen(false)
      return
    }
    try {
      const hits = await recipientBookService.search(q, clientCode || null)
      setRecipientSuggestions(hits)
      setRecipientDropdownOpen(hits.length > 0)
    } catch {
      setRecipientSuggestions([])
      setRecipientDropdownOpen(false)
    }
  }

  /** Apply a saved recipient — overwrites the current recipient block. */
  const applySavedRecipient = (r: SavedRecipient) => {
    setRecipient((cur) => ({
      ...cur,
      name: r.name ?? cur.name,
      company: r.company ?? cur.company,
      phone: r.phone ?? cur.phone,
      phoneCountryCode: r.phoneCountryCode ?? cur.phoneCountryCode,
      email: r.email ?? cur.email,
      addressLine1: r.addressLine1 ?? cur.addressLine1,
      addressLine2: r.addressLine2 ?? cur.addressLine2,
      addressLine3: r.addressLine3 ?? cur.addressLine3,
      city: r.city ?? cur.city,
      state: r.state ?? cur.state,
      postalCode: r.postalCode ?? cur.postalCode,
      countryCode: r.countryCode ?? cur.countryCode,
      residential: r.residential ?? cur.residential,
    }))
    setRecipientSearch(r.name)
    setRecipientSuggestions([])
    setRecipientDropdownOpen(false)
    notify.success(`Loaded ${r.name} from the address book.`)
  }

  /** Apply the carrier's suggested address to the recipient block. */
  const applyCarrierSuggestion = () => {
    const s = carrierAddressResult?.suggested
    if (!s) return
    setRecipient((cur) => ({
      ...cur,
      addressLine1: s.addressLine1 ?? cur.addressLine1,
      addressLine2: s.addressLine2 ?? cur.addressLine2,
      addressLine3: s.addressLine3 ?? cur.addressLine3,
      city: s.city ?? cur.city,
      state: s.state ?? cur.state,
      postalCode: s.postalCode ?? cur.postalCode,
      countryCode: s.countryCode ?? cur.countryCode,
    }))
    setCarrierAddressResult(null)
    notify.success('Applied carrier-suggested address.')
  }

  // Wizard payload = a snapshot of the current inline state in the shape
  // CustomsWizard expects (OrderCustomsPayload). Rebuilt each time the
  // wizard opens so it starts from whatever the user last typed inline.
  const wizardPayload = useMemo<OrderCustomsPayload>(() => ({
    items: items
      .filter((it) => it.description.trim() || it.hsCode.trim())
      .map<CustomsItem>((it) => ({
        description: it.description.trim(),
        hsCode: it.hsCode.trim() || null,
        countryOfOrigin: it.countryOfOrigin.trim().toUpperCase() || null,
        quantity: Number(it.quantity) || 1,
        unitValue: Number(it.unitValue) || 0,
        sku: it.sku.trim() || null,
      })),
    incoterms,
    reasonForExport,
    currency,
    weightUnit,
  }), [items, incoterms, reasonForExport, currency, weightUnit])

  /** Copy wizard-side state back into the inline form state so both stay in sync. */
  const acceptWizardPayload = (payload: OrderCustomsPayload) => {
    const rows: ItemRow[] = (payload.items ?? []).map((it) => ({
      description: it.description ?? '',
      sku: it.sku ?? '',
      hsCode: it.hsCode ?? '',
      countryOfOrigin: it.countryOfOrigin ?? '',
      quantity: String(it.quantity ?? 1),
      unitValue: it.unitValue != null ? String(it.unitValue) : '',
      boxSeq: '', // Sprint 48 B11 — wizard doesn't collect boxSeq yet; preserved as blank
    }))
    // Keep at least one row so the inline form isn't blank after a Save
    // with zero items entered in the wizard.
    setItems(rows.length ? rows : [blankItem()])
    if (payload.incoterms) setIncoterms(payload.incoterms)
    if (payload.reasonForExport) setReasonForExport(payload.reasonForExport)
    if (payload.currency) setCurrency(payload.currency)
    if (payload.weightUnit && (payload.weightUnit === 'LB' || payload.weightUnit === 'KG')) {
      setWeightUnit(payload.weightUnit)
    }
    setWizardOpen(false)
    notify.success('Customs details saved to this shipment.')
  }

  const patchItem = (i: number, patch: Partial<ItemRow>) =>
    setItems((rows) => rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))
  const addItem = () => setItems((rows) => [blankItem(), ...rows])
  const removeItem = (i: number) => setItems((rows) => (rows.length > 1 ? rows.filter((_, idx) => idx !== i) : rows))
  const invoiceTotal = items.reduce((sum, it) => sum + (Number(it.quantity) || 0) * (Number(it.unitValue) || 0), 0)

  // ── AI assist handlers (one per section, suggestion-only) ──────────────────
  const weightInLb = () => {
    const w = Number(weight) || 0
    return weightUnit === 'KG' ? Math.round(w * 2.20462 * 100) / 100 : w
  }

  /** Package & weight — recommend a package + weight from the item list. */
  const suggestPackagingAi = async () => {
    const usable = items.filter((it) => it.description.trim())
    if (!usable.length) return notify.error('Add at least one item description first.')
    setPkgBusy(true)
    try {
      const r = await aiService.suggestPackaging(
        usable.map((it) => ({ description: it.description.trim(), quantity: Number(it.quantity) || 1 })),
      )
      const filled: string[] = []
      if (r.weightLb) {
        setWeightUnit('LB')
        setWeight(String(r.weightLb))
        filled.push('weight')
      }
      if (r.lengthIn || r.widthIn || r.heightIn) {
        setDimUnit('IN')
        setPackageChoice(CUSTOM_PKG)
        if (r.lengthIn) setLength(String(r.lengthIn))
        if (r.widthIn) setWidth(String(r.widthIn))
        if (r.heightIn) setHeight(String(r.heightIn))
        filled.push('dimensions')
      }
      if (!filled.length) return notify.error('No packaging estimate was returned.')
      const why = r.rationale ? ` — ${r.rationale.replace(/\s*\.\s*$/, '')}` : ''
      notify.success(`Suggested ${filled.join(' + ')}${why}. Please review.`)
    } catch (err) {
      notify.apiError(err, 'Packaging suggestion failed.')
    } finally {
      setPkgBusy(false)
    }
  }

  /** Account & service — recommend a service + incoterm for the route. */
  const recommendServiceAi = async () => {
    if (!recipient.countryCode) return notify.error('Set the ship-to country first.')
    setSvcBusy(true)
    try {
      const r = await aiService.recommendService({
        fromCountry: sender.countryCode || undefined,
        toCountry: recipient.countryCode || undefined,
        weightLb: weightInLb() || undefined,
        available: servicesForCarrier.map((s) => s.name),
      })
      const applied: string[] = []
      const wanted = (r.serviceName || r.serviceCode || '').toLowerCase()
      const match = wanted ? servicesForCarrier.find((s) => s.name.toLowerCase() === wanted) : undefined
      if (match) {
        setServiceId(match.id)
        applied.push(`service “${match.name}”`)
      }
      if (r.incoterm === 'DDP' || r.incoterm === 'DAP') {
        setIncoterms(r.incoterm)
        applied.push(r.incoterm)
      }
      notify.success(
        `${applied.length ? `Applied ${applied.join(' + ')}. ` : ''}${r.rationale || `Recommended ${r.serviceName || r.serviceCode || 'a service'}`}`,
      )
    } catch (err) {
      notify.apiError(err, 'Service recommendation failed.')
    } finally {
      setSvcBusy(false)
    }
  }

  /** Shipment — pre-ship AI sanity review of the whole form. */
  const reviewShipmentAi = async () => {
    setReviewBusy(true)
    try {
      const pkgName =
        packageChoice === CUSTOM_PKG
          ? 'CUSTOM'
          : packagesForCarrier.find((p) => String(p.id) === packageChoice)?.name || undefined
      const r = await aiService.reviewShipment({
        fromCountry: sender.countryCode,
        toCountry: recipient.countryCode,
        weightLb: weightInLb() || undefined,
        lengthIn: Number(length) || undefined,
        widthIn: Number(width) || undefined,
        heightIn: Number(height) || undefined,
        packageCode: pkgName,
        incoterm: incoterms,
        items: isInternational
          ? items
              .filter((it) => it.description.trim())
              .map((it) => ({
                description: it.description.trim(),
                hsCode: it.hsCode || undefined,
                value: (Number(it.quantity) || 0) * (Number(it.unitValue) || 0),
              }))
          : [],
      })
      setReviewWarnings(r.warnings || [])
      if (!r.warnings?.length) notify.success('AI review: no issues found — good to ship.')
    } catch (err) {
      notify.apiError(err, 'AI review failed.')
    } finally {
      setReviewBusy(false)
    }
  }

  /** Flatten a (possibly nested/array) Formik error tree into a list of messages. */
  const flattenErrors = (node: unknown): string[] => {
    if (!node) return []
    if (typeof node === 'string') return [node]
    if (Array.isArray(node)) return node.flatMap(flattenErrors)
    return Object.values(node as Record<string, unknown>).flatMap(flattenErrors)
  }

  const submit = async () => {
    // Yup + Formik gate — validate the mirrored form values before anything else.
    setSubmitAttempted(true)
    const errs = await formik.validateForm(formValues as unknown as ShipmentFormValues)
    const msgs = flattenErrors(errs)
    if (msgs.length > 0) {
      const more = msgs.length - 1
      // Lightweight toast (not a blocking modal) — the field-level red messages
      // below each input are the primary guidance.
      showToast(
        more > 0
          ? `${msgs[0]} — and ${more} other field${more === 1 ? '' : 's'} highlighted in red.`
          : msgs[0],
        `${msgs.length} field${msgs.length === 1 ? '' : 's'} need attention`,
      )
      // Bring the first invalid field into view.
      scrollToFirstError()
      return
    }

    const w = Number(weight)

    const cleanItems: ManualShipmentItem[] = items
      .filter((it) => it.description.trim())
      .map((it) => ({
        description: it.description.trim(),
        sku: it.sku.trim() || undefined,
        hsCode: it.hsCode.trim() || undefined,
        countryOfOrigin: it.countryOfOrigin.trim().toUpperCase() || undefined,
        quantity: it.quantity ? Number(it.quantity) : null,
        unitValue: it.unitValue ? Number(it.unitValue) : null,
        // Sprint 48 B11 — assign item to a specific physical package;
        // backend derives per-box declared value from sum of items
        // when at least one item is assigned.
        boxSeq: it.boxSeq ? Number(it.boxSeq) : undefined,
      }))

    // Match the typed bill-to number to a known account on this carrier (for credentials);
    // if it's not on file, the backend falls back to the carrier's platform credentials.
    const matched = accounts.find(
      (a) => canon(a.carrierCode) === carrier && (a.accountNumber || '').toLowerCase() === accountNumber.trim().toLowerCase(),
    )

    const payload: ManualShipmentPayload = {
      sender,
      recipient,
      isReturn,
      carrierCode: carrier,
      accountNumber: accountNumber.trim(),
      accountId: matched?.id ?? null,
      serviceId: serviceId === '' ? null : Number(serviceId),
      packagePresetId: isCustomPkg ? null : Number(packageChoice),
      length: isCustomPkg ? Number(length) : null,
      width: isCustomPkg ? Number(width) : null,
      height: isCustomPkg ? Number(height) : null,
      dimUnit,
      weight: w,
      weightUnit,
      clientCode: clientCode.trim() || undefined,
      warehouseCode: warehouseCode || undefined,
      declaredValue: declaredValue ? Number(declaredValue) : null,
      // Sprint 27 — attach the DG block when populated; backend threads
      // it into ShipmentRequestDTO.dangerousGoods and every connector's
      // hazmat wire format keys off it.
      ...(dgBlock ? { dangerousGoods: dgBlock } : {}),
      // Sprint 35 — signature + insurance. NONE → omit so the backend
      // uses the carrier default; explicit values flow through to each
      // connector's per-carrier wire format.
      ...(signatureOption !== 'NONE' ? { signatureOption } : {}),
      ...(insuredValue && Number(insuredValue) > 0
        ? { insuredValue: Number(insuredValue), insuredValueCurrency: currency }
        : {}),
      // Sprint 29 — multi-package. When extra boxes are present, build
      // a packages[] array with box 1 mirroring the top-level fields and
      // boxes 2..N from extraPackages. Backend's effectivePackages() also
      // handles the null case, so empty extraPackages leaves the payload
      // untouched (existing single-package behavior).
      ...(extraPackages.length > 0 ? {
        packages: [
          {
            sequenceNumber: 1,
            packageType: isCustomPkg ? undefined : String(packageChoice),
            weight: w,
            weightUnit,
            length: isCustomPkg ? Number(length) : undefined,
            width: isCustomPkg ? Number(width) : undefined,
            height: isCustomPkg ? Number(height) : undefined,
            dimUnit,
            declaredValue: declaredValue ? Number(declaredValue) : undefined,
          },
          ...extraPackages.map((p, i) => ({
            sequenceNumber: i + 2,
            packageType: p.packageType || undefined,
            weight: Number(p.weight),
            weightUnit,
            length: p.length ? Number(p.length) : undefined,
            width: p.width ? Number(p.width) : undefined,
            height: p.height ? Number(p.height) : undefined,
            dimUnit,
            declaredValue: p.declaredValue ? Number(p.declaredValue) : undefined,
          })),
        ],
      } : {}),
      ...(isInternational ? { items: cleanItems, reasonForExport, currency, incoterms } : {}),
      ...(isInternational && override ? { importer: override.importer, broker: override.broker } : {}),
    }

    setSubmitting(true)
    try {
      const res = await orderService.generateManualLabel(payload)
      const orderNo = res.data?.orderNo
      // Sprint 43 — persist custom field values against the new order.
      // Best-effort: never block the success navigation on this call.
      if (orderNo && Object.keys(customFieldValues).length > 0) {
        try {
          await customFieldService.upsertValues(orderNo, customFieldValues, clientCode || null)
        } catch (cfErr) {
          notify.error(
            cfErr instanceof Error
              ? `Label generated but custom fields failed to save: ${cfErr.message}`
              : 'Label generated but custom fields failed to save.',
          )
        }
      }
      notify.success(res.message || 'Shipment label generated.')
      navigate(orderNo ? `/label/${orderNo}` : '/orders')
    } catch (e) {
      const raw = e instanceof ApiError
        ? e.message
        : e instanceof Error
          ? e.message
          : 'Failed to generate the label.'
      // Backend's IntlShipmentValidator concatenates every gap into a single
      // message with a stable prefix. Parse it back into a structured
      // notify.error so the user sees a title + bullet list instead of a
      // wall of text.
      const parsed = parseIntlValidationMessage(raw)
      if (parsed) {
        notify.error({ title: parsed.title, body: parsed.body })
      } else {
        notify.error(raw)
      }
    } finally {
      setSubmitting(false)
    }
  }

  const noCarriers = !loading && carrierOptions.length === 0

  return (
    <div className="pb-6">
      {/* Validation toast — non-blocking; auto-dismisses. */}
      {toast ? (
        <div
          key={toast.id}
          role="alert"
          className="fixed right-4 top-4 z-[100] w-[min(92vw,360px)] animate-[msToastIn_0.25s_ease-out] overflow-hidden rounded-2xl border border-rose-200 bg-white shadow-[0_12px_40px_-12px_rgba(159,18,57,0.35)]"
        >
          <div className="flex items-start gap-3 p-3.5">
            <span className="mt-0.5 inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-rose-50 text-rose-600 ring-1 ring-rose-100">
              <FiAlertTriangle className="h-4 w-4" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="text-[12.5px] font-bold text-[#1f150c]">{toast.title}</p>
              <p className="mt-0.5 text-[12px] leading-snug text-[#5a4526]">{toast.body}</p>
            </div>
            <button
              type="button"
              onClick={() => setToast(null)}
              aria-label="Dismiss"
              className="-mr-1 -mt-1 shrink-0 rounded-lg p-1 text-[#b6a684] transition hover:bg-[#faf7f0] hover:text-[#5a4526]"
            >
              <FiX className="h-3.5 w-3.5" />
            </button>
          </div>
          <div className="h-1 w-full origin-left animate-[msToastBar_6s_linear_forwards] bg-rose-400/70" />
        </div>
      ) : null}
      <div className="w-full space-y-4">
        {loading ? (
          <section className="rounded-2xl border border-slate-200 bg-white p-10 text-center text-sm text-[#8a7959] shadow-sm">
            Loading carriers, services and packaging…
          </section>
        ) : noCarriers ? (
          <section className="rounded-2xl border border-dashed border-[#e3d9c4] bg-[#faf7f0] p-10 text-center shadow-sm">
            <p className="text-sm font-semibold text-[#412d15]">No shippable carrier yet</p>
            <p className="mx-auto mt-1 max-w-md text-[13px] text-[#8a7959]">
              A carrier needs an active account <em>and</em> synced live services. Verify a carrier and sync its
              services in Settings first.
            </p>
          </section>
        ) : (
          <>
            {/* ── Shipment / Return toggle ── */}
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <div className="inline-flex items-center gap-1 rounded-2xl border border-[#e3d9c4] bg-white p-1 shadow-sm">
                  {(['SHIPMENT', 'RETURN'] as const).map((m) => (
                    <button
                      key={m}
                      type="button"
                      onClick={() => switchMode(m)}
                      className={`inline-flex items-center justify-center gap-1.5 rounded-xl px-4 py-2 text-[12.5px] font-semibold transition ${
                        mode === m ? 'bg-[#1f150c] text-[#f4eede] shadow-sm' : 'text-[#5a4526] hover:bg-[#faf7f0]'
                      }`}
                    >
                      {m === 'SHIPMENT' ? <FiTruck className="h-3.5 w-3.5" /> : <FiRotateCcw className="h-3.5 w-3.5" />}
                      {m === 'SHIPMENT' ? 'Shipment' : 'Return'}
                    </button>
                  ))}
                </div>
                {isReturn ? (
                  <span className="text-[12px] text-[#8a7959]">
                    Reverse label — the customer ships back to your address. Billed to your account.
                  </span>
                ) : null}
              </div>
              <button
                type="button"
                onClick={() => navigate('/orders')}
                className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                <FiArrowLeft className="h-3.5 w-3.5" />
                Back to orders
              </button>
            </div>

            {/* ── Top: client · reason of export · currency ── */}
            <SectionCard
              icon={<FiUsers className="h-3.5 w-3.5" />}
              title="Shipment"
              note="Choosing a client fills its ship-from and auto-selects its default carrier account. Reason & currency remember your last choice."
            >
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
                <Field label="Client">
                  <select className={inputCls} value={clientCode} onChange={(e) => applyClient(e.target.value)}>
                    <option value="">No client — ad-hoc</option>
                    {clients.map((c) => (
                      <option key={c.clientCode} value={c.clientCode}>
                        {c.clientCode} — {c.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field
                  label={`Ship from${clientWarehouses.length ? ` · ${clientWarehouses.length}` : ''}`}
                  hint={
                    !clientCode
                      ? 'Pick a client to load warehouses.'
                      : clientWarehouses.length === 0
                        ? 'No warehouses attached — the sender block below is manual.'
                        : undefined
                  }
                >
                  <select
                    className={inputCls}
                    value={warehouseCode}
                    onChange={(e) => setWarehouseCode(e.target.value)}
                    disabled={!clientCode || clientWarehouses.length === 0}
                  >
                    <option value="">
                      {clientCode && clientWarehouses.length === 0 ? 'None attached' : 'Manual sender'}
                    </option>
                    {clientWarehouses.map((cw) => (
                      <option key={cw.id} value={cw.warehouse?.code ?? ''}>
                        {cw.warehouse?.code} — {cw.warehouse?.name}
                        {cw.isDefault ? ' ★' : ''}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Carrier" required error={errAt('carrier')}>
                  <select className={inputCls} value={carrier} onChange={(e) => setCarrier(e.target.value)}>
                    {carrierOptions.map((c) => (
                      <option key={c} value={c}>{CARRIER_LABEL[c] || c}</option>
                    ))}
                  </select>
                </Field>
                {isInternational ? (
                  <Field label="Reason of export" error={errAt('reasonForExport')}>
                    <select className={inputCls} value={reasonForExport} onChange={(e) => setReasonForExport(e.target.value)}>
                      {EXPORT_REASONS.map((r) => (
                        <option key={r} value={r}>{r.charAt(0) + r.slice(1).toLowerCase()}</option>
                      ))}
                    </select>
                  </Field>
                ) : null}
                <Field label="Currency" error={errAt('currency')}>
                  <select className={inputCls} value={currency} onChange={(e) => setCurrency(e.target.value)}>
                    {CURRENCIES.map((c) => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                </Field>
              </div>
            </SectionCard>

            {/* ── Addresses + Carrier + Package (compact 3-col band) ── */}
            <div className="grid grid-cols-1 gap-4 lg:grid-cols-2 xl:grid-cols-3">
              <SectionCard
                icon={<FiHome className="h-3.5 w-3.5" />}
                title={isReturn ? 'Return from · customer' : 'Ship from · sender'}
                className="xl:row-span-2"
              >
                <AddressBlock value={sender} onChange={(patch) => setSender((s) => ({ ...s, ...patch }))} withEmail={isReturn} errors={addrErrors('sender')} />
              </SectionCard>
              <SectionCard
                icon={<FiMapPin className="h-3.5 w-3.5" />}
                title={isReturn ? 'Return to · your address' : 'Ship to · recipient'}
                className="xl:row-span-2"
              >
                <AddressBlock
                  value={recipient}
                  onChange={(patch) => {
                    setRecipient((r) => ({ ...r, ...patch }))
                    // Editing the address invalidates any prior validation result,
                    // so clear the stale carrier / format-check banners.
                    setCarrierAddressResult(null)
                    setRecipientCheck(null)
                  }}
                  withEmail={!isReturn}
                  hideLine3
                  errors={addrErrors('recipient')}
                  extraAction={
                    // Sprint 38 — address-book combobox. Type ≥ 2 chars to
                    // search; picking a suggestion overwrites every field
                    // in the recipient block below.
                    <div className="relative">
                      <div className="relative">
                        <input
                          type="text"
                          value={recipientSearch}
                          onChange={(e) => void runRecipientSearch(e.target.value)}
                          onFocus={() => recipientSuggestions.length > 0 && setRecipientDropdownOpen(true)}
                          onBlur={() => setTimeout(() => setRecipientDropdownOpen(false), 150)}
                          placeholder="Search address book (name, city, postal code)…"
                          className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 pl-8 text-[12px] outline-none focus:border-[#1f150c]"
                        />
                        <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3 w-3 -translate-y-1/2 text-[#8a7959]" />
                      </div>
                      {recipientDropdownOpen && recipientSuggestions.length > 0 ? (
                        <ul className="absolute z-10 mt-0.5 max-h-64 w-full overflow-y-auto rounded-lg border border-slate-200 bg-white shadow-lg">
                          {recipientSuggestions.map((s) => (
                            <li key={s.id}>
                              <button
                                type="button"
                                onMouseDown={(e) => e.preventDefault()}
                                onClick={() => applySavedRecipient(s)}
                                className="flex w-full items-start justify-between gap-2 px-2.5 py-1.5 text-left text-[11.5px] hover:bg-slate-50"
                              >
                                <div className="min-w-0">
                                  <p className="truncate font-semibold text-slate-950">{s.name}</p>
                                  <p className="truncate text-[10.5px] text-slate-500">
                                    {s.addressLine1}
                                    {s.city ? `, ${s.city}` : ''}
                                    {s.state ? `, ${s.state}` : ''}
                                    {' '}{s.postalCode} {s.countryCode}
                                  </p>
                                </div>
                                {s.tag ? (
                                  <span className="whitespace-nowrap rounded-full bg-slate-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-slate-500">
                                    {s.tag}
                                  </span>
                                ) : null}
                              </button>
                            </li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  }
                />
                <div className="mt-3 flex justify-end">
                  <button
                    type="button"
                    onClick={() => void validateRecipientWithCarrier()}
                    disabled={carrierValidating || !carrier}
                    title={carrier
                      ? `Carrier-side check — ask ${carrier} whether they can deliver here + residential/commercial classification`
                      : 'Pick a carrier first'}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-[#1f150c] bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] transition hover:bg-[#33221a] disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {carrierValidating ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#8a7959] border-t-[#f4eede]" />
                    ) : (
                      <FiCheckCircle className="h-3.5 w-3.5" />
                    )}
                    Validate with Carrier
                  </button>
                </div>
                {!destAllowed && destRules?.mode && recipient.countryCode ? (
                  <div className="mt-3 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
                    <p className="flex items-center gap-2 font-semibold">
                      <FiAlertTriangle className="h-4 w-4 shrink-0" />
                      {destRules.mode === 'ALLOW'
                        ? `${clientCode} is not configured to ship to ${recipient.countryCode.toUpperCase()}.`
                        : `${clientCode} has ${recipient.countryCode.toUpperCase()} on its deny list.`}
                    </p>
                    <p className="mt-0.5 pl-6 text-[11.5px] text-amber-700">
                      Update the client's Destinations tab, or pick a different country to proceed.
                    </p>
                  </div>
                ) : null}
                {recipientCheck ? (
                  recipientCheck.valid ? (
                    <div className="mt-3 flex items-center gap-2 rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] font-medium text-emerald-800">
                      <FiCheckCircle className="h-4 w-4 shrink-0" /> Address looks valid.
                    </div>
                  ) : (
                    <div className="mt-3 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
                      <p className="flex items-center gap-2 font-semibold">
                        <FiAlertTriangle className="h-4 w-4 shrink-0" /> Address needs attention
                      </p>
                      <ul className="mt-1 list-disc space-y-0.5 pl-6">
                        {recipientCheck.issues.map((iss, idx) => (
                          <li key={idx}>{iss}</li>
                        ))}
                      </ul>
                    </div>
                  )
                ) : null}
                {carrierAddressResult ? (
                  <CarrierAddressBanner
                    result={carrierAddressResult}
                    requestCountry={recipient.countryCode}
                    onApply={applyCarrierSuggestion}
                    onDismiss={() => setCarrierAddressResult(null)}
                  />
                ) : null}
              </SectionCard>
              <SectionCard
                icon={<FiTruck className="h-3.5 w-3.5" />}
                title="Account & service"
                badge={
                  <div className="flex items-center gap-2">
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
                      {sender.countryCode || '—'} → {recipient.countryCode || '—'} · {isInternational ? 'Intl' : 'Domestic'}
                    </span>
                  </div>
                }
              >
                <div className="grid grid-cols-1 gap-3">
                  <Field label="Account (bill to)" required error={errAt('account')}>
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
                  <Field label="Service level">
                    <div className="flex items-center gap-1.5">
                      <select className={inputCls} value={serviceId} onChange={(e) => setServiceId(e.target.value ? Number(e.target.value) : '')}>
                        {servicesForCarrier.length === 0 ? <option value="">Carrier default</option> : null}
                        {servicesForCarrier.map((s) => (
                          <option key={s.id} value={s.id}>{s.name}</option>
                        ))}
                      </select>
                      <button
                        type="button"
                        disabled={!canOpenRatePicker}
                        onClick={() => setRatePickerOpen(true)}
                        title={canOpenRatePicker
                          ? 'Fetch live rates across every configured carrier'
                          : 'Enter postal codes and weight first'}
                        className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-[#1f150c] bg-[#1f150c] px-2.5 py-1 text-[11px] font-semibold text-[#f4eede] transition hover:bg-[#33221a] disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        <FiSearch className="h-2.5 w-2.5" />
                        Compare rates
                      </button>
                    </div>
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
                  {isInternational ? (
                    <Field label="Incoterms" error={errAt('incoterms')}>
                      <select className={inputCls} value={incoterms} onChange={(e) => setIncoterms(e.target.value)}>
                        <option value="DDP">DDP — sender pays duties</option>
                        <option value="DAP">DAP — receiver pays duties</option>
                      </select>
                    </Field>
                  ) : null}
                </div>
              </SectionCard>

            {/* ── Package & weight ── */}
            <SectionCard
              icon={<FiPackage className="h-3.5 w-3.5" />}
              title="Package & weight"
              wrapHeader
              badge={
                <div className="flex flex-1 items-center justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => void suggestPackagingAi()}
                    disabled={pkgBusy}
                    title="Suggest packaging & weight from your items"
                    className="inline-flex items-center gap-1 rounded-lg border border-dashed border-[#cdbf9f] bg-white px-2.5 py-1 text-[10px] font-bold uppercase tracking-[0.08em] text-[#5a4526] transition hover:border-[#412d15] hover:bg-[#faf7f0] disabled:opacity-50"
                  >
                    {pkgBusy ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#412d15]" />
                    ) : (
                      <FiZap className="h-3 w-3" />
                    )}
                    Suggest
                  </button>

                  {/* Weight + dims unit toggles grouped in one compact pill */}
                  <div className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-1.5 py-1">
                    <span className="text-[8.5px] font-bold uppercase tracking-[0.1em] text-[#a1906d]">Wt</span>
                    <div className="inline-flex overflow-hidden rounded-md bg-[#faf7f0]">
                      {(['LB', 'KG'] as const).map((u) => (
                        <button
                          key={u}
                          type="button"
                          onClick={() => setWeightUnit(u)}
                          className={`px-2 py-0.5 text-[11px] font-semibold transition ${weightUnit === u ? 'bg-[#1f150c] text-[#f4eede]' : 'text-[#5a4526] hover:bg-[#efe7d4]'}`}
                        >
                          {u.toLowerCase()}
                        </button>
                      ))}
                    </div>
                    <span className="h-4 w-px bg-[#e3d9c4]" />
                    <span className="text-[8.5px] font-bold uppercase tracking-[0.1em] text-[#a1906d]">Dim</span>
                    <div className="inline-flex overflow-hidden rounded-md bg-[#faf7f0]">
                      {(['IN', 'CM'] as const).map((u) => (
                        <button
                          key={u}
                          type="button"
                          onClick={() => setDimUnit(u)}
                          className={`px-2 py-0.5 text-[11px] font-semibold transition ${dimUnit === u ? 'bg-[#1f150c] text-[#f4eede]' : 'text-[#5a4526] hover:bg-[#efe7d4]'}`}
                        >
                          {u.toLowerCase()}
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              }
            >
              <div className="space-y-3">
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Packaging" required className="col-span-2">
                    <select className={inputCls} value={packageChoice} onChange={(e) => setPackageChoice(e.target.value)}>
                      <optgroup label={`${CARRIER_LABEL[carrier] || carrier} packaging`}>
                        {packagesForCarrier.map((p) => (
                          <option key={p.id} value={String(p.id)}>{p.name}</option>
                        ))}
                      </optgroup>
                      {customBoxes.length ? (
                        <optgroup label="Your boxes">
                          {customBoxes.map((p) => (
                            <option key={p.id} value={String(p.id)}>{p.name}</option>
                          ))}
                        </optgroup>
                      ) : null}
                      <option value={CUSTOM_PKG}>Custom package…</option>
                    </select>
                  </Field>
                  <Field label={`Weight (${weightUnit.toLowerCase()})`} required error={errAt('weight')}>
                    <input className={inputCls} type="number" min="0" step="0.1" value={weight} onChange={(e) => setWeight(e.target.value)} placeholder="2.5" />
                  </Field>
                  <Field label={`Declared value (${currency})`} error={errAt('declaredValue')}>
                    <input className={inputCls} type="number" min="0" step="0.01" value={declaredValue} onChange={(e) => setDeclaredValue(e.target.value)} placeholder="100.00" />
                  </Field>
                </div>
                {/* Sprint 35 — signature at delivery + insured value.
                    NONE = carrier default (usually no signature domestic,
                    indirect on air); ADULT = 21+ ID required (higher fee).
                    Insured value beyond the carrier's free $100 tier
                    incurs an insurance surcharge on UPS/FedEx; USPS +
                    DHL treat it as a separate rider. */}
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Signature at delivery"
                         hint="Adult signature = 21+ ID; higher fee">
                    <select className={inputCls}
                            value={signatureOption}
                            onChange={(e) => setSignatureOption(e.target.value as typeof signatureOption)}>
                      <option value="NONE">Carrier default</option>
                      <option value="INDIRECT">Indirect (anyone at address)</option>
                      <option value="DIRECT">Direct (someone at address)</option>
                      <option value="ADULT">Adult signature (21+)</option>
                    </select>
                  </Field>
                  <Field label={`Insured value (${currency})`}
                         error={errAt('insuredValue')}
                         hint="Beyond the carrier's free $100 tier">
                    <input className={inputCls} type="number" min="0" step="0.01"
                           value={insuredValue}
                           onChange={(e) => setInsuredValue(e.target.value)}
                           placeholder="0.00" />
                  </Field>
                </div>
                {isCustomPkg ? (
                  <div className="grid grid-cols-3 gap-3">
                    <Field label={`Length (${dimUnit.toLowerCase()})`} required error={errAt('length')}>
                      <input className={inputCls} type="number" min="0" step="0.1" value={length} onChange={(e) => setLength(e.target.value)} placeholder="12" />
                    </Field>
                    <Field label={`Width (${dimUnit.toLowerCase()})`} required error={errAt('width')}>
                      <input className={inputCls} type="number" min="0" step="0.1" value={width} onChange={(e) => setWidth(e.target.value)} placeholder="9" />
                    </Field>
                    <Field label={`Height/depth (${dimUnit.toLowerCase()})`} required error={errAt('height')}>
                      <input className={inputCls} type="number" min="0" step="0.1" value={height} onChange={(e) => setHeight(e.target.value)} placeholder="4" />
                    </Field>
                  </div>
                ) : null}

                {/* Sprint 29 — additional boxes. First box uses the fields
                    above; extra rows collect per-box weight + dims. */}
                {extraPackages.map((p, idx) => (
                  <div key={idx} className="rounded-xl border border-dashed border-[#e3d9c4] bg-[#faf7f0]/60 p-3">
                    <div className="mb-2 flex items-center justify-between">
                      <p className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
                        Box {idx + 2}
                      </p>
                      <button
                        type="button"
                        onClick={() => setExtraPackages((cur) => cur.filter((_, i) => i !== idx))}
                        aria-label={`Remove box ${idx + 2}`}
                        className="inline-flex h-6 w-6 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#8a7959] hover:bg-rose-50 hover:text-rose-600"
                      >
                        <FiTrash2 className="h-3 w-3" />
                      </button>
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <Field label={`Weight (${weightUnit.toLowerCase()})`} required>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.weight}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, weight: e.target.value } : x)))}
                          placeholder="2.5"
                        />
                      </Field>
                      <Field label={`Declared value (${currency})`}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.01"
                          value={p.declaredValue}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, declaredValue: e.target.value } : x)))}
                          placeholder="100.00"
                        />
                      </Field>
                    </div>
                    <div className="mt-2 grid grid-cols-3 gap-2">
                      <Field label={`L (${dimUnit.toLowerCase()})`}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.length}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, length: e.target.value } : x)))}
                        />
                      </Field>
                      <Field label={`W (${dimUnit.toLowerCase()})`}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.width}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, width: e.target.value } : x)))}
                        />
                      </Field>
                      <Field label={`H (${dimUnit.toLowerCase()})`}>
                        <input
                          className={inputCls}
                          type="number" min="0" step="0.1"
                          value={p.height}
                          onChange={(e) => setExtraPackages((cur) =>
                            cur.map((x, i) => (i === idx ? { ...x, height: e.target.value } : x)))}
                        />
                      </Field>
                    </div>
                  </div>
                ))}
                <button
                  type="button"
                  onClick={() => setExtraPackages((cur) => [...cur, blankExtraPackage()])}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]"
                >
                  <FiPlus className="h-3 w-3" />
                  {extraPackages.length === 0 ? 'Add another box (multi-package shipment)' : 'Add another box'}
                </button>
              </div>
              </SectionCard>
            </div>

            {/* ── Customs · international only ── */}
            {isInternational ? (
              <SectionCard
                icon={<FiGlobe className="h-3.5 w-3.5" />}
                title="Importer of record & customs broker"
                badge={
                  <div className="flex items-center gap-2">
                    {override ? (
                      <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-amber-700">
                        Overridden · this shipment
                      </span>
                    ) : (
                      <span className="rounded-full bg-sky-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-sky-700">
                        Resolved for {destCountry || '—'}
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={() => setOverrideEditorOpen(true)}
                      className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                    >
                      <FiEdit3 className="h-3 w-3" />
                      {activeParties ? 'Edit' : 'Add'}
                    </button>
                    {override ? (
                      <button
                        type="button"
                        onClick={() => setOverride(null)}
                        className="text-[11px] font-semibold text-[#8a7959] underline-offset-2 hover:underline"
                      >
                        Reset
                      </button>
                    ) : null}
                  </div>
                }
              >
                {activeParties && vImp && vBrk ? (
                  <div className="grid gap-3 sm:grid-cols-2">
                    <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0]/60 p-3">
                      <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">Importer of record</p>
                      <p className="mt-1 text-[13.5px] font-semibold text-[#1f150c]">{vImp.name || '—'}</p>
                      {joinParts([vImp.addressLine1, vImp.addressLine2]) ? (
                        <p className="text-[12px] text-[#5a4526]">{joinParts([vImp.addressLine1, vImp.addressLine2])}</p>
                      ) : null}
                      <p className="text-[12px] text-[#5a4526]">
                        {joinParts([vImp.city, vImp.state, vImp.postalCode, vImp.countryCode])}
                      </p>
                      {vImp.phone ? <p className="text-[11.5px] text-[#8a7959]">PH: {vImp.phone}</p> : null}
                      {joinParts(
                        [
                          vImp.iec ? `IEC ${vImp.iec}` : '',
                          vImp.gstin ? `GSTIN ${vImp.gstin}` : '',
                          vImp.eori ? `EORI ${vImp.eori}` : '',
                          vImp.taxId ? `${vImp.taxIdType || 'Tax ID'} ${vImp.taxId}` : '',
                          vImp.ioss ? `IOSS ${vImp.ioss}` : '',
                        ],
                        ' · ',
                      ) ? (
                        <p className="mt-1 font-mono text-[11px] text-[#8a7959]">
                          {joinParts(
                            [
                              vImp.iec ? `IEC ${vImp.iec}` : '',
                              vImp.gstin ? `GSTIN ${vImp.gstin}` : '',
                              vImp.eori ? `EORI ${vImp.eori}` : '',
                              vImp.taxId ? `${vImp.taxIdType || 'Tax ID'} ${vImp.taxId}` : '',
                              vImp.ioss ? `IOSS ${vImp.ioss}` : '',
                            ],
                            ' · ',
                          )}
                        </p>
                      ) : null}
                      <span className="mt-2 inline-block rounded-full bg-[#efe7d4] px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-[#5a4526]">
                        {vImp.type === 'RECEIVER' ? 'Receiver · DAP' : 'Business · DDP'}
                      </span>
                    </div>
                    <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0]/60 p-3">
                      <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">Customs broker</p>
                      {vBrk.name || vBrk.company ? (
                        <>
                          <p className="mt-1 text-[13.5px] font-semibold text-[#1f150c]">{vBrk.name || vBrk.company}</p>
                          {vBrk.name && vBrk.company ? <p className="text-[12px] text-[#5a4526]">{vBrk.company}</p> : null}
                          {joinParts([vBrk.addressLine1, vBrk.city, vBrk.state, vBrk.postalCode, vBrk.countryCode]) ? (
                            <p className="text-[12px] text-[#5a4526]">
                              {joinParts([vBrk.addressLine1, vBrk.city, vBrk.state, vBrk.postalCode, vBrk.countryCode])}
                            </p>
                          ) : null}
                          <span className="mt-2 inline-block rounded-full bg-[#efe7d4] px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-[#5a4526]">
                            Broker Select
                          </span>
                        </>
                      ) : (
                        <p className="mt-1 text-[12.5px] text-[#5a4526]">
                          Carrier clears customs <span className="text-[#8a7959]">(carrier default brokerage)</span>
                        </p>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2.5 text-[12px] text-amber-800">
                    {clientCode
                      ? <>No importer/broker profile covers <strong>{destCountry}</strong> for {clientCode}.</>
                      : 'No client selected.'}{' '}
                    Click <strong>Add</strong> to enter importer/broker for <strong>this shipment only</strong> (the
                    client's saved profile is not changed).
                  </div>
                )}
              </SectionCard>
            ) : null}

            {/* ── Items · commercial invoice (international only) ── */}
            {isInternational ? (
              <SectionCard
                icon={<FiFileText className="h-3.5 w-3.5" />}
                title="Items · commercial invoice"
                badge={
                  <div className="flex items-center gap-2">
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-amber-700">
                      Cross-border · required
                    </span>
                    <button
                      type="button"
                      onClick={() => setWizardOpen(true)}
                      title="Step through the customs declaration with per-carrier hints"
                      className="inline-flex items-center gap-1 rounded-lg border border-emerald-600 bg-white px-2.5 py-1 text-[11px] font-semibold text-emerald-700 transition hover:bg-emerald-50 shadow-sm"
                    >
                      <FiGlobe className="h-3.5 w-3.5" /> Guided wizard
                    </button>
                    <button
                      type="button"
                      disabled={!canEstimateLandedCost}
                      onClick={() => setLandedCostOpen(true)}
                      title={canEstimateLandedCost
                        ? `Ask ${carrier} for freight + duty + tax estimate`
                        : 'Pick a carrier + fill weight + declared value first'}
                      className="inline-flex items-center gap-1 rounded-lg border border-[#1f150c] bg-[#1f150c] px-2.5 py-1 text-[11px] font-semibold text-[#f4eede] transition hover:bg-[#33221a] disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <FiZap className="h-3.5 w-3.5" /> Landed cost
                    </button>
                    <button
                      type="button"
                      onClick={addItem}
                      className="inline-flex items-center gap-1 rounded-lg border border-dashed border-[#cdbf9f] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                    >
                      <FiPlus className="h-3.5 w-3.5" /> Add item
                    </button>
                  </div>
                }
                note={
                  <>
                    {sender.countryCode} → {recipient.countryCode} crosses a customs border. These lines print on the
                    commercial invoice customs uses to assess duty &amp; tax. Importer/broker resolve from the client's
                    customs profile.
                  </>
                }
              >
                <div className="space-y-1.5 overflow-x-auto">
                  {/* header labels (once) */}
                  {/* Sprint 48 B11 — added "Pkg #" column between Amount and trash.
                      Total packages available = primary box + extraPackages. */}
                  <div className="hidden min-w-[820px] grid-cols-[minmax(0,2fr)_1fr_0.9fr_0.55fr_0.55fr_1fr_1fr_0.6fr_44px] gap-2 sm:grid">
                    {['Description *', 'SKU', 'HS code', 'Origin', 'Qty', `Unit value (${currency}) *`, `Amount (${currency})`, 'Pkg #'].map((h) => (
                      <span key={h} className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">{h}</span>
                    ))}
                    <span />
                  </div>

                  <VirtualizedNewShipmentItems
                    items={items}
                    patchItem={patchItem}
                    removeItem={removeItem}
                    totalPkgs={1 + extraPackages.length}
                    canRemove={items.length > 1}
                    inputCls={inputCls}
                    itemErr={itemErrAt}
                  />

                  {/* invoice total */}
                  <div className="flex min-w-[760px] items-center justify-end gap-3 border-t border-dashed border-[#e3d9c4] px-0.5 pt-2">
                    <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">Total amount</span>
                    <span className="font-mono text-[14px] font-semibold tabular-nums text-[#1f150c]">
                      {invoiceTotal.toFixed(2)} {currency}
                    </span>
                  </div>
                </div>
              </SectionCard>
            ) : null}
          </>
        )}
      </div>

      {/* Sprint 43 — Custom fields (tenant-defined metadata). Panel
       *  self-hides when the tenant has no applicable fields. */}
      {!loading && !noCarriers ? (
        <CustomFieldsSection
          tenantId={clientCode || null}
          values={customFieldValues}
          onChange={setCustomFieldValues}
          compact
        />
      ) : null}

      {/* sticky footer action bar — stays within the content column.
          `!mt-6` beats the parent space-y-4 so there's a clear gap between the
          form cards and the action bar. */}
      {!loading && !noCarriers ? (
        <div className="sticky bottom-4 z-30 !mt-6 space-y-2">
          {reviewWarnings ? (
            <div className="rounded-2xl border border-[#e3d9c4] bg-white p-3.5 shadow-[0_18px_50px_rgba(31,21,12,0.14)]">
              <div className="mb-2 flex items-center justify-between">
                <span className="inline-flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-[0.12em] text-[#5a4526]">
                  <FiZap className="h-3.5 w-3.5" /> AI pre-ship review
                </span>
                <button type="button" onClick={() => setReviewWarnings(null)} className="text-[11px] font-semibold text-[#8a7959] hover:text-[#412d15]">
                  Dismiss
                </button>
              </div>
              {reviewWarnings.length === 0 ? (
                <p className="flex items-center gap-1.5 text-[12.5px] text-emerald-700">
                  <FiCheckCircle className="h-4 w-4" /> No issues found — good to ship.
                </p>
              ) : (
                <ul className="space-y-1.5">
                  {reviewWarnings.map((w, idx) => {
                    const tone =
                      w.severity === 'high'
                        ? 'bg-rose-50 text-rose-700 ring-rose-200'
                        : w.severity === 'medium'
                          ? 'bg-amber-50 text-amber-700 ring-amber-200'
                          : 'bg-slate-50 text-slate-600 ring-slate-200'
                    return (
                      <li key={idx} className="flex items-start gap-2 text-[12.5px] text-[#3f3527]">
                        <span className={`mt-0.5 inline-flex shrink-0 rounded-full px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide ring-1 ${tone}`}>
                          {w.severity}
                        </span>
                        <span>
                          {w.field ? <span className="font-semibold">{w.field}: </span> : null}
                          {w.message}
                        </span>
                      </li>
                    )
                  })}
                </ul>
              )}
            </div>
          ) : null}
          <div className="flex items-center justify-between gap-3 rounded-2xl border border-[#e3d9c4] bg-white px-5 py-3 shadow-[0_18px_50px_rgba(31,21,12,0.16)]">
            <span className="hidden text-[11.5px] text-[#8a7959] sm:block">
              The label is purchased immediately on the selected account.
              {isInternational ? ' Commercial invoice included for this cross-border lane.' : ''}
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => void reviewShipmentAi()}
                disabled={reviewBusy}
                className="inline-flex items-center gap-1.5 rounded-xl border border-[#cdbf9f] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#412d15] hover:bg-[#faf7f0] disabled:opacity-50"
              >
                {reviewBusy ? (
                  <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#412d15]" />
                ) : (
                  <FiZap className="h-3.5 w-3.5" />
                )}
                AI review
              </button>
              <button
                type="button"
                onClick={() => navigate('/orders')}
                className="rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void submit()}
                disabled={submitting}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none"
              >
                {submitting ? (
                  <>
                    <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                    Generating…
                  </>
                ) : (
                  <>
                    <FiZap className="h-3.5 w-3.5" />
                    {isReturn ? 'Generate return label' : 'Generate label'}
                    <FiArrowRight className="h-3.5 w-3.5" />
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {ratePickerOpen ? (
        <RatePickerModal
          request={rateShopRequest}
          onClose={() => setRatePickerOpen(false)}
          onSelect={handleRateSelected}
        />
      ) : null}

      {dgWizardOpen ? (
        <DangerousGoodsWizard
          value={dgBlock}
          onChange={(next) => setDgBlock(next)}
          onComplete={(next) => {
            setDgBlock(next)
            setDgWizardOpen(false)
            notify.success(
              `Dangerous goods block attached · ${next.commodities.length} commodity(ies).`,
            )
          }}
          onCancel={() => setDgWizardOpen(false)}
        />
      ) : null}

      {landedCostOpen ? (
        <LandedCostModal
          request={landedCostRequest}
          onClose={() => setLandedCostOpen(false)}
        />
      ) : null}

      {overrideEditorOpen ? (
        <ShipmentPartiesOverrideModal
          importer={editorSeed.importer}
          broker={editorSeed.broker}
          destCountry={destCountry}
          onClose={() => setOverrideEditorOpen(false)}
          onSave={(importer, broker) => {
            setOverride({ importer, broker })
            setOverrideEditorOpen(false)
          }}
        />
      ) : null}

      {/* Customs wizard — full-screen modal overlay. Owns its own scroll +
          keyboard trap; the parent form stays mounted so state is preserved
          if the user cancels. */}
      {wizardOpen ? (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Customs declaration wizard"
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
        >
          <div className="flex h-[min(720px,90vh)] w-full max-w-[720px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]">
            <CustomsWizard
              carrierCode={carrier}
              originCountry={sender.countryCode}
              destinationCountry={recipient.countryCode}
              value={wizardPayload}
              onChange={() => {
                // No-op: the wizard renders from wizardPayload but writes
                // back only on Complete. We don't mirror in-progress edits
                // to the inline form because the user might cancel.
              }}
              onComplete={acceptWizardPayload}
              onCancel={() => setWizardOpen(false)}
            />
          </div>
        </div>
      ) : null}
    </div>
  )
}

/**
 * Sprint 31 — inline banner for carrier-side address validation results.
 * Colour by matchLevel; when the carrier returned a suggested address,
 * show the diff + an Apply button that overwrites the recipient block.
 */
function CarrierAddressBanner({
  result,
  requestCountry,
  onApply,
  onDismiss,
}: {
  result: AddressValidationResponse
  /** The country the user actually typed — used to reject cross-country suggestions. */
  requestCountry?: string | null
  onApply: () => void
  onDismiss: () => void
}) {
  const level = result.matchLevel
  const palette =
    level === 'EXACT'
      ? { border: 'border-emerald-200', bg: 'bg-emerald-50', text: 'text-emerald-800' }
      : level === 'CORRECTED' || level === 'AMBIGUOUS'
        ? { border: 'border-amber-200', bg: 'bg-amber-50', text: 'text-amber-800' }
        : level === 'NOT_SUPPORTED'
          ? { border: 'border-slate-200', bg: 'bg-slate-50', text: 'text-slate-700' }
          : { border: 'border-rose-200', bg: 'bg-rose-50', text: 'text-rose-800' }
  // Guard against the carrier returning a nonsensical suggestion in a different
  // country (seen from the FedEx sandbox: a Chilean address for a US shipment).
  // If the suggested country doesn't match what the user typed, don't offer it.
  const rawSuggested = result.suggested
  const countryMismatch =
    !!rawSuggested?.countryCode &&
    !!requestCountry &&
    rawSuggested.countryCode.toUpperCase() !== requestCountry.toUpperCase()
  const s = countryMismatch ? null : rawSuggested
  return (
    <div className={`mt-3 rounded-xl border ${palette.border} ${palette.bg} px-3 py-2 text-[12px] ${palette.text}`}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="flex items-center gap-1.5 font-semibold">
            <span className="rounded-full bg-white/60 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em]">
              {result.carrierCode} · {level}
            </span>
            {result.classification && result.classification !== 'UNKNOWN' ? (
              <span className="rounded-full bg-white/60 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em]">
                {result.classification}
              </span>
            ) : null}
          </p>
          <p className="mt-1">{result.message}</p>
          {countryMismatch ? (
            <p className="mt-1 text-[11px] italic opacity-80">
              The carrier suggested an address in a different country ({rawSuggested?.countryCode}) — ignored. Please verify the address manually.
            </p>
          ) : null}
          {s ? (
            <div className="mt-2 rounded-lg bg-white/60 px-2.5 py-1.5 font-mono text-[10.5px]">
              <p>{s.addressLine1}</p>
              {s.addressLine2 ? <p>{s.addressLine2}</p> : null}
              {s.addressLine3 ? <p>{s.addressLine3}</p> : null}
              <p>
                {s.city}, {s.state} {s.postalCode} {s.countryCode}
              </p>
            </div>
          ) : null}
          {result.warnings && result.warnings.length > 0 ? (
            <ul className="mt-1 list-disc space-y-0.5 pl-4 text-[11px]">
              {result.warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          ) : null}
        </div>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          className="shrink-0 rounded p-1 hover:bg-white/40"
        >
          <FiX className="h-3 w-3" />
        </button>
      </div>
      {s ? (
        <button
          type="button"
          onClick={onApply}
          className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-current bg-white/60 px-2.5 py-1 text-[11px] font-semibold hover:bg-white/80"
        >
          Apply suggested address
        </button>
      ) : null}
    </div>
  )
}

/* ---------------- Sprint 48 audit fix — virtualized CI items list ---------------- */

/** Module-scoped mirror of the local ItemRow type so the virtualizer's
 *  memoized row component can be declared outside the main function
 *  (memo needs a stable reference across parent re-renders). */
export type NewShipmentItemRow = {
  description: string
  sku: string
  hsCode: string
  countryOfOrigin: string
  quantity: string
  unitValue: string
  boxSeq: string
}

/**
 * Virtualized wrapper for the CI items table. Renders only the rows in /
 * near the viewport plus a small overscan; DOM stays at ~10 rows regardless
 * of items.length. Fixes the jank-past-30-items audit finding.
 *
 * <p>Uses measured height (ResizeObserver) so rows with wrapping
 * descriptions or an open HsCodeCombobox dropdown expand naturally.
 * 60vh scroll container is responsive to viewport height.
 */
/** Red-outline modifier appended to an input's class when it has an error. */
const itemErrRing = (msg?: string): string =>
  msg ? ' !border-rose-400 focus:!border-rose-400 focus:!ring-rose-100' : ''

export function VirtualizedNewShipmentItems({
  items,
  patchItem,
  removeItem,
  totalPkgs,
  canRemove,
  inputCls,
  itemErr,
}: {
  items: NewShipmentItemRow[]
  patchItem: (i: number, patch: Partial<NewShipmentItemRow>) => void
  removeItem: (i: number) => void
  totalPkgs: number
  canRemove: boolean
  inputCls: string
  /** Per-item validation lookup (only returns messages after a submit attempt). */
  itemErr?: (index: number, field: string) => string | undefined
}) {
  const parentRef = useRef<HTMLDivElement>(null)
  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Virtual's useVirtualizer() returns functions that cannot be memoized safely — library-level incompatibility with react-hooks analyzer, not a code issue
  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 72,
    overscan: 5,
  })
  return (
    <div
      ref={parentRef}
      style={{ height: '60vh', overflowY: 'auto', contain: 'strict' }}
      className="rounded-xl border border-slate-200 bg-white"
    >
      <div
        style={{
          height: `${virtualizer.getTotalSize()}px`,
          width: '100%',
          position: 'relative',
        }}
      >
        {virtualizer.getVirtualItems().map((virtualRow) => (
          <div
            key={virtualRow.key}
            data-index={virtualRow.index}
            ref={virtualizer.measureElement}
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              transform: `translateY(${virtualRow.start}px)`,
            }}
            className="px-2 py-1"
          >
            <NewShipmentItemsRow
              index={virtualRow.index}
              it={items[virtualRow.index]}
              patchItem={patchItem}
              removeItem={removeItem}
              totalPkgs={totalPkgs}
              canRemove={canRemove}
              inputCls={inputCls}
              itemErr={itemErr}
            />
          </div>
        ))}
      </div>
    </div>
  )
}

/** Memoized row — typing in one row doesn't reconcile the other 99. */
const NewShipmentItemsRow = memo(function NewShipmentItemsRow({
  index,
  it,
  patchItem,
  removeItem,
  totalPkgs,
  canRemove,
  inputCls,
  itemErr,
}: {
  index: number
  it: NewShipmentItemRow
  patchItem: (i: number, patch: Partial<NewShipmentItemRow>) => void
  removeItem: (i: number) => void
  totalPkgs: number
  canRemove: boolean
  inputCls: string
  itemErr?: (index: number, field: string) => string | undefined
}) {
  const patch = useCallback((delta: Partial<NewShipmentItemRow>) => patchItem(index, delta), [patchItem, index])
  const remove = useCallback(() => removeItem(index), [removeItem, index])
  const amount = (Number(it.quantity) || 0) * (Number(it.unitValue) || 0)
  const err = (field: string) => (itemErr ? itemErr(index, field) : undefined)
  return (
    <div className="space-y-1">
      <div className="grid min-w-[820px] grid-cols-[minmax(0,2fr)_1fr_0.9fr_0.55fr_0.55fr_1fr_1fr_0.6fr_44px] items-start gap-2">
        <input className={`${inputCls}${itemErrRing(err('description'))}`} value={it.description} onChange={(e) => patch({ description: e.target.value })} placeholder="Cotton t-shirt" />
        <input className={`${inputCls}${itemErrRing(err('sku'))}`} value={it.sku} onChange={(e) => patch({ sku: e.target.value })} placeholder="SKU-001" />
        <HsCodeCombobox
          value={it.hsCode}
          onChange={(v) => patch({ hsCode: v })}
          onDescriptionSuggest={(desc) => {
            if (!it.description || !it.description.trim()) patch({ description: desc })
          }}
        />
        <input className={`${inputCls} uppercase${itemErrRing(err('countryOfOrigin'))}`} value={it.countryOfOrigin} onChange={(e) => patch({ countryOfOrigin: e.target.value })} placeholder="US" maxLength={2} />
        <input className={`${inputCls}${itemErrRing(err('quantity'))}`} type="number" min="1" step="1" value={it.quantity} onFocus={(e) => e.currentTarget.select()} onChange={(e) => patch({ quantity: e.target.value })} placeholder="1" />
        <input className={`${inputCls}${itemErrRing(err('unitValue'))}`} type="number" min="0" step="0.01" value={it.unitValue} onChange={(e) => patch({ unitValue: e.target.value })} placeholder="20.00" />
        <div className={`${inputCls} flex items-center justify-end bg-[#faf7f0] font-mono tabular-nums`}>
          {amount > 0 ? amount.toFixed(2) : '—'}
        </div>
        <select
          className={inputCls}
          value={it.boxSeq}
          onChange={(e) => patch({ boxSeq: e.target.value })}
          title="Assign this item to a specific package. Backend sums assigned items per box for declared value."
        >
          <option value="">—</option>
          {Array.from({ length: totalPkgs }, (_, n) => n + 1).map((n) => (
            <option key={n} value={String(n)}>{n}</option>
          ))}
        </select>
        <div className="flex items-center justify-center">
          <button
            type="button"
            onClick={remove}
            disabled={!canRemove}
            className="rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#b6a684] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-40"
            aria-label={`Remove item ${index + 1}`}
          >
            <FiTrash2 className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
      {(() => {
        const msgs = [
          err('description'),
          err('sku'),
          err('hsCode'),
          err('countryOfOrigin'),
          err('quantity'),
          err('unitValue'),
        ].filter(Boolean)
        return msgs.length ? (
          <p className="ms-field-error px-1 text-[10.5px] font-semibold text-rose-600">{msgs.join(' · ')}</p>
        ) : null
      })()}
    </div>
  )
})
