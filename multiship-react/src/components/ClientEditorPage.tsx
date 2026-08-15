import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiAlertCircle,
  FiArrowLeft,
  FiCheck,
  FiChevronLeft,
  FiChevronRight,
  FiEdit2,
  FiHome,
  FiLoader,
  FiPlus,
  FiStar,
} from 'react-icons/fi'
import { ApiError } from '../api/apiClient'
import { clientService, type Address, type Client, type ClientUpsertPayload } from '../api/clientService'
import { TimezoneCombobox } from './TimezoneCombobox'
import { accountRefService, type CarrierAccountRef } from '../api/accountRefService'
import {
  clientWarehouseService,
  warehouseService,
  type Warehouse,
} from '../api/warehouseService'
// clientAllowedPackages — used by the Packages step, currently hidden from
// the wizard rail; keep commented for when the step comes back.
// import { clientAllowedPackagesService, type ClientAllowedPackage } from '../api/clientCatalogService'
import { shippingConfigService, type ShippingServiceItem } from '../api/shippingConfigService'
import { formatCarrierName, carrierEnvironmentOptions, type CarrierEnvironment } from '../utils/carrierUtils'
import { SHIPPING_PURPOSES, clearanceOptionsForCarrier } from '../utils/customsOptions'
import {
  checkClientCodeAvailable,
  FIELD_LIMITS,
  hasErrors,
  validateAddress,
  validateClientCode,
  validateCode,
  validateEmail,
  validateName,
  validatePhone,
  type AddressLike,
} from '../utils/clientValidation'
import { validateCarrierAccount } from '../validation/carrierAccountValidation'
import CountrySelect from './workspace/CountrySelect'
import Select from './workspace/Select'
// Hidden-step components (kept commented for the day they come back into the wizard):
// import ClientAllowlistTab from './modals/ClientAllowlistTab'
// import ClientDestinationsTab from './modals/ClientDestinationsTab'
// import ClientPolicyTab from './modals/ClientPolicyTab'
// import ClientMarkupTab from './modals/ClientMarkupTab'
// import ClientOwnedPackagesPanel from './modals/ClientOwnedPackagesPanel'
import CarrierConnections from './CarrierConnections'
import WarehouseEditorModal from './modals/WarehouseEditorModal'
import ClientShippingMappingTab from './modals/ClientShippingMappingTab'
import CustomsProfileModal from './modals/CustomsProfileModal'
import { customsProfileService, type CustomsProfile } from '../api/customsProfileService'
import {
  CARRIER_ACCOUNT_RULES,
  intersectionAddressCaps,
  bindingCarriers,
  type AddressCaps,
  type CarrierAccountRule,
  type CarrierCode,
} from '../utils/carrierFieldLimits'
import { computeAllowedCarriers, platformAccounts as platformAccountsFrom } from '../utils/allowedCarriers'

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

/** Wizard step keys — trimmed to the mandatory onboarding path:
 *    identity → shipFrom → return → carriers → mapping
 *  Warehouses / Destinations / Packages / Policy / Markup are still reachable
 *  from Settings pages but no longer live in the client editor.
 *
 *  In create mode the wizard is linear and Create is only enabled once every
 *  step has been visited (visitedSteps) AND the three data steps validate.
 *  Carriers + Mapping in create mode show a deferred-configuration panel —
 *  their inputs need a persisted clientCode, so their actual configuration
 *  happens on the freshly-created client after the operator hits Create.  */
type StepKey =
  | 'identity'
  | 'shipFrom'
  | 'return'
  | 'carriers'
  | 'mapping'
  | 'importerBroker'
  | 'summary'

/** Steps that count as "must complete before Create client fires" in create
 *  mode. importerBroker is intentionally excluded — it's optional per the
 *  onboarding brief. summary IS mandatory — the operator must land on it
 *  to Submit. The Create-blockers check reads this list. */
const MANDATORY_STEPS: ReadonlySet<StepKey> = new Set([
  'identity', 'shipFrom', 'return', 'carriers', 'mapping', 'summary',
])

const STEP_DEFS: ReadonlyArray<{ key: StepKey; label: string; short: string; optional?: boolean }> = [
  { key: 'identity',       label: 'Identity',                 short: 'Identity' },
  { key: 'shipFrom',       label: 'Ship From',                short: 'Ship From' },
  { key: 'return',         label: 'Return address',           short: 'Return' },
  { key: 'carriers',       label: 'Carrier accounts',         short: 'Carriers' },
  { key: 'mapping',        label: 'Shipping service mapping', short: 'Mapping' },
  { key: 'importerBroker', label: 'Importer / Broker',        short: 'Importer',   optional: true },
  { key: 'summary',        label: 'Review & submit',          short: 'Summary' },
]

/**
 * Create-mode-only draft for the Importer / Broker step. Persisted to the
 * per-user localStorage draft alongside carrierDrafts + mappingDrafts, and
 * committed via `customsProfileService.save` immediately after the client
 * is created (mirrors the carrier + mapping post-create fan-out).
 *
 * `filled=false` means the operator explicitly skipped this step — the
 * persist path is a no-op. `filled=true` requires the BUSINESS-importer
 * identity fields; RECEIVER (DAP consignee-is-IOR) has no identity of
 * its own, so those fields stay optional.
 */
type ImporterBrokerDraft = {
  filled: boolean
  countries: string[]  // ISO-3166 alpha-2; empty = catch-all
  importerType: 'BUSINESS' | 'RECEIVER'
  importerName: string
  importerCountry: string
  importerAddress1: string
  importerAddress2: string
  importerCity: string
  importerState: string
  importerPostcode: string
  importerPhone: string
  importerTaxId: string
  importerTaxIdType: string
  brokerName: string
  brokerPhone: string
  incoterms: string
  reasonForExport: string
}

const emptyImporterBrokerDraft = (): ImporterBrokerDraft => ({
  filled: false,
  countries: [],
  importerType: 'BUSINESS',
  importerName: '',
  importerCountry: '',
  importerAddress1: '',
  importerAddress2: '',
  importerCity: '',
  importerState: '',
  importerPostcode: '',
  importerPhone: '',
  importerTaxId: '',
  importerTaxIdType: '',
  brokerName: '',
  brokerPhone: '',
  incoterms: '',
  reasonForExport: '',
})

/** BUSINESS-importer requires the identity + address block. RECEIVER
 *  profiles legitimately have no importer identity (DAP terms — the
 *  receiver is the IOR), so a filled=true RECEIVER always validates. */
function importerBrokerDraftValid(d: ImporterBrokerDraft): boolean {
  if (!d.filled) return true
  if (d.importerType === 'RECEIVER') return true
  return !!d.importerName.trim()
    && !!d.importerCountry.trim()
    && !!d.importerAddress1.trim()
    && !!d.importerCity.trim()
    && !!d.importerPostcode.trim()
}

const stepIndex = (key: StepKey) => STEP_DEFS.findIndex((s) => s.key === key)

/** localStorage key for the create-mode draft — namespaced per operator so
 *  two users on the same machine don't step on each other's in-progress work. */
const draftStorageKey = () => {
  const user = (localStorage.getItem('multiship_user') || 'anonymous').trim() || 'anonymous'
  return `clientEditorDraft:${user}`
}

/** One carrier-account staged in create mode. Field shape mirrors the
 *  accountRefService.upsertAccount payload so `handleCreate` can post each
 *  draft directly. `id` is client-side only — a monotonic counter used as
 *  the React key. */
type CarrierAccountDraft = {
  id: number
  carrierCode: string
  accountNumber: string
  accountName: string
  clientId: string
  clientSecret: string
  environment: CarrierEnvironment
  clientDefault: boolean
  /** International-shipment defaults for this account. Optional; carriers
   *  fall back to their own defaults when unset. */
  shippingPurpose: string
  clearanceOption: string
  /** Third-party billing default — only meaningful when clearanceOption is
   *  THIRD_PARTY. Persisted with the account; per-shipment overrides live
   *  on the Shipment row (follow-up). */
  thirdPartyAccount: string
  thirdPartyName: string
  thirdPartyAddress1: string
  thirdPartyCity: string
  thirdPartyState: string
  thirdPartyPostcode: string
  thirdPartyCountry: string
}

/** One shipping-service mapping rule staged in create mode. Minimal fields
 *  only — full scoping (destination zone, warehouse restriction, package
 *  allowlist) lives in the post-create Mapping step editor. */
type MappingRuleDraft = {
  id: number
  shipviaCd: string
  serviceId: number
}

type DraftShape = {
  form: ClientUpsertPayload
  selectedShipFromWarehouseId: number | null
  visitedSteps: StepKey[]
  activeStep: StepKey
  carrierDrafts?: CarrierAccountDraft[]
  mappingDrafts?: MappingRuleDraft[]
  importerBrokerDraft?: ImporterBrokerDraft
}

const inputBaseClass =
  'w-full rounded-xl border bg-slate-50 px-2.5 py-1.5 text-[12.5px] text-slate-950 outline-none transition focus:bg-white focus:ring-2'
const inputOk = 'border-slate-200 focus:border-sky-600 focus:ring-sky-100'
const inputErr = 'border-rose-400 focus:border-rose-500 focus:ring-rose-100'

/** Small labeled field wrapper with an optional inline error line under it. */
function Field({
  label,
  children,
  required,
  error,
  hint,
}: {
  label: string
  children: ReactNode
  required?: boolean
  error?: string | null
  hint?: string
}) {
  return (
    <label className="block">
      <span className="mb-0.5 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-slate-400">
        {label}
        {required ? <span className="text-rose-500">*</span> : null}
      </span>
      {children}
      {error ? (
        <p className="mt-0.5 flex items-start gap-1 text-[10.5px] font-semibold text-rose-600">
          <FiAlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
          {error}
        </p>
      ) : hint ? (
        <p className="mt-0.5 text-[10.5px] text-slate-400">{hint}</p>
      ) : null}
    </label>
  )
}

const carrierOptions = [
  { code: 'UPS', label: 'UPS' },
  { code: 'FEDEX', label: 'FedEx' },
  { code: 'USPS', label: 'USPS' },
  { code: 'DHL', label: 'DHL Express' },
]

const emptyAddress: Address = { name: '', line1: '', line2: '', city: '', state: '', zip: '', country: 'US', phone: '' }

const emptyForm = (code: string = ''): ClientUpsertPayload => ({
  clientCode: code,
  name: '',
  email: '',
  phone: '',
  shipFrom: { ...emptyAddress },
  returnAddress: { ...emptyAddress },
  returnSameAsShipFrom: true,
})

/**
 * Full-page client editor at `/settings/clients/{code}` (edit) or
 * `/settings/clients/new` (create). Presents the profile as a numbered
 * wizard:
 *   Create mode = linear (Back / Next / Create); later steps are locked
 *     until the required identity + address steps validate and the row is
 *     persisted (which happens on "Create client" at the end of step 3).
 *   Edit mode = free-navigate; any step is directly clickable and each
 *     section persists its own edits independently.
 *
 * Field validation lives in `clientValidation.ts`. Steps compute their own
 * `errors` map on every render; a step is "valid" when the map is empty.
 * Fields render red only after they've been touched (blur) — so operators
 * aren't yelled at the moment the form opens.
 */
