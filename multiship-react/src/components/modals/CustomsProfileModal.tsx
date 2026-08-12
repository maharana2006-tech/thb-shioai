import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { notify } from '../../utils/notify'
import {
  FiBriefcase,
  FiGlobe,
  FiMapPin,
  FiTrash2,
  FiUser,
  FiUsers,
  FiX,
} from 'react-icons/fi'
import { customsProfileService, type CustomsProfile } from '../../api/customsProfileService'
import type { Client } from '../../api/clientService'
import {
  COUNTRIES,
  countriesInRegion,
  countryName,
  groupByRegion,
  regionOf,
  territoryLabel,
  territoryOf,
  type Region,
} from '../../utils/countries'
import { taxIdentityFor, taxIdentityTitle } from '../../utils/taxIdentity'
import { SHIPPING_PURPOSES } from '../../utils/customsOptions'
import { FIELD_LIMITS, validateLength, validateName, validatePhone } from '../../utils/clientValidation'
import Select from '../workspace/Select'
import RegionCountryPicker from '../workspace/RegionCountryPicker'

/**
 * ISO-4217 currency codes accepted by the customs-profile Currency picker.
 * The set covers the countries our carriers most commonly ship from and
 * corresponds 1:1 with the {@link CURRENCY_BY_COUNTRY} preselect map — any
 * addition here that isn't reachable from a country default is fine; the
 * reverse (a country code that maps to a currency missing here) is not.
 */
const CUSTOMS_CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'AUD', 'NZD', 'JPY', 'CNY', 'HKD', 'INR', 'MXN', 'BRL', 'SGD', 'CHF'] as const

/**
 * ISO-2 country → default currency mapping used to preselect the
 * customs-profile currency from the client's ship-from country. Only
 * covers the origins we actively ship from; anything unlisted defaults
 * to USD.
 */
const CURRENCY_BY_COUNTRY: Record<string, string> = {
  US: 'USD',
  CA: 'CAD',
  GB: 'GBP',
  UK: 'GBP',
  IE: 'EUR', DE: 'EUR', FR: 'EUR', IT: 'EUR', ES: 'EUR', NL: 'EUR', BE: 'EUR', PT: 'EUR', AT: 'EUR', FI: 'EUR',
  GR: 'EUR', LU: 'EUR', SK: 'EUR', SI: 'EUR', EE: 'EUR', LV: 'EUR', LT: 'EUR', CY: 'EUR', MT: 'EUR', HR: 'EUR',
  AU: 'AUD',
  NZ: 'NZD',
  JP: 'JPY',
  CN: 'CNY',
  HK: 'HKD',
  IN: 'INR',
  MX: 'MXN',
  BR: 'BRL',
  SG: 'SGD',
  CH: 'CHF',
}

/** Best-guess default currency for a given ship-from country (ISO-2). */
function defaultCurrencyForCountry(country: string | null | undefined): string {
  if (!country) return 'USD'
  return CURRENCY_BY_COUNTRY[country.trim().toUpperCase()] ?? 'USD'
}

interface CustomsProfileModalProps {
  /** All clients — feeds the client picker. */
  clients: Client[]
  /** Lock the profile to this client (launched from a client row). */
  lockedClientCode?: string
  /** Existing profile to edit; omit to create a new one. */
  profile?: CustomsProfile | null
  /** Every profile (used to block countries already owned by another profile). */
  existingProfiles?: CustomsProfile[]
  onClose: () => void
  onSaved?: () => void
}

const inputClassName =
  'w-full rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] text-[#1f150c] outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10 disabled:cursor-not-allowed disabled:bg-[#eee6d6] disabled:text-[#b6a684]'
/** inputClassName with the border swapped to a rose error ring when invalid. */
const inputErrClassName =
  'w-full rounded-xl border border-rose-400 bg-white px-3 py-2 text-[13px] text-[#1f150c] outline-none transition focus:border-rose-500 focus:ring-4 focus:ring-rose-100'
const fieldCls = (bad?: string | null) => (bad ? inputErrClassName : inputClassName)


const blank = (): CustomsProfile => ({ countries: [] })

