import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiAlertCircle,
  FiArrowLeft,
  FiArrowRight,
  FiCheck,
  FiChevronLeft,
  FiChevronRight,
  FiHome,
  FiLoader,
  FiPlus,
  FiStar,
} from 'react-icons/fi'
import { ApiError } from '../api/apiClient'
import { clientService, type Address, type Client, type ClientUpsertPayload } from '../api/clientService'
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
  validateEmail,
  validateName,
  validatePhone,
  type AddressLike,
} from '../utils/clientValidation'
import CarrierLogo from './workspace/CarrierLogo'
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

/** Steps that count as "must complete before Create client fires" in create
 *  mode. importerBroker is intentionally excluded — it's optional per the
 *  onboarding brief. The Create-blockers check reads this list. */
const MANDATORY_STEPS: ReadonlySet<StepKey> = new Set([
  'identity', 'shipFrom', 'return', 'carriers', 'mapping',
])

const STEP_DEFS: ReadonlyArray<{ key: StepKey; label: string; short: string; optional?: boolean }> = [
  { key: 'identity',       label: 'Identity',                 short: 'Identity' },
  { key: 'shipFrom',       label: 'Ship From',                short: 'Ship From' },
  { key: 'return',         label: 'Return address',           short: 'Return' },
  { key: 'carriers',       label: 'Carrier accounts',         short: 'Carriers' },
  { key: 'mapping',        label: 'Shipping service mapping', short: 'Mapping' },
  { key: 'importerBroker', label: 'Importer / Broker',        short: 'Importer',   optional: true },
]

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
  accountForm: {
    carrierCode: string
    accountNumber: string
    clientId: string
    clientSecret: string
    environment: CarrierEnvironment
    clientDefault: boolean
  }
  showAccountForm: boolean
  selectedShipFromWarehouseId: number | null
  visitedSteps: StepKey[]
  activeStep: StepKey
  carrierDrafts?: CarrierAccountDraft[]
  mappingDrafts?: MappingRuleDraft[]
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
  const [showAccountForm, setShowAccountForm] = useState(draft?.showAccountForm ?? false)
  const [accountForm, setAccountForm] = useState(
    draft?.accountForm ?? {
      carrierCode: 'UPS',
      accountNumber: '',
      clientId: '',
      clientSecret: '',
      environment: 'SANDBOX' as CarrierEnvironment,
      clientDefault: true,
    },
  )

  /** Create-mode carrier account drafts. Committed after Create client. */
  const [carrierDrafts, setCarrierDrafts] = useState<CarrierAccountDraft[]>(
    () => draft?.carrierDrafts ?? [],
  )
  /** Create-mode mapping rule drafts. Committed after Create client. */
  const [mappingDrafts, setMappingDrafts] = useState<MappingRuleDraft[]>(
    () => draft?.mappingDrafts ?? [],
  )
  const nextDraftId = useRef(
    Math.max(
      0,
      ...((draft?.carrierDrafts ?? []).map((d) => d.id)),
      ...((draft?.mappingDrafts ?? []).map((d) => d.id)),
    ) + 1,
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
      notify.error(error instanceof Error ? error.message : 'Failed to load warehouses.')
    } finally {
      setPickWarehousesLoading(false)
    }
  }

  useEffect(() => {
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
        })
        setAccounts(c.carrierAccounts ?? [])
      })
      .catch((err: unknown) => {
        if (cancelled) return
        notify.error(err instanceof Error ? err.message : 'Failed to load client.')
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
    if (!editingCode) return
    let cancelled = false
    clientService.listClientAccounts(editingCode)
      .then((a) => { if (!cancelled) setAccounts(a) })
      .catch(() => { /* list is cosmetic */ })
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
        accountForm,
        showAccountForm,
        selectedShipFromWarehouseId,
        visitedSteps: [...visitedSteps],
        activeStep,
        carrierDrafts,
        mappingDrafts,
      }
      localStorage.setItem(draftStorageKey(), JSON.stringify(snapshot))
    } catch { /* localStorage full / disabled — not fatal */ }
  }, [isEdit, form, accountForm, showAccountForm, selectedShipFromWarehouseId, visitedSteps, activeStep, carrierDrafts, mappingDrafts])

  // ===== Live duplicate-code check =====
  // Debounced: fires 500ms after the last keystroke. Skips in edit mode
  // (client code is immutable there) and when the field-shape validator is
  // already unhappy — don't spam the API with obviously-bad codes.
  useEffect(() => {
    if (isEdit) return
    const trimmed = (form.clientCode || '').trim().toUpperCase()
    if (!trimmed || validateClientCode(trimmed) != null) {
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
    () => validateAddress(form.shipFrom as AddressLike, { required: true }),
    [form.shipFrom],
  )

  const returnErrors = useMemo(() => {
    // Toggle on = return is "same as ship from"; no separate validation.
    if (form.returnSameAsShipFrom) return {}
    return validateAddress(form.returnAddress as AddressLike, { required: true })
  }, [form.returnSameAsShipFrom, form.returnAddress])

  const accountErrors = useMemo(() => {
    // In create mode when the operator opens the optional carrier form, all
    // three secrets must be filled. Errors are only shown after touch.
    if (isEdit || !showAccountForm) return {}
    const e: Record<string, string | null> = {}
    if (!accountForm.accountNumber.trim()) e.accountNumber = 'Account number is required.'
    if (!accountForm.clientId.trim()) e.clientId = 'Client ID is required.'
    if (!accountForm.clientSecret.trim()) e.clientSecret = 'Client secret is required.'
    return e
  }, [isEdit, showAccountForm, accountForm.accountNumber, accountForm.clientId, accountForm.clientSecret])

  const stepValid = (key: StepKey): boolean => {
    switch (key) {
      case 'identity': return !hasErrors(identityErrors) && !hasErrors(accountErrors)
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
        if (showAccountForm) touchAllIn('account', ['accountNumber', 'clientId', 'clientSecret'])
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
   * one draft carrier account OR filled the optional inline account form on
   * the Identity step — both commit through the same accountRefService call
   * on Create client, so either one satisfies the "≥1 carrier account" rule.
   */
  const carriersStepComplete =
    carrierDrafts.length > 0
    || (showAccountForm
        && !!accountForm.accountNumber.trim()
        && !!accountForm.clientId.trim()
        && !!accountForm.clientSecret.trim())

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

  /**
   * "Has this step's data been provided?" — used for both the step-rail's
   *  accessibility gate and the Next-button's per-step validity check. In
   *  edit mode every step counts as complete (all data comes from the server
   *  and the operator is free-navigating). Importer/Broker is optional so
   *  it always reports complete — nothing depends on it. */
  const stepComplete = (key: StepKey): boolean => {
    if (isEdit) return true
    switch (key) {
      case 'identity':       return stepValid('identity')
      case 'shipFrom':       return stepValid('shipFrom') && selectedShipFromWarehouseId != null
      case 'return':         return stepValid('return')
      case 'carriers':       return carriersStepComplete
      case 'mapping':        return mappingStepComplete
      case 'importerBroker': return true
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
    return reasons
  }, [
    isEdit, allStepsVisited, visitedSteps, selectedShipFromWarehouseId,
    carriersStepComplete, mappingStepComplete,
    // stepValid is derived from other state; the specific keys it reads are
    // in the deps via visitedSteps + form (closed over).
    // eslint-disable-next-line react-hooks/exhaustive-deps
    form, showAccountForm, accountForm,
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
      }
      const response = await clientService.createClient(payload)
      // Optional carrier account attached during create.
      if (!isEdit && showAccountForm) {
        try {
          await accountRefService.upsertAccount({
            accountNumber: accountForm.accountNumber.trim(),
            carrierCode: accountForm.carrierCode,
            clientId: accountForm.clientId.trim(),
            clientSecret: accountForm.clientSecret.trim(),
            environment: accountForm.environment,
            customerNo: response.data.clientCode,
            clientDefault: accountForm.clientDefault,
          })
          notify.success(
            `Client ${response.data.clientCode} created with its ${formatCarrierName(accountForm.carrierCode)} account${
              accountForm.clientDefault ? ' (default)' : ''
            }.`,
          )
        } catch (accountError) {
          notify.error(
            `Client created, but the carrier account failed: ${
              accountError instanceof Error ? accountError.message : 'unknown error'
            }. Add it from the Carriers step.`,
          )
        }
      } else {
        notify.success(`Client ${response.data.clientCode} created.`)
      }

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
      // Clear the draft — the wizard's committed state is now the source of
      // truth. Any subsequent /clients/new visit starts fresh.
      try { localStorage.removeItem(draftStorageKey()) } catch { /* not fatal */ }
      navigate(`/settings/clients/${encodeURIComponent(response.data.clientCode)}`, {
        replace: true,
        state: { advanceTo: 'carriers' },
      })
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === 'CLIENT_CODE_TAKEN') {
        notify.error(`Client code ${form.clientCode.toUpperCase()} is already registered.`)
      } else {
        notify.error(error instanceof Error ? error.message : 'Failed to save the client.')
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
      notify.error(error instanceof Error ? error.message : 'Failed to save the client.')
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
            accountForm={accountForm}
            setAccountForm={setAccountForm}
            accountErrors={accountErrors}
            showAccountForm={showAccountForm}
            setShowAccountForm={setShowAccountForm}
            accounts={accounts}
            onManageCarriers={() => setActiveStep('carriers')}
          />
        ) : null}

        {activeStep === 'shipFrom' ? (
          <ShipFromStep
            warehouses={visibleShipFromWarehouses}
            loading={pickWarehousesLoading}
            selectedId={selectedShipFromWarehouseId}
            onPick={pickShipFromWarehouse}
            onAddWarehouseClick={() => setShowShipFromAddWarehouse(true)}
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
            warehouse={null}
            onClose={() => setShowShipFromAddWarehouse(false)}
            onSaved={async (saved) => {
              setShowShipFromAddWarehouse(false)
              await loadPickWarehouses()
              // Auto-select the freshly-created warehouse so the operator
              // doesn't have to hunt for it in the picker.
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
            addDraft={(d) => {
              setMappingDrafts((cur) => [
                ...cur,
                { ...d, id: nextDraftId.current++ },
              ])
            }}
            removeDraft={(id) => setMappingDrafts((cur) => cur.filter((d) => d.id !== id))}
          />
        ))}

        {/* Importer / Broker — optional step. Live editor in edit mode via
            the existing CustomsProfileModal; deferred in create mode since
            profiles hang off the persisted client row. Skipping is fine — the
            operator can add profiles from Settings → Importer/Broker later. */}
        {activeStep === 'importerBroker' && (client ? (
          <ImporterBrokerStep clientCode={client.clientCode} clientName={client.name} />
        ) : (
          <div className="px-4 py-3">
            <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 p-5 text-center">
              <p className="text-[12.5px] font-semibold text-slate-800">Importer / Broker (optional)</p>
              <p className="mx-auto mt-1 max-w-md text-[11px] leading-4 text-slate-500">
                Importer + broker profiles for customs are configured against a persisted client.
                You can safely skip this step — click <span className="font-semibold">Create client</span>
                {' '}below and add profiles from Settings → Importer/Broker (or come back to this step
                in edit mode) whenever you're ready.
              </p>
            </div>
          </div>
        ))}
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
          {!isEdit && (activeStep === 'mapping' || activeStep === 'importerBroker') ? (
            <button
              type="button"
              onClick={() => void handleCreate()}
              disabled={saving || !readyToCreate}
              title={
                readyToCreate
                  ? undefined
                  : createBlockers.length
                    ? `Cannot create client yet:\n  • ${createBlockers.join('\n  • ')}`
                    : 'Cannot create client yet.'
              }
              className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
            >
              {saving ? 'Creating…' : 'Create client'}
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
  accountForm,
  setAccountForm,
  accountErrors,
  showAccountForm,
  setShowAccountForm,
  accounts,
  onManageCarriers,
}: {
  form: ClientUpsertPayload
  isEdit: boolean
  errors: Record<string, string | null>
  checkingCode: boolean
  touched: Record<string, boolean>
  markTouched: (key: string) => void
  set: (key: keyof ClientUpsertPayload) => (e: { target: { value: string } }) => void
  accountForm: {
    carrierCode: string
    accountNumber: string
    clientId: string
    clientSecret: string
    environment: CarrierEnvironment
    clientDefault: boolean
  }
  setAccountForm: React.Dispatch<React.SetStateAction<{
    carrierCode: string
    accountNumber: string
    clientId: string
    clientSecret: string
    environment: CarrierEnvironment
    clientDefault: boolean
  }>>
  accountErrors: Record<string, string | null>
  showAccountForm: boolean
  setShowAccountForm: (v: boolean) => void
  accounts: CarrierAccountRef[]
  onManageCarriers: () => void
}) {
  const err = (k: string) => (touched[`identity.${k}`] ? errors[k] || null : null)
  const aErr = (k: string) => (touched[`account.${k}`] ? accountErrors[k] || null : null)
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

      {/* Carrier accounts — shown as chips in edit mode with a link to the
          Carriers step; in create mode an optional inline account form. */}
      {isEdit && accounts.length ? (
        <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
          <div className="flex items-center justify-between">
            <h4 className="text-[12px] font-semibold text-slate-950">Carrier accounts</h4>
            <button
              type="button"
              onClick={onManageCarriers}
              className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10.5px] font-semibold text-[#412d15] transition hover:bg-slate-50"
            >
              Manage in Carriers step
              <FiArrowRight className="h-3 w-3" />
            </button>
          </div>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {accounts.map((account) => (
              <div key={account.id} className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2 py-1">
                <CarrierLogo carrierId={account.carrierCode} size={14} className="rounded-sm" />
                <span className="text-[11px] font-semibold text-slate-800">
                  {account.accountName || formatCarrierName(account.carrierCode)} · {account.accountNumber}
                </span>
                {account.clientDefault ? <FiStar className="h-3 w-3 text-[#412d15]" /> : null}
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {!isEdit ? (
        <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
          <div className="flex items-center justify-between gap-3">
            <div>
              <h4 className="text-[12px] font-semibold text-slate-950">Carrier account</h4>
              <p className="text-[10.5px] text-slate-500">
                Optional — add it now and this client's orders ship automatically.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setShowAccountForm(!showAccountForm)}
              className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10.5px] font-semibold text-slate-700 transition hover:bg-slate-50"
            >
              <FiPlus className={`h-3 w-3 transition ${showAccountForm ? 'rotate-45' : ''}`} />
              {showAccountForm ? 'Remove' : 'Add account'}
            </button>
          </div>

          {showAccountForm ? (
            <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-3">
              <Field label="Carrier" required>
                <Select
                  value={accountForm.carrierCode}
                  onChange={(e) => setAccountForm((cur) => ({ ...cur, carrierCode: e.target.value }))}
                >
                  {carrierOptions.map((option) => (
                    <option key={option.code} value={option.code}>{option.label}</option>
                  ))}
                </Select>
              </Field>
              <Field label="Account number" required error={aErr('accountNumber')}>
                <input
                  value={accountForm.accountNumber}
                  onChange={(e) => setAccountForm((cur) => ({ ...cur, accountNumber: e.target.value }))}
                  onBlur={() => markTouched('account.accountNumber')}
                  maxLength={100}
                  autoComplete="off"
                  spellCheck={false}
                  aria-invalid={aErr('accountNumber') ? true : undefined}
                  className={`${inputBaseClass} ${aErr('accountNumber') ? inputErr : inputOk}`}
                />
              </Field>
              <Field label="Client ID" required error={aErr('clientId')}>
                <input
                  value={accountForm.clientId}
                  onChange={(e) => setAccountForm((cur) => ({ ...cur, clientId: e.target.value }))}
                  onBlur={() => markTouched('account.clientId')}
                  maxLength={255}
                  autoComplete="off"
                  spellCheck={false}
                  aria-invalid={aErr('clientId') ? true : undefined}
                  className={`${inputBaseClass} ${aErr('clientId') ? inputErr : inputOk}`}
                />
              </Field>
              <Field label="Client Secret" required error={aErr('clientSecret')}>
                <input
                  type="password"
                  value={accountForm.clientSecret}
                  onChange={(e) => setAccountForm((cur) => ({ ...cur, clientSecret: e.target.value }))}
                  onBlur={() => markTouched('account.clientSecret')}
                  maxLength={255}
                  autoComplete="new-password"
                  aria-invalid={aErr('clientSecret') ? true : undefined}
                  className={`${inputBaseClass} ${aErr('clientSecret') ? inputErr : inputOk}`}
                />
              </Field>
              <Field label="Environment">
                <Select
                  value={accountForm.environment}
                  onChange={(e) => setAccountForm((cur) => ({ ...cur, environment: e.target.value as CarrierEnvironment }))}
                >
                  {carrierEnvironmentOptions.map((option) => (
                    <option key={option} value={option}>{option}</option>
                  ))}
                </Select>
              </Field>
              <label className="flex items-end gap-2 pb-2 text-[12px] font-semibold text-slate-700">
                <input
                  type="checkbox"
                  checked={accountForm.clientDefault}
                  onChange={(e) => setAccountForm((cur) => ({ ...cur, clientDefault: e.target.checked }))}
                  className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
                />
                Default account
              </label>
            </div>
          ) : null}
        </div>
      ) : null}
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

  const canSave = f.accountNumber.trim() && f.clientId.trim() && f.clientSecret.trim()

  const save = () => {
    if (!canSave) return
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
                <option value="UPS">UPS</option>
                <option value="FEDEX">FedEx</option>
                <option value="USPS">USPS</option>
              </Select>
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Account number *</span>
              <input
                value={f.accountNumber}
                onChange={(e) => setF((c) => ({ ...c, accountNumber: e.target.value }))}
                maxLength={100}
                autoComplete="off"
                className={`${inputBaseClass} ${inputOk}`}
              />
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
                maxLength={255}
                autoComplete="off"
                spellCheck={false}
                className={`${inputBaseClass} ${inputOk}`}
              />
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Client Secret *</span>
              <input
                type="password"
                value={f.clientSecret}
                onChange={(e) => setF((c) => ({ ...c, clientSecret: e.target.value }))}
                maxLength={255}
                autoComplete="new-password"
                className={`${inputBaseClass} ${inputOk}`}
              />
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
              onClick={() => setAdding(false)}
              className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-100"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={save}
              disabled={!canSave}
              className="rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
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
}: {
  drafts: MappingRuleDraft[]
  services: ShippingServiceItem[]
  addDraft: (d: Omit<MappingRuleDraft, 'id'>) => void
  removeDraft: (id: number) => void
}) {
  const [adding, setAdding] = useState(false)
  const [shipviaCd, setShipviaCd] = useState('')
  const [serviceId, setServiceId] = useState('')

  const canSave = !!shipviaCd.trim() && !!serviceId
  const svcById = useMemo(() => new Map(services.map((s) => [s.id, s])), [services])

  const save = () => {
    if (!canSave) return
    addDraft({ shipviaCd: shipviaCd.trim(), serviceId: Number(serviceId) })
    setShipviaCd('')
    setServiceId('')
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
          <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Order Ship Via *</span>
              <input
                value={shipviaCd}
                onChange={(e) => setShipviaCd(e.target.value.toUpperCase())}
                placeholder="e.g. P80"
                className={`${inputBaseClass} ${inputOk} font-mono`}
              />
            </label>
            <label className="block">
              <span className="mb-0.5 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Carrier Ship Via *</span>
              <Select value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
                <option value="">Pick a carrier service…</option>
                {services.filter((s) => s.enabled).map((s) => (
                  <option key={s.id} value={s.id}>
                    {formatCarrierName(s.carrier)} — {s.name}
                  </option>
                ))}
              </Select>
            </label>
          </div>
          <div className="mt-3 flex items-center justify-end gap-2 border-t border-slate-100 pt-3">
            <button
              type="button"
              onClick={() => setAdding(false)}
              className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-100"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={save}
              disabled={!canSave}
              className="rounded-xl bg-[#1f150c] px-4 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
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
}: {
  block: Address
  same: boolean
  setSame: (next: boolean) => void
  errors: Partial<Record<keyof AddressLike, string>>
  touched: Record<string, boolean>
  markTouched: (key: string) => void
  setAddr: (block: 'shipFrom' | 'returnAddress', key: keyof Address) => (e: { target: { value: string } }) => void
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
}: {
  block: Address
  addressKey: 'shipFrom' | 'returnAddress'
  err: (k: keyof AddressLike) => string | null
  markTouched: (key: string) => void
  setAddr: (block: 'shipFrom' | 'returnAddress', key: keyof Address) => (e: { target: { value: string } }) => void
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
  return (
    <div className="grid grid-cols-3 gap-2">
      <div className="col-span-3">
        <Field label="Attention / company" required error={err('name')}>
          <input
            value={a.name ?? ''}
            onChange={setAddr(addressKey, 'name')}
            onBlur={() => markTouched(`${addressKey}.name`)}
            className={cls('name')}
            placeholder="Warehouse / contact name"
            maxLength={FIELD_LIMITS.addr.name}
            autoComplete="organization"
            aria-invalid={err('name') ? true : undefined}
          />
        </Field>
      </div>
      <div className="col-span-2">
        <Field label="Street address" required error={err('line1')}>
          <input
            value={a.line1 ?? ''}
            onChange={setAddr(addressKey, 'line1')}
            onBlur={() => markTouched(`${addressKey}.line1`)}
            className={cls('line1')}
            placeholder="123 Industrial Blvd"
            maxLength={FIELD_LIMITS.addr.line1}
            autoComplete="address-line1"
            aria-invalid={err('line1') ? true : undefined}
          />
        </Field>
      </div>
      <Field label="Suite / unit" error={err('line2')}>
        <input
          value={a.line2 ?? ''}
          onChange={setAddr(addressKey, 'line2')}
          onBlur={() => markTouched(`${addressKey}.line2`)}
          className={cls('line2')}
          placeholder="Suite 400"
          maxLength={FIELD_LIMITS.addr.line2}
          autoComplete="address-line2"
          aria-invalid={err('line2') ? true : undefined}
        />
      </Field>
      <Field label="City" required error={err('city')}>
        <input
          value={a.city ?? ''}
          onChange={setAddr(addressKey, 'city')}
          onBlur={() => markTouched(`${addressKey}.city`)}
          className={cls('city')}
          placeholder="Chicago"
          maxLength={FIELD_LIMITS.addr.city}
          autoComplete="address-level2"
          aria-invalid={err('city') ? true : undefined}
        />
      </Field>
      <Field label="State" required error={err('state')}>
        <input
          value={a.state ?? ''}
          onChange={setAddr(addressKey, 'state')}
          onBlur={() => markTouched(`${addressKey}.state`)}
          className={cls('state')}
          placeholder="IL"
          maxLength={FIELD_LIMITS.addr.state}
          autoComplete="address-level1"
          aria-invalid={err('state') ? true : undefined}
        />
      </Field>
      <Field label="Zip" required error={err('zip')} hint={zipHint}>
        <input
          value={a.zip ?? ''}
          onChange={setAddr(addressKey, 'zip')}
          onBlur={() => markTouched(`${addressKey}.zip`)}
          className={cls('zip')}
          placeholder="60601"
          maxLength={FIELD_LIMITS.addr.zip}
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
      <Field label="Phone" error={err('phone')}>
        <input
          type="tel"
          value={a.phone ?? ''}
          onChange={setAddr(addressKey, 'phone')}
          onBlur={() => markTouched(`${addressKey}.phone`)}
          className={cls('phone')}
          placeholder="+1 555-123-4567"
          maxLength={FIELD_LIMITS.addr.phone}
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
      notify.error(error instanceof Error ? error.message : 'Failed to load importer / broker profiles.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
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
      notify.error(error instanceof Error ? error.message : 'Failed to remove the profile.')
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