export default function ClientEditorPage() {
  const navigate = useNavigate()
  const { clientCode: urlClientCode } = useParams<{ clientCode?: string }>()
  const [searchParams] = useSearchParams()
  const editingCode = urlClientCode && urlClientCode !== 'new' ? urlClientCode : null
  const isEdit = editingCode != null
  const prefillCode = !isEdit ? (searchParams.get('code') || '') : ''

  const [client, setClient] = useState<Client | null>(null)
  const [loading, setLoading] = useState(isEdit)

  // Hydrate create-mode state from the per-user draft in localStorage on
  // first render. Silent restoration: no "continue previous?" prompt — the
  // draft is invisible until the operator revisits `/settings/clients/new`.
  const draft = useMemo<DraftShape | null>(() => {
    if (isEdit) return null
    try {
      const raw = localStorage.getItem(draftStorageKey())
      return raw ? (JSON.parse(raw) as DraftShape) : null
    } catch {
      return null
    }
    // Draft is loaded once on mount only — subsequent changes are pushed OUT
    // to localStorage, not read back in.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const [activeStep, setActiveStep] = useState<StepKey>(draft?.activeStep ?? 'identity')
  const [form, setForm] = useState<ClientUpsertPayload>(
    () => draft?.form ?? emptyForm(prefillCode),
  )
  const [saving, setSaving] = useState(false)

  /** Steps the operator has landed on at least once. Gates the Create button
   *  in create mode — must visit every step before the wizard can commit. */
  const [visitedSteps, setVisitedSteps] = useState<Set<StepKey>>(
    () => new Set(draft?.visitedSteps ?? ['identity']),
  )
  const visitStep = (key: StepKey) => {
    setVisitedSteps((cur) => {
      if (cur.has(key)) return cur
      const next = new Set(cur)
      next.add(key)
      return next
    })
  }

  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])

  /** Create-mode carrier account drafts. Committed after Create client. */
  const [carrierDrafts, setCarrierDrafts] = useState<CarrierAccountDraft[]>(
    () => draft?.carrierDrafts ?? [],
  )
  /** Create-mode mapping rule drafts. Committed after Create client. */
  const [mappingDrafts, setMappingDrafts] = useState<MappingRuleDraft[]>(
    () => draft?.mappingDrafts ?? [],
  )
  /** Create-mode importer/broker draft. Committed after Create client if
   *  `filled=true` (otherwise the step was explicitly skipped). */
  const [importerBrokerDraft, setImporterBrokerDraft] = useState<ImporterBrokerDraft>(
    () => draft?.importerBrokerDraft ?? emptyImporterBrokerDraft(),
  )
  const nextDraftId = useRef(
    Math.max(
      0,
      ...((draft?.carrierDrafts ?? []).map((d) => d.id)),
      ...((draft?.mappingDrafts ?? []).map((d) => d.id)),
    ) + 1,
  )

  /**
   * Union of every carrier this client is (or would be) wired for — used to
   * intersect address caps in the AddressGrid. Sources:
   *   - {@link accounts}       (edit mode: existing rows fetched from server)
   *   - {@link carrierDrafts}  (create mode: staged accounts pending commit)
   *
   * Kept as a set of raw carrier code strings — the utility upper-cases and
   * filters to known codes (UPS / FEDEX / USPS / DHL) internally.
   */
  const enabledCarrierCodes = useMemo<string[]>(() => {
    const set = new Set<string>()
    accounts.forEach((a) => { if (a.carrierCode) set.add(a.carrierCode) })
    carrierDrafts.forEach((d) => { if (d.carrierCode) set.add(d.carrierCode) })
    return Array.from(set)
  }, [accounts, carrierDrafts])

  /** Intersection of per-carrier address caps — the strictest limit per
   *  field across every enabled carrier. Empty set falls back to the loose
   *  DB-column defaults inside the utility. */
  const addressCaps = useMemo<AddressCaps>(
    () => intersectionAddressCaps(enabledCarrierCodes),
    [enabledCarrierCodes],
  )

  /** Which carriers set the currently-binding cap on a given address field?
   *  Powers the "cap set by UPS/FEDEX" helper text under each input. */
  const bindingHint = useCallback(
    (field: keyof AddressCaps): CarrierCode[] =>
      bindingCarriers(addressCaps, enabledCarrierCodes, field),
    [addressCaps, enabledCarrierCodes],
  )

  /** Per-field "have you interacted?" flags — a field only renders its error
   *  after the operator has left it, so the initial view isn't red-dotted. */
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const markTouched = (key: string) => setTouched((cur) => ({ ...cur, [key]: true }))

  /** Live duplicate-code check result. Populated on blur / debounced typing
   *  in create mode; edit mode leaves it null (the code is immutable there). */
  const [codeConflict, setCodeConflict] = useState<string | null>(null)
  const [checkingCode, setCheckingCode] = useState(false)

  // ===== Shipping service catalog (for the mapping draft picker) =====
  const [servicesCatalog, setServicesCatalog] = useState<ShippingServiceItem[]>([])
  useEffect(() => {
    let cancelled = false
    shippingConfigService.catalog()
      .then((r) => { if (!cancelled) setServicesCatalog(r.services) })
      .catch(() => { /* not fatal — mapping draft picker just stays empty */ })
    return () => { cancelled = true }
  }, [])

  // ===== Ship-From warehouse picker =====
  /** Warehouses available to pick as Ship From — every active PLATFORM
   *  warehouse plus, in edit mode, any CLIENT-owned warehouse owned by this
   *  client. Loaded once + on demand after the inline Add-warehouse modal
   *  saves a new row. */
  const [pickWarehouses, setPickWarehouses] = useState<Warehouse[]>([])
  const [pickWarehousesLoading, setPickWarehousesLoading] = useState(true)
  const [selectedShipFromWarehouseId, setSelectedShipFromWarehouseId] = useState<number | null>(
    draft?.selectedShipFromWarehouseId ?? null,
  )
  const [showShipFromAddWarehouse, setShowShipFromAddWarehouse] = useState(false)
  // null when the modal is opening for create OR closed; a Warehouse when
  // opening for edit from the Ship From picker's preview card. Cleared when
  // the modal closes so the next open defaults back to create.
  const [warehouseBeingEdited, setWarehouseBeingEdited] = useState<Warehouse | null>(null)
  /** Warehouse ids currently attached to this client (edit mode only). The
   *  Ship From picker filters these OUT so the operator only sees warehouses
   *  they could switch TO — with the currently-selected default kept visible
   *  so the picked value doesn't vanish out from under them. */
  const [attachedWarehouseIds, setAttachedWarehouseIds] = useState<Set<number>>(new Set())

  const loadPickWarehouses = async () => {
    setPickWarehousesLoading(true)
    try {
      const platform = await warehouseService.listWarehouses({ ownerType: 'PLATFORM', active: 'YES', size: 500 })
      const own = editingCode
        ? await warehouseService.listWarehouses({ ownerType: 'CLIENT', ownerClientCode: editingCode, active: 'YES', size: 200 })
        : { data: { content: [] as Warehouse[] } }
      const merged: Warehouse[] = []
      const seen = new Set<number>()
      for (const w of [...(platform.data?.content ?? []), ...(own.data?.content ?? [])]) {
        if (!seen.has(w.id)) { seen.add(w.id); merged.push(w) }
      }
      setPickWarehouses(merged)
    } catch (error) {
      notify.apiError(error, 'Failed to load warehouses.')
    } finally {
      setPickWarehousesLoading(false)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on client change; loadPickWarehouses() sets warehouse list state
    void loadPickWarehouses()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingCode])

  // Pre-select the client's default attached warehouse in edit mode so the
  // Ship From picker isn't empty when reopening an existing client, AND
  // record the full attached-id set so the picker can filter them out (only
  // the currently-selected default stays visible).
  useEffect(() => {
    if (!editingCode) return
    let cancelled = false
    clientWarehouseService.listForClient(editingCode)
      .then((r) => {
        if (cancelled) return
        const rows = r.data ?? []
        const ids = new Set<number>()
        for (const row of rows) if (row.warehouse?.id != null) ids.add(row.warehouse.id)
        setAttachedWarehouseIds(ids)
        const def = rows.find((row) => row.isDefault)
        if (def?.warehouse?.id != null) setSelectedShipFromWarehouseId(def.warehouse.id)
      })
      .catch(() => { /* not fatal */ })
    return () => { cancelled = true }
  }, [editingCode])

  /** Warehouses actually shown in the Ship From picker: drops everything that's
   *  already attached to this client, EXCEPT the currently-selected default —
   *  hiding the pre-selected row would make it silently disappear from the
   *  dropdown while `selectedShipFromWarehouseId` still points at it. */
  const visibleShipFromWarehouses = useMemo(() => {
    if (!editingCode || attachedWarehouseIds.size === 0) return pickWarehouses
    return pickWarehouses.filter((w) =>
      w.id === selectedShipFromWarehouseId || !attachedWarehouseIds.has(w.id),
    )
  }, [pickWarehouses, attachedWarehouseIds, selectedShipFromWarehouseId, editingCode])

  const set = (key: keyof ClientUpsertPayload) => (event: { target: { value: string } }) => {
    const raw = event.target.value
    // Client code is uppercase-normalized on the wire; do it in state too so
    // the operator sees exactly what's saved (and no case-only collisions
    // with existing clients slip through the local dup check).
    const value = key === 'clientCode' ? raw.toUpperCase() : raw
    setForm((cur) => ({ ...cur, [key]: value }))
    if (key === 'clientCode') setCodeConflict(null) // stale check → clear
  }

  /** Pick a warehouse as the Ship From origin — copies the warehouse's
   *  address into form.shipFrom so the backend stays on the same schema and
   *  labels keep printing the same fields. The warehouse id is remembered so
   *  we can attach + default it on the client after save. */
  const pickShipFromWarehouse = (wh: Warehouse | null) => {
    if (!wh) {
      setSelectedShipFromWarehouseId(null)
      return
    }
    setSelectedShipFromWarehouseId(wh.id)
    const a = wh.address ?? {}
    setForm((cur) => ({
      ...cur,
      shipFrom: {
        name: a.name || wh.name || wh.code,
        line1: a.line1 || '',
        line2: a.line2 || '',
        city: a.city || '',
        state: a.state || '',
        zip: a.zip || '',
        country: (a.country || 'US').toUpperCase(),
        phone: a.phone || '',
      },
    }))
    // Mark the address touched so validation errors (if any) render right
    // away instead of on the next blur — the operator hasn't typed anything.
    setTouched((cur) => {
      const next = { ...cur }
      for (const k of Object.keys(emptyAddress)) next[`shipFrom.${k}`] = true
      return next
    })
  }

  const setAddr = (block: 'shipFrom' | 'returnAddress', key: keyof Address) => (event: { target: { value: string } }) =>
    setForm((cur) => ({ ...cur, [block]: { ...cur[block], [key]: event.target.value } }))

  // ===== Load in edit mode =====
  useEffect(() => {
    if (!isEdit || !editingCode) return
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading spinner before async client fetch in edit mode
    setLoading(true)
    clientService
      .getClient(editingCode)
      .then((resp) => {
        if (cancelled) return
        const c = resp.data
        if (!c) throw new Error('Client not found')
        setClient(c)
        setForm({
          clientCode: c.clientCode,
          name: c.name || '',
          email: c.email || '',
          phone: c.phone || '',
          shipFrom: { ...emptyAddress, ...(c.shipFrom ?? {}) },
          returnAddress: { ...emptyAddress, ...(c.returnAddress ?? {}) },
          returnSameAsShipFrom: c.returnSameAsShipFrom ?? true,
          // Sprint 50 Tier 1 finding #4 — hydrate per-tenant defaults so
          // the editor's Defaults panel shows what's currently persisted.
          defaultCurrency: c.defaultCurrency ?? '',
          defaultWeightUnit: c.defaultWeightUnit ?? '',
          defaultDimUnit: c.defaultDimUnit ?? '',
          timezone: c.timezone ?? '',
          defaultOriginCountry: c.defaultOriginCountry ?? '',
        })
        setAccounts(c.carrierAccounts ?? [])
      })
      .catch((err: unknown) => {
        if (cancelled) return
        notify.apiError(err, 'Failed to load client.')
        navigate('/settings/clients')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [isEdit, editingCode, navigate])

  useEffect(() => {
    let cancelled = false
    if (editingCode) {
      // Edit mode — this client's own accounts drive the pickers.
      clientService.listClientAccounts(editingCode)
        .then((a) => { if (!cancelled) setAccounts(a) })
        .catch(() => { /* list is cosmetic */ })
    } else {
      // Create mode — the client has no persisted accounts yet, but the
      // Mapping step's "platform carrier account" picker needs the shared
      // platform account book to source a carrier for a rule. Load it so
      // that picker isn't stuck on "No platform accounts".
      accountRefService.listAccounts()
        .then((a) => { if (!cancelled) setAccounts(a) })
        .catch(() => { /* picker just stays empty — non-fatal */ })
    }
    return () => { cancelled = true }
  }, [editingCode])

  // ===== Per-user draft persistence =====
  // Only in create mode — edit already carries a persisted server row. Writes
  // on every relevant change so a browser close mid-wizard restores exactly
  // where the operator left off. Cleared on successful create.
  useEffect(() => {
    if (isEdit) return
    try {
      const snapshot: DraftShape = {
        form,
        selectedShipFromWarehouseId,
        visitedSteps: [...visitedSteps],
        activeStep,
        carrierDrafts,
        mappingDrafts,
        importerBrokerDraft,
      }
      localStorage.setItem(draftStorageKey(), JSON.stringify(snapshot))
    } catch { /* localStorage full / disabled — not fatal */ }
  }, [isEdit, form, selectedShipFromWarehouseId, visitedSteps, activeStep, carrierDrafts, mappingDrafts, importerBrokerDraft])

  // ===== Live duplicate-code check =====
  // Debounced: fires 500ms after the last keystroke. Skips in edit mode
  // (client code is immutable there) and when the field-shape validator is
  // already unhappy — don't spam the API with obviously-bad codes.
  useEffect(() => {
    if (isEdit) return
    const trimmed = (form.clientCode || '').trim().toUpperCase()
    if (!trimmed || validateClientCode(trimmed) != null) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- clear stale conflict/spinner when field is empty or shape-invalid; guards debounced network call, not derivable at render
      setCodeConflict(null)
      setCheckingCode(false)
      return
    }
    setCheckingCode(true)
    let cancelled = false
    const t = window.setTimeout(async () => {
      const conflict = await checkClientCodeAvailable(trimmed, async (code) => {
        const resp = await clientService.getClient(code)
        return resp
      })
      if (cancelled) return
      setCodeConflict(conflict)
      setCheckingCode(false)
    }, 500)
    return () => {
      cancelled = true
      window.clearTimeout(t)
      setCheckingCode(false)
    }
  }, [isEdit, form.clientCode])

  // ===== Per-step validation (recomputed each render — cheap) =====
  const identityErrors = useMemo(() => ({
    // The live duplicate check trumps the shape check when both would fire
    // (the operator already knows their typed value is well-formed; what
    // they need to see is that it's taken).
    clientCode: codeConflict ?? validateClientCode(form.clientCode),
    name: validateName(form.name),
    email: validateEmail(form.email || '', false),
    phone: validatePhone(form.phone || '', false),
  }), [form.clientCode, form.name, form.email, form.phone, codeConflict])

  const shipFromErrors = useMemo(
    () => validateAddress(form.shipFrom as AddressLike, { required: true, caps: addressCaps }),
    [form.shipFrom, addressCaps],
  )

  const returnErrors = useMemo(() => {
    // Toggle on = return is "same as ship from"; no separate validation.
    if (form.returnSameAsShipFrom) return {}
    return validateAddress(form.returnAddress as AddressLike, { required: true, caps: addressCaps })
  }, [form.returnSameAsShipFrom, form.returnAddress, addressCaps])

  const stepValid = (key: StepKey): boolean => {
    switch (key) {
      case 'identity': return !hasErrors(identityErrors)
      case 'shipFrom': return !hasErrors(shipFromErrors)
      case 'return':   return !hasErrors(returnErrors)
      default:         return true // client-dependent steps aren't gated
    }
  }

  // Force-touch every field in a step's error map — used when the operator
  // hits Next without having explored the step's fields yet, so the red
  // errors appear inline instead of silently blocking navigation.
  const touchAllIn = (prefix: string, keys: string[]) => {
    setTouched((cur) => {
      const next = { ...cur }
      for (const k of keys) next[`${prefix}.${k}`] = true
      return next
    })
  }
  const touchStep = (step: StepKey) => {
    switch (step) {
      case 'identity':
        touchAllIn('identity', ['clientCode', 'name', 'email', 'phone'])
        break
      case 'shipFrom':
        touchAllIn('shipFrom', Object.keys(emptyAddress))
        break
      case 'return':
        if (!form.returnSameAsShipFrom) touchAllIn('returnAddress', Object.keys(emptyAddress))
        break
    }
  }

  // ===== Navigation =====
  const goStep = (key: StepKey) => {
    // In create mode block skip-ahead into a step whose prerequisites aren't
    // met yet. Bail with a toast pointing at the earliest incomplete step so
    // the operator knows exactly what to fix. Edit mode is always free.
    if (!isEdit && !isStepAccessible(key)) {
      const firstIncomplete = STEP_DEFS.find((s) =>
        MANDATORY_STEPS.has(s.key) && !stepComplete(s.key),
      )
      notify.error(
        firstIncomplete
          ? `Complete "${firstIncomplete.label}" before jumping ahead.`
          : 'Complete the earlier mandatory steps first.',
      )
      return
    }
    setActiveStep(key)
    visitStep(key)
  }

  const goNext = () => {
    const i = stepIndex(activeStep)
    if (i < 0 || i >= STEP_DEFS.length - 1) return
    // Sequential lock in create mode: the current step must actually be
    // complete before Next fires — no more "click Next with garbage and get
    // a toast". The Next button itself is disabled by the same check, so
    // reaching this branch means the operator bypassed the disabled state
    // (keyboard shortcut, dev tools, etc.).
    if (!isEdit && !stepComplete(activeStep)) {
      touchStep(activeStep)
      notify.error(stepBlockers(activeStep).join(' ') || 'Complete this step to continue.')
      return
    }
    const nextKey = STEP_DEFS[i + 1].key
    setActiveStep(nextKey)
    visitStep(nextKey)
  }

  const goBack = () => {
    // Back always works — the operator is walking away from a step, not
    // into a locked one.
    const i = stepIndex(activeStep)
    if (i > 0) setActiveStep(STEP_DEFS[i - 1].key)
  }

  const onClose = () => navigate('/settings/clients')

  /** Every MANDATORY step visited AND every mandatory field valid = ready to
   *  create. Optional steps like Importer/Broker don't gate Create. */
  const allStepsVisited = STEP_DEFS
    .filter((s) => MANDATORY_STEPS.has(s.key))
    .every((s) => visitedSteps.has(s.key))

  /**
   * The Carriers step is "complete" when the operator has staged at least
   * one draft carrier account. The staged rows commit through
   * accountRefService on Create client.
   */
  const carriersStepComplete = carrierDrafts.length > 0

  /** The Mapping step is complete when the operator has staged at least one
   *  draft rule (ship via + carrier service). */
  const mappingStepComplete = mappingDrafts.length > 0

  const readyToCreate = allStepsVisited
    && stepValid('identity')
    && stepValid('shipFrom')
    && stepValid('return')
    && selectedShipFromWarehouseId != null
    && carriersStepComplete
    && mappingStepComplete
    && importerBrokerDraftValid(importerBrokerDraft)

  /**
   * "Has this step's data been provided?" — used for both the step-rail's
   *  accessibility gate and the Next-button's per-step validity check. In
   *  edit mode every step counts as complete (all data comes from the server
   *  and the operator is free-navigating). Importer/Broker is optional so
   *  it reports complete when either skipped OR filled + valid. Summary is
   *  complete when every mandatory upstream step is complete. */
  const stepComplete = (key: StepKey): boolean => {
    if (isEdit) return true
    switch (key) {
      case 'identity':       return stepValid('identity')
      case 'shipFrom':       return stepValid('shipFrom') && selectedShipFromWarehouseId != null
      case 'return':         return stepValid('return')
      case 'carriers':       return carriersStepComplete
      case 'mapping':        return mappingStepComplete
      case 'importerBroker': return importerBrokerDraftValid(importerBrokerDraft)
      case 'summary':
        return stepValid('identity')
          && stepValid('shipFrom') && selectedShipFromWarehouseId != null
          && stepValid('return')
          && carriersStepComplete
          && mappingStepComplete
          && importerBrokerDraftValid(importerBrokerDraft)
    }
  }

  /** Human-readable "what's missing?" list for a given step — used as the
   *  tooltip on the disabled Next button. Empty list = step is complete. */
  const stepBlockers = (key: StepKey): string[] => {
    if (isEdit || stepComplete(key)) return []
    const out: string[] = []
    switch (key) {
      case 'identity':
        if (!stepValid('identity')) out.push('Fix Identity fields (client code, name, email/phone format).')
        break
      case 'shipFrom':
        if (selectedShipFromWarehouseId == null) out.push('Pick a Ship From warehouse.')
        else if (!stepValid('shipFrom')) out.push("Warehouse's address is missing required fields.")
        break
      case 'return':
        if (!stepValid('return')) out.push('Complete the Return address.')
        break
      case 'carriers':
        if (!carriersStepComplete) out.push('Add at least one carrier account.')
        break
      case 'mapping':
        if (!mappingStepComplete) out.push('Add at least one shipping-service mapping.')
        break
      case 'importerBroker':
        if (!importerBrokerDraftValid(importerBrokerDraft))
          out.push('BUSINESS importer needs name, country, address, city and postal code — or switch to RECEIVER, or uncheck "Fill importer/broker".')
        break
      case 'summary':
        // Aggregate blockers from every mandatory upstream step so the
        // Submit tooltip lists exactly what to fix.
        for (const k of ['identity', 'shipFrom', 'return', 'carriers', 'mapping'] as const) {
          out.push(...stepBlockers(k))
        }
        if (!importerBrokerDraftValid(importerBrokerDraft))
          out.push('Importer/broker step has an invalid draft — fix it or uncheck "Fill importer/broker".')
        break
    }
    return out
  }

  /**
   * Sequential lock — a step is only accessible in create mode when every
   * mandatory step before it is complete. Guarantees the operator can't skip
   * ahead to (say) Mapping without a valid Identity + Ship From + Return +
   * Carriers already staged. Importer/Broker is not mandatory but sits after
   * Mapping in the rail; it becomes accessible once Mapping is complete.
   *
   * Edit mode bypasses the lock entirely — the persisted client has all
   * data and operators frequently need to jump to a targeted step.
   */
  const isStepAccessible = (key: StepKey): boolean => {
    if (isEdit) return true
    const target = stepIndex(key)
    for (const s of STEP_DEFS) {
      const i = stepIndex(s.key)
      if (i >= target) break
      if (!MANDATORY_STEPS.has(s.key)) continue
      if (!stepComplete(s.key)) return false
    }
    return true
  }

  /** Human-readable "why is Create disabled?" list — used as the tooltip on
   *  the disabled Create button so the operator can see exactly what's
   *  blocking commit without having to visit every step. */
  const createBlockers = useMemo(() => {
    if (isEdit) return []
    const reasons: string[] = []
    if (!allStepsVisited) {
      const missing = STEP_DEFS
        .filter((s) => MANDATORY_STEPS.has(s.key) && !visitedSteps.has(s.key))
        .map((s) => s.short)
      reasons.push(`Visit: ${missing.join(', ')}`)
    }
    if (!stepValid('identity')) reasons.push('Fix Identity fields')
    if (selectedShipFromWarehouseId == null) reasons.push('Pick a Ship From warehouse')
    else if (!stepValid('shipFrom')) reasons.push('Fix Ship From warehouse address')
    if (!stepValid('return')) reasons.push('Fix Return address')
    if (!carriersStepComplete) reasons.push('Add at least one carrier account')
    if (!mappingStepComplete) reasons.push('Add at least one shipping-service mapping')
    if (!importerBrokerDraftValid(importerBrokerDraft))
      reasons.push('Fix Importer / Broker draft (or uncheck "Fill importer/broker" to skip)')
    return reasons
    // eslint-disable-next-line react-hooks/exhaustive-deps -- stepValid is a plain function (not memoized); it reads form which IS in the dep list, so it recomputes correctly. Adding stepValid itself would break memoization on every render.
  }, [
    isEdit, allStepsVisited, visitedSteps, selectedShipFromWarehouseId,
    carriersStepComplete, mappingStepComplete, importerBrokerDraft,
    form,
  ])

  // ===== Save handlers =====
  /** Persist edits on the identity + addresses steps. Called from the wizard
   *  footer's "Save" button in edit mode, and from goNext() in create mode
   *  when the operator crosses into a client-dependent step for the first
   *  time (advanceTo = the step to land on after the successful create). */
  const handleCreate = async () => {
    // Full validation before hitting the API — including the "have you
    // visited every step?" gate that lives outside stepValid().
    touchStep('identity')
    touchStep('shipFrom')
    touchStep('return')
    if (!readyToCreate) {
      notify.error('Complete every step and fix the highlighted fields before creating.')
      return
    }

    setSaving(true)
    try {
      // Trim every string before hitting the API — the backend doesn't strip
      // whitespace, so a stray trailing space would silently persist.
      const trimmedAddr = (a: Address | undefined): Address | undefined => a && {
        name: a.name?.trim() ?? '',
        line1: a.line1?.trim() ?? '',
        line2: a.line2?.trim() ?? '',
        city: a.city?.trim() ?? '',
        state: a.state?.trim() ?? '',
        zip: a.zip?.trim() ?? '',
        country: a.country?.trim().toUpperCase() ?? '',
        phone: a.phone?.trim() ?? '',
      }
      const payload: ClientUpsertPayload = {
        clientCode: form.clientCode.trim().toUpperCase(),
        name: form.name.trim(),
        email: form.email?.trim() || '',
        phone: form.phone?.trim() || '',
        shipFrom: trimmedAddr(form.shipFrom as Address) ?? undefined,
        returnAddress: form.returnSameAsShipFrom
          ? undefined
          : trimmedAddr(form.returnAddress as Address),
        returnSameAsShipFrom: form.returnSameAsShipFrom,
        // Sprint 50 Tier 1 finding #4 — per-tenant defaults. Send only when
        // non-empty; the backend accepts nullable and the DTO's @Size/@Pattern
        // validators fire an empty-string pass-through, so we omit fully.
        defaultCurrency: form.defaultCurrency?.trim().toUpperCase() || undefined,
        defaultWeightUnit: form.defaultWeightUnit?.trim().toUpperCase() || undefined,
        defaultDimUnit: form.defaultDimUnit?.trim().toUpperCase() || undefined,
        timezone: form.timezone?.trim() || undefined,
        defaultOriginCountry: form.defaultOriginCountry?.trim().toUpperCase() || undefined,
      }
      const response = await clientService.createClient(payload)
      notify.success(`Client ${response.data.clientCode} created.`)

      // ===== Commit draft carrier accounts + mapping rules =====
      // Best-effort per row; a single failure surfaces as a toast but doesn't
      // roll back the client. The operator can retry from Carriers / Mapping.
      const carrierFailures: string[] = []
      for (const d of carrierDrafts) {
        try {
          await accountRefService.upsertAccount({
            accountNumber: d.accountNumber.trim(),
            carrierCode: d.carrierCode,
            clientId: d.clientId.trim(),
            clientSecret: d.clientSecret.trim(),
            environment: d.environment,
            customerNo: response.data.clientCode,
            clientDefault: d.clientDefault,
            shippingPurpose: d.shippingPurpose || null,
            clearanceOption: d.clearanceOption || null,
            // Third-party billing default — only send when the draft picked
            // THIRD_PARTY; otherwise send empty strings so the backend clears
            // any prior third-party row on this account (matches how the
            // CarrierConnections drawer sends).
            thirdPartyAccount:  d.clearanceOption === 'THIRD_PARTY' ? (d.thirdPartyAccount  || null) : '',
            thirdPartyName:     d.clearanceOption === 'THIRD_PARTY' ? (d.thirdPartyName     || null) : '',
            thirdPartyAddress1: d.clearanceOption === 'THIRD_PARTY' ? (d.thirdPartyAddress1 || null) : '',
            thirdPartyCity:     d.clearanceOption === 'THIRD_PARTY' ? (d.thirdPartyCity     || null) : '',
            thirdPartyState:    d.clearanceOption === 'THIRD_PARTY' ? (d.thirdPartyState    || null) : '',
            thirdPartyPostcode: d.clearanceOption === 'THIRD_PARTY' ? (d.thirdPartyPostcode || null) : '',
            thirdPartyCountry:  d.clearanceOption === 'THIRD_PARTY' ? (d.thirdPartyCountry  || null) : '',
          })
        } catch (e) {
          carrierFailures.push(
            `${formatCarrierName(d.carrierCode)} · ${d.accountNumber}: ${e instanceof Error ? e.message : 'failed'}`,
          )
        }
      }
      if (carrierFailures.length > 0) {
        notify.error(`Some carrier accounts failed to save:\n${carrierFailures.join('\n')}\nAdd them from Carriers step.`)
      } else if (carrierDrafts.length > 0) {
        notify.success(`${carrierDrafts.length} carrier account${carrierDrafts.length === 1 ? '' : 's'} saved.`)
      }

      const mappingFailures: string[] = []
      for (const m of mappingDrafts) {
        try {
          await shippingConfigService.saveRule({
            shipviaCd: m.shipviaCd.trim(),
            clientCode: response.data.clientCode,
            destType: 'ANY',
            destValue: null,
            serviceId: m.serviceId,
            warehouseIds: [],
            allowedPresetIds: [],
          })
        } catch (e) {
          mappingFailures.push(`${m.shipviaCd}: ${e instanceof Error ? e.message : 'failed'}`)
        }
      }
      if (mappingFailures.length > 0) {
        notify.error(`Some mappings failed to save:\n${mappingFailures.join('\n')}\nAdd them from Mapping step.`)
      } else if (mappingDrafts.length > 0) {
        notify.success(`${mappingDrafts.length} mapping${mappingDrafts.length === 1 ? '' : 's'} saved.`)
      }

      // Importer / Broker draft — only persist when the operator filled it
      // (filled=true). Failure is best-effort like carriers + mappings; the
      // operator can retry from Settings → Importer/Broker.
      if (importerBrokerDraft.filled) {
        try {
          await customsProfileService.save(response.data.clientCode, {
            countries: importerBrokerDraft.countries,
            importerType: importerBrokerDraft.importerType,
            importerName: importerBrokerDraft.importerName.trim() || null,
            importerCountry: importerBrokerDraft.importerCountry.trim().toUpperCase() || null,
            importerAddress1: importerBrokerDraft.importerAddress1.trim() || null,
            importerAddress2: importerBrokerDraft.importerAddress2.trim() || null,
            importerCity: importerBrokerDraft.importerCity.trim() || null,
            importerState: importerBrokerDraft.importerState.trim() || null,
            importerPostcode: importerBrokerDraft.importerPostcode.trim() || null,
            importerPhone: importerBrokerDraft.importerPhone.trim() || null,
            importerTaxId: importerBrokerDraft.importerTaxId.trim() || null,
            importerTaxIdType: importerBrokerDraft.importerTaxIdType.trim() || null,
            brokerName: importerBrokerDraft.brokerName.trim() || null,
            brokerPhone: importerBrokerDraft.brokerPhone.trim() || null,
            incoterms: importerBrokerDraft.incoterms.trim().toUpperCase() || null,
            reasonForExport: importerBrokerDraft.reasonForExport.trim().toUpperCase() || null,
          } as CustomsProfile)
          notify.success('Importer / broker profile saved.')
        } catch (ibError) {
          notify.error(
            `Client created, but the importer/broker profile failed: ${
              ibError instanceof Error ? ibError.message : 'unknown error'
            }. Add it from Settings → Importer/Broker.`,
          )
        }
      }

      // Attach the picked Ship From warehouse and default it — the picker
      // step is decoupled from the Warehouses step but must guarantee the
      // rule-resolution defaults line up on first shipment.
      if (selectedShipFromWarehouseId != null) {
        const wh = pickWarehouses.find((w) => w.id === selectedShipFromWarehouseId)
        if (wh) {
          try {
            await clientWarehouseService.attach(response.data.clientCode, {
              warehouseCode: wh.code,
              makeDefault: true,
            })
          } catch (attachError) {
            notify.error(
              attachError instanceof Error
                ? `Client created, but attaching Ship From warehouse ${wh.code} failed: ${attachError.message}`
                : `Client created, but attaching Ship From warehouse ${wh.code} failed.`,
            )
          }
        }
      }
      // Redirect so the URL carries the persisted code and edit-mode loaders
      // + child components pick up the client. Land on Carriers — that's the
      // first step whose actual configuration UI needs a persisted clientCode,
      // so the operator can pick up where the deferred-config panel left off.
      setClient(response.data)
      setCarrierDrafts([])
      setMappingDrafts([])
      setImporterBrokerDraft(emptyImporterBrokerDraft())
      // Clear the draft — the wizard's committed state is now the source of
      // truth. Any subsequent /clients/new visit starts fresh.
      try { localStorage.removeItem(draftStorageKey()) } catch { /* not fatal */ }
      navigate(`/settings/clients/${encodeURIComponent(response.data.clientCode)}`, {
        replace: true,
        state: { advanceTo: 'carriers' },
      })
    } catch (error) {
      // CLIENT_CODE_TAKEN needs the caller-typed code interpolated, so it
      // stays inline. Everything else flows through the friendly-message map.
      if (error instanceof ApiError && error.errorCode === 'CLIENT_CODE_TAKEN') {
        notify.error(`Client code ${form.clientCode.toUpperCase()} is already registered.`)
      } else {
        notify.apiError(error, 'Failed to save the client.')
      }
    } finally {
      setSaving(false)
    }
  }

  const handleUpdate = async () => {
    touchStep('identity')
    touchStep('shipFrom')
    touchStep('return')
    if (!stepValid('identity') || !stepValid('shipFrom') || !stepValid('return')) {
      notify.error('Fix the highlighted fields to save.')
      return
    }
    setSaving(true)
    try {
      const trimmedAddr = (a: Address | undefined): Address | undefined => a && {
        name: a.name?.trim() ?? '',
        line1: a.line1?.trim() ?? '',
        line2: a.line2?.trim() ?? '',
        city: a.city?.trim() ?? '',
        state: a.state?.trim() ?? '',
        zip: a.zip?.trim() ?? '',
        country: a.country?.trim().toUpperCase() ?? '',
        phone: a.phone?.trim() ?? '',
      }
      const payload: ClientUpsertPayload = {
        clientCode: form.clientCode,
        name: form.name.trim(),
        email: form.email?.trim() || '',
        phone: form.phone?.trim() || '',
        shipFrom: trimmedAddr(form.shipFrom as Address) ?? undefined,
        returnAddress: form.returnSameAsShipFrom
          ? undefined
          : trimmedAddr(form.returnAddress as Address),
        returnSameAsShipFrom: form.returnSameAsShipFrom,
        // Sprint 50 Tier 1 finding #4 — per-tenant defaults (same as create).
        defaultCurrency: form.defaultCurrency?.trim().toUpperCase() || undefined,
        defaultWeightUnit: form.defaultWeightUnit?.trim().toUpperCase() || undefined,
        defaultDimUnit: form.defaultDimUnit?.trim().toUpperCase() || undefined,
        timezone: form.timezone?.trim() || undefined,
        defaultOriginCountry: form.defaultOriginCountry?.trim().toUpperCase() || undefined,
      }
      const response = await clientService.updateClient(form.clientCode, payload)
      setClient(response.data)
      // Same Ship From warehouse follow-up as create: attach + default the
      // picked warehouse so the origin isn't just address text on the row.
      if (selectedShipFromWarehouseId != null) {
        const wh = pickWarehouses.find((w) => w.id === selectedShipFromWarehouseId)
        if (wh) {
          try {
            await clientWarehouseService.attach(response.data.clientCode, {
              warehouseCode: wh.code,
              makeDefault: true,
            })
          } catch (attachError) {
            // Attach may 409 if it's already attached — that's fine; a fresh
            // "already attached" isn't worth toasting. Anything else is.
            if (!(attachError instanceof ApiError && attachError.errorCode === 'WAREHOUSE_ALREADY_ATTACHED')) {
              notify.error(
                attachError instanceof Error
                  ? attachError.message
                  : 'Client saved, but attaching Ship From warehouse failed.',
              )
            } else {
              // Even when already attached, make it the default (best-effort).
              try {
                await clientWarehouseService.setDefault(response.data.clientCode, wh.code)
              } catch { /* not fatal */ }
            }
          }
        }
      }
      notify.success(`Client ${response.data.clientCode} updated.`)
    } catch (error) {
      notify.apiError(error, 'Failed to save the client.')
    } finally {
      setSaving(false)
    }
  }

  // If we just created a client via handleCreate, react-router's navigate()
  // fired with a state hint for where to land — pick it up once the edit load
  // effect has hydrated `client`.
  useEffect(() => {
    if (!client) return
    const advanceTo = (window.history.state?.usr?.advanceTo as StepKey | undefined)
    if (advanceTo && STEP_DEFS.some((s) => s.key === advanceTo)) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot step-jump when nav hint is present in history state; consumed immediately (see replaceState below), can't be derived at render
      setActiveStep(advanceTo)
      // Consume so a subsequent refresh doesn't keep re-triggering it.
      window.history.replaceState({ ...window.history.state, usr: {} }, '')
    }
  }, [client])

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center text-[12.5px] text-slate-500">
        <FiLoader className="mr-2 h-4 w-4 animate-spin" /> Loading client…
      </div>
    )
  }

  const currentIndex = stepIndex(activeStep)
  const isFirst = currentIndex <= 0
  const isLast = currentIndex >= STEP_DEFS.length - 1

  return (
    <div className="rounded-2xl border border-slate-200 bg-white shadow-sm">
      {/* Header — back arrow + title + optional Save Changes shortcut (edit). */}
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 px-4 py-2.5">
        <div className="flex min-w-0 items-center gap-2.5">
          <button
            type="button"
            onClick={onClose}
            aria-label="Back to clients"
            title="Back to clients"
            className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50 hover:text-slate-950"
          >
            <FiArrowLeft className="h-3.5 w-3.5" />
          </button>
          <div className="min-w-0">
            <p className="text-[9.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              {isEdit ? 'Edit client' : 'New client'}
            </p>
            <h3 className="truncate text-[13.5px] font-semibold leading-tight text-slate-950">
              {isEdit ? `${client?.name} (${form.clientCode})` : 'Register a client'}
            </h3>
          </div>
        </div>
        {isEdit && (activeStep === 'identity' || activeStep === 'shipFrom' || activeStep === 'return') ? (
          <div className="flex shrink-0 items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-100"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => { void handleUpdate() }}
              disabled={saving}
              className="rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              {saving ? 'Saving…' : 'Save changes'}
            </button>
          </div>
        ) : null}
      </div>

      {/* Numbered step rail — horizontal, wraps below md. Pill states:
            active     — dark filled
            done       — emerald + check
            invalid    — rose (visited with missing/bad data)
            locked     — greyed disabled (create mode: prior mandatory step
                         is incomplete, so the operator can't skip ahead here)
          Edit mode = every pill is directly clickable. */}
      <div role="tablist" aria-label="Client wizard steps" className="flex flex-wrap items-center gap-1 border-b border-slate-100 px-3 py-2">
        {STEP_DEFS.map((s, i) => {
          const active = activeStep === s.key
          const visited = visitedSteps.has(s.key)
          const validating = s.key === 'identity' || s.key === 'shipFrom' || s.key === 'return'
          const draftIncomplete = !isEdit && visited && (
            (s.key === 'carriers' && !carriersStepComplete) ||
            (s.key === 'mapping' && !mappingStepComplete)
          )
          const invalid = (!isEdit && validating && !stepValid(s.key) && (
            (s.key === 'identity' && (touched['identity.clientCode'] || touched['identity.name'])) ||
            (s.key === 'shipFrom' && (touched['shipFrom.line1'] || visited)) ||
            (s.key === 'return' && !form.returnSameAsShipFrom && touched['returnAddress.line1'])
          )) || draftIncomplete
          const done = !invalid && (isEdit
            ? true
            : validating
              ? stepValid(s.key) && visited
              : s.key === 'carriers'
                ? carriersStepComplete
                : s.key === 'mapping'
                  ? mappingStepComplete
                  : visited)
          // Sequential lock in create mode — a pill for a step whose
          // prerequisites aren't met is disabled. The active pill is never
          // locked (would trap the operator on a step they can't leave).
          const locked = !isEdit && !active && !isStepAccessible(s.key)
          const firstIncomplete = locked
            ? STEP_DEFS.find((x) => MANDATORY_STEPS.has(x.key) && !stepComplete(x.key))
            : null
          const pillTitle = locked && firstIncomplete
            ? `Complete "${firstIncomplete.label}" before opening this step.`
            : s.label
          return (
            <button
              key={s.key}
              type="button"
              role="tab"
              aria-selected={active}
              aria-controls={`client-editor-panel-${s.key}`}
              onClick={() => goStep(s.key)}
              disabled={locked}
              title={pillTitle}
              className={`group inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11.5px] font-semibold transition ${
                active
                  ? 'border-[#1f150c] bg-[#1f150c] text-white'
                  : invalid
                    ? 'border-rose-300 bg-rose-50 text-rose-700 hover:bg-rose-100'
                    : done
                      ? 'border-emerald-200 bg-emerald-50 text-emerald-800 hover:bg-emerald-100'
                      : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
              } ${locked ? 'cursor-not-allowed opacity-45 hover:bg-white' : ''}`}
            >
              <span
                className={`inline-flex h-4 w-4 items-center justify-center rounded-full text-[9.5px] font-bold ${
                  active
                    ? 'bg-white text-[#1f150c]'
                    : invalid
                      ? 'bg-rose-600 text-white'
                      : done
                        ? 'bg-emerald-500 text-white'
                        : 'bg-slate-100 text-slate-500'
                }`}
              >
                {invalid ? '!' : done && !active ? <FiCheck className="h-2.5 w-2.5" /> : i + 1}
              </span>
              <span className="whitespace-nowrap">{s.short}</span>
            </button>
          )
        })}
      </div>

      {/* Step body — one at a time. Each step lives in its own panel so the
          existing child components (Warehouses / Mapping / Destinations / …)
          keep working unchanged as focused single-purpose panels. */}
      <div id={`client-editor-panel-${activeStep}`} role="tabpanel">
        {activeStep === 'identity' ? (
          <IdentityStep
            form={form}
            isEdit={isEdit}
            errors={identityErrors}
            checkingCode={checkingCode}
            touched={touched}
            markTouched={markTouched}
            set={set}
          />
        ) : null}

        {activeStep === 'shipFrom' ? (
          <ShipFromStep
            warehouses={visibleShipFromWarehouses}
            loading={pickWarehousesLoading}
            selectedId={selectedShipFromWarehouseId}
            onPick={pickShipFromWarehouse}
            onAddWarehouseClick={() => { setWarehouseBeingEdited(null); setShowShipFromAddWarehouse(true) }}
            onEditWarehouseClick={(w) => { setWarehouseBeingEdited(w); setShowShipFromAddWarehouse(true) }}
            addressPreview={form.shipFrom as Address}
            errors={shipFromErrors}
            touched={touched}
            isEdit={isEdit}
            hiddenAttachedCount={
              isEdit ? Math.max(0, pickWarehouses.length - visibleShipFromWarehouses.length) : 0
            }
          />
        ) : null}

        {showShipFromAddWarehouse ? (
          <WarehouseEditorModal
            warehouse={warehouseBeingEdited}
            defaultCarrierCode={
              accounts.find((a) => a.clientDefault)?.carrierCode
              || accounts[0]?.carrierCode
              || undefined
            }
            onClose={() => { setShowShipFromAddWarehouse(false); setWarehouseBeingEdited(null) }}
            onSaved={async (saved) => {
              setShowShipFromAddWarehouse(false)
              setWarehouseBeingEdited(null)
              await loadPickWarehouses()
              // Auto-select the saved warehouse — for create it's the newly
              // added row, for edit it's the same row with updated fields
              // (address preview refreshes because we re-pick).
              if (saved) pickShipFromWarehouse(saved)
            }}
          />
        ) : null}

        {activeStep === 'return' ? (
          <ReturnStep
            block={form.returnAddress as Address}
            same={form.returnSameAsShipFrom ?? true}
            setSame={(next) => setForm((cur) => ({ ...cur, returnSameAsShipFrom: next }))}
            errors={returnErrors}
            touched={touched}
            markTouched={markTouched}
            setAddr={setAddr}
            caps={addressCaps}
            bindingHint={bindingHint}
          />
        ) : null}

        {/* Carriers — live editor in edit mode; staged-draft list in create
            mode. Drafts get POSTed to accountRefService after the client is
            persisted (see the drafts loop in handleCreate). */}
        {activeStep === 'carriers' && (client ? (
          <div className="px-1 pt-2">
            <CarrierConnections initialClientFilter={client.clientCode} embedded />
          </div>
        ) : (
          <CarrierDraftStep
            drafts={carrierDrafts}
            addDraft={(d) => {
              setCarrierDrafts((cur) => [
                ...cur,
                { ...d, id: nextDraftId.current++ },
              ])
            }}
            removeDraft={(id) => setCarrierDrafts((cur) => cur.filter((d) => d.id !== id))}
          />
        ))}

        {/* Mapping — live editor in edit mode; staged-draft list in create
            mode. Draft is intentionally minimal (shipvia + service) — full
            scoping fields land after the client is persisted. */}
        {activeStep === 'mapping' && (client ? (
          <ClientShippingMappingTab clientCode={client.clientCode} />
        ) : (
          <MappingDraftStep
            drafts={mappingDrafts}
            services={servicesCatalog}
            carrierDrafts={carrierDrafts}
            accounts={accounts}
            addDraft={(d) => {
              setMappingDrafts((cur) => [
                ...cur,
                { ...d, id: nextDraftId.current++ },
              ])
            }}
            removeDraft={(id) => setMappingDrafts((cur) => cur.filter((d) => d.id !== id))}
          />
        ))}

        {/* Importer / Broker — optional. Live editor in edit mode via the
            existing CustomsProfileModal-backed step. Create mode captures a
            single primary profile as a draft (persisted after Submit via
            customsProfileService.save); the operator can add more profiles
            later from Settings → Importer/Broker. */}
        {activeStep === 'importerBroker' && (client ? (
          <ImporterBrokerStep clientCode={client.clientCode} clientName={client.name} />
        ) : (
          <ImporterBrokerDraftStep
            draft={importerBrokerDraft}
            setDraft={setImporterBrokerDraft}
          />
        ))}

        {/* Summary — final step in create mode. Every section rendered as
            a card with values + validation status; Submit fires the client
            creation cascade. */}
        {!isEdit && activeStep === 'summary' ? (
          <SummaryStep
            form={form}
            selectedShipFromWarehouseId={selectedShipFromWarehouseId}
            shipFromWarehouseLabel={
              pickWarehouses.find((w) => w.id === selectedShipFromWarehouseId)
                ? `${pickWarehouses.find((w) => w.id === selectedShipFromWarehouseId)!.code} — ${pickWarehouses.find((w) => w.id === selectedShipFromWarehouseId)!.name}`
                : null
            }
            carrierDrafts={carrierDrafts}
            mappingDrafts={mappingDrafts}
            importerBrokerDraft={importerBrokerDraft}
            stepComplete={stepComplete}
            stepBlockers={stepBlockers}
            jumpTo={(k) => setActiveStep(k)}
          />
        ) : null}
      </div>

      {/* Wizard footer — Back / Next / Create / Finish. In create mode Next
          gates on the current step's validity; when crossing into a client-
          dependent step it triggers create + auto-advance. */}
      <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 bg-slate-50/60 px-4 py-3">
        <button
          type="button"
          onClick={goBack}
          disabled={isFirst}
          className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <FiChevronLeft className="h-3.5 w-3.5" /> Back
        </button>

        <p className="text-[11px] text-slate-500">
          Step {currentIndex + 1} of {STEP_DEFS.length}
          {' · '}
          <span className="font-semibold text-slate-700">{STEP_DEFS[currentIndex].label}</span>
        </p>

        <div className="flex items-center gap-2">
          {!isEdit && activeStep === 'summary' ? (
            <button
              type="button"
              onClick={() => void handleCreate()}
              disabled={saving || !readyToCreate}
              title={
                readyToCreate
                  ? undefined
                  : createBlockers.length
                    ? `Cannot submit yet:\n  • ${createBlockers.join('\n  • ')}`
                    : 'Cannot submit yet.'
              }
              className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              {saving ? 'Submitting…' : 'Submit — create client'}
              <FiCheck className="h-3.5 w-3.5" />
            </button>
          ) : isLast ? (
            <button
              type="button"
              onClick={onClose}
              className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
            >
              Finish
              <FiCheck className="h-3.5 w-3.5" />
            </button>
          ) : (() => {
            // Disable Next in create mode when the current step's data isn't
            // complete — the sequential-lock gate matches the disabled state
            // in the step rail. Tooltip lists exactly what's missing.
            const nextBlocked = !isEdit && !stepComplete(activeStep)
            const nextBlockers = nextBlocked ? stepBlockers(activeStep) : []
            return (
              <button
                type="button"
                onClick={goNext}
                disabled={nextBlocked}
                title={
                  nextBlocked && nextBlockers.length
                    ? `Complete this step to continue:\n  • ${nextBlockers.join('\n  • ')}`
                    : nextBlocked
                      ? 'Complete this step to continue.'
                      : undefined
                }
                className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                Next
                <FiChevronRight className="h-3.5 w-3.5" />
              </button>
            )
          })()}
        </div>
      </footer>
    </div>
  )
}

