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
import Select from '../workspace/Select'
import RegionCountryPicker from '../workspace/RegionCountryPicker'

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
  'w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400'


const blank = (): CustomsProfile => ({ countries: [] })

function Field({ label, required, children }: { label: string; required?: boolean; children: ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-slate-400">
        {label}
        {required ? <span className="ml-0.5 text-rose-500">*</span> : null}
      </span>
      {children}
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
    <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white">
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 bg-slate-50/60 px-4 py-2.5">
        <div className="flex items-center gap-2.5">
          <span className="grid h-6 w-6 place-items-center rounded-full bg-[#1f150c] text-[10.5px] font-bold text-[#f4eede]">
            {step}
          </span>
          <span className={`inline-flex h-6 w-6 items-center justify-center rounded-lg ${tone}`}>{icon}</span>
          <div>
            <h4 className="text-[12.5px] font-semibold text-slate-950">{title}</h4>
            {hint ? <p className="text-[10.5px] text-slate-400">{hint}</p> : null}
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
    if (!clientCode) {
      notify.error('Choose a client.')
      return
    }
    const countries = form.countries ?? []
    if (!countries.length) {
      notify.error('Select at least one destination country.')
      return
    }
    // One region per profile — also catches legacy cross-region data on edit.
    const region = regionOf(countries[0])
    if (countries.some((c) => regionOf(c) !== region)) {
      notify.error('A profile covers one region — remove countries outside ' + region + '.')
      return
    }
    // A usable registered importer is mandatory — the customs gate must never
    // pass on a husk profile.
    if (!form.importerName?.trim()) {
      notify.error('Importer name is required.')
      return
    }
    if (!form.importerAddress1?.trim() || !form.importerCity?.trim()) {
      notify.error('Importer address line 1 and city are required — customs paperwork needs a real address.')
      return
    }
    // One importer registration is valid for exactly ONE customs territory
    // (EU/EAEU/GCC/SACU as a whole, or a single country) — a "Europe"
    // profile mixing the EU with the UK would apply an EU EORI to UK
    // shipments, which is invalid post-Brexit.
    const territory = territoryOf(countries[0])
    const outsider = countries.find((c) => territoryOf(c) !== territory)
    if (outsider) {
      notify.error(
        `${countryName(outsider)} is outside ${territoryLabel(territory)} — an importer registration covers one customs territory. Create a separate profile for it.`
      )
      return
    }
    // The Importer of Record must be established IN that territory.
    if (form.importerCountry && territoryOf(form.importerCountry) !== territory) {
      notify.error(`Importer country must be in ${territoryLabel(territory)}.`)
      return
    }
    // A named broker needs at least a name — otherwise ghost broker data
    // persists while the backend treats the profile as carrier-default.
    if (ownBroker && !form.brokerName?.trim()) {
      notify.error('Enter the broker name — or choose "Carrier clears customs".')
      return
    }
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
      notify.error(e instanceof Error ? e.message : 'Failed to save the profile.')
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
      notify.error(e instanceof Error ? e.message : 'Failed to delete the profile.')
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
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label="Importer / Broker profile"
      onClick={onClose}
    >
      <div
        className="flex max-h-[92vh] w-full max-w-4xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* header */}
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div className="flex items-center gap-3">
            <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-[#1f150c] text-[14px] font-bold text-[#e1dcc9] shadow-sm">
              {initials || <FiUsers className="h-4 w-4" />}
            </span>
            <div>
              <p className="inline-flex items-center gap-1.5 text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
                <FiGlobe className="h-3.5 w-3.5" /> Importer / Broker profile
              </p>
              <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
                {editing ? 'Edit profile' : 'New profile'}
                {client ? <span className="text-slate-400"> · {client.name}</span> : null}
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
              className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
              aria-label="Close"
            >
              <FiX className="h-4 w-4" />
            </button>
          </div>
        </div>

        <div className="flex-1 space-y-3.5 overflow-y-auto bg-slate-50/50 px-5 py-4">
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
                <Field label="Client" required>
                  <Select
                    value={clientCode}
                    onChange={(e) => {
                      setClientCode(e.target.value.toUpperCase())
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
                  <p className="mt-1.5 text-[11px] text-slate-400">
                    {editing ? 'Client is fixed while editing.' : 'Pre-selected from the client.'}
                  </p>
                ) : null}

                {/* selection summary */}
                <div className="mt-3 rounded-xl border border-slate-100 bg-slate-50/80 p-3">
                  <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Selection</p>
                  {selectedRegion ? (
                    <>
                      <p className="mt-1 text-[12px] font-semibold text-[#412d15]">{selectedRegion.region}</p>
                      <div className="mt-1.5 flex flex-wrap gap-1">
                        {selectedRegion.codes.map((c) => (
                          <span key={c} title={countryName(c)} className="rounded-md bg-white px-1.5 py-0.5 text-[10.5px] font-semibold text-slate-600 ring-1 ring-slate-200">
                            {c}
                          </span>
                        ))}
                      </div>
                    </>
                  ) : (
                    <p className="mt-1 text-[11.5px] text-slate-400">Nothing selected yet.</p>
                  )}
                </div>
              </div>

              <RegionCountryPicker
                value={form.countries ?? []}
                onChange={(codes) => setForm((cur) => ({ ...cur, countries: codes }))}
                disabledCodes={disabledCodes}
                territoryConstrained
              />
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
                <Field label="Importer Name" required><input value={form.importerName ?? ''} onChange={set('importerName')} className={inputClassName} /></Field>
              </div>
              <Field label="Contact"><input value={form.importerContact ?? ''} onChange={set('importerContact')} className={inputClassName} /></Field>
              <Field label="Phone"><input value={form.importerPhone ?? ''} onChange={set('importerPhone')} className={inputClassName} /></Field>
              <div className="col-span-2">
                <Field label="Address (1)" required><input value={form.importerAddress1 ?? ''} onChange={set('importerAddress1')} className={inputClassName} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Address (2)"><input value={form.importerAddress2 ?? ''} onChange={set('importerAddress2')} className={inputClassName} /></Field>
              </div>
              <Field label="Country"><CountrySelect allowedCodes={territoryCodes} region={activeRegion} value={form.importerCountry ?? ''} onChange={(v) => setForm((c) => ({ ...c, importerCountry: v }))} /></Field>
              <Field label="City" required><input value={form.importerCity ?? ''} onChange={set('importerCity')} className={inputClassName} /></Field>
              <Field label="State"><input value={form.importerState ?? ''} onChange={set('importerState')} className={inputClassName} /></Field>
              <Field label="Post Code"><input value={form.importerPostcode ?? ''} onChange={set('importerPostcode')} className={inputClassName} /></Field>
            </div>

            {/* Tax identity — COUNTRY-SPECIFIC: every customs territory names
                its own identifiers (CNPJ, RFC, EIN, VAT+EORI, GSTIN+IEC…),
                so the fields come from the territory's spec, never a generic
                "Tax ID Type" dropdown. */}
            <div className="mt-3 rounded-xl border border-slate-100 bg-slate-50/80 p-3">
              <p className="mb-2 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                {activeTerritory ? taxIdentityTitle(activeTerritory) : 'Tax identity'}
              </p>
              {activeTerritory && taxSpec ? (
                <>
                  <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
                    {taxSpec.fields.map((f) => (
                      <Field key={f.column} label={f.label}>
                        <input
                          value={(form[f.column] as string | null | undefined) ?? ''}
                          onChange={set(f.column)}
                          placeholder={f.placeholder}
                          className={inputClassName}
                        />
                      </Field>
                    ))}
                  </div>
                  {taxSpec.note ? <p className="mt-2 text-[10.5px] text-sky-700">{taxSpec.note}</p> : null}
                </>
              ) : (
                <p className="text-[11.5px] text-slate-400">
                  Pick the destination countries first — each customs territory requires its own identifiers.
                </p>
              )}
            </div>
          </Section>

          {/* 3 — Broker */}
          <Section
            step="3"
            icon={<FiBriefcase className="h-3.5 w-3.5" />}
            tone="bg-sky-50 text-sky-700"
            title="Customs broker"
            hint="A licensed broker at the destination border — never the shipper itself."
          >
            {/* carrier-default brokerage vs Broker Select */}
            <div className="mb-3 grid gap-2 sm:grid-cols-2">
              <button
                type="button"
                onClick={() => setOwnBroker(false)}
                className={`rounded-xl border p-3 text-left transition ${
                  !ownBroker ? 'border-[#412d15] bg-[#412d15]/[0.04] ring-1 ring-[#412d15]/20' : 'border-slate-200 hover:border-slate-300'
                }`}
              >
                <p className="text-[12px] font-semibold text-slate-900">Carrier clears customs <span className="ml-1 rounded bg-emerald-50 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-emerald-700">Recommended</span></p>
                <p className="mt-0.5 text-[11px] text-slate-500">UPS/FedEx brokerage is included with international shipments — nothing to set up.</p>
              </button>
              <button
                type="button"
                onClick={() => setOwnBroker(true)}
                className={`rounded-xl border p-3 text-left transition ${
                  ownBroker ? 'border-[#412d15] bg-[#412d15]/[0.04] ring-1 ring-[#412d15]/20' : 'border-slate-200 hover:border-slate-300'
                }`}
              >
                <p className="text-[12px] font-semibold text-slate-900">Own broker (Broker Select)</p>
                <p className="mt-0.5 text-[11px] text-slate-500">The carrier hands the shipment to your named broker at the border.</p>
              </button>
            </div>

            {ownBroker ? (
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
              <div className="col-span-2">
                <Field label="Broker Name"><input value={form.brokerName ?? ''} onChange={set('brokerName')} className={inputClassName} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Broker Company"><input value={form.brokerCompany ?? ''} onChange={set('brokerCompany')} className={inputClassName} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Address (1)"><input value={form.brokerAddress1 ?? ''} onChange={set('brokerAddress1')} className={inputClassName} /></Field>
              </div>
              <div className="col-span-2">
                <Field label="Address (2)"><input value={form.brokerAddress2 ?? ''} onChange={set('brokerAddress2')} className={inputClassName} /></Field>
              </div>
              <Field label="Country"><CountrySelect preferRegion={activeRegion} value={form.brokerCountry ?? ''} onChange={(v) => setForm((c) => ({ ...c, brokerCountry: v }))} /></Field>
              <Field label="City"><input value={form.brokerCity ?? ''} onChange={set('brokerCity')} className={inputClassName} /></Field>
              <Field label="State"><input value={form.brokerState ?? ''} onChange={set('brokerState')} className={inputClassName} /></Field>
              <Field label="Postal Code"><input value={form.brokerPostcode ?? ''} onChange={set('brokerPostcode')} className={inputClassName} /></Field>
              <Field label="Phone"><input value={form.brokerPhone ?? ''} onChange={set('brokerPhone')} className={inputClassName} /></Field>
              <Field label="Broker ID / Filer"><input value={form.brokerId ?? ''} onChange={set('brokerId')} className={inputClassName} /></Field>
              <div className="col-span-2">
                <Field label="License No"><input value={form.brokerLicense ?? ''} onChange={set('brokerLicense')} className={inputClassName} /></Field>
              </div>
            </div>
            ) : null}
          </Section>

          {/* Shipment defaults (incoterms/duties/reason/currency) are HIDDEN
              from this form per client request — they will be managed from a
              separate module. The backend fields, existing values, and all
              document rendering (invoice/label/drawer) remain wired. */}
        </div>

        {/* footer */}
        <div className="flex items-center justify-between gap-3 border-t border-slate-100 px-5 py-4">
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
              className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void handleSave()}
              disabled={saving}
              className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              {saving ? 'Saving…' : editing ? 'Save changes' : 'Create profile'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