function Field({ label, required, error, children }: { label: string; required?: boolean; error?: string | null; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-[#b6a684]">
        {label}
        {required ? <span className="ml-0.5 text-rose-500">*</span> : null}
      </span>
      {children}
      {error ? (
        <span className="mt-1 flex items-center gap-1 text-[10.5px] font-semibold text-rose-600">
          <span aria-hidden>⚠</span> {error}
        </span>
      ) : null}
    </label>
  )
}

/** A numbered section card: espresso step badge + title + optional aside. */
function Section({
  step,
  icon,
  tone,
  title,
  hint,
  aside,
  children,
}: {
  step: string
  icon: ReactNode
  tone: string
  title: string
  hint?: string
  aside?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white">
      <div className="flex items-center justify-between gap-3 border-b border-[#eee6d6] bg-[#faf7f0]/60 px-4 py-2.5">
        <div className="flex items-center gap-2.5">
          <span className="grid h-6 w-6 place-items-center rounded-full bg-[#1f150c] text-[10.5px] font-bold text-[#f4eede]">
            {step}
          </span>
          <span className={`inline-flex h-6 w-6 items-center justify-center rounded-lg ${tone}`}>{icon}</span>
          <div>
            <h4 className="text-[12.5px] font-semibold text-[#1f150c]">{title}</h4>
            {hint ? <p className="text-[10.5px] text-[#b6a684]">{hint}</p> : null}
          </div>
        </div>
        {aside}
      </div>
      <div className="p-4">{children}</div>
    </section>
  )
}

/**
 * Country dropdown.
 * - `region` (importer): STRICT — the Importer of Record is legally registered
 *   in the destination country, so only that region's countries are offered.
 * - `preferRegion` (broker): OPEN — brokers/forwarders often sit at ORIGIN
 *   (e.g. a US forwarder clearing India through local partners), so every
 *   country is allowed; the destination region is just listed first.
 * An out-of-list current value (legacy data) stays visible so the select
 * never renders blank.
 */
function CountrySelect({
  value,
  onChange,
  disabled,
  region,
  preferRegion,
  allowedCodes,
}: {
  value: string
  onChange: (v: string) => void
  disabled?: boolean
  region?: Region
  preferRegion?: Region
  /** Strictest filter: only these codes are offered (customs-territory lock). */
  allowedCodes?: string[]
}) {
  const strict = allowedCodes
    ? COUNTRIES.filter((c) => allowedCodes.includes(c.code))
    : region
      ? countriesInRegion(region)
      : null
  const options = strict ?? COUNTRIES
  const hasValue = !!value && options.some((c) => c.code === value)
  const preferred = !strict && preferRegion ? countriesInRegion(preferRegion) : null
  const rest = preferred ? COUNTRIES.filter((c) => c.region !== preferRegion) : null
  return (
    <Select value={value ?? ''} onChange={(e) => onChange(e.target.value)} disabled={disabled}>
      <option value="">Select…</option>
      {value && !hasValue ? (
        <option value={value}>{value} — {countryName(value)} (outside region)</option>
      ) : null}
      {preferred && rest ? (
        <>
          <optgroup label={`${preferRegion} — destination region`}>
            {preferred.map((c) => (
              <option key={c.code} value={c.code}>{c.code} — {c.name}</option>
            ))}
          </optgroup>
          <optgroup label="Other countries">
            {rest.map((c) => (
              <option key={c.code} value={c.code}>{c.code} — {c.name}</option>
            ))}
          </optgroup>
        </>
      ) : (
        options.map((c) => (
          <option key={c.code} value={c.code}>{c.code} — {c.name}</option>
        ))
      )}
    </Select>
  )
}

/**
 * Create/edit ONE importer + broker profile covering countries of a single
 * region. Steps: client + destinations → importer → broker → shipment
 * defaults. Launched from the management page (client chosen here) or a
 * client row (client locked).
 */
export default function CustomsProfileModal({
  clients,
  lockedClientCode,
  profile,
  existingProfiles = [],
  onClose,
  onSaved,
}: CustomsProfileModalProps) {
  const [form, setForm] = useState<CustomsProfile>(() => profile ?? blank())
  const [clientCode, setClientCode] = useState<string>(
    () => (profile?.clientCode ?? lockedClientCode ?? '').toUpperCase()
  )
  const [saving, setSaving] = useState(false)
  /**
   * Named broker (Broker Select) vs the carrier's own brokerage (the default).
   * Presence = name OR company, matching the backend's brokerage marker — a
   * company-only broker must not silently read as "carrier default".
   */
  const [ownBroker, setOwnBroker] = useState<boolean>(() => !!(profile?.brokerName || profile?.brokerCompany))
  const [fetchedProfiles, setFetchedProfiles] = useState<CustomsProfile[]>([])

  const clientLocked = !!lockedClientCode || !!profile?.id
  const editing = !!profile?.id

  const client = useMemo(
    () => clients.find((c) => c.clientCode.toUpperCase() === clientCode) ?? null,
    [clients, clientCode]
  )

  // When the caller didn't hand us the full profile list, fetch the selected
  // client's profiles so we can still block countries owned by other profiles.
  useEffect(() => {
    if (existingProfiles.length || !clientCode) {
      setFetchedProfiles([])
      return
    }
    let cancelled = false
    customsProfileService
      .list(clientCode)
      .then((list) => {
        if (!cancelled) setFetchedProfiles(list)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [clientCode, existingProfiles.length])

  const knownProfiles = existingProfiles.length ? existingProfiles : fetchedProfiles

  const set = (key: keyof CustomsProfile) => (e: { target: { value: string } }) =>
    setForm((cur) => ({ ...cur, [key]: e.target.value }))

  // ── Inline per-field validation (importer + broker input fields) ──────────
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const markTouched = (k: string) => setTouched((cur) => ({ ...cur, [k]: true }))
  // Section-level gate errors (client / destinations / importer country). These
  // used to fire as notify popups; now they render inline under their control.
  // Populated on a failed save attempt; each clears when its input changes.
  const [gateErrors, setGateErrors] = useState<{ client?: string; destinations?: string; importerCountry?: string }>({})
  const clearGate = (k: keyof typeof gateErrors) =>
    setGateErrors((cur) => (cur[k] ? { ...cur, [k]: undefined } : cur))
  const fieldErrors = useMemo(() => {
    const s = (v: unknown) => (typeof v === 'string' ? v : v == null ? '' : String(v))
    // Optional free-text: bounded length + the app-wide <> XSS guard (via
    // validateLength), skipped when blank so optional fields stay optional.
    const opt = (v: unknown, max: number, label: string) =>
      s(v).trim() ? validateLength(s(v), max, label, false) : null
    const out: Record<string, string | null> = {
      // Registered importer — name/address1/city are required by the backend.
      importerName: validateName(s(form.importerName)),
      importerContact: opt(form.importerContact, FIELD_LIMITS.addr.name, 'Contact'),
      importerPhone: validatePhone(s(form.importerPhone), false),
      importerAddress1: validateLength(s(form.importerAddress1), FIELD_LIMITS.addr.line1, 'Address line 1', true, 2),
      importerAddress2: opt(form.importerAddress2, FIELD_LIMITS.addr.line2, 'Address line 2'),
      importerCity: validateLength(s(form.importerCity), FIELD_LIMITS.addr.city, 'City', true, 2),
      importerState: opt(form.importerState, FIELD_LIMITS.addr.state, 'State'),
      importerPostcode: opt(form.importerPostcode, FIELD_LIMITS.addr.zip, 'Post code'),
      // Broker — only validated when the operator chose "our own broker".
      // Name required; the rest are optional but still <>-safe + length-bounded.
      brokerName: ownBroker ? validateName(s(form.brokerName)) : null,
      brokerPhone: opt(form.brokerPhone, FIELD_LIMITS.phone, 'Phone'),
      brokerCompany: ownBroker ? opt(form.brokerCompany, FIELD_LIMITS.name, 'Broker company') : null,
      brokerAddress1: ownBroker ? opt(form.brokerAddress1, FIELD_LIMITS.addr.line1, 'Address line 1') : null,
      brokerAddress2: ownBroker ? opt(form.brokerAddress2, FIELD_LIMITS.addr.line2, 'Address line 2') : null,
      brokerCity: ownBroker ? opt(form.brokerCity, FIELD_LIMITS.addr.city, 'City') : null,
      brokerState: ownBroker ? opt(form.brokerState, FIELD_LIMITS.addr.state, 'State') : null,
      brokerPostcode: ownBroker ? opt(form.brokerPostcode, FIELD_LIMITS.addr.zip, 'Postal code') : null,
      brokerId: ownBroker ? opt(form.brokerId, 50, 'Broker ID / Filer') : null,
      brokerLicense: ownBroker ? opt(form.brokerLicense, 50, 'License no') : null,
    }
    // Country-specific tax identifiers (EIN / VAT+EORI / GSTIN+IEC / CNPJ …).
    // We don't hard-code each country's exact format, but every ID is <>-safe
    // and length-bounded so a stray tag or pasted blob can't slip through.
    const territory = (form.countries ?? []).length ? territoryOf((form.countries ?? [])[0]) : null
    const spec = territory ? taxIdentityFor(territory) : null
    if (spec) {
      for (const f of spec.fields) {
        out[f.column] = opt((form as unknown as Record<string, unknown>)[f.column], 40, f.label)
      }
    }
    return out
  }, [form, ownBroker])
  const err = (k: string): string | null => (touched[k] ? fieldErrors[k] : null)
  const hasFieldErrors = Object.values(fieldErrors).some(Boolean)
  const touchAllFields = () =>
    setTouched(Object.keys(fieldErrors).reduce((m, k) => ({ ...m, [k]: true }), {}))

  /**
   * Preselect the customs-defaults currency from the picked client's
   * Ship-From country. Only fills when the operator hasn't already picked
   * a currency (create mode + blank field) — never overrides an explicit
   * choice or an existing profile's persisted value.
   */
  useEffect(() => {
    if (editing) return
    if (form.currency && form.currency.trim()) return
    const originCountry = client?.shipFrom?.country ?? null
    if (!originCountry) return
    setForm((cur) => ({ ...cur, currency: defaultCurrencyForCountry(originCountry) }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client?.shipFrom?.country, editing])

  // Countries already owned by ANOTHER profile of this client — cannot double-book.
  const disabledCodes = useMemo(() => {
    const s = new Set<string>()
    knownProfiles
      .filter((p) => (p.clientCode ?? '').toUpperCase() === clientCode && p.id !== form.id)
      .forEach((p) => (p.countries ?? []).forEach((c) => s.add(c.toUpperCase())))
    return s
  }, [knownProfiles, clientCode, form.id])

  const selectedRegion = groupByRegion(form.countries ?? [])[0] ?? null
  /**
   * Every profile is a BUSINESS importer (fixed registered entity) — the
   * Receiver/DAP mode exists in the backend but is hidden from the UI per
   * client request. The customs territory is pinned by the first destination:
   * one importer registration is valid for exactly one territory, so the
   * picker and the importer-country dropdown both lock to it.
   */
  const activeTerritory = (form.countries ?? []).length
    ? territoryOf((form.countries ?? [])[0])
    : null
  const territoryCodes = useMemo(
    () => (activeTerritory ? COUNTRIES.filter((c) => territoryOf(c.code) === activeTerritory).map((c) => c.code) : undefined),
    [activeTerritory]
  )
  /** Country-specific tax identifiers the destination territory requires. */
  const taxSpec = useMemo(() => (activeTerritory ? taxIdentityFor(activeTerritory) : null), [activeTerritory])
  /** The one region this profile covers — importer/broker countries must live in it. */
  const activeRegion: Region | undefined = (form.countries ?? []).length
    ? regionOf((form.countries ?? [])[0])
    : undefined

  // When the destination region/territory changes, an importer country from
  // the old one is stale — clear it so the user re-picks. BUSINESS locks to
  // the customs territory (stricter); otherwise the region. The broker is
  // deliberately NOT touched: brokers/forwarders may sit anywhere (a US
  // forwarder can clear India shipments through local partners).
  useEffect(() => {
    if (!activeRegion) return
    setForm((cur) => {
      if (!cur.importerCountry) return cur
      const stale = activeTerritory
        ? territoryOf(cur.importerCountry) !== activeTerritory
        : regionOf(cur.importerCountry) !== activeRegion
      return stale ? { ...cur, importerCountry: '' } : cur
    })
  }, [activeRegion, activeTerritory])

  // NOTE: the old "Same as Client" broker checkbox was removed deliberately —
  // a customs broker is a LICENSED third party at the destination (19 USC
  // §1641 in the US; CBLR 2018 in India). The shipper's own origin identity
  // never belongs in the broker fields; "no broker" is the carrier-default card.

  const handleSave = async () => {
    // Collect ALL section-level gate errors so they surface together, inline
    // under their control — no notify popups. Field-level errors (importer /
    // broker inputs, incl. an empty broker name) already render under each
    // input via the Field `error` prop.
    const gates: { client?: string; destinations?: string; importerCountry?: string } = {}
    const countries = form.countries ?? []
    if (!clientCode) {
      gates.client = 'Choose a client.'
    }
    if (!countries.length) {
      gates.destinations = 'Select at least one destination country.'
    } else {
      // One region per profile — also catches legacy cross-region data on edit.
      const region = regionOf(countries[0])
      if (countries.some((c) => regionOf(c) !== region)) {
        gates.destinations = 'A profile covers one region — remove countries outside ' + region + '.'
      } else {
        // One importer registration is valid for exactly ONE customs territory
        // (EU/EAEU/GCC/SACU as a whole, or a single country) — a "Europe"
        // profile mixing the EU with the UK would apply an EU EORI to UK
        // shipments, which is invalid post-Brexit.
        const territory = territoryOf(countries[0])
        const outsider = countries.find((c) => territoryOf(c) !== territory)
        if (outsider) {
          gates.destinations = `${countryName(outsider)} is outside ${territoryLabel(territory)} — an importer registration covers one customs territory. Create a separate profile for it.`
        } else if (form.importerCountry && territoryOf(form.importerCountry) !== territory) {
          // The Importer of Record must be established IN that territory.
          gates.importerCountry = `Importer country must be in ${territoryLabel(territory)}.`
        }
      }
    }

    const fieldBad = hasFieldErrors
    if (fieldBad) touchAllFields()
    setGateErrors(gates)
    if (Object.keys(gates).length > 0 || fieldBad) return

    setSaving(true)
    try {
      // Carrier-default brokerage carries no broker — blank the fields so
      // stale data never rides along in the customs payload.
      const brokerWipe = !ownBroker
        ? {
            brokerName: '', brokerCompany: '', brokerCountry: '', brokerAddress1: '',
            brokerAddress2: '', brokerPhone: '', brokerCity: '', brokerState: '',
            brokerPostcode: '', brokerId: '', brokerLicense: '',
          }
        : {}
      await customsProfileService.save(clientCode, {
        ...form,
        ...brokerWipe,
        // The type code comes from the territory spec so documents print
        // "CNPJ: …" / "EIN: …" — never a user-picked generic type.
        importerTaxIdType: taxSpec?.typeCode ?? form.importerTaxIdType,
        importerType: 'BUSINESS',
        clientCode,
      })
      notify.success(`Importer/Broker for ${clientCode} saved (${form.countries.length} destinations).`)
      onSaved?.()
      onClose()
    } catch (e) {
      notify.apiError(e, 'Failed to save the profile.')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!editing || !form.id) return
    if (!(await notify.confirm('Delete this importer/broker profile?', {
      title: 'Delete profile',
      confirmLabel: 'Delete',
      danger: true,
    }))) return
    setSaving(true)
    try {
      await customsProfileService.remove(clientCode, form.id)
      notify.success('Profile deleted.')
      onSaved?.()
      onClose()
    } catch (e) {
      notify.apiError(e, 'Failed to delete the profile.')
    } finally {
      setSaving(false)
    }
  }

  const initials = (client?.name || clientCode || '?')
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('')

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label="Importer / Broker profile"
      onClick={onClose}
    >
      <div
        className="flex max-h-[92vh] w-full max-w-4xl flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* header */}
        <div className="flex items-start justify-between gap-3 border-b border-[#eee6d6] px-5 py-4">
          <div className="flex items-center gap-3">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[#1f150c] text-[14px] font-bold text-[#e1dcc9] shadow-sm">
              {initials || <FiUsers className="h-4 w-4" />}
            </span>
            <div>
              <p className="inline-flex items-center gap-1.5 text-[10.5px] font-bold uppercase tracking-[0.16em] text-[#b6a684]">
                <FiGlobe className="h-3.5 w-3.5" /> Importer / Broker profile
              </p>
              <h3 className="mt-1 text-[15px] font-semibold text-[#1f150c]">
                {editing ? 'Edit profile' : 'New profile'}
                {client ? <span className="text-[#b6a684]"> · {client.name}</span> : null}
              </h3>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {/* live summary pill */}
            {selectedRegion ? (
              <span className="hidden items-center gap-1.5 rounded-full bg-[#412d15]/10 px-3 py-1.5 text-[11px] font-bold text-[#412d15] sm:inline-flex">
                <FiMapPin className="h-3 w-3" />
                {selectedRegion.region} · {(form.countries ?? []).length} destination{(form.countries ?? []).length === 1 ? '' : 's'}
              </span>
            ) : null}
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-[#e3d9c4] bg-white p-2 text-[#8a7959] transition hover:bg-[#faf7f0]"
              aria-label="Close"
            >
              <FiX className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="flex-1 space-y-3.5 overflow-y-auto bg-[#faf7f0]/50 px-5 py-4">
          {/* 1 — Client & destinations */}
          <Section
            step="1"
            icon={<FiUsers className="h-3.5 w-3.5" />}
            tone="bg-[#412d15]/10 text-[#412d15]"
            title="Client & destinations"
            hint="Who ships, and to which countries of one region this profile applies."
          >
            <div className="grid gap-4 md:grid-cols-[minmax(0,250px)_1fr]">
              <div>
                <Field label="Client" required error={gateErrors.client}>
                  <Select
                    value={clientCode}
                    onChange={(e) => {
                      setClientCode(e.target.value.toUpperCase())
                      clearGate('client')
                    }}
                    disabled={clientLocked}
                  >
                    <option value="">Choose a client…</option>
                    {clients.map((c) => (
                      <option key={c.clientCode} value={c.clientCode}>{c.clientCode} — {c.name}</option>
                    ))}
                  </Select>
                </Field>
                {clientLocked ? (
                  <p className="mt-1.5 text-[11px] text-[#b6a684]">
                    {editing ? 'Client is fixed while editing.' : 'Pre-selected from the client.'}
                  </p>
                ) : null}

                {/* selection summary */}
                <div className="mt-3 rounded-xl border border-[#eee6d6] bg-[#faf7f0]/80 p-3">
                  <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">Selection</p>
                  {selectedRegion ? (
                    <>
                      <p className="mt-1 text-[12px] font-semibold text-[#412d15]">{selectedRegion.region}</p>
                      <div className="mt-1.5 flex flex-wrap gap-1">
                        {selectedRegion.codes.map((c) => (
                          <span key={c} title={countryName(c)} className="rounded-md bg-white px-1.5 py-0.5 text-[10.5px] font-semibold text-[#5a4526] ring-1 ring-[#e3d9c4]">
                            {c}
                          </span>
                        ))}
                      </div>
                    </>
                  ) : (
                    <p className="mt-1 text-[11.5px] text-[#b6a684]">Nothing selected yet.</p>
                  )}
                </div>
              </div>

              <div>
                <RegionCountryPicker
                  value={form.countries ?? []}
                  onChange={(codes) => { setForm((cur) => ({ ...cur, countries: codes })); clearGate('destinations') }}
                  disabledCodes={disabledCodes}
                  territoryConstrained
                />
                {gateErrors.destinations ? (
                  <p className="mt-1.5 flex items-start gap-1 text-[10.5px] font-semibold text-rose-600">
                    <span aria-hidden>⚠</span> {gateErrors.destinations}
                  </p>
                ) : null}
              </div>
            </div>
          </Section>

          {/* 2 — Importer */}
          <Section
            step="2"
            icon={<FiUser className="h-3.5 w-3.5" />}
            tone="bg-[#412d15]/10 text-[#412d15]"
            title="Importer of record"
            hint="The registered entity importing the goods at the destination."
          >
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <div className="col-span-2">
                <Field label="Importer Name" required error={err('importerName')}><input value={form.importerName ?? ''} onChange={set('importerName')} onBlur={() => markTouched('importerName')} maxLength={FIELD_LIMITS.name} className={fieldCls(err('importerName'))} /></Field>
              </div>
              <Field label="Contact" error={err('importerContact')}><input value={form.importerContact ?? ''} onChange={set('importerContact')} onBlur={() => markTouched('importerContact')} maxLength={FIELD_LIMITS.addr.name} className={fieldCls(err('importerContact'))} /></Field>
              <Field label="Phone" error={err('importerPhone')}><input value={form.importerPhone ?? ''} onChange={set('importerPhone')} onBlur={() => markTouched('importerPhone')} type="tel" inputMode="tel" maxLength={FIELD_LIMITS.phone} className={fieldCls(err('importerPhone'))} /></Field>
              <div className="col-span-2">
                <Field label="Address (1)" required error={err('importerAddress1')}><input value={form.importerAddress1 ?? ''} onChange={set('importerAddress1')} onBlur={() => markTouched('importerAddress1')} maxLength={FIELD_LIMITS.addr.line1} className={fieldCls(err('importerAddress1'))} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Address (2)" error={err('importerAddress2')}><input value={form.importerAddress2 ?? ''} onChange={set('importerAddress2')} onBlur={() => markTouched('importerAddress2')} maxLength={FIELD_LIMITS.addr.line2} className={fieldCls(err('importerAddress2'))} /></Field>
              </div>
              <Field label="Country" error={gateErrors.importerCountry}><CountrySelect allowedCodes={territoryCodes} region={activeRegion} value={form.importerCountry ?? ''} onChange={(v) => { setForm((c) => ({ ...c, importerCountry: v })); clearGate('importerCountry') }} /></Field>
              <Field label="City" required error={err('importerCity')}><input value={form.importerCity ?? ''} onChange={set('importerCity')} onBlur={() => markTouched('importerCity')} maxLength={FIELD_LIMITS.addr.city} className={fieldCls(err('importerCity'))} /></Field>
              <Field label="State" error={err('importerState')}><input value={form.importerState ?? ''} onChange={set('importerState')} onBlur={() => markTouched('importerState')} maxLength={FIELD_LIMITS.addr.state} className={fieldCls(err('importerState'))} /></Field>
              <Field label="Post Code" error={err('importerPostcode')}><input value={form.importerPostcode ?? ''} onChange={set('importerPostcode')} onBlur={() => markTouched('importerPostcode')} maxLength={FIELD_LIMITS.addr.zip} className={fieldCls(err('importerPostcode'))} /></Field>
            </div>

            {/* Tax identity — COUNTRY-SPECIFIC: every customs territory names
                its own identifiers (CNPJ, RFC, EIN, VAT+EORI, GSTIN+IEC…),
                so the fields come from the territory's spec, never a generic
                "Tax ID Type" dropdown. */}
            <div className="mt-3 rounded-xl border border-[#eee6d6] bg-[#faf7f0]/80 p-3">
              <p className="mb-2 text-[10px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
                {activeTerritory ? taxIdentityTitle(activeTerritory) : 'Tax identity'}
              </p>
              {activeTerritory && taxSpec ? (
                <>
                  <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                    {taxSpec.fields.map((f) => (
                      <Field key={f.column} label={f.label} error={err(f.column)}>
                        <input
                          value={(form[f.column] as string | null | undefined) ?? ''}
                          onChange={set(f.column)}
                          onBlur={() => markTouched(f.column)}
                          placeholder={f.placeholder}
                          maxLength={40}
                          className={fieldCls(err(f.column))}
                        />
                      </Field>
                    ))}
                  </div>
                  {taxSpec.note ? <p className="mt-2 text-[10.5px] text-[#5a4526]">{taxSpec.note}</p> : null}
                </>
              ) : (
                <p className="text-[11.5px] text-[#b6a684]">
                  Pick the destination countries first — each customs territory requires its own identifiers.
                </p>
              )}
            </div>
          </Section>

          {/* 3 — Broker */}
          <Section
            step="3"
            icon={<FiBriefcase className="h-3.5 w-3.5" />}
            tone="bg-[#faf7f0] text-[#5a4526]"
            title="Customs broker"
            hint="A licensed broker at the destination border — never the shipper itself."
          >
            {/* carrier-default brokerage vs Broker Select */}
            <div className="mb-3 grid gap-2 sm:grid-cols-2">
              <button
                type="button"
                onClick={() => setOwnBroker(false)}
                className={`rounded-xl border p-3 text-left transition ${
                  !ownBroker ? 'border-[#412d15] bg-[#412d15]/[0.04] ring-1 ring-[#412d15]/20' : 'border-[#e3d9c4] hover:border-[#cdbf9f]'
                }`}
              >
                <p className="text-[12px] font-semibold text-[#1f150c]">Carrier clears customs <span className="ml-1 rounded bg-emerald-50 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-emerald-700">Recommended</span></p>
                <p className="mt-0.5 text-[11px] text-[#8a7959]">UPS/FedEx brokerage is included with international shipments — nothing to set up.</p>
              </button>
              <button
                type="button"
                onClick={() => setOwnBroker(true)}
                className={`rounded-xl border p-3 text-left transition ${
                  ownBroker ? 'border-[#412d15] bg-[#412d15]/[0.04] ring-1 ring-[#412d15]/20' : 'border-[#e3d9c4] hover:border-[#cdbf9f]'
                }`}
              >
                <p className="text-[12px] font-semibold text-[#1f150c]">Own broker (Broker Select)</p>
                <p className="mt-0.5 text-[11px] text-[#8a7959]">The carrier hands the shipment to your named broker at the border.</p>
              </button>
            </div>

            {ownBroker ? (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <div className="col-span-2">
                <Field label="Broker Name" required error={err('brokerName')}><input value={form.brokerName ?? ''} onChange={set('brokerName')} onBlur={() => markTouched('brokerName')} maxLength={FIELD_LIMITS.name} className={fieldCls(err('brokerName'))} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Broker Company" error={err('brokerCompany')}><input value={form.brokerCompany ?? ''} onChange={set('brokerCompany')} onBlur={() => markTouched('brokerCompany')} maxLength={FIELD_LIMITS.name} className={fieldCls(err('brokerCompany'))} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Address (1)" error={err('brokerAddress1')}><input value={form.brokerAddress1 ?? ''} onChange={set('brokerAddress1')} onBlur={() => markTouched('brokerAddress1')} maxLength={FIELD_LIMITS.addr.line1} className={fieldCls(err('brokerAddress1'))} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Address (2)" error={err('brokerAddress2')}><input value={form.brokerAddress2 ?? ''} onChange={set('brokerAddress2')} onBlur={() => markTouched('brokerAddress2')} maxLength={FIELD_LIMITS.addr.line2} className={fieldCls(err('brokerAddress2'))} /></Field>
              </div>
              <Field label="Country"><CountrySelect preferRegion={activeRegion} value={form.brokerCountry ?? ''} onChange={(v) => setForm((c) => ({ ...c, brokerCountry: v }))} /></Field>
              <Field label="City" error={err('brokerCity')}><input value={form.brokerCity ?? ''} onChange={set('brokerCity')} onBlur={() => markTouched('brokerCity')} maxLength={FIELD_LIMITS.addr.city} className={fieldCls(err('brokerCity'))} /></Field>
              <Field label="State" error={err('brokerState')}><input value={form.brokerState ?? ''} onChange={set('brokerState')} onBlur={() => markTouched('brokerState')} maxLength={FIELD_LIMITS.addr.state} className={fieldCls(err('brokerState'))} /></Field>
              <Field label="Postal Code" error={err('brokerPostcode')}><input value={form.brokerPostcode ?? ''} onChange={set('brokerPostcode')} onBlur={() => markTouched('brokerPostcode')} maxLength={FIELD_LIMITS.addr.zip} className={fieldCls(err('brokerPostcode'))} /></Field>
              <Field label="Phone" error={err('brokerPhone')}><input value={form.brokerPhone ?? ''} onChange={set('brokerPhone')} onBlur={() => markTouched('brokerPhone')} type="tel" inputMode="tel" maxLength={FIELD_LIMITS.phone} className={fieldCls(err('brokerPhone'))} /></Field>
              <Field label="Broker ID / Filer" error={err('brokerId')}><input value={form.brokerId ?? ''} onChange={set('brokerId')} onBlur={() => markTouched('brokerId')} maxLength={50} className={fieldCls(err('brokerId'))} /></Field>
              <div className="col-span-2">
                <Field label="License No" error={err('brokerLicense')}><input value={form.brokerLicense ?? ''} onChange={set('brokerLicense')} onBlur={() => markTouched('brokerLicense')} maxLength={50} className={fieldCls(err('brokerLicense'))} /></Field>
              </div>
            </div>
            ) : null}
          </Section>

          {/* 4 — Customs defaults (shipment-level fields the carriers pick up
              when a shipment on this client + destination doesn't carry its
              own overrides). Every field is optional; blank = carrier
              default. Currency is preseeded from the client's ship-from
              country when the operator hasn't chosen one yet. */}
          <Section
            step="4"
            icon={<FiGlobe className="h-3.5 w-3.5" />}
            tone="bg-emerald-50 text-emerald-700"
            title="Customs defaults"
            hint="Applied to every international shipment on this client + destination when the shipment itself doesn't override. All optional — blank = carrier default (SALE / DAP / RECIPIENT / USD)."
          >
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <Field label="Shipping purpose">
                <Select
                  value={form.reasonForExport ?? ''}
                  onChange={(e) => setForm((c) => ({ ...c, reasonForExport: e.target.value || null }))}
                >
                  <option value="">— carrier default (SALE) —</option>
                  {SHIPPING_PURPOSES.map((p) => (
                    <option key={p.value} value={p.value}>{p.label}</option>
                  ))}
                </Select>
              </Field>
              <Field label="Incoterms">
                <Select
                  value={form.incoterms ?? ''}
                  onChange={(e) => setForm((c) => ({ ...c, incoterms: e.target.value || null }))}
                >
                  <option value="">— carrier default (DAP) —</option>
                  <option value="DDP">DDP — Delivered Duty Paid</option>
                  <option value="DAP">DAP — Delivered At Place</option>
                  <option value="DDU">DDU — Delivered Duty Unpaid</option>
                </Select>
              </Field>
              <Field label="Duty billing">
                <Select
                  value={form.dutiesBillTo ?? ''}
                  onChange={(e) => setForm((c) => ({ ...c, dutiesBillTo: e.target.value || null }))}
                >
                  <option value="">— carrier default —</option>
                  <option value="SENDER">Sender pays</option>
                  <option value="RECIPIENT">Recipient pays</option>
                  <option value="THIRD_PARTY">Third party</option>
                </Select>
              </Field>
              <Field label="Customs currency">
                <Select
                  value={form.currency ?? ''}
                  onChange={(e) => setForm((c) => ({ ...c, currency: e.target.value || null }))}
                >
                  <option value="">— carrier default (USD) —</option>
                  {CUSTOMS_CURRENCIES.map((code) => (
                    <option key={code} value={code}>{code}</option>
                  ))}
                </Select>
              </Field>
              {/* Third-party duty account — only meaningful when Duty billing
                   = THIRD_PARTY. Kept in the same section so operators see
                   the pairing. */}
              {form.dutiesBillTo === 'THIRD_PARTY' ? (
                <div className="col-span-2 md:col-span-4">
                  <Field label="Third-party duty account #">
                    <input
                      value={form.dutiesAccount ?? ''}
                      onChange={set('dutiesAccount')}
                      placeholder="Payer's carrier account #"
                      className={inputClassName}
                    />
                  </Field>
                </div>
              ) : null}
            </div>
          </Section>
        </div>

        {/* footer */}
        <div className="flex items-center justify-between gap-3 border-t border-[#eee6d6] px-5 py-4">
          <div>
            {editing ? (
              <button
                type="button"
                onClick={() => void handleDelete()}
                disabled={saving}
                className="inline-flex items-center gap-1.5 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] font-semibold text-rose-700 transition hover:bg-rose-100 disabled:opacity-50"
              >
                <FiTrash2 className="h-3.5 w-3.5" /> Delete
              </button>
            ) : null}
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-[#e3d9c4] bg-white px-4 py-2 text-[13px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void handleSave()}
              disabled={saving}
              className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#cdbf9f]"
            >
              {saving ? 'Saving…' : editing ? 'Save changes' : 'Create profile'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