// ===== Step components =====

function IdentityStep({
  form,
  isEdit,
  errors,
  checkingCode,
  touched,
  markTouched,
  set,
}: {
  form: ClientUpsertPayload
  isEdit: boolean
  errors: Record<string, string | null>
  checkingCode: boolean
  touched: Record<string, boolean>
  markTouched: (key: string) => void
  set: (key: keyof ClientUpsertPayload) => (e: { target: { value: string } }) => void
}) {
  const err = (k: string) => (touched[`identity.${k}`] ? errors[k] || null : null)
  const codeHint = isEdit
    ? 'Immutable after create.'
    : checkingCode
      ? 'Checking availability…'
      : "Letters, digits, '-', '_'. Uppercase-normalized."
  return (
    <div className="px-4 py-3 space-y-3">
      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-4">
        <Field label="Client code" required error={err('clientCode')} hint={codeHint}>
          <input
            value={form.clientCode}
            onChange={set('clientCode')}
            onBlur={() => markTouched('identity.clientCode')}
            readOnly={isEdit}
            placeholder="MA1885"
            maxLength={FIELD_LIMITS.clientCode}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={err('clientCode') ? true : undefined}
            className={`${inputBaseClass} ${err('clientCode') ? inputErr : inputOk} ${isEdit ? 'opacity-70' : ''} uppercase`}
          />
        </Field>
        <Field label="Client name" required error={err('name')}>
          <input
            value={form.name}
            onChange={set('name')}
            onBlur={() => markTouched('identity.name')}
            placeholder="Modern Art Fabrics"
            maxLength={FIELD_LIMITS.name}
            aria-invalid={err('name') ? true : undefined}
            className={`${inputBaseClass} ${err('name') ? inputErr : inputOk}`}
          />
        </Field>
        <Field label="Email" error={err('email')}>
          <input
            type="email"
            value={form.email}
            onChange={set('email')}
            onBlur={() => markTouched('identity.email')}
            placeholder="contact@client.com"
            maxLength={FIELD_LIMITS.email}
            autoComplete="email"
            spellCheck={false}
            inputMode="email"
            aria-invalid={err('email') ? true : undefined}
            className={`${inputBaseClass} ${err('email') ? inputErr : inputOk}`}
          />
        </Field>
        <Field label="Phone" error={err('phone')}>
          <input
            type="tel"
            value={form.phone}
            onChange={set('phone')}
            onBlur={() => markTouched('identity.phone')}
            placeholder="+1 555-123-4567"
            maxLength={FIELD_LIMITS.phone}
            autoComplete="tel"
            inputMode="tel"
            aria-invalid={err('phone') ? true : undefined}
            className={`${inputBaseClass} ${err('phone') ? inputErr : inputOk}`}
          />
        </Field>
      </div>

      {/* Sprint 50 Tier 1 finding #4 — per-tenant defaults panel. All fields
          optional; the label/rate/customs pipelines fall back to platform
          hardcodes when NULL. Backend enforces ISO 4217 currency,
          {LB,KG,OZ,G} weight, {IN,CM,MM} dim, ISO-3166-1 alpha-2 country.
          Empty select value → undefined in the payload → NULL in the DB. */}
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <h4 className="text-[12px] font-semibold text-slate-950">Defaults</h4>
        <p className="text-[10.5px] text-slate-500">
          Applied when a shipment doesn't override. Leave any blank to use the platform default.
        </p>
        <div className="mt-2 grid grid-cols-1 gap-2.5 sm:grid-cols-5">
          <Field label="Currency">
            <select
              value={form.defaultCurrency || ''}
              onChange={set('defaultCurrency')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="USD">USD</option>
              <option value="EUR">EUR</option>
              <option value="GBP">GBP</option>
              <option value="CAD">CAD</option>
              <option value="AUD">AUD</option>
              <option value="INR">INR</option>
              <option value="JPY">JPY</option>
              <option value="CNY">CNY</option>
              <option value="MXN">MXN</option>
              <option value="AED">AED</option>
            </select>
          </Field>
          <Field label="Weight unit">
            <select
              value={form.defaultWeightUnit || ''}
              onChange={set('defaultWeightUnit')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="LB">LB (pounds)</option>
              <option value="KG">KG (kilograms)</option>
              <option value="OZ">OZ (ounces)</option>
              <option value="G">G (grams)</option>
            </select>
          </Field>
          <Field label="Dimension unit">
            <select
              value={form.defaultDimUnit || ''}
              onChange={set('defaultDimUnit')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="IN">IN (inches)</option>
              <option value="CM">CM (centimeters)</option>
              <option value="MM">MM (millimeters)</option>
            </select>
          </Field>
          <Field label="Origin country">
            <select
              value={form.defaultOriginCountry || ''}
              onChange={set('defaultOriginCountry')}
              className={`${inputBaseClass} ${inputOk}`}
            >
              <option value="">— none —</option>
              <option value="US">US — United States</option>
              <option value="CA">CA — Canada</option>
              <option value="MX">MX — Mexico</option>
              <option value="GB">GB — United Kingdom</option>
              <option value="DE">DE — Germany</option>
              <option value="FR">FR — France</option>
              <option value="IN">IN — India</option>
              <option value="CN">CN — China</option>
              <option value="AU">AU — Australia</option>
              <option value="JP">JP — Japan</option>
              <option value="AE">AE — UAE</option>
              <option value="SG">SG — Singapore</option>
            </select>
          </Field>
          <Field label="Timezone" hint="IANA (e.g. America/New_York)">
            <TimezoneCombobox
              value={form.timezone || ''}
              onChange={(v) => set('timezone')({ target: { value: v } })}
              className={`${inputBaseClass} ${inputOk}`}
            />
          </Field>
        </div>
      </div>

    </div>
  )
}

/**
 * Ship From step — picks a warehouse whose address becomes Client.shipFrom on
 * save. Backend + Client schema stay unchanged; only the input shape moves
 * from "type an address" to "pick a warehouse". The picker offers every
 * active PLATFORM warehouse plus, in edit mode, the client's own CLIENT-owned
 * ones. An inline "Add warehouse" button opens WarehouseEditorModal for the
 * one-off case where none of the existing rows fit.
 *
 * The address preview below the picker mirrors the fields that will actually
 * be sent to the API — validators run against form.shipFrom, so a picked
 * warehouse with a bad zip / missing city surfaces the same inline errors.
 */
/**
 * Deferred-configuration placeholder. Rendered on client-dependent steps
 * (Carriers, Mapping) while the wizard is still in create mode — the actual
 * editors those steps host need a persisted clientCode, so this is what the
 * operator sees before they hit Create client on the Mapping step.
 */
// DeferredStepPanel used to render "Configuration deferred" cards on Carriers
// and Mapping in create mode. Replaced by full draft editors below — keeping
// this comment as an anchor in case we bring it back.

/**
 * Carriers step in create mode — a staged-draft list. Each row is a carrier
 * account that will POST to accountRefService.upsertAccount right after the
 * client is created. Draft fields mirror the API payload exactly so the
 * commit path in handleCreate is a straight pass-through.
 *
 * Not shown when the client already exists (edit / post-create) — that path
 * hands off to <CarrierConnections embedded /> which is the full editor.
 */
function CarrierDraftStep({
  drafts,
  addDraft,
  removeDraft,
}: {
  drafts: CarrierAccountDraft[]
  addDraft: (d: Omit<CarrierAccountDraft, 'id'>) => void
  removeDraft: (id: number) => void
}) {
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

/**
 * Mapping step in create mode — a staged-draft list. Rules commit to
 * shippingConfigService.saveRule after the client is persisted. Only the
 * minimum fields (order ship-via + carrier service) are captured here; the
 * post-create Mapping tab is where destinations / warehouses / packages get
 * layered on top.
 */
function MappingDraftStep({
  drafts,
  services,
  addDraft,
  removeDraft,
  carrierDrafts,
  accounts,
}: {
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
}) {
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

function ShipFromStep({
  warehouses,
  loading,
  selectedId,
  onPick,
  onAddWarehouseClick,
  onEditWarehouseClick,
  addressPreview,
  errors,
  touched,
  isEdit,
  hiddenAttachedCount,
}: {
  warehouses: Warehouse[]
  loading: boolean
  selectedId: number | null
  onPick: (wh: Warehouse | null) => void
  onAddWarehouseClick: () => void
  /** Fires when the operator taps the Edit button on the picked-preview
   *  card. Only exposed on CLIENT-owned warehouses (PLATFORM warehouses
   *  are shared and must be edited from Settings → Warehouses by admin). */
  onEditWarehouseClick: (wh: Warehouse) => void
  addressPreview: Address
  errors: Partial<Record<keyof AddressLike, string>>
  touched: Record<string, boolean>
  isEdit: boolean
  /** Count of warehouses filtered out of the picker because they're already
   *  attached to this client — surfaced as a small hint so it doesn't feel
   *  like a bug when the list is shorter than /settings/warehouses shows. */
  hiddenAttachedCount: number
}) {
  const err = (k: keyof AddressLike) => (touched[`shipFrom.${k}`] ? errors[k] || null : null)
  const platform = warehouses.filter((w) => (w.ownerType || '').toUpperCase() === 'PLATFORM')
  const own = warehouses.filter((w) => (w.ownerType || '').toUpperCase() === 'CLIENT')
  const picked = warehouses.find((w) => w.id === selectedId) || null
  const hasAnyErr = err('name') || err('line1') || err('city') || err('state') || err('zip') || err('country')

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h4 className="text-[12px] font-semibold text-slate-950">Ship From — pick a warehouse</h4>
            <p className="mb-2 text-[10.5px] leading-4 text-slate-500">
              Origin printed on this client's labels. Picking a warehouse copies its address into the
              client's Ship From and attaches it as the default warehouse on save.
              {isEdit && hiddenAttachedCount > 0 ? (
                <>
                  {' '}
                  <span className="text-slate-400">
                    ({hiddenAttachedCount} warehouse{hiddenAttachedCount === 1 ? '' : 's'} already
                    attached to this client {hiddenAttachedCount === 1 ? 'is' : 'are'} hidden —
                    detach from Settings → Warehouses to see them here.)
                  </span>
                </>
              ) : null}
            </p>
          </div>
          <button
            type="button"
            onClick={onAddWarehouseClick}
            className="inline-flex shrink-0 items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1 text-[11.5px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiPlus className="h-3 w-3" /> Add warehouse
          </button>
        </div>

        <Field label="Warehouse" required error={selectedId == null && (touched['shipFrom.line1'] || touched['shipFrom.name']) ? 'Pick a warehouse to set Ship From.' : null}>
          <Select
            value={selectedId != null ? String(selectedId) : ''}
            onChange={(e) => {
              const v = e.target.value
              if (!v) { onPick(null); return }
              const wh = warehouses.find((w) => String(w.id) === v) || null
              onPick(wh)
            }}
            disabled={loading}
            aria-label="Ship From warehouse"
          >
            <option value="">
              {loading ? 'Loading warehouses…' : warehouses.length === 0 ? 'No warehouses — add one first' : 'Pick a warehouse…'}
            </option>
            {platform.length ? (
              <optgroup label="Platform warehouses">
                {platform.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} — {w.name}{w.address?.country ? ` · ${w.address.country}` : ''}
                  </option>
                ))}
              </optgroup>
            ) : null}
            {isEdit && own.length ? (
              <optgroup label="Client-owned">
                {own.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} — {w.name}{w.address?.country ? ` · ${w.address.country}` : ''}
                  </option>
                ))}
              </optgroup>
            ) : null}
          </Select>
        </Field>

        {/* Address preview — the values that'll actually POST as Client.shipFrom.
            Read-only so the operator sees them as data derived from the picked
            warehouse; if they don't match, edit the warehouse instead. */}
        {picked ? (
          <div className={`mt-3 rounded-xl border ${hasAnyErr ? 'border-rose-300 bg-rose-50/40' : 'border-slate-200 bg-white'} px-3 py-2.5`}>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="flex items-center gap-1.5 text-[11.5px] font-semibold text-slate-800">
                  <FiHome className="h-3 w-3 text-slate-500" />
                  {picked.code} · {picked.name}
                  <span className={`ml-1 rounded-full px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ${
                    (picked.ownerType || '').toUpperCase() === 'PLATFORM'
                      ? 'bg-sky-100 text-sky-700' : 'bg-[#412d15]/10 text-[#412d15]'
                  }`}>
                    {(picked.ownerType || 'PLATFORM').toUpperCase()}
                  </span>
                </p>
                <p className="mt-1 text-[11px] leading-4 text-slate-600">
                  {addressPreview.name ? <>{addressPreview.name}<br /></> : null}
                  {addressPreview.line1 || <span className="text-slate-400 italic">no street</span>}
                  {addressPreview.line2 ? <>, {addressPreview.line2}</> : ''}
                  <br />
                  {[addressPreview.city, addressPreview.state, addressPreview.zip].filter(Boolean).join(', ') || <span className="text-slate-400 italic">no city / state / zip</span>}
                  {addressPreview.country ? ` · ${addressPreview.country}` : ''}
                  {addressPreview.phone ? <><br />{addressPreview.phone}</> : null}
                </p>
              </div>
              {(picked.ownerType || '').toUpperCase() === 'CLIENT' ? (
                <button
                  type="button"
                  onClick={() => onEditWarehouseClick(picked)}
                  title="Edit this warehouse (shared across clients — CLIENT-owned only)"
                  className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-slate-700 transition hover:border-[#412d15] hover:bg-[#faf7f0] hover:text-[#412d15]"
                >
                  <FiEdit2 className="h-3 w-3" /> Edit
                </button>
              ) : null}
            </div>
            {hasAnyErr ? (
              <div className="mt-2 space-y-0.5 rounded-lg bg-rose-50 px-2 py-1.5 text-[10.5px] font-semibold text-rose-700">
                <p className="flex items-center gap-1">
                  <FiAlertCircle className="h-3 w-3" />
                  This warehouse's address is missing required fields:
                </p>
                <ul className="ml-4 list-disc">
                  {err('name') ? <li>{err('name')}</li> : null}
                  {err('line1') ? <li>{err('line1')}</li> : null}
                  {err('city') ? <li>{err('city')}</li> : null}
                  {err('state') ? <li>{err('state')}</li> : null}
                  {err('zip') ? <li>{err('zip')}</li> : null}
                  {err('country') ? <li>{err('country')}</li> : null}
                </ul>
                <p className="mt-1 text-slate-600">Edit the warehouse from Warehouses (or Settings → Warehouses) to fix these fields.</p>
              </div>
            ) : null}
          </div>
        ) : (
          <p className="mt-3 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2.5 text-center text-[11.5px] text-slate-500">
            {loading
              ? 'Loading available warehouses…'
              : warehouses.length === 0
                ? 'No warehouses in the system yet. Click Add warehouse above to create one.'
                : 'Pick a warehouse from the dropdown to preview its address.'}
          </p>
        )}
      </div>
    </div>
  )
}

