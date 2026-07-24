import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiZap, FiArrowRight, FiArrowLeft, FiTruck, FiPackage, FiMapPin, FiHome, FiUsers, FiFileText, FiPlus, FiTrash2, FiRotateCcw, FiGlobe, FiEdit3, FiCheckCircle, FiAlertTriangle, FiSearch } from 'react-icons/fi'
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
import { addressService } from '../api/addressService'
import { clientWarehouseService, type ClientWarehouse } from '../api/warehouseService'
import {
  clientAllowedPackagesService,
  clientAllowedServicesService,
} from '../api/clientCatalogService'
import {
  clientDestinationsService,
  type ClientDestinationRules,
} from '../api/clientPolicyService'
import PageSectionHeader from './workspace/PageSectionHeader'
import ShipmentPartiesOverrideModal, { type Party } from './modals/ShipmentPartiesOverrideModal'

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

function Field({ label, required, hint, children, className = '' }: { label: string; required?: boolean; hint?: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block space-y-1 ${className}`}>
      <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
        {label}
        {required ? <span className="text-rose-500"> *</span> : null}
      </span>
      {children}
      {hint ? <span className="mt-1 block text-[10.5px] normal-case tracking-normal text-slate-400">{hint}</span> : null}
    </label>
  )
}

/** Espresso section shell used across the page. */
function SectionCard({ icon, title, badge, note, children }: { icon: ReactNode; title: string; badge?: ReactNode; note?: ReactNode; children: ReactNode }) {
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
      <div className="flex min-h-[38px] items-center justify-between gap-2 border-b border-dashed border-[#e3d9c4] pb-2">
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

function AddressBlock({
  value,
  onChange,
  withEmail,
}: {
  value: ManualShipmentAddress
  onChange: (patch: Partial<ManualShipmentAddress>) => void
  withEmail?: boolean
}) {
  return (
    <div className="grid grid-cols-2 gap-3">
      <Field label="Full name" required className="col-span-2 sm:col-span-1">
        <input className={inputCls} value={value.name} onChange={(e) => onChange({ name: e.target.value })} placeholder="Jane Doe" />
      </Field>
      <Field label="Company" className="col-span-2 sm:col-span-1">
        <input className={inputCls} value={value.company} onChange={(e) => onChange({ company: e.target.value })} placeholder="Acme Inc." />
      </Field>
      <Field label="Address line 1" required className="col-span-2">
        <input className={inputCls} value={value.addressLine1} onChange={(e) => onChange({ addressLine1: e.target.value })} placeholder="123 Market St" />
      </Field>
      <Field label="Address line 2" className="col-span-2">
        <input className={inputCls} value={value.addressLine2} onChange={(e) => onChange({ addressLine2: e.target.value })} placeholder="Suite 400" />
      </Field>
      <Field label="City" required>
        <input className={inputCls} value={value.city} onChange={(e) => onChange({ city: e.target.value })} placeholder="Buffalo" />
      </Field>
      <Field label="State / region">
        <input className={inputCls} value={value.state} onChange={(e) => onChange({ state: e.target.value })} placeholder="NY" />
      </Field>
      <Field label="Postal code" required>
        <input className={inputCls} value={value.postalCode} onChange={(e) => onChange({ postalCode: e.target.value })} placeholder="14201" />
      </Field>
      <Field label="Country" required>
        <CountrySelect value={value.countryCode} onChange={(code) => onChange({ countryCode: code })} />
      </Field>
      <Field label="Phone">
        <input className={inputCls} value={value.phone} onChange={(e) => onChange({ phone: e.target.value })} placeholder="2125550100" />
      </Field>
      {withEmail ? (
        <Field label="Email">
          <input className={inputCls} value={value.email} onChange={(e) => onChange({ email: e.target.value })} placeholder="jane@acme.com" />
        </Field>
      ) : null}
    </div>
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

  // 3PL guardrails: client's attached warehouses, allowlists, and destination
  // rules. Fetched when clientCode changes; drive the warehouse picker,
  // filtered dropdowns, and the ship-to warning banner.
  const [clientWarehouses, setClientWarehouses] = useState<ClientWarehouse[]>([])
  const [warehouseCode, setWarehouseCode] = useState('')
  /** Set of service ids on the client's allowlist. null = client has no
   *  allowlist yet, treat as unrestricted so shipments still ship. */
  const [allowedServiceIds, setAllowedServiceIds] = useState<Set<number> | null>(null)
  const [allowedPackageIds, setAllowedPackageIds] = useState<Set<number> | null>(null)
  const [destRules, setDestRules] = useState<ClientDestinationRules | null>(null)

  // Recipient address validation result (from the Validate button).
  const [recipientCheck, setRecipientCheck] = useState<{ valid: boolean; issues: string[] } | null>(null)
  const [validating, setValidating] = useState(false)

  // International commercial-invoice line items (shown only for cross-border lanes).
  type ItemRow = { description: string; sku: string; hsCode: string; countryOfOrigin: string; quantity: string; unitValue: string }
  const blankItem = (): ItemRow => ({ description: '', sku: '', hsCode: '', countryOfOrigin: '', quantity: '1', unitValue: '' })
  const [items, setItems] = useState<ItemRow[]>([blankItem()])
  // Reason of export + currency are sticky — they prefill from the last shipment.
  const [reasonForExport, setReasonForExport] = useState(() => readSticky('ms:lastReason', 'SALE'))
  const [currency, setCurrency] = useState(() => readSticky('ms:lastCurrency', 'USD'))

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
        notify.error(e instanceof Error ? e.message : 'Failed to load shipment options.')
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
    if (!carrier && carrierOptions.length) setCarrier(carrierOptions[0])
  }, [carrierOptions, carrier])

  // The selected client's importer/broker profiles (per destination-country set).
  useEffect(() => {
    if (!clientCode) {
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
      setClientWarehouses([])
      setWarehouseCode('')
      setAllowedServiceIds(null)
      setAllowedPackageIds(null)
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
        const pkgs = pkgResp.data ?? []
        setAllowedPackageIds(pkgs.length ? new Set(pkgs.map((p) => p.presetId)) : null)
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
    setAccountNumber((cur) =>
      accountsForCarrier.some((a) => (a.accountNumber || '').toLowerCase() === cur.trim().toLowerCase())
        ? cur
        : accountsForCarrier[0]?.accountNumber ?? cur,
    )
    setServiceId((cur) => (servicesForCarrier.some((s) => s.id === cur) ? cur : servicesForCarrier[0]?.id ?? ''))
    setPackageChoice((cur) =>
      cur === CUSTOM_PKG || packagesForCarrier.some((p) => String(p.id) === cur)
        ? cur
        : packagesForCarrier[0]?.id != null
          ? String(packagesForCarrier[0]?.id)
          : CUSTOM_PKG,
    )
    // Re-validate account/service/package whenever the carrier, client, or route changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [carrier, clientCode, accountsForCarrier, servicesForCarrier, packagesForCarrier])

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
    if (yourAddr) {
      const fill = (base: ManualShipmentAddress): ManualShipmentAddress => ({
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
      })
      if (isReturn) setRecipient((r) => fill(r))
      else setSender((s) => fill(s))
    }
    const accts = (client.carrierAccounts || []).filter((a) => a.active)
    const def = accts.find((a) => a.clientDefault) || accts[0]
    if (def && carrierOptions.includes(canon(def.carrierCode))) {
      setCarrier(canon(def.carrierCode))
      setAccountNumber(def.accountNumber || '')
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
    setOverride(null)
  }, [clientCode, destCountry])

  // Sticky defaults: remember the last reason/currency for the next shipment.
  useEffect(() => writeSticky('ms:lastReason', reasonForExport), [reasonForExport])
  useEffect(() => writeSticky('ms:lastCurrency', currency), [currency])

  // A changed recipient invalidates a previous validation result.
  useEffect(() => {
    setRecipientCheck(null)
  }, [recipient.addressLine1, recipient.city, recipient.state, recipient.postalCode, recipient.countryCode])

  /** Validate the ship-to address against the platform's address checks. */
  const validateRecipient = async () => {
    setValidating(true)
    try {
      const res = await addressService.validate(recipient)
      const d = res.data
      setRecipientCheck({ valid: !!d?.valid, issues: d?.issues ?? [] })
      if (d?.valid) notify.success('Recipient address looks valid.')
      else notify.error('Recipient address has issues.')
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Address validation failed.')
    } finally {
      setValidating(false)
    }
  }

  const patchItem = (i: number, patch: Partial<ItemRow>) =>
    setItems((rows) => rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))
  const addItem = () => setItems((rows) => [blankItem(), ...rows])
  const removeItem = (i: number) => setItems((rows) => (rows.length > 1 ? rows.filter((_, idx) => idx !== i) : rows))
  const invoiceTotal = items.reduce((sum, it) => sum + (Number(it.quantity) || 0) * (Number(it.unitValue) || 0), 0)

  const submit = async () => {
    if (!carrier) return notify.error('Pick a carrier you have a verified account with.')
    if (!accountNumber.trim()) return notify.error('Enter the bill-to account number.')
    if (!recipient.name.trim() || !recipient.addressLine1.trim() || !recipient.city.trim() || !recipient.postalCode.trim()) {
      return notify.error('Recipient name, address, city and postal code are required.')
    }
    const w = Number(weight)
    if (!w || w <= 0) return notify.error('Enter a shipment weight greater than zero.')
    if (isCustomPkg && (!Number(length) || !Number(width) || !Number(height))) {
      return notify.error('Custom packaging needs length, width and height.')
    }

    const cleanItems: ManualShipmentItem[] = items
      .filter((it) => it.description.trim())
      .map((it) => ({
        description: it.description.trim(),
        sku: it.sku.trim() || undefined,
        hsCode: it.hsCode.trim() || undefined,
        countryOfOrigin: it.countryOfOrigin.trim().toUpperCase() || undefined,
        quantity: it.quantity ? Number(it.quantity) : null,
        unitValue: it.unitValue ? Number(it.unitValue) : null,
      }))
    if (isInternational) {
      if (!cleanItems.length) {
        return notify.error('International shipments need at least one commercial-invoice item.')
      }
      if (cleanItems.some((it) => it.unitValue == null || it.unitValue <= 0)) {
        return notify.error('Each commercial-invoice item needs a unit value greater than zero.')
      }
    }

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
      declaredValue: declaredValue ? Number(declaredValue) : null,
      ...(isInternational ? { items: cleanItems, reasonForExport, currency, incoterms } : {}),
      ...(isInternational && override ? { importer: override.importer, broker: override.broker } : {}),
    }

    setSubmitting(true)
    try {
      const res = await orderService.generateManualLabel(payload)
      const orderNo = res.data?.orderNo
      notify.success(res.message || 'Shipment label generated.')
      navigate(orderNo ? `/label/${orderNo}` : '/orders')
    } catch (e) {
      if (e instanceof ApiError) notify.error(e.message)
      else notify.error(e instanceof Error ? e.message : 'Failed to generate the label.')
    } finally {
      setSubmitting(false)
    }
  }

  const noCarriers = !loading && carrierOptions.length === 0

  return (
    <div className="space-y-4 pb-6">
      <PageSectionHeader
        eyebrow="Operations"
        title="New shipment"
        description="Manually enter a shipment — pick the client, carrier, addresses and package, then generate its label in one step."
        actions={
          <button
            type="button"
            onClick={() => navigate('/orders')}
            className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            <FiArrowLeft className="h-3.5 w-3.5" />
            Back to orders
          </button>
        }
      />

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
                <Field label="Carrier" required>
                  <select className={inputCls} value={carrier} onChange={(e) => setCarrier(e.target.value)}>
                    {carrierOptions.map((c) => (
                      <option key={c} value={c}>{CARRIER_LABEL[c] || c}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Reason of export">
                  <select className={inputCls} value={reasonForExport} onChange={(e) => setReasonForExport(e.target.value)}>
                    {EXPORT_REASONS.map((r) => (
                      <option key={r} value={r}>{r.charAt(0) + r.slice(1).toLowerCase()}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Currency">
                  <select className={inputCls} value={currency} onChange={(e) => setCurrency(e.target.value)}>
                    {CURRENCIES.map((c) => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                </Field>
              </div>
            </SectionCard>

            {/* ── Addresses ── */}
            <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
              <SectionCard
                icon={<FiHome className="h-3.5 w-3.5" />}
                title={isReturn ? 'Return from · customer' : 'Ship from · sender'}
              >
                <AddressBlock value={sender} onChange={(patch) => setSender((s) => ({ ...s, ...patch }))} withEmail={isReturn} />
              </SectionCard>
              <SectionCard
                icon={<FiMapPin className="h-3.5 w-3.5" />}
                title={isReturn ? 'Return to · your address' : 'Ship to · recipient'}
                badge={
                  <button
                    type="button"
                    onClick={() => void validateRecipient()}
                    disabled={validating}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:opacity-50"
                  >
                    {validating ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#5a4526]" />
                    ) : (
                      <FiCheckCircle className="h-3.5 w-3.5" />
                    )}
                    Validate address
                  </button>
                }
              >
                <AddressBlock value={recipient} onChange={(patch) => setRecipient((r) => ({ ...r, ...patch }))} withEmail={!isReturn} />
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
              </SectionCard>
            </div>

            {/* ── Carrier & package ── */}
            <div className="grid grid-cols-1 items-stretch gap-4 lg:grid-cols-2">
              <SectionCard
                icon={<FiTruck className="h-3.5 w-3.5" />}
                title="Account & service"
                badge={
                  <span className="rounded-full bg-[#efe7d4] px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-[#5a4526]">
                    {sender.countryCode || '—'} → {recipient.countryCode || '—'} · {isInternational ? 'Intl' : 'Domestic'}
                  </span>
                }
              >
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <Field label="Account (bill to)" required>
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
                    <select className={inputCls} value={serviceId} onChange={(e) => setServiceId(e.target.value ? Number(e.target.value) : '')}>
                      {servicesForCarrier.length === 0 ? <option value="">Carrier default</option> : null}
                      {servicesForCarrier.map((s) => (
                        <option key={s.id} value={s.id}>{s.name}</option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Incoterms">
                    <select className={inputCls} value={incoterms} onChange={(e) => setIncoterms(e.target.value)}>
                      <option value="DDP">DDP — sender pays duties</option>
                      <option value="DAP">DAP — receiver pays duties</option>
                    </select>
                  </Field>
                </div>
              </SectionCard>

            {/* ── Package & weight ── */}
            <SectionCard
              icon={<FiPackage className="h-3.5 w-3.5" />}
              title="Package & weight"
              badge={
                <div className="flex flex-wrap items-center gap-3">
                  <div className="flex items-center gap-1.5">
                    <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#8a7959]">Weight</span>
                    <div className="inline-flex rounded-lg border border-[#e3d9c4] bg-white p-0.5">
                      {(['LB', 'KG'] as const).map((u) => (
                        <button
                          key={u}
                          type="button"
                          onClick={() => setWeightUnit(u)}
                          className={`rounded-md px-2 py-0.5 text-[11px] font-semibold transition ${weightUnit === u ? 'bg-[#1f150c] text-[#f4eede]' : 'text-[#5a4526] hover:bg-[#faf7f0]'}`}
                        >
                          {u.toLowerCase()}
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#8a7959]">Dims</span>
                    <div className="inline-flex rounded-lg border border-[#e3d9c4] bg-white p-0.5">
                      {(['IN', 'CM'] as const).map((u) => (
                        <button
                          key={u}
                          type="button"
                          onClick={() => setDimUnit(u)}
                          className={`rounded-md px-2 py-0.5 text-[11px] font-semibold transition ${dimUnit === u ? 'bg-[#1f150c] text-[#f4eede]' : 'text-[#5a4526] hover:bg-[#faf7f0]'}`}
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
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <Field label="Packaging" required>
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
                  <Field label={`Weight (${weightUnit.toLowerCase()})`} required>
                    <input className={inputCls} type="number" min="0" step="0.1" value={weight} onChange={(e) => setWeight(e.target.value)} placeholder="2.5" />
                  </Field>
                  <Field label={`Declared value (${currency})`}>
                    <input className={inputCls} type="number" min="0" step="0.01" value={declaredValue} onChange={(e) => setDeclaredValue(e.target.value)} placeholder="100.00" />
                  </Field>
                </div>
                {isCustomPkg ? (
                  <div className="grid grid-cols-3 gap-3">
                    <Field label={`Length (${dimUnit.toLowerCase()})`} required>
                      <input className={inputCls} type="number" min="0" step="0.1" value={length} onChange={(e) => setLength(e.target.value)} placeholder="12" />
                    </Field>
                    <Field label={`Width (${dimUnit.toLowerCase()})`} required>
                      <input className={inputCls} type="number" min="0" step="0.1" value={width} onChange={(e) => setWidth(e.target.value)} placeholder="9" />
                    </Field>
                    <Field label={`Height (${dimUnit.toLowerCase()})`} required>
                      <input className={inputCls} type="number" min="0" step="0.1" value={height} onChange={(e) => setHeight(e.target.value)} placeholder="4" />
                    </Field>
                  </div>
                ) : null}
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
                  <div className="hidden min-w-[760px] grid-cols-[minmax(0,2fr)_1fr_0.9fr_0.55fr_0.55fr_1fr_1fr_44px] gap-2 sm:grid">
                    {['Description *', 'SKU', 'HS code', 'Origin', 'Qty', `Unit value (${currency}) *`, `Amount (${currency})`].map((h) => (
                      <span key={h} className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">{h}</span>
                    ))}
                    <span />
                  </div>

                  {items.map((it, i) => {
                    const amount = (Number(it.quantity) || 0) * (Number(it.unitValue) || 0)
                    return (
                      <div
                        key={i}
                        className="grid min-w-[760px] grid-cols-[minmax(0,2fr)_1fr_0.9fr_0.55fr_0.55fr_1fr_1fr_44px] items-center gap-2"
                      >
                        <input className={inputCls} value={it.description} onChange={(e) => patchItem(i, { description: e.target.value })} placeholder="Cotton t-shirt" />
                        <input className={inputCls} value={it.sku} onChange={(e) => patchItem(i, { sku: e.target.value })} placeholder="SKU-001" />
                        <input className={inputCls} value={it.hsCode} onChange={(e) => patchItem(i, { hsCode: e.target.value })} placeholder="6109.10" />
                        <input className={`${inputCls} uppercase`} value={it.countryOfOrigin} onChange={(e) => patchItem(i, { countryOfOrigin: e.target.value })} placeholder="US" maxLength={2} />
                        <input className={inputCls} type="number" min="1" step="1" value={it.quantity} onChange={(e) => patchItem(i, { quantity: e.target.value })} placeholder="1" />
                        <input className={inputCls} type="number" min="0" step="0.01" value={it.unitValue} onChange={(e) => patchItem(i, { unitValue: e.target.value })} placeholder="20.00" />
                        <div className={`${inputCls} flex items-center justify-end bg-[#faf7f0] font-mono tabular-nums`}>
                          {amount > 0 ? amount.toFixed(2) : '—'}
                        </div>
                        <div className="flex items-center justify-center">
                          <button
                            type="button"
                            onClick={() => removeItem(i)}
                            disabled={items.length === 1}
                            className="rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#b6a684] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-40"
                            aria-label={`Remove item ${i + 1}`}
                          >
                            <FiTrash2 className="h-3.5 w-3.5" />
                          </button>
                        </div>
                      </div>
                    )
                  })}

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

      {/* sticky footer action bar — stays within the content column */}
      {!loading && !noCarriers ? (
        <div className="sticky bottom-4 z-30">
          <div className="flex items-center justify-between gap-3 rounded-2xl border border-[#e3d9c4] bg-white px-5 py-3 shadow-[0_18px_50px_rgba(31,21,12,0.16)]">
            <span className="hidden text-[11.5px] text-[#8a7959] sm:block">
              The label is purchased immediately on the selected account.
              {isInternational ? ' Commercial invoice included for this cross-border lane.' : ''}
            </span>
            <div className="flex items-center gap-2">
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
    </div>
  )
}
