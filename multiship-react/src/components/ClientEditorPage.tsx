import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiArrowLeft,
  FiCheck,
  FiChevronLeft,
  FiChevronRight,
  FiLoader,
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
import { formatCarrierName } from '../utils/carrierUtils'
import {
  checkClientCodeAvailable,
  hasErrors,
  validateAddress,
  validateClientCode,
  validateEmail,
  validateName,
  validatePhone,
  type AddressLike,
} from '../utils/clientValidation'
import { IdentityStep } from './ClientEditorSteps/IdentityStep'
import { ShipFromStep } from './ClientEditorSteps/ShipFromStep'
import { ReturnStep } from './ClientEditorSteps/ReturnStep'
import { SummaryStep } from './ClientEditorSteps/SummaryStep'
import { CarrierDraftStep } from './ClientEditorSteps/CarrierDraftStep'
import { MappingDraftStep } from './ClientEditorSteps/MappingDraftStep'
import { ImporterBrokerStep } from './ClientEditorSteps/ImporterBrokerStep'
import { ImporterBrokerDraftStep } from './ClientEditorSteps/ImporterBrokerDraftStep'
import {
  emptyImporterBrokerDraft,
  importerBrokerDraftValid,
  type CarrierAccountDraft,
  type ImporterBrokerDraft,
  type MappingRuleDraft,
  type StepKey,
} from './ClientEditorSteps/_types'
// Hidden-step components (kept commented for the day they come back into the wizard):
// import ClientAllowlistTab from './modals/ClientAllowlistTab'
// import ClientDestinationsTab from './modals/ClientDestinationsTab'
// import ClientPolicyTab from './modals/ClientPolicyTab'
import ClientMarkupTab from './modals/ClientMarkupTab'
// import ClientOwnedPackagesPanel from './modals/ClientOwnedPackagesPanel'
import CarrierConnections from './CarrierConnections'
import WarehouseEditorModal from './modals/WarehouseEditorModal'
import ClientShippingMappingTab from './modals/ClientShippingMappingTab'
import { customsProfileService, type CustomsProfile } from '../api/customsProfileService'
import {
  intersectionAddressCaps,
  bindingCarriers,
  type AddressCaps,
  type CarrierCode,
} from '../utils/carrierFieldLimits'

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
  { key: 'markup',         label: 'Billing markup',           short: 'Markup',     optional: true },
  { key: 'importerBroker', label: 'Importer / Broker',        short: 'Importer',   optional: true },
  { key: 'summary',        label: 'Review & submit',          short: 'Summary' },
]

const stepIndex = (key: StepKey) => STEP_DEFS.findIndex((s) => s.key === key)

/** localStorage key for the create-mode draft — namespaced per operator so
 *  two users on the same machine don't step on each other's in-progress work. */