function ReturnStep({
  block,
  same,
  setSame,
  errors,
  touched,
  markTouched,
  setAddr,
  caps,
  bindingHint,
}: {
  block: Address
  same: boolean
  setSame: (next: boolean) => void
  errors: Partial<Record<keyof AddressLike, string>>
  touched: Record<string, boolean>
  markTouched: (key: string) => void
  setAddr: (block: 'shipFrom' | 'returnAddress', key: keyof Address) => (e: { target: { value: string } }) => void
  caps: AddressCaps
  bindingHint: (field: keyof AddressCaps) => CarrierCode[]
}) {
  const err = (k: keyof AddressLike) => (touched[`returnAddress.${k}`] ? errors[k] || null : null)
  return (
    <div className="px-4 py-3">
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <div className="flex items-start justify-between gap-2">
          <div>
            <h4 className="text-[12px] font-semibold text-slate-950">Return address</h4>
            <p className="text-[10.5px] leading-4 text-slate-500">
              Where undeliverable parcels come back to.
            </p>
          </div>
          <label className="flex shrink-0 items-center gap-1.5 pt-0.5 text-[11px] font-semibold text-slate-700">
            <input
              type="checkbox"
              checked={same}
              onChange={(e) => setSame(e.target.checked)}
              className="h-3.5 w-3.5 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
            />
            Same as Ship From
          </label>
        </div>
        {same ? (
          <p className="mt-2 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2 text-center text-[11px] text-slate-500">
            Returns use the Ship From address.
          </p>
        ) : (
          <div className="mt-2">
            <AddressGrid
              block={block}
              addressKey="returnAddress"
              err={err}
              markTouched={markTouched}
              setAddr={setAddr}
              caps={caps}
              bindingHint={bindingHint}
            />
          </div>
        )}
      </div>
    </div>
  )
}

