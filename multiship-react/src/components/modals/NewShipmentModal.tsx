import { useEffect, useMemo, useState, type ReactNode } from 'react'
import toast from 'react-hot-toast'
import { FiX, FiZap, FiArrowRight, FiTruck, FiPackage, FiMapPin, FiHome, FiUsers, FiFileText, FiPlus, FiTrash2 } from 'react-icons/fi'
import { ApiError } from '../../api/apiClient'
import {
  orderService,
  type ManualShipmentAddress,
  type ManualShipmentItem,
  type ManualShipmentPayload,
} from '../../api/orderService'
import { accountRefService, type CarrierAccountRef } from '../../api/accountRefService'
import { clientService, type Client } from '../../api/clientService'
import { shippingConfigService, type ShippingServiceItem, type PackagePreset } from '../../api/shippingConfigService'

/** Canonicalise a carrier code (ERP aliases → UPS/FEDEX/USPS). */
const canon = (c?: string | null) => {
  const v = (c || '').trim().toUpperCase()
  if (v === 'P80') return 'UPS'
  if (v === 'F77') return 'FEDEX'
  if (v === 'L01') return 'USPS'
  return v
}

const COUNTRIES = [
  ['US', 'United States'], ['CA', 'Canada'], ['MX', 'Mexico'], ['GB', 'United Kingdom'],
  ['DE', 'Germany'], ['FR', 'France'], ['NL', 'Netherlands'], ['IT', 'Italy'], ['ES', 'Spain'],
  ['IN', 'India'], ['CN', 'China'], ['JP', 'Japan'], ['AU', 'Australia'], ['SG', 'Singapore'], ['BR', 'Brazil'],
]

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