const draftStorageKey = () => {
  const user = (localStorage.getItem('multiship_user') || 'anonymous').trim() || 'anonymous'
  return `clientEditorDraft:${user}`
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
    // Client code is uppercase + whitespace-stripped on the wire; do it in
    // state too so the operator sees EXACTLY what's saved (and no case-only
    // / whitespace-only collisions with existing clients slip through the
    // local dup check). Prior behavior only uppercased — a pasted "  ma1885 "
    // would render as "  MA1885 " in the summary but save as "MA1885".
    const value = key === 'clientCode' ? raw.toUpperCase().replace(/\s+/g, '') : raw
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
    // Edit-mode carriers now come exclusively from the getClient response
    // in the effect above (line ~589 setAccounts(c.carrierAccounts ?? [])).
    // Prior behavior fired listClientAccounts here too — non-deterministic
    // resolution order meant the second-to-resolve overwrote the first,
    // occasionally shadowing fresh getClient data with a stale list cache.
    //
    // Create mode still needs the shared platform account book so the
    // Mapping step's "platform carrier account" picker has options to
    // source a carrier for a rule.
    if (editingCode) return
    let cancelled = false
    accountRefService.listAccounts()
      .then((a) => { if (!cancelled) setAccounts(a) })
      .catch(() => { /* picker just stays empty — non-fatal */ })
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

  /**
   * Return-address caps are DIFFERENT from ship-from caps: the return
   * address is where inbound labels come back to; the enabled carriers'
   * per-field limits may not apply (an operator may want to accept
   * returns to a broader address than they ship from). Use the loose
   * DB-column defaults (empty carrier set) rather than intersecting
   * enabled-carrier caps.
   */
  const returnAddressCaps = useMemo<AddressCaps>(
    () => intersectionAddressCaps([]),
    [],
  )

  const returnErrors = useMemo(() => {
    // Toggle on = return is "same as ship from"; no separate validation.
    if (form.returnSameAsShipFrom) return {}
    return validateAddress(form.returnAddress as AddressLike, { required: true, caps: returnAddressCaps })
  }, [form.returnSameAsShipFrom, form.returnAddress, returnAddressCaps])

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
      case 'markup':         return true
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
      case 'markup':
        // Optional in create mode — markup requires a persisted clientCode,
        // so it's only settable after the client exists (see the deferred
        // panel this step renders while !isEdit).
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
      // Sprint 52 — nudge the operator toward the Billing markup tab
      // immediately after creation when no row was seeded. Same predicate
      // as the step-nav amber badge / MarkupTab banner so all four
      // surfaces agree. New clients always land here (create path
      // doesn't seed markup) but we guard on hasBillingMarkup=false so a
      // future default-seeder wouldn't false-fire this toast.
      if (response.data.hasBillingMarkup === false) {
        notify.info(
          `Set a billing markup for ${response.data.clientCode} on the Markup tab before generating labels — MARKUP_REQUIRED_FOR_CLIENT will otherwise reject every label call for this client.`,
        )
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
          // Sprint 52 — amber warning on the "Billing markup" step when
          // the client is loaded but has no client_billing_markup row.
          // Not "invalid" (nothing was mis-entered), not "done" (row
          // doesn't exist yet) — a fourth "warn" state signals a fixable
          // production gap without escalating to the rose/red palette.
          const warn = s.key === 'markup' && isEdit && client != null && client.hasBillingMarkup === false
          const done = !invalid && !warn && (isEdit
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
            : warn
              ? 'No billing markup saved — labels for this client will be refused until you Save one on this tab.'
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
              data-testid={`step-pill-${s.key}`}
              className={`group inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11.5px] font-semibold transition ${
                active
                  ? 'border-[#1f150c] bg-[#1f150c] text-white'
                  : invalid
                    ? 'border-rose-300 bg-rose-50 text-rose-700 hover:bg-rose-100'
                    : warn
                      ? 'border-amber-300 bg-amber-50 text-amber-800 hover:bg-amber-100'
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
                      : warn
                        ? 'bg-amber-500 text-white'
                        : done
                          ? 'bg-emerald-500 text-white'
                          : 'bg-slate-100 text-slate-500'
                }`}
              >
                {invalid ? '!' : warn ? '⚠' : done && !active ? <FiCheck className="h-2.5 w-2.5" /> : i + 1}
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

        {/* Billing markup — optional, edit-mode only. The endpoint is keyed
            by clientCode, so a not-yet-created client has nowhere to save
            this to; create mode shows a placeholder pointing back here. */}
        {activeStep === 'markup' && (client ? (
          <ClientMarkupTab clientCode={client.clientCode} />
        ) : (
          <div className="flex-1 overflow-y-auto px-5 py-4" role="tabpanel" id="client-editor-panel-markup">
            <h4 className="text-[12.5px] font-semibold text-slate-950">Billing markup</h4>
            <p className="mt-2 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-[11.5px] text-slate-500">
              Save this client first — billing markup is set from this same Edit client page once the client exists.
            </p>
          </div>
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

// ===== Step components (F5-B extracted: Identity / ShipFrom / Return /
//       AddressGrid / Summary / SummaryCard now live in ClientEditorSteps/*.tsx.
//       Still in-file pending F5-B2: CarrierDraftStep, MappingDraftStep,
//       ImporterBrokerStep, ImporterBrokerDraftStep) =====


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


/* ============================================================================
 * Summary step (create mode only)
 * Per-section digest cards with green / amber / red status and a Fix link
 * that jumps back to the offending step.
 * ==========================================================================*/