function AddressGrid({
  block,
  addressKey,
  err,
  markTouched,
  setAddr,
  caps,
  bindingHint,
}: {
  block: Address
  addressKey: 'shipFrom' | 'returnAddress'
  err: (k: keyof AddressLike) => string | null
  markTouched: (key: string) => void
  setAddr: (block: 'shipFrom' | 'returnAddress', key: keyof Address) => (e: { target: { value: string } }) => void
  caps: AddressCaps
  bindingHint: (field: keyof AddressCaps) => CarrierCode[]
}) {
  const a = block
  const cls = (k: keyof AddressLike) => `${inputBaseClass} ${err(k) ? inputErr : inputOk}`
  const country = (a.country || '').toUpperCase()
  const zipHint = country
    ? country === 'US' ? 'Format: 12345 or 12345-6789'
      : country === 'CA' ? 'Format: A1A 1A1'
      : country === 'GB' ? 'e.g. SW1A 1AA'
      : country === 'NL' ? 'e.g. 1012 AB'
      : country === 'JP' ? 'e.g. 100-0001'
      : country === 'BR' ? 'e.g. 01310-100'
      : undefined
    : undefined
  // Small "cap set by <carriers>" line under each capped input. Skipped
  // when no carrier is enabled yet — the caps degrade to loose DB defaults
  // in that case and the extra text is noise.
  const capHint = (field: keyof AddressCaps): string | undefined => {
    const carriers = bindingHint(field)
    if (carriers.length === 0) return undefined
    return `max ${caps[field]} chars · ${carriers.join(' / ')}`
  }
  const joinHint = (...parts: Array<string | undefined>) =>
    parts.filter((p): p is string => !!p && p.length > 0).join(' · ') || undefined
  return (
    <div className="grid grid-cols-3 gap-2">
      <div className="col-span-3">
        <Field label="Attention / company" required error={err('name')} hint={capHint('name')}>
          <input
            value={a.name ?? ''}
            onChange={setAddr(addressKey, 'name')}
            onBlur={() => markTouched(`${addressKey}.name`)}
            className={cls('name')}
            placeholder="Warehouse / contact name"
            maxLength={caps.name}
            autoComplete="organization"
            aria-invalid={err('name') ? true : undefined}
          />
        </Field>
      </div>
      <div className="col-span-2">
        <Field label="Street address" required error={err('line1')} hint={capHint('line')}>
          <input
            value={a.line1 ?? ''}
            onChange={setAddr(addressKey, 'line1')}
            onBlur={() => markTouched(`${addressKey}.line1`)}
            className={cls('line1')}
            placeholder="123 Industrial Blvd"
            maxLength={caps.line}
            autoComplete="address-line1"
            aria-invalid={err('line1') ? true : undefined}
          />
        </Field>
      </div>
      <Field label="Suite / unit" error={err('line2')} hint={capHint('line')}>
        <input
          value={a.line2 ?? ''}
          onChange={setAddr(addressKey, 'line2')}
          onBlur={() => markTouched(`${addressKey}.line2`)}
          className={cls('line2')}
          placeholder="Suite 400"
          maxLength={caps.line}
          autoComplete="address-line2"
          aria-invalid={err('line2') ? true : undefined}
        />
      </Field>
      <Field label="City" required error={err('city')} hint={capHint('city')}>
        <input
          value={a.city ?? ''}
          onChange={setAddr(addressKey, 'city')}
          onBlur={() => markTouched(`${addressKey}.city`)}
          className={cls('city')}
          placeholder="Chicago"
          maxLength={caps.city}
          autoComplete="address-level2"
          aria-invalid={err('city') ? true : undefined}
        />
      </Field>
      <Field label="State" required error={err('state')} hint={capHint('state')}>
        <input
          value={a.state ?? ''}
          onChange={setAddr(addressKey, 'state')}
          onBlur={() => markTouched(`${addressKey}.state`)}
          className={cls('state')}
          placeholder="IL"
          maxLength={caps.state}
          autoComplete="address-level1"
          aria-invalid={err('state') ? true : undefined}
        />
      </Field>
      <Field label="Zip" required error={err('zip')} hint={joinHint(zipHint, capHint('zip'))}>
        <input
          value={a.zip ?? ''}
          onChange={setAddr(addressKey, 'zip')}
          onBlur={() => markTouched(`${addressKey}.zip`)}
          className={cls('zip')}
          placeholder="60601"
          maxLength={caps.zip}
          autoComplete="postal-code"
          aria-invalid={err('zip') ? true : undefined}
        />
      </Field>
      <div className="col-span-2">
        <Field label="Country" required error={err('country')}>
          <CountrySelect
            value={a.country}
            onChange={(code) => {
              // Force ISO-2 into state — CountrySelect emits it uppercase but
              // legacy rows might carry lowercase; normalise regardless.
              setAddr(addressKey, 'country')({ target: { value: (code || '').toUpperCase() } })
              markTouched(`${addressKey}.country`)
            }}
            inputClassName={cls('country')}
          />
        </Field>
      </div>
      <Field label="Phone" error={err('phone')} hint={capHint('phone')}>
        <input
          type="tel"
          value={a.phone ?? ''}
          onChange={setAddr(addressKey, 'phone')}
          onBlur={() => markTouched(`${addressKey}.phone`)}
          className={cls('phone')}
          placeholder="+1 555-123-4567"
          maxLength={caps.phone}
          autoComplete="tel"
          inputMode="tel"
          aria-invalid={err('phone') ? true : undefined}
        />
      </Field>
    </div>
  )
}