function Field({ label, required, children, className = '' }: { label: string; required?: boolean; children: ReactNode; className?: string }) {
  return (
    <label className={`block space-y-1 ${className}`}>
      <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
        {label}
        {required ? <span className="text-rose-500"> *</span> : null}
      </span>
      {children}
    </label>
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
        <select className={inputCls} value={value.countryCode} onChange={(e) => onChange({ countryCode: e.target.value })}>
          {COUNTRIES.map(([code, name]) => (
            <option key={code} value={code}>{name} ({code})</option>
          ))}
        </select>
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

export default function NewShipmentModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (orderNo?: number) => void
}) {
  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [services, setServices] = useState<ShippingServiceItem[]>([])
  const [packages, setPackages] = useState<PackagePreset[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)

  const [sender, setSender] = useState<ManualShipmentAddress>(defaultSender())
  const [recipient, setRecipient] = useState<ManualShipmentAddress>(blankAddress())

  const [carrier, setCarrier] = useState('')
  const [accountId, setAccountId] = useState<number | ''>('')
  const [serviceId, setServiceId] = useState<number | ''>('')
  const [packageChoice, setPackageChoice] = useState<string>('') // preset id as string, or CUSTOM_PKG
  const [length, setLength] = useState('')
  const [width, setWidth] = useState('')
  const [height, setHeight] = useState('')
  const [weight, setWeight] = useState('')
  const [declaredValue, setDeclaredValue] = useState('')
  const [goodsDescription, setGoodsDescription] = useState('')
  const [clientCode, setClientCode] = useState('')
  const [reference, setReference] = useState('')

  // International commercial-invoice line items (shown only for cross-border lanes).
  type ItemRow = { description: string; hsCode: string; countryOfOrigin: string; quantity: string; unitValue: string; weight: string }
  const blankItem = (): ItemRow => ({ description: '', hsCode: '', countryOfOrigin: '', quantity: '1', unitValue: '', weight: '' })
  const [items, setItems] = useState<ItemRow[]>([blankItem()])
  const [reasonForExport, setReasonForExport] = useState('SALE')
  const [currency, setCurrency] = useState('USD')

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
        toast.error(e instanceof Error ? e.message : 'Failed to load shipment options.')
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

  // Default the carrier once options are known.
  useEffect(() => {
    if (!carrier && carrierOptions.length) setCarrier(carrierOptions[0])
  }, [carrierOptions, carrier])

  const originMatch = (o?: string | null) => (o ?? 'US').toUpperCase() === (sender.countryCode || 'US').toUpperCase()
  // With a client chosen, prefer that client's own accounts on the carrier; fall
  // back to all accounts (platform) when the client has none on that carrier.
  const accountsForCarrier = useMemo(() => {
    const onCarrier = accounts.filter((a) => canon(a.carrierCode) === carrier)
    if (!clientCode) return onCarrier
    const own = onCarrier.filter((a) => (a.customerNo || '').toUpperCase() === clientCode.toUpperCase())
    return own.length ? own : onCarrier
  }, [accounts, carrier, clientCode])
  const servicesForCarrier = useMemo(
    () => services.filter((s) => canon(s.carrier) === carrier && originMatch(s.originCountry)),
    [services, carrier, sender.countryCode],
  )
  const packagesForCarrier = useMemo(
    () => packages.filter((p) => p.kind === 'CARRIER' && canon(p.carrier) === carrier && originMatch(p.originCountry)),
    [packages, carrier, sender.countryCode],
  )
  const customBoxes = useMemo(() => packages.filter((p) => p.kind !== 'CARRIER'), [packages])

  // Re-validate dependent selections when the carrier (or client) changes — but
  // KEEP a valid current pick so the client auto-selected account isn't clobbered.
  useEffect(() => {
    setAccountId((cur) => (accountsForCarrier.some((a) => a.id === cur) ? cur : accountsForCarrier[0]?.id ?? ''))
    setServiceId((cur) => (servicesForCarrier.some((s) => s.id === cur) ? cur : servicesForCarrier[0]?.id ?? ''))
    setPackageChoice((cur) =>
      cur === CUSTOM_PKG || packagesForCarrier.some((p) => String(p.id) === cur)
        ? cur
        : packagesForCarrier[0]?.id != null
          ? String(packagesForCarrier[0]?.id)
          : CUSTOM_PKG,
    )
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [carrier, clientCode])

  /** Select a client: prefill ship-from and auto-pick its default carrier + account. */
  const applyClient = (code: string) => {
    setClientCode(code)
    const client = clients.find((c) => c.clientCode === code)
    if (!client) return
    if (client.shipFrom) {
      const a = client.shipFrom
      setSender((s) => ({
        name: client.name || s.name,
        company: client.name || s.company,
        phone: client.phone || s.phone,
        email: client.email || s.email,
        addressLine1: a.line1 || s.addressLine1,
        addressLine2: a.line2 || '',
        city: a.city || s.city,
        state: a.state || s.state,
        postalCode: a.zip || s.postalCode,
        countryCode: a.country || s.countryCode,
      }))
    }
    // The client's default carrier IS the carrier of its default (client_default) account.
    const accts = (client.carrierAccounts || []).filter((a) => a.active)
    const def = accts.find((a) => a.clientDefault) || accts[0]
    if (def && carrierOptions.includes(canon(def.carrierCode))) {
      setCarrier(canon(def.carrierCode))
      setAccountId(def.id)
    }
  }

  const isCustomPkg = packageChoice === CUSTOM_PKG

  // A commercial invoice (line items) is required only across a customs border.
  // Same country, or two countries in the same customs union (EU), ship domestic.
  const EU = new Set([
    'AT', 'BE', 'BG', 'HR', 'CY', 'CZ', 'DK', 'EE', 'FI', 'FR', 'DE', 'GR', 'HU', 'IE', 'IT',
    'LV', 'LT', 'LU', 'MT', 'NL', 'PL', 'PT', 'RO', 'SK', 'SI', 'ES', 'SE',
  ])
  const sameTerritory = (a: string, b: string) => a === b || (EU.has(a) && EU.has(b))
  const isInternational =
    !!sender.countryCode &&
    !!recipient.countryCode &&
    !sameTerritory(sender.countryCode.toUpperCase(), recipient.countryCode.toUpperCase())

  const patchItem = (i: number, patch: Partial<ItemRow>) =>
    setItems((rows) => rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r)))
  const addItem = () => setItems((rows) => [...rows, blankItem()])
  const removeItem = (i: number) => setItems((rows) => (rows.length > 1 ? rows.filter((_, idx) => idx !== i) : rows))

  const submit = async () => {
    if (!carrier) return toast.error('Pick a carrier you have a verified account with.')
    if (accountId === '') return toast.error('Select the carrier account to bill.')
    if (!recipient.name.trim() || !recipient.addressLine1.trim() || !recipient.city.trim() || !recipient.postalCode.trim()) {
      return toast.error('Recipient name, address, city and postal code are required.')
    }
    const w = Number(weight)
    if (!w || w <= 0) return toast.error('Enter a shipment weight greater than zero.')
    if (isCustomPkg && (!Number(length) || !Number(width) || !Number(height))) {
      return toast.error('Custom packaging needs length, width and height.')
    }

    // International: at least one commercial-invoice line with a description + value.
    const cleanItems: ManualShipmentItem[] = items
      .filter((it) => it.description.trim())
      .map((it) => ({
        description: it.description.trim(),
        hsCode: it.hsCode.trim() || undefined,
        countryOfOrigin: it.countryOfOrigin.trim().toUpperCase() || undefined,
        quantity: it.quantity ? Number(it.quantity) : null,
        unitValue: it.unitValue ? Number(it.unitValue) : null,
        weight: it.weight ? Number(it.weight) : null,
      }))
    if (isInternational) {
      if (!cleanItems.length) {
        return toast.error('International shipments need at least one commercial-invoice item.')
      }
      if (cleanItems.some((it) => it.unitValue == null || it.unitValue <= 0)) {
        return toast.error('Each commercial-invoice item needs a unit value greater than zero.')
      }
    }

    const payload: ManualShipmentPayload = {
      sender,
      recipient,
      accountId: Number(accountId),
      serviceId: serviceId === '' ? null : Number(serviceId),
      packagePresetId: isCustomPkg ? null : Number(packageChoice),
      length: isCustomPkg ? Number(length) : null,
      width: isCustomPkg ? Number(width) : null,
      height: isCustomPkg ? Number(height) : null,
      dimUnit: 'IN',
      weight: w,
      weightUnit: 'LB',
      clientCode: clientCode.trim() || undefined,
      declaredValue: declaredValue ? Number(declaredValue) : null,
      goodsDescription: goodsDescription.trim() || undefined,
      reference: reference.trim() || undefined,
      ...(isInternational
        ? { items: cleanItems, reasonForExport, currency, incoterms: undefined }
        : {}),
    }

    setSubmitting(true)
    try {
      const res = await orderService.generateManualLabel(payload)
      const data = res.data
      toast.success(res.message || 'Shipment label generated.')
      onCreated(data?.orderNo)
    } catch (e) {
      if (e instanceof ApiError) toast.error(e.message)
      else toast.error(e instanceof Error ? e.message : 'Failed to generate the label.')
    } finally {
      setSubmitting(false)
    }
  }

  const noCarriers = !loading && carrierOptions.length === 0

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-950/40 p-4 backdrop-blur-sm">
      <div className="my-6 w-full max-w-3xl overflow-hidden rounded-2xl bg-white shadow-2xl">
        {/* header band */}
        <div className="flex items-start justify-between gap-4 bg-[#1f150c] px-6 py-4 text-[#f4eede]">
          <div>
            <p className="font-mono text-[10px] font-bold uppercase tracking-[0.22em] text-[#b6a684]">
              Manual shipment · one-shot label
            </p>
            <h2 className="mt-0.5 text-lg font-semibold">New shipment</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1.5 text-[#b6a684] transition hover:bg-white/10 hover:text-white"
            aria-label="Close"
          >
            <FiX className="h-5 w-5" />
          </button>
        </div>

        <div className="max-h-[70vh] space-y-6 overflow-y-auto px-6 py-5">
          {loading ? (
            <p className="py-10 text-center text-sm text-[#8a7959]">Loading carriers, services and packaging…</p>
          ) : noCarriers ? (
            <div className="rounded-xl border border-dashed border-[#e3d9c4] bg-[#faf7f0] px-4 py-8 text-center">
              <p className="text-sm font-semibold text-[#412d15]">No shippable carrier yet</p>
              <p className="mt-1 text-[13px] text-[#8a7959]">
                A carrier needs an active account <em>and</em> synced live services. Verify a carrier and sync its services first.
              </p>
            </div>
          ) : (
            <>
              {/* ── Carrier & service ── */}
              <section className="space-y-3">
                <div className="flex items-center gap-2 border-b border-dashed border-[#e3d9c4] pb-1.5">
                  <FiUsers className="h-3.5 w-3.5 text-[#8a7959]" />
                  <h3 className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">Client & carrier</h3>
                </div>
                <Field label="Client">
                  <select className={inputCls} value={clientCode} onChange={(e) => applyClient(e.target.value)}>
                    <option value="">No client — ad-hoc shipment</option>
                    {clients.map((c) => (
                      <option key={c.clientCode} value={c.clientCode}>
                        {c.clientCode} — {c.name}
                      </option>
                    ))}
                  </select>
                </Field>
                <p className="-mt-1 text-[11px] text-[#8a7959]">
                  Choosing a client fills its ship-from and auto-selects its default carrier account.
                </p>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                  <Field label="Carrier" required>
                    <select className={inputCls} value={carrier} onChange={(e) => setCarrier(e.target.value)}>
                      {carrierOptions.map((c) => (
                        <option key={c} value={c}>{CARRIER_LABEL[c] || c}</option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Account (bill to)" required>
                    <select className={inputCls} value={accountId} onChange={(e) => setAccountId(e.target.value ? Number(e.target.value) : '')}>
                      {accountsForCarrier.length === 0 ? <option value="">No account</option> : null}
                      {accountsForCarrier.map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.accountName || a.accountNumber} · {a.accountNumber}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="Service level" className="col-span-2 sm:col-span-1">
                    <select className={inputCls} value={serviceId} onChange={(e) => setServiceId(e.target.value ? Number(e.target.value) : '')}>
                      {servicesForCarrier.length === 0 ? <option value="">Carrier default</option> : null}
                      {servicesForCarrier.map((s) => (
                        <option key={s.id} value={s.id}>{s.name}</option>
                      ))}
                    </select>
                  </Field>
                </div>
              </section>

              {/* ── Ship from ── */}
              <section className="space-y-3">
                <div className="flex items-center gap-2 border-b border-dashed border-[#e3d9c4] pb-1.5">
                  <FiHome className="h-3.5 w-3.5 text-[#8a7959]" />
                  <h3 className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">Ship from · sender</h3>
                </div>
                <AddressBlock value={sender} onChange={(patch) => setSender((s) => ({ ...s, ...patch }))} />
              </section>

              {/* ── Ship to ── */}
              <section className="space-y-3">
                <div className="flex items-center gap-2 border-b border-dashed border-[#e3d9c4] pb-1.5">
                  <FiMapPin className="h-3.5 w-3.5 text-[#8a7959]" />
                  <h3 className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">Ship to · recipient</h3>
                </div>
                <AddressBlock value={recipient} onChange={(patch) => setRecipient((r) => ({ ...r, ...patch }))} withEmail />
              </section>

              {/* ── Package & weight ── */}
              <section className="space-y-3">
                <div className="flex items-center gap-2 border-b border-dashed border-[#e3d9c4] pb-1.5">
                  <FiPackage className="h-3.5 w-3.5 text-[#8a7959]" />
                  <h3 className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">Package & weight</h3>
                </div>
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
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
                      <option value={CUSTOM_PKG}>Custom dimensions…</option>
                    </select>
                  </Field>
                  <Field label="Weight (lb)" required>
                    <input className={inputCls} type="number" min="0" step="0.1" value={weight} onChange={(e) => setWeight(e.target.value)} placeholder="2.5" />
                  </Field>
                  <Field label="Declared value ($)">
                    <input className={inputCls} type="number" min="0" step="0.01" value={declaredValue} onChange={(e) => setDeclaredValue(e.target.value)} placeholder="100.00" />
                  </Field>
                </div>
                {isCustomPkg ? (
                  <div className="grid grid-cols-3 gap-3">
                    <Field label="Length (in)" required>
                      <input className={inputCls} type="number" min="0" step="0.1" value={length} onChange={(e) => setLength(e.target.value)} placeholder="12" />
                    </Field>
                    <Field label="Width (in)" required>
                      <input className={inputCls} type="number" min="0" step="0.1" value={width} onChange={(e) => setWidth(e.target.value)} placeholder="9" />
                    </Field>
                    <Field label="Height (in)" required>
                      <input className={inputCls} type="number" min="0" step="0.1" value={height} onChange={(e) => setHeight(e.target.value)} placeholder="4" />
                    </Field>
                  </div>
                ) : null}
                <div className="grid grid-cols-2 gap-3">
                  <Field label="Goods description">
                    <input className={inputCls} value={goodsDescription} onChange={(e) => setGoodsDescription(e.target.value)} placeholder="Apparel" />
                  </Field>
                  <Field label="Reference">
                    <input className={inputCls} value={reference} onChange={(e) => setReference(e.target.value)} placeholder="Optional" />
                  </Field>
                </div>
              </section>

              {/* ── Commercial invoice items (international only) ── */}
              {isInternational ? (
                <section className="space-y-3">
                  <div className="flex items-center justify-between gap-2 border-b border-dashed border-[#e3d9c4] pb-1.5">
                    <div className="flex items-center gap-2">
                      <FiFileText className="h-3.5 w-3.5 text-[#8a7959]" />
                      <h3 className="font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">
                        Items · commercial invoice
                      </h3>
                    </div>
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-amber-700">
                      Cross-border · required
                    </span>
                  </div>
                  <p className="-mt-1 text-[11px] text-[#8a7959]">
                    {sender.countryCode} → {recipient.countryCode} crosses a customs border. These lines print on the
                    commercial invoice customs uses to assess duty &amp; tax. Importer/broker resolve from the client's
                    customs profile.
                  </p>

                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <Field label="Reason for export">
                      <select className={inputCls} value={reasonForExport} onChange={(e) => setReasonForExport(e.target.value)}>
                        {['SALE', 'GIFT', 'SAMPLE', 'RETURN', 'REPAIR', 'PERSONAL'].map((r) => (
                          <option key={r} value={r}>{r.charAt(0) + r.slice(1).toLowerCase()}</option>
                        ))}
                      </select>
                    </Field>
                    <Field label="Currency">
                      <select className={inputCls} value={currency} onChange={(e) => setCurrency(e.target.value)}>
                        {['USD', 'EUR', 'GBP', 'CAD', 'INR', 'AUD', 'SGD', 'JPY'].map((c) => (
                          <option key={c} value={c}>{c}</option>
                        ))}
                      </select>
                    </Field>
                  </div>

                  <div className="space-y-2">
                    {items.map((it, i) => (
                      <div key={i} className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0]/60 p-3">
                        <div className="mb-2 flex items-center justify-between">
                          <span className="font-mono text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
                            Item {i + 1}
                          </span>
                          <button
                            type="button"
                            onClick={() => removeItem(i)}
                            disabled={items.length === 1}
                            className="rounded-lg p-1 text-[#b6a684] transition hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-40"
                            aria-label={`Remove item ${i + 1}`}
                          >
                            <FiTrash2 className="h-3.5 w-3.5" />
                          </button>
                        </div>
                        <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-6">
                          <Field label="Description" required className="col-span-2 sm:col-span-3">
                            <input className={inputCls} value={it.description} onChange={(e) => patchItem(i, { description: e.target.value })} placeholder="Cotton t-shirt" />
                          </Field>
                          <Field label="HS code" className="sm:col-span-2">
                            <input className={inputCls} value={it.hsCode} onChange={(e) => patchItem(i, { hsCode: e.target.value })} placeholder="6109.10" />
                          </Field>
                          <Field label="Origin">
                            <input className={inputCls} value={it.countryOfOrigin} onChange={(e) => patchItem(i, { countryOfOrigin: e.target.value })} placeholder="US" maxLength={2} />
                          </Field>
                          <Field label="Qty">
                            <input className={inputCls} type="number" min="1" step="1" value={it.quantity} onChange={(e) => patchItem(i, { quantity: e.target.value })} placeholder="1" />
                          </Field>
                          <Field label={`Unit value (${currency})`} required>
                            <input className={inputCls} type="number" min="0" step="0.01" value={it.unitValue} onChange={(e) => patchItem(i, { unitValue: e.target.value })} placeholder="20.00" />
                          </Field>
                          <Field label="Weight (lb)">
                            <input className={inputCls} type="number" min="0" step="0.1" value={it.weight} onChange={(e) => patchItem(i, { weight: e.target.value })} placeholder="0.5" />
                          </Field>
                        </div>
                      </div>
                    ))}
                    <button
                      type="button"
                      onClick={addItem}
                      className="inline-flex items-center gap-1.5 rounded-xl border border-dashed border-[#cdbf9f] bg-white px-3 py-2 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
                    >
                      <FiPlus className="h-3.5 w-3.5" />
                      Add item
                    </button>
                  </div>
                </section>
              ) : null}
            </>
          )}
        </div>

        {/* footer */}
        <div className="flex items-center justify-between gap-3 border-t border-[#e3d9c4] bg-[#faf7f0] px-6 py-3.5">
          <span className="text-[11.5px] text-[#8a7959]">The label is purchased immediately on the selected account.</span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-white"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void submit()}
              disabled={submitting || loading || noCarriers}
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
                  Generate label
                  <FiArrowRight className="h-3.5 w-3.5" />
                </>
              )}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