/**
 * Importer / Broker step for the edit-mode wizard. Lists every customs
 * profile the client has, opens CustomsProfileModal for Add / Edit, and
 * removes profiles via customsProfileService.remove. The step is optional
 * — no data required to commit — so nothing here gates Create client.
 */
function ImporterBrokerStep({
  clientCode,
  clientName,
}: {
  clientCode: string
  clientName: string
}) {
  const [profiles, setProfiles] = useState<CustomsProfile[]>([])
  const [loading, setLoading] = useState(true)
  const [modal, setModal] = useState<{ mode: 'new' } | { mode: 'edit'; profile: CustomsProfile } | null>(null)

  const refresh = async () => {
    setLoading(true)
    try {
      const list = await customsProfileService.list(clientCode)
      setProfiles(list)
    } catch (error) {
      notify.apiError(error, 'Failed to load importer / broker profiles.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on client change; refresh() sets loading + profiles state
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientCode])

  const remove = async (profile: CustomsProfile) => {
    if (profile.id == null) return
    const importerLabel = profile.importerName || profile.importerType || 'this profile'
    if (!(await notify.confirm(`Remove ${importerLabel}?`, {
      title: 'Remove importer / broker profile',
      confirmLabel: 'Remove',
      danger: true,
    }))) return
    try {
      await customsProfileService.remove(clientCode, profile.id)
      notify.success('Profile removed.')
      await refresh()
    } catch (error) {
      notify.apiError(error, 'Failed to remove the profile.')
    }
  }

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h4 className="text-[12.5px] font-semibold text-slate-950">
            Importer / Broker profiles
            <span className="ml-1.5 rounded-full bg-slate-100 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-slate-500">
              optional
            </span>
          </h4>
          <p className="text-[11px] leading-5 text-slate-500">
            Customs profiles for international shipments — importer + broker identity per
            destination region. A single profile can cover many countries.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setModal({ mode: 'new' })}
          className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
        >
          <FiPlus className="h-3.5 w-3.5" /> Add profile
        </button>
      </div>

      {loading ? (
        <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
          Loading…
        </p>
      ) : profiles.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 px-5 py-6 text-center">
          <p className="text-[12px] font-semibold text-slate-800">No importer / broker profiles yet</p>
          <p className="mx-auto mt-1 max-w-md text-[11px] leading-4 text-slate-500">
            Add one if this client ships internationally and needs a custom importer or a specific
            broker. Purely optional — carriers use their default brokerage otherwise.
          </p>
          <button
            type="button"
            onClick={() => setModal({ mode: 'new' })}
            className="mt-3 inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add first profile
          </button>
        </div>
      ) : (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <ul className="divide-y divide-slate-100">
            {profiles.map((p) => (
              <li key={p.id} className="flex items-center gap-3 px-3 py-2.5">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[12px] font-semibold text-slate-800">
                    {p.importerName || <span className="text-slate-400 italic">Unnamed importer</span>}
                    <span className="ml-2 rounded-md bg-[#412d15]/[0.07] px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-[#412d15]">
                      {p.importerType || 'RECEIVER'}
                    </span>
                    {p.brokerName || p.brokerCompany ? (
                      <span className="ml-1.5 text-[10.5px] font-normal text-slate-500">
                        · broker {p.brokerName || p.brokerCompany}
                      </span>
                    ) : null}
                  </p>
                  <p className="mt-0.5 flex flex-wrap items-center gap-1 text-[10.5px] text-slate-500">
                    Covers:
                    {p.countries.length === 0
                      ? <span className="italic text-slate-400">no destinations</span>
                      : p.countries.slice(0, 8).map((c) => (
                          <span
                            key={c}
                            className="rounded-md bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] font-bold text-slate-600"
                          >
                            {c}
                          </span>
                        ))}
                    {p.countries.length > 8 ? <span className="font-semibold text-slate-400">+{p.countries.length - 8}</span> : null}
                    {p.incoterms ? <span className="ml-1">· {p.incoterms}</span> : null}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setModal({ mode: 'edit', profile: p })}
                  className="inline-flex h-7 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 text-[10.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
                >
                  Edit
                </button>
                <button
                  type="button"
                  onClick={() => void remove(p)}
                  aria-label="Remove profile"
                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600"
                >
                  <FiPlus className="h-3.5 w-3.5 rotate-45" />
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {modal ? (
        <CustomsProfileModal
          clients={[{ clientCode, name: clientName } as Client]}
          lockedClientCode={clientCode}
          profile={modal.mode === 'edit' ? modal.profile : undefined}
          existingProfiles={profiles}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            void refresh()
          }}
        />
      ) : null}
    </div>
  )
}

/* ============================================================================
 * Importer / Broker draft step (create mode)
 * A scoped-down subset of ClientCustomsProfile — enough for a first-time
 * onboarding profile. The full field surface is on the settings page.
 * ==========================================================================*/

function ImporterBrokerDraftStep({
  draft,
  setDraft,
}: {
  draft: ImporterBrokerDraft
  setDraft: React.Dispatch<React.SetStateAction<ImporterBrokerDraft>>
}) {
  const update = <K extends keyof ImporterBrokerDraft>(k: K, v: ImporterBrokerDraft[K]) =>
    setDraft((cur) => ({ ...cur, [k]: v }))

  return (
    <div className="px-4 py-3 space-y-3">
      <label className="flex items-start gap-2 rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <input
          type="checkbox"
          checked={draft.filled}
          onChange={(e) => update('filled', e.target.checked)}
          className="mt-0.5 h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
        />
        <span className="flex-1">
          <span className="block text-[12.5px] font-semibold text-slate-950">
            Fill Importer / Broker now
          </span>
          <span className="mt-0.5 block text-[11px] leading-4 text-slate-500">
            Optional. Captures one primary importer profile for customs on international shipments.
            Unchecked = skip this step; you can add profiles later from Settings → Importer/Broker.
          </span>
        </span>
      </label>

      {draft.filled ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-4 space-y-4">
          {/* Importer type */}
          <div>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Importer type
            </p>
            <div className="flex flex-wrap gap-2">
              {(['BUSINESS', 'RECEIVER'] as const).map((v) => (
                <label
                  key={v}
                  className={`inline-flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-[12px] font-semibold transition ${
                    draft.importerType === v
                      ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]'
                      : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  <input
                    type="radio"
                    name="importerType"
                    value={v}
                    checked={draft.importerType === v}
                    onChange={() => update('importerType', v)}
                    className="sr-only"
                  />
                  {v === 'BUSINESS' ? 'BUSINESS · fixed importer (DDP)' : 'RECEIVER · consignee is IOR (DAP)'}
                </label>
              ))}
            </div>
            <p className="mt-1 text-[10.5px] text-slate-500">
              BUSINESS: this client's own importer identity is on file. RECEIVER: no importer of record — the destination consignee is the IOR (DAP terms).
            </p>
          </div>

          {/* Importer identity — only relevant for BUSINESS */}
          {draft.importerType === 'BUSINESS' ? (
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <Field label="Importer name" required>
                <input value={draft.importerName} onChange={(e) => update('importerName', e.target.value)} maxLength={200} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Country (ISO-2)" required>
                <input value={draft.importerCountry} onChange={(e) => update('importerCountry', e.target.value.toUpperCase())} maxLength={2} className={`${inputBaseClass} ${inputOk} uppercase font-semibold`} />
              </Field>
              <Field label="Address line 1" required>
                <input value={draft.importerAddress1} onChange={(e) => update('importerAddress1', e.target.value)} maxLength={255} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Address line 2">
                <input value={draft.importerAddress2} onChange={(e) => update('importerAddress2', e.target.value)} maxLength={255} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="City" required>
                <input value={draft.importerCity} onChange={(e) => update('importerCity', e.target.value)} maxLength={120} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="State / region">
                <input value={draft.importerState} onChange={(e) => update('importerState', e.target.value)} maxLength={120} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Postal code" required>
                <input value={draft.importerPostcode} onChange={(e) => update('importerPostcode', e.target.value)} maxLength={20} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Phone">
                <input value={draft.importerPhone} onChange={(e) => update('importerPhone', e.target.value)} type="tel" inputMode="tel" maxLength={50} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Tax ID (EIN / EORI / GSTIN / …)" hint="Optional but usually required by the destination customs authority.">
                <input value={draft.importerTaxId} onChange={(e) => update('importerTaxId', e.target.value)} maxLength={60} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Tax ID type" hint="EIN, EORI, GSTIN, IEC, IOSS …">
                <input value={draft.importerTaxIdType} onChange={(e) => update('importerTaxIdType', e.target.value.toUpperCase())} maxLength={20} className={`${inputBaseClass} ${inputOk} uppercase`} />
              </Field>
            </div>
          ) : null}

          {/* Shipment defaults */}
          <div>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Shipment defaults (optional)
            </p>
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <Field label="Incoterms" hint="DDP · DAP · DDU · EXW · CIF · FOB">
                <select value={draft.incoterms} onChange={(e) => update('incoterms', e.target.value)} className={`${inputBaseClass} ${inputOk}`}>
                  <option value="">— carrier default —</option>
                  {['DDP', 'DAP', 'DDU', 'EXW', 'CIF', 'FOB', 'DPU', 'CPT', 'CIP', 'FCA', 'FAS', 'CFR'].map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </Field>
              <Field label="Reason for export">
                <select value={draft.reasonForExport} onChange={(e) => update('reasonForExport', e.target.value)} className={`${inputBaseClass} ${inputOk}`}>
                  <option value="">— carrier default —</option>
                  <option value="SALE">SALE — commercial sale</option>
                  <option value="GIFT">GIFT</option>
                  <option value="SAMPLE">SAMPLE</option>
                  <option value="RETURN">RETURN</option>
                  <option value="REPAIR">REPAIR</option>
                  <option value="DOCUMENTS">DOCUMENTS</option>
                </select>
              </Field>
            </div>
          </div>

          {/* Broker (optional) */}
          <div>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Broker (optional)
            </p>
            <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
              <Field label="Broker name">
                <input value={draft.brokerName} onChange={(e) => update('brokerName', e.target.value)} maxLength={200} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
              <Field label="Broker phone">
                <input value={draft.brokerPhone} onChange={(e) => update('brokerPhone', e.target.value)} type="tel" inputMode="tel" maxLength={50} className={`${inputBaseClass} ${inputOk}`} />
              </Field>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

/* ============================================================================
 * Summary step (create mode only)
 * Per-section digest cards with green / amber / red status and a Fix link
 * that jumps back to the offending step.
 * ==========================================================================*/

function SummaryStep({
  form,
  selectedShipFromWarehouseId,
  shipFromWarehouseLabel,
  carrierDrafts,
  mappingDrafts,
  importerBrokerDraft,
  stepComplete,
  stepBlockers,
  jumpTo,
}: {
  form: ClientUpsertPayload
  selectedShipFromWarehouseId: number | null
  shipFromWarehouseLabel: string | null
  carrierDrafts: CarrierAccountDraft[]
  mappingDrafts: MappingRuleDraft[]
  importerBrokerDraft: ImporterBrokerDraft
  stepComplete: (k: StepKey) => boolean
  stepBlockers: (k: StepKey) => string[]
  jumpTo: (k: StepKey) => void
}) {
  const identityBody = (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      <li><span className="font-mono font-semibold">{form.clientCode || '—'}</span> · {form.name || <em className="text-slate-400">no name</em>}</li>
      {form.email ? <li>{form.email}</li> : null}
      {form.phone ? <li>{form.phone}</li> : null}
      <li className="text-slate-500">
        Defaults: {[form.defaultCurrency, form.defaultWeightUnit, form.defaultDimUnit, form.defaultOriginCountry, form.timezone]
          .filter(Boolean).join(' · ') || <em>none set</em>}
      </li>
    </ul>
  )

  const shipFromBody = (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      <li className="font-semibold">{shipFromWarehouseLabel || <em className="text-slate-400">no warehouse picked</em>}</li>
      {form.shipFrom ? (
        <>
          {form.shipFrom.name ? <li>{form.shipFrom.name}</li> : null}
          <li>
            {form.shipFrom.line1 || <em className="text-slate-400">no street</em>}
            {form.shipFrom.line2 ? `, ${form.shipFrom.line2}` : ''}
          </li>
          <li>
            {[form.shipFrom.city, form.shipFrom.state, form.shipFrom.zip].filter(Boolean).join(', ') || <em className="text-slate-400">no city / state / zip</em>}
            {form.shipFrom.country ? ` · ${form.shipFrom.country}` : ''}
          </li>
        </>
      ) : null}
    </ul>
  )

  const returnBody = form.returnSameAsShipFrom ? (
    <p className="text-[11.5px] text-slate-700">Mirrors Ship From address.</p>
  ) : form.returnAddress ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      {form.returnAddress.name ? <li>{form.returnAddress.name}</li> : null}
      <li>{form.returnAddress.line1 || <em className="text-slate-400">no street</em>}{form.returnAddress.line2 ? `, ${form.returnAddress.line2}` : ''}</li>
      <li>{[form.returnAddress.city, form.returnAddress.state, form.returnAddress.zip].filter(Boolean).join(', ')}{form.returnAddress.country ? ` · ${form.returnAddress.country}` : ''}</li>
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">no return address set</p>
  )

  const carriersBody = carrierDrafts.length ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      {carrierDrafts.slice(0, 5).map((d) => (
        <li key={d.id}>
          {formatCarrierName(d.carrierCode)} · {d.accountNumber}
          {d.clientDefault ? <span className="ml-1 text-[10px] font-semibold text-[#412d15]">(default)</span> : null}
        </li>
      ))}
      {carrierDrafts.length > 5 ? <li className="italic text-slate-500">+ {carrierDrafts.length - 5} more…</li> : null}
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">no carrier accounts staged</p>
  )

  const mappingBody = mappingDrafts.length ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      {mappingDrafts.slice(0, 5).map((d) => (
        <li key={d.id}>
          <span className="rounded bg-[#1f150c] px-1.5 py-0.5 font-mono text-[10px] text-[#e1dcc9]">{d.shipviaCd}</span>
          {' → service #'}{d.serviceId}
        </li>
      ))}
      {mappingDrafts.length > 5 ? <li className="italic text-slate-500">+ {mappingDrafts.length - 5} more…</li> : null}
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">no mappings staged</p>
  )

  const importerBody = importerBrokerDraft.filled ? (
    <ul className="space-y-0.5 text-[11.5px] leading-4 text-slate-700">
      <li>
        <span className="rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700">{importerBrokerDraft.importerType}</span>
        {' '}{importerBrokerDraft.importerName || <em className="text-slate-400">no importer name</em>}
      </li>
      {importerBrokerDraft.importerType === 'BUSINESS' ? (
        <>
          <li>{[importerBrokerDraft.importerAddress1, importerBrokerDraft.importerCity, importerBrokerDraft.importerPostcode, importerBrokerDraft.importerCountry].filter(Boolean).join(', ')}</li>
          {importerBrokerDraft.importerTaxId ? <li>Tax ID: {importerBrokerDraft.importerTaxIdType || '?'}/{importerBrokerDraft.importerTaxId}</li> : null}
        </>
      ) : null}
      {importerBrokerDraft.incoterms || importerBrokerDraft.reasonForExport ? (
        <li className="text-slate-500">Defaults: {[importerBrokerDraft.incoterms, importerBrokerDraft.reasonForExport].filter(Boolean).join(' · ')}</li>
      ) : null}
      {importerBrokerDraft.brokerName ? <li>Broker: {importerBrokerDraft.brokerName}{importerBrokerDraft.brokerPhone ? ` · ${importerBrokerDraft.brokerPhone}` : ''}</li> : null}
    </ul>
  ) : (
    <p className="text-[11.5px] italic text-slate-500">skipped — add profiles later from Settings → Importer/Broker</p>
  )

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <p className="text-[12.5px] font-semibold text-slate-950">Review & submit</p>
        <p className="mt-0.5 text-[11px] leading-4 text-slate-500">
          Everything below will be created on Submit. Green sections are ready; red sections must be fixed. Amber sections are optional-and-empty — Submit is still allowed.
        </p>
      </div>

      <SummaryCard
        title="Identity"
        stepKey="identity"
        status={stepComplete('identity') ? 'ok' : 'block'}
        blockers={stepBlockers('identity')}
        jumpTo={jumpTo}
      >
        {identityBody}
      </SummaryCard>

      <SummaryCard
        title="Ship From"
        stepKey="shipFrom"
        status={(stepComplete('shipFrom') && selectedShipFromWarehouseId != null) ? 'ok' : 'block'}
        blockers={stepBlockers('shipFrom')}
        jumpTo={jumpTo}
      >
        {shipFromBody}
      </SummaryCard>

      <SummaryCard
        title="Return address"
        stepKey="return"
        status={stepComplete('return') ? 'ok' : 'block'}
        blockers={stepBlockers('return')}
        jumpTo={jumpTo}
      >
        {returnBody}
      </SummaryCard>

      <SummaryCard
        title={`Carrier accounts (${carrierDrafts.length})`}
        stepKey="carriers"
        status={stepComplete('carriers') ? 'ok' : 'block'}
        blockers={stepBlockers('carriers')}
        jumpTo={jumpTo}
      >
        {carriersBody}
      </SummaryCard>

      <SummaryCard
        title={`Shipping mappings (${mappingDrafts.length})`}
        stepKey="mapping"
        status={stepComplete('mapping') ? 'ok' : 'block'}
        blockers={stepBlockers('mapping')}
        jumpTo={jumpTo}
      >
        {mappingBody}
      </SummaryCard>

      <SummaryCard
        title="Importer / Broker"
        stepKey="importerBroker"
        status={
          !importerBrokerDraft.filled
            ? 'warn'  // amber: intentionally skipped (still valid)
            : stepComplete('importerBroker') ? 'ok' : 'block'
        }
        blockers={stepBlockers('importerBroker')}
        jumpTo={jumpTo}
      >
        {importerBody}
      </SummaryCard>
    </div>
  )
}

function SummaryCard({
  title,
  stepKey,
  status,
  blockers,
  jumpTo,
  children,
}: {
  title: string
  stepKey: StepKey
  status: 'ok' | 'warn' | 'block'
  blockers: string[]
  jumpTo: (k: StepKey) => void
  children: ReactNode
}) {
  const tone =
    status === 'ok'
      ? { bar: 'border-emerald-200 bg-emerald-50/40', pill: 'bg-emerald-100 text-emerald-800', label: 'READY' }
      : status === 'warn'
        ? { bar: 'border-amber-200 bg-amber-50/40', pill: 'bg-amber-100 text-amber-800', label: 'SKIPPED' }
        : { bar: 'border-rose-300 bg-rose-50/40', pill: 'bg-rose-100 text-rose-800', label: 'NEEDS FIX' }

  return (
    <div className={`rounded-2xl border ${tone.bar} p-3`}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex items-center gap-2 text-[12px] font-semibold text-slate-950">
            {title}
            <span className={`rounded-full ${tone.pill} px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide`}>
              {tone.label}
            </span>
          </p>
          <div className="mt-1.5">
            {children}
          </div>
          {status === 'block' && blockers.length ? (
            <ul className="mt-1.5 list-disc space-y-0.5 pl-4 text-[10.5px] font-semibold text-rose-700">
              {blockers.map((b, i) => <li key={i}>{b}</li>)}
            </ul>
          ) : null}
        </div>
        <button
          type="button"
          onClick={() => jumpTo(stepKey)}
          className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-slate-700 transition hover:border-[#412d15] hover:bg-[#faf7f0] hover:text-[#412d15]"
        >
          {status === 'block' ? 'Fix' : 'Edit'} <FiChevronRight className="h-3 w-3" />
        </button>
      </div>
    </div>
  )
}
