import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  FiArrowLeft,
  FiArrowRight,
  FiCheck,
  FiChevronRight,
  FiFlag,
  FiHome,
  FiPackage,
  FiSkipForward,
  FiTruck,
  FiUser,
  FiX,
} from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { ApiError } from '../../api/apiClient'
import {
  clientService,
  type Address,
  type Client,
  type ClientUpsertPayload,
} from '../../api/clientService'
import { accountRefService, type CarrierAccountRef } from '../../api/accountRefService'
import {
  warehouseService,
  clientWarehouseService,
  type Warehouse,
} from '../../api/warehouseService'
import {
  shippingConfigService,
  type ShippingServiceItem,
  type PackagePreset,
} from '../../api/shippingConfigService'
import { clientAllowedServicesService } from '../../api/clientCatalogService'
import { clientCodeMapService, type ClientCodeMap, type CodeMapKind } from '../../api/clientCodeMapService'
import { formatCarrierName } from '../../utils/carrierUtils'
import { COUNTRIES, REGIONS } from '../../utils/countries'
import { FiEdit3, FiPlus, FiTrash2 } from 'react-icons/fi'
import { carrierEnvironmentOptions, type CarrierEnvironment } from '../../utils/carrierUtils'
import CountrySelect from '../workspace/CountrySelect'
import Select from '../workspace/Select'

/**
 * Client onboarding wizard — 7 steps, skip on every step past the
 * first. Commits are incremental: after step 1 the client row exists,
 * each subsequent step writes its own bit if the user provides data,
 * or silently advances on Skip. Steps 6 & 7 populate the per-client
 * ERP alias tables (SHIPVIA + SERVICE + PACKAGE) so incoming orders
 * translate raw ERP codes into platform IDs on arrival.
 *
 * Anything not covered here (allowed packages, destinations, policy,
 * markup, platform-wide ship-method rules) stays on the tabbed Edit
 * modal for post-onboarding config.
 */

type StepId = 1 | 2 | 3 | 4 | 5 | 6 | 7
type StepStatus = 'pending' | 'active' | 'done' | 'skipped'

interface StepMeta {
  id: StepId
  label: string
  hint: string
  icon: React.ReactNode
}

const STEPS: StepMeta[] = [
  { id: 1, label: 'Identity',       hint: 'Code, name, contact',                          icon: <FiUser /> },
  { id: 2, label: 'Ship-from',      hint: "Where the client's labels originate",           icon: <FiHome /> },
  { id: 3, label: 'Return',         hint: 'Where undeliverable parcels go',                icon: <FiFlag /> },
  { id: 4, label: 'Carrier',        hint: 'Carrier accounts (one or many)',                icon: <FiTruck /> },
  { id: 5, label: 'Warehouse',      hint: 'First warehouse + allowed carriers',            icon: <FiPackage /> },
  { id: 6, label: 'Service aliases',hint: 'ERP shipvia / service codes → platform service',icon: <FiEdit3 /> },
  { id: 7, label: 'Package aliases',hint: 'ERP package codes → platform preset',           icon: <FiPackage /> },
]

const emptyAddress = (): Address => ({
  name: '', line1: '', line2: '', city: '', state: '', zip: '', country: 'US', phone: '',
})

interface Props {
  onClose: () => void
  onFinished: (client: Client) => void
}

type WarehouseChoice = 'reuse' | 'existing' | 'new'

/** Add-form state for an alias row (SHIPVIA / SERVICE / PACKAGE). */
interface AliasDraft {
  erpCode: string
  targetId: string
  /** Blank = "any". Country wins over region when both are set. */
  destCountry: string
  destRegion: string
}
const emptyAliasDraft = (): AliasDraft => ({
  erpCode: '', targetId: '', destCountry: '', destRegion: '',
})

export default function ClientOnboardingWizard({ onClose, onFinished }: Props) {
  const [step, setStep] = useState<StepId>(1)
  const [statuses, setStatuses] = useState<Record<StepId, StepStatus>>({
    1: 'active', 2: 'pending', 3: 'pending', 4: 'pending', 5: 'pending', 6: 'pending', 7: 'pending',
  })
  const [saving, setSaving] = useState(false)

  // ===== Step 1 — Identity =====
  const [identity, setIdentity] = useState({ clientCode: '', name: '', email: '', phone: '' })

  // ===== Step 2 — Ship-from =====
  const [shipFrom, setShipFrom] = useState<Address>(emptyAddress())

  // ===== Step 3 — Return =====
  const [returnSameAsShipFrom, setReturnSameAsShipFrom] = useState(true)
  const [returnAddress, setReturnAddress] = useState<Address>(emptyAddress())

  // ===== Step 4 — Carrier accounts (one or many) =====
  // Accounts already saved this session — populated as the user adds each one.
  const [addedAccounts, setAddedAccounts] = useState<CarrierAccountRef[]>([])
  // Inline add-form for the next account. Blank means "no form open"; when
  // there are zero saved accounts we start with the form pre-opened.
  const [carrierForm, setCarrierForm] = useState<{
    open: boolean
    carrierCode: string
    accountNumber: string
    accountName: string
    clientId: string
    clientSecret: string
    environment: CarrierEnvironment
  }>({
    open: true,
    carrierCode: 'UPS',
    accountNumber: '',
    accountName: '',
    clientId: '',
    clientSecret: '',
    environment: 'SANDBOX',
  })
  const [addingAccount, setAddingAccount] = useState(false)

  // ===== Step 5 — Warehouse + allowlist =====
  const [warehouseChoice, setWarehouseChoice] = useState<WarehouseChoice>('reuse')
  const [existingWarehouses, setExistingWarehouses] = useState<Warehouse[]>([])
  const [selectedExistingCode, setSelectedExistingCode] = useState('')
  const [newWarehouse, setNewWarehouse] = useState<{ code: string; name: string; address: Address }>({
    code: '',
    name: '',
    address: emptyAddress(),
  })
  const [allowedCarriers, setAllowedCarriers] = useState<Set<string>>(
    new Set(['UPS', 'FEDEX', 'USPS', 'DHL']),
  )
  const [catalog, setCatalog] = useState<ShippingServiceItem[]>([])
  const [presets, setPresets] = useState<PackagePreset[]>([])

  // ===== Steps 6 & 7 — ERP code aliases (per-client) =====
  //  Step 6: SHIPVIA + SERVICE lists (both target a ShippingService id).
  //  Step 7: PACKAGE list (targets a PackagePreset id).
  const [shipviaMaps, setShipviaMaps] = useState<ClientCodeMap[]>([])
  const [serviceMaps, setServiceMaps] = useState<ClientCodeMap[]>([])
  const [packageMaps, setPackageMaps] = useState<ClientCodeMap[]>([])
  const [shipviaDraft, setShipviaDraft] = useState<AliasDraft>({ erpCode: '', targetId: '', destCountry: '', destRegion: '' })
  const [serviceDraft, setServiceDraft] = useState<AliasDraft>({ erpCode: '', targetId: '', destCountry: '', destRegion: '' })
  const [packageDraft, setPackageDraft] = useState<AliasDraft>({ erpCode: '', targetId: '', destCountry: '', destRegion: '' })
  const [addingAlias, setAddingAlias] = useState(false)

  // Server-truth client after step 1 (needed for follow-up API calls).
  const [createdClient, setCreatedClient] = useState<Client | null>(null)
  const currentClientCode = createdClient?.clientCode ?? identity.clientCode

  const firstFieldRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    firstFieldRef.current?.focus()
  }, [step])

  // Preload warehouses + shipping catalog when the user reaches step 5.
  useEffect(() => {
    if (step !== 5) return
    warehouseService
      .listWarehouses({ ownerType: 'PLATFORM', active: 'YES', size: 100 })
      .then((r) => setExistingWarehouses(r.data?.content ?? []))
      .catch(() => setExistingWarehouses([]))
    shippingConfigService
      .catalog()
      .then((c) => setCatalog(c.services ?? []))
      .catch(() => setCatalog([]))
  }, [step])

  // Steps 6 & 7 — load the catalogs (services + presets) and any already-
  // saved aliases for this client. Runs on entry to both steps so returning
  // to Step 6 after Step 7 doesn't miss a fresh row saved in Step 7.
  useEffect(() => {
    if (step !== 6 && step !== 7) return
    if (catalog.length === 0) {
      shippingConfigService
        .catalog()
        .then((c) => setCatalog(c.services ?? []))
        .catch(() => setCatalog([]))
    }
    if (presets.length === 0) {
      shippingConfigService
        .listPresets()
        .then(setPresets)
        .catch(() => setPresets([]))
    }
    if (!currentClientCode) return
    if (step === 6) {
      Promise.all([
        clientCodeMapService.list(currentClientCode, 'SHIPVIA').catch(() => ({ data: [] as ClientCodeMap[] })),
        clientCodeMapService.list(currentClientCode, 'SERVICE').catch(() => ({ data: [] as ClientCodeMap[] })),
      ]).then(([sv, sr]) => {
        setShipviaMaps(sv.data ?? [])
        setServiceMaps(sr.data ?? [])
      })
    } else {
      clientCodeMapService
        .list(currentClientCode, 'PACKAGE')
        .then((r) => setPackageMaps(r.data ?? []))
        .catch(() => setPackageMaps([]))
    }
  }, [step, currentClientCode])

  // ===== helpers =====

  const markStep = (id: StepId, status: StepStatus) =>
    setStatuses((prev) => ({ ...prev, [id]: status }))

  const advanceTo = useCallback((next: StepId) => {
    setStatuses((prev) => {
      const copy = { ...prev }
      // Anything below `next` that isn't done/skipped becomes pending; the
      // next step becomes active.
      Object.keys(copy).forEach((k) => {
        const n = Number(k) as StepId
        if (n === next) copy[n] = 'active'
        else if (copy[n] === 'active') copy[n] = 'pending'
      })
      return copy
    })
    setStep(next)
  }, [])

  const jumpBackTo = (id: StepId) => {
    if (id > step) return  // can only jump BACK via the rail
    if (id === 1) advanceTo(1)
    else advanceTo(id)
  }

  // ===== per-step commits =====

  const commitIdentity = async (): Promise<boolean> => {
    // Idempotent — if we already created the client, Next is a plain advance.
    // Editing name/email/phone after creation is done via the tabbed Edit
    // modal after onboarding; we don't try to updateClient() from step 1 so
    // the code isn't accidentally reused as a payload key.
    if (createdClient) {
      markStep(1, 'done')
      return true
    }
    if (!identity.clientCode.trim() || !identity.name.trim()) {
      notify.error('Client code and name are required.')
      return false
    }
    setSaving(true)
    try {
      const payload: ClientUpsertPayload = {
        clientCode: identity.clientCode.trim().toUpperCase(),
        name: identity.name.trim(),
        email: identity.email.trim() || undefined,
        phone: identity.phone.trim() || undefined,
      }
      const resp = await clientService.createClient(payload)
      if (!resp.data) throw new Error(resp.message || 'Could not create client.')
      setCreatedClient(resp.data)
      markStep(1, 'done')
      notify.success(`Client ${resp.data.clientCode} created.`)
      return true
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : 'Failed to create client.')
      return false
    } finally {
      setSaving(false)
    }
  }

  const commitShipFrom = async (): Promise<boolean> => {
    if (!hasAddressValue(shipFrom)) {
      notify.error('Add at least the street and city, or Skip to fill this in later.')
      return false
    }
    setSaving(true)
    try {
      const resp = await clientService.updateClient(currentClientCode, {
        clientCode: currentClientCode,
        name: createdClient?.name || identity.name,
        email: createdClient?.email ?? undefined,
        phone: createdClient?.phone ?? undefined,
        shipFrom,
        returnAddress: createdClient?.returnAddress ?? undefined,
        returnSameAsShipFrom: createdClient?.returnSameAsShipFrom ?? true,
      })
      if (resp.data) setCreatedClient(resp.data)
      markStep(2, 'done')
      return true
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : 'Failed to save ship-from address.')
      return false
    } finally {
      setSaving(false)
    }
  }

  const commitReturn = async (): Promise<boolean> => {
    if (!returnSameAsShipFrom && !hasAddressValue(returnAddress)) {
      notify.error('Return address needs a street and city, or use the same-as-ship-from toggle.')
      return false
    }
    setSaving(true)
    try {
      const resp = await clientService.updateClient(currentClientCode, {
        clientCode: currentClientCode,
        name: createdClient?.name || identity.name,
        email: createdClient?.email ?? undefined,
        phone: createdClient?.phone ?? undefined,
        shipFrom: createdClient?.shipFrom ?? shipFrom,
        returnAddress: returnSameAsShipFrom ? undefined : returnAddress,
        returnSameAsShipFrom,
      })
      if (resp.data) setCreatedClient(resp.data)
      markStep(3, 'done')
      return true
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : 'Failed to save return address.')
      return false
    } finally {
      setSaving(false)
    }
  }

  /**
   * Save the currently-open account form and append to addedAccounts. The
   * first account added becomes the client's default; subsequent ones are
   * non-default (the admin picks a new default from the Edit screen).
   */
  const addOneCarrierAccount = async (): Promise<boolean> => {
    if (!carrierForm.accountNumber.trim() || !carrierForm.clientId.trim() || !carrierForm.clientSecret.trim()) {
      notify.error('Account number, client ID, and secret are all required.')
      return false
    }
    setAddingAccount(true)
    try {
      const resp = await accountRefService.upsertAccount({
        carrierCode: carrierForm.carrierCode,
        accountNumber: carrierForm.accountNumber.trim(),
        accountName: carrierForm.accountName.trim() || undefined,
        clientId: carrierForm.clientId.trim(),
        clientSecret: carrierForm.clientSecret.trim(),
        environment: carrierForm.environment,
        customerNo: currentClientCode,
        clientDefault: addedAccounts.length === 0,
      })
      if (resp.data) {
        setAddedAccounts((cur) => [...cur, resp.data as CarrierAccountRef])
      }
      // Reset the form for the next entry but keep the form open so
      // the user can keep going.
      setCarrierForm({
        open: false,
        carrierCode: nextCarrierAfter(carrierForm.carrierCode),
        accountNumber: '',
        accountName: '',
        clientId: '',
        clientSecret: '',
        environment: 'SANDBOX',
      })
      notify.success(`${carrierForm.carrierCode} account linked to ${currentClientCode}.`)
      return true
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : 'Failed to save carrier account.')
      return false
    } finally {
      setAddingAccount(false)
    }
  }

  /**
   * Next on step 4 = advance. If the form is open with a valid entry we
   * save it first, then advance. If it's open but incomplete AND at least
   * one account exists, we just advance (partial entry is dropped silently
   * — the user made a choice to continue). If it's open but incomplete
   * and no account exists, we surface an error unless the user Skips.
   */
  const commitCarrierAccounts = async (): Promise<boolean> => {
    if (carrierForm.open) {
      const anyTyped =
        carrierForm.accountNumber.trim() || carrierForm.clientId.trim() || carrierForm.clientSecret.trim()
      const complete =
        carrierForm.accountNumber.trim() && carrierForm.clientId.trim() && carrierForm.clientSecret.trim()
      if (complete) {
        const ok = await addOneCarrierAccount()
        if (!ok) return false
      } else if (anyTyped) {
        // Partial data typed — safer to force the user to either complete
        // it, discard it, or Skip explicitly.
        notify.error('Complete the account form (or clear it) before continuing. Use Skip to move on without saving.')
        return false
      }
    }
    markStep(4, 'done')
    return true
  }

  /** Warehouse + allowlist commits happen together on Finish. */
  const commitWarehouseAndAllowlist = async (): Promise<boolean> => {
    setSaving(true)
    try {
      // ---- Warehouse ----
      if (warehouseChoice === 'reuse') {
        const useAddr = createdClient?.shipFrom ?? shipFrom
        if (hasAddressValue(useAddr)) {
          const code = generateWarehouseCode(currentClientCode)
          await warehouseService.createWarehouse({
            code,
            name: `${currentClientCode} Warehouse`,
            address: useAddr,
            ownerType: 'CLIENT',
            ownerClientCode: currentClientCode,
            active: true,
          })
          await clientWarehouseService.attach(currentClientCode, {
            warehouseCode: code, makeDefault: true,
          })
        }
      } else if (warehouseChoice === 'existing') {
        if (selectedExistingCode) {
          await clientWarehouseService.attach(currentClientCode, {
            warehouseCode: selectedExistingCode, makeDefault: true,
          })
        }
      } else if (warehouseChoice === 'new') {
        if (newWarehouse.code.trim() && newWarehouse.name.trim()) {
          await warehouseService.createWarehouse({
            code: newWarehouse.code.trim().toUpperCase(),
            name: newWarehouse.name.trim(),
            address: newWarehouse.address,
            ownerType: 'PLATFORM',
            active: true,
          })
          await clientWarehouseService.attach(currentClientCode, {
            warehouseCode: newWarehouse.code.trim().toUpperCase(), makeDefault: true,
          })
        } else {
          notify.error('A new warehouse needs a code and a name — or pick a different option.')
          setSaving(false)
          return false
        }
      }

      // ---- Allowlist ----
      const allowedSvcs = catalog.filter(
        (s) => s.enabled && allowedCarriers.has(s.carrier.toUpperCase()),
      )
      let firstDefault = true
      for (const svc of allowedSvcs) {
        try {
          await clientAllowedServicesService.allow(currentClientCode, {
            serviceId: svc.id,
            makeDefault: firstDefault,
          })
          firstDefault = false
        } catch {
          /* individual failures don't stop the wizard — flagged only in the toast. */
        }
      }

      markStep(5, 'done')
      return true
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : 'Failed to finalise onboarding.')
      return false
    } finally {
      setSaving(false)
    }
  }

  /**
   * Save the currently-typed draft (if any) for a given alias kind and
   * refresh the saved-rows list. Called from the inline add buttons on
   * Steps 6 & 7 AND from the Next dispatch as a "sweep unsaved" pass so
   * a user who typed a valid row but forgot to click Add doesn't lose it.
   */
  const saveAliasDraft = async (
    kind: CodeMapKind,
    draft: AliasDraft,
    onSuccess: (row: ClientCodeMap) => void,
    onReset: () => void,
  ): Promise<boolean> => {
    if (!draft.erpCode.trim() || !draft.targetId) return true  // nothing to save
    setAddingAlias(true)
    try {
      const resp = await clientCodeMapService.upsert(currentClientCode, kind, {
        erpCode: draft.erpCode.trim(),
        targetId: Number(draft.targetId),
        destCountry: draft.destCountry.trim() || null,
        destRegion: draft.destRegion.trim() || null,
      })
      if (resp.data) onSuccess(resp.data)
      onReset()
      return true
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : `Failed to save ${kind} alias.`)
      return false
    } finally {
      setAddingAlias(false)
    }
  }

  const commitServiceAliases = async (): Promise<boolean> => {
    // Sweep any typed-but-not-Added drafts before advancing.
    const svOk = await saveAliasDraft(
      'SHIPVIA', shipviaDraft,
      (row) => setShipviaMaps((cur) => [...cur, row]),
      () => setShipviaDraft(emptyAliasDraft()),
    )
    if (!svOk) return false
    const srOk = await saveAliasDraft(
      'SERVICE', serviceDraft,
      (row) => setServiceMaps((cur) => [...cur, row]),
      () => setServiceDraft(emptyAliasDraft()),
    )
    if (!srOk) return false
    markStep(6, 'done')
    return true
  }

  const commitPackageAliases = async (): Promise<boolean> => {
    const ok = await saveAliasDraft(
      'PACKAGE', packageDraft,
      (row) => setPackageMaps((cur) => [...cur, row]),
      () => setPackageDraft(emptyAliasDraft()),
    )
    if (!ok) return false
    markStep(7, 'done')
    return true
  }

  const removeAlias = async (
    kind: CodeMapKind,
    row: ClientCodeMap,
    onGone: () => void,
  ) => {
    try {
      await clientCodeMapService.remove(currentClientCode, kind, row.id)
      onGone()
    } catch (err) {
      notify.error(err instanceof ApiError ? err.message : `Failed to delete ${kind} alias.`)
    }
  }

  // ===== navigation =====

  const goNext = async () => {
    let ok = true
    if (step === 1) ok = await commitIdentity()
    else if (step === 2) ok = await commitShipFrom()
    else if (step === 3) ok = await commitReturn()
    else if (step === 4) ok = await commitCarrierAccounts()
    else if (step === 5) ok = await commitWarehouseAndAllowlist()
    else if (step === 6) ok = await commitServiceAliases()
    else if (step === 7) {
      ok = await commitPackageAliases()
      if (ok && createdClient) {
        onFinished(createdClient)
        onClose()
        return
      }
    }
    if (ok && step < 7) advanceTo((step + 1) as StepId)
  }

  const goSkip = () => {
    if (step === 1) return  // step 1 is never skippable
    markStep(step, 'skipped')
    if (step < 7) advanceTo((step + 1) as StepId)
    else if (createdClient) {
      onFinished(createdClient)
      onClose()
    } else {
      onClose()
    }
  }

  const goBack = () => {
    if (step > 1) advanceTo((step - 1) as StepId)
  }

  // ===== render =====

  const isLast = step === 7
  const nextLabel = isLast ? 'Finish' : 'Next'

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center">
      <div className="absolute inset-0 bg-slate-950/45 backdrop-blur-sm" onClick={onClose} />
      <div className="relative z-10 flex h-[720px] w-[1100px] max-w-[calc(100vw-32px)] max-h-[calc(100vh-32px)] overflow-hidden rounded-2xl bg-white shadow-2xl">
        {/* ==== Left rail ==== */}
        <div className="flex w-[240px] flex-col border-r border-slate-200 bg-slate-50 p-5">
          <h2 className="text-[13px] font-semibold uppercase tracking-[0.16em] text-slate-500">Onboarding</h2>
          <p className="mt-1 text-[11px] text-slate-400">Get a new client shippable in five steps.</p>
          <div className="mt-6 space-y-2">
            {STEPS.map((s) => (
              <StepRailItem
                key={s.id}
                step={s}
                status={statuses[s.id]}
                active={s.id === step}
                disabled={s.id > step}
                onClick={() => jumpBackTo(s.id)}
              />
            ))}
          </div>
          <div className="mt-auto pt-6 text-[11px] text-slate-400">
            Advanced settings (packages, destinations, policy, markup) are available from the Edit client screen after onboarding.
          </div>
        </div>

        {/* ==== Right pane ==== */}
        <div className="flex flex-1 flex-col">
          <div className="flex items-start justify-between border-b border-slate-200 px-6 py-4">
            <div>
              <p className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
                Step {step} of 5
              </p>
              <h3 className="mt-0.5 text-[17px] font-semibold text-slate-900">{STEPS[step - 1].label}</h3>
              <p className="mt-0.5 text-[12.5px] text-slate-500">{STEPS[step - 1].hint}</p>
            </div>
            <button
              type="button"
              onClick={onClose}
              className="rounded-md p-1.5 text-slate-400 hover:bg-slate-100 hover:text-slate-800"
              aria-label="Close"
            >
              <FiX />
            </button>
          </div>

          <div className="flex-1 overflow-y-auto px-6 py-5">
            {step === 1 ? (
              <IdentityStep
                identity={identity}
                setIdentity={setIdentity}
                inputRef={firstFieldRef}
                locked={Boolean(createdClient)}
              />
            ) : null}
            {step === 2 ? (
              <AddressStep
                title="Ship-from address"
                caption="This becomes the FROM block on every label."
                address={shipFrom}
                onChange={setShipFrom}
                inputRef={firstFieldRef}
              />
            ) : null}
            {step === 3 ? (
              <ReturnStep
                returnSameAsShipFrom={returnSameAsShipFrom}
                setReturnSameAsShipFrom={setReturnSameAsShipFrom}
                returnAddress={returnAddress}
                setReturnAddress={setReturnAddress}
                shipFrom={createdClient?.shipFrom ?? shipFrom}
              />
            ) : null}
            {step === 4 ? (
              <CarrierStep
                form={carrierForm}
                setForm={setCarrierForm}
                inputRef={firstFieldRef}
                clientCode={currentClientCode}
                addedAccounts={addedAccounts}
                adding={addingAccount}
                onAdd={addOneCarrierAccount}
              />
            ) : null}
            {step === 5 ? (
              <WarehouseAllowlistStep
                clientCode={currentClientCode}
                warehouseChoice={warehouseChoice}
                setWarehouseChoice={setWarehouseChoice}
                existingWarehouses={existingWarehouses}
                selectedExistingCode={selectedExistingCode}
                setSelectedExistingCode={setSelectedExistingCode}
                newWarehouse={newWarehouse}
                setNewWarehouse={setNewWarehouse}
                allowedCarriers={allowedCarriers}
                setAllowedCarriers={setAllowedCarriers}
                shipFromPreview={createdClient?.shipFrom ?? shipFrom}
              />
            ) : null}
            {step === 6 ? (
              <ServiceAliasStep
                clientCode={currentClientCode}
                carriersInScope={carriersInScope(addedAccounts, allowedCarriers)}
                services={catalog}
                shipviaMaps={shipviaMaps}
                serviceMaps={serviceMaps}
                shipviaDraft={shipviaDraft}
                setShipviaDraft={setShipviaDraft}
                serviceDraft={serviceDraft}
                setServiceDraft={setServiceDraft}
                adding={addingAlias}
                onAdd={(kind) => {
                  if (kind === 'SHIPVIA') {
                    return saveAliasDraft(
                      'SHIPVIA', shipviaDraft,
                      (row) => setShipviaMaps((cur) => [...cur, row]),
                      () => setShipviaDraft(emptyAliasDraft()),
                    )
                  }
                  return saveAliasDraft(
                    'SERVICE', serviceDraft,
                    (row) => setServiceMaps((cur) => [...cur, row]),
                    () => setServiceDraft(emptyAliasDraft()),
                  )
                }}
                onRemove={(kind, row) => {
                  if (kind === 'SHIPVIA') {
                    return removeAlias('SHIPVIA', row, () =>
                      setShipviaMaps((cur) => cur.filter((r) => r.id !== row.id)))
                  }
                  return removeAlias('SERVICE', row, () =>
                    setServiceMaps((cur) => cur.filter((r) => r.id !== row.id)))
                }}
              />
            ) : null}
            {step === 7 ? (
              <PackageAliasStep
                clientCode={currentClientCode}
                carriersInScope={carriersInScope(addedAccounts, allowedCarriers)}
                presets={presets}
                packageMaps={packageMaps}
                packageDraft={packageDraft}
                setPackageDraft={setPackageDraft}
                adding={addingAlias}
                onAdd={() =>
                  saveAliasDraft(
                    'PACKAGE', packageDraft,
                    (row) => setPackageMaps((cur) => [...cur, row]),
                    () => setPackageDraft(emptyAliasDraft()),
                  )
                }
                onRemove={(row) =>
                  removeAlias('PACKAGE', row, () =>
                    setPackageMaps((cur) => cur.filter((r) => r.id !== row.id)))
                }
              />
            ) : null}
          </div>

          {/* Footer */}
          <div className="flex items-center justify-between gap-2 border-t border-slate-200 px-6 py-3">
            <button
              type="button"
              onClick={goBack}
              disabled={step === 1 || saving}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-40"
            >
              <FiArrowLeft /> Back
            </button>

            <div className="flex items-center gap-2">
              {step > 1 ? (
                <button
                  type="button"
                  onClick={goSkip}
                  disabled={saving}
                  className="inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 text-[12.5px] font-semibold text-slate-500 hover:bg-slate-100 disabled:opacity-40"
                  title="Save what you've entered and move on"
                >
                  <FiSkipForward /> Skip
                </button>
              ) : null}
              <button
                type="button"
                onClick={goNext}
                disabled={saving}
                className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-4 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50"
              >
                {saving ? 'Saving…' : nextLabel}
                {isLast ? <FiCheck /> : <FiArrowRight />}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

// ==========================================================================
// Rail
// ==========================================================================

function StepRailItem({
  step,
  status,
  active,
  disabled,
  onClick,
}: {
  step: StepMeta
  status: StepStatus
  active: boolean
  disabled: boolean
  onClick: () => void
}) {
  const bg =
    status === 'done'
      ? 'bg-emerald-500 text-white'
      : status === 'skipped'
        ? 'bg-amber-400 text-white'
        : active
          ? 'bg-[#1f150c] text-white'
          : 'bg-white text-slate-500 border border-slate-200'

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={`group flex w-full items-center gap-3 rounded-lg p-2 text-left transition ${
        disabled ? 'cursor-not-allowed opacity-60' : 'hover:bg-white'
      } ${active ? 'bg-white shadow-sm' : ''}`}
    >
      <span
        className={`flex h-8 w-8 items-center justify-center rounded-full text-[13px] ${bg}`}
      >
        {status === 'done' ? <FiCheck /> : status === 'skipped' ? <FiChevronRight /> : step.id}
      </span>
      <span>
        <span className="block text-[12.5px] font-semibold text-slate-800">{step.label}</span>
        <span className="block text-[10.5px] text-slate-400">{step.hint}</span>
      </span>
    </button>
  )
}

// ==========================================================================
// Step components
// ==========================================================================

function IdentityStep({
  identity, setIdentity, inputRef, locked,
}: {
  identity: { clientCode: string; name: string; email: string; phone: string }
  setIdentity: React.Dispatch<React.SetStateAction<{ clientCode: string; name: string; email: string; phone: string }>>
  inputRef: React.RefObject<HTMLInputElement | null>
  locked: boolean
}) {
  return (
    <div className="max-w-lg space-y-4">
      <div className="grid grid-cols-2 gap-3">
        <div>
          <Label required>Client code</Label>
          <input
            ref={inputRef}
            type="text"
            value={identity.clientCode}
            disabled={locked}
            onChange={(e) => setIdentity((c) => ({ ...c, clientCode: e.target.value.toUpperCase() }))}
            placeholder="ARHDEV"
            className={inputCls}
          />
          <p className="mt-1 text-[11px] text-slate-400">
            Uppercase. Orders link to this code — it can't be changed once created.
          </p>
        </div>
        <div>
          <Label required>Client name</Label>
          <input
            type="text"
            value={identity.name}
            onChange={(e) => setIdentity((c) => ({ ...c, name: e.target.value }))}
            placeholder="Acme Fulfillment Inc."
            className={inputCls}
          />
        </div>
      </div>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <Label>Email</Label>
          <input
            type="email"
            value={identity.email}
            onChange={(e) => setIdentity((c) => ({ ...c, email: e.target.value }))}
            placeholder="ops@acme.example"
            className={inputCls}
          />
        </div>
        <div>
          <Label>Phone</Label>
          <input
            type="tel"
            value={identity.phone}
            onChange={(e) => setIdentity((c) => ({ ...c, phone: e.target.value }))}
            placeholder="+1 555 123 4567"
            className={inputCls}
          />
        </div>
      </div>
      {locked ? (
        <p className="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] text-emerald-800">
          Client saved. Continue to the next step or use the rail to jump back.
        </p>
      ) : null}
    </div>
  )
}

function AddressStep({
  title, caption, address, onChange, inputRef,
}: {
  title: string
  caption: string
  address: Address
  onChange: (a: Address) => void
  inputRef: React.RefObject<HTMLInputElement | null>
}) {
  const set = (k: keyof Address) => (v: string) => onChange({ ...address, [k]: v })
  return (
    <div className="max-w-2xl space-y-3">
      <p className="text-[12.5px] text-slate-500">
        {caption} Fields are optional individually — but at least the street and city are needed to save.
      </p>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <Label>Contact name</Label>
          <input ref={inputRef} type="text" value={address.name || ''} onChange={(e) => set('name')(e.target.value)} className={inputCls} placeholder={title === 'Ship-from address' ? 'Warehouse name / label' : 'Return contact'} />
        </div>
        <div>
          <Label>Phone</Label>
          <input type="tel" value={address.phone || ''} onChange={(e) => set('phone')(e.target.value)} className={inputCls} />
        </div>
      </div>
      <div>
        <Label>Street</Label>
        <input type="text" value={address.line1 || ''} onChange={(e) => set('line1')(e.target.value)} className={inputCls} placeholder="123 Depot Way" />
      </div>
      <div>
        <Label>Suite / unit</Label>
        <input type="text" value={address.line2 || ''} onChange={(e) => set('line2')(e.target.value)} className={inputCls} placeholder="Suite 200" />
      </div>
      <div className="grid grid-cols-4 gap-3">
        <div className="col-span-2">
          <Label>City</Label>
          <input type="text" value={address.city || ''} onChange={(e) => set('city')(e.target.value)} className={inputCls} />
        </div>
        <div>
          <Label>State</Label>
          <input type="text" value={address.state || ''} onChange={(e) => set('state')(e.target.value)} className={inputCls} />
        </div>
        <div>
          <Label>ZIP</Label>
          <input type="text" value={address.zip || ''} onChange={(e) => set('zip')(e.target.value)} className={inputCls} />
        </div>
      </div>
      <div>
        <Label>Country</Label>
        <CountrySelect
          value={address.country || 'US'}
          onChange={(v) => set('country')(v)}
        />
      </div>
    </div>
  )
}

function ReturnStep({
  returnSameAsShipFrom, setReturnSameAsShipFrom, returnAddress, setReturnAddress, shipFrom,
}: {
  returnSameAsShipFrom: boolean
  setReturnSameAsShipFrom: (v: boolean) => void
  returnAddress: Address
  setReturnAddress: (a: Address) => void
  shipFrom: Address | null | undefined
}) {
  return (
    <div className="max-w-2xl space-y-4">
      <label className="flex items-center gap-2 text-[13px] text-slate-800">
        <input
          type="checkbox"
          checked={returnSameAsShipFrom}
          onChange={(e) => setReturnSameAsShipFrom(e.target.checked)}
        />
        Same as ship-from address
      </label>

      {returnSameAsShipFrom ? (
        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-[12.5px] text-slate-600">
          Returns will go to the same address you set on the previous step.
          {shipFrom?.line1 ? (
            <div className="mt-2 font-mono text-[12px] text-slate-800">
              {shipFrom.line1}, {shipFrom.city}, {shipFrom.state} {shipFrom.zip}
            </div>
          ) : (
            <div className="mt-2 text-[12px] italic text-slate-400">
              Ship-from address is empty for now.
            </div>
          )}
        </div>
      ) : (
        <AddressStep
          title="Return address"
          caption="Where undeliverable parcels come back to."
          address={returnAddress}
          onChange={setReturnAddress}
          inputRef={{ current: null }}
        />
      )}
    </div>
  )
}

function CarrierStep({
  form, setForm, inputRef, clientCode, addedAccounts, adding, onAdd,
}: {
  form: {
    open: boolean; carrierCode: string; accountNumber: string; accountName: string;
    clientId: string; clientSecret: string; environment: CarrierEnvironment
  }
  setForm: React.Dispatch<React.SetStateAction<{
    open: boolean; carrierCode: string; accountNumber: string; accountName: string;
    clientId: string; clientSecret: string; environment: CarrierEnvironment
  }>>
  inputRef: React.RefObject<HTMLInputElement | null>
  clientCode: string
  addedAccounts: CarrierAccountRef[]
  adding: boolean
  onAdd: () => Promise<boolean>
}) {
  const set = (k: keyof typeof form) => (v: string) => setForm((c) => ({ ...c, [k]: v }))

  return (
    <div className="max-w-2xl space-y-4">
      <p className="text-[12.5px] text-slate-500">
        Link one or more carrier accounts to <strong>{clientCode}</strong>. The first added becomes
        the default; add more (e.g. UPS + FedEx + USPS) to make the client shippable on every
        service you allowlist in step 5.
      </p>

      {/* ---- Already-added accounts list ---- */}
      {addedAccounts.length > 0 ? (
        <div className="overflow-hidden rounded-lg border border-emerald-200">
          <table className="min-w-full text-[12.5px]">
            <thead className="bg-emerald-50 text-[10.5px] font-bold uppercase tracking-[0.14em] text-emerald-800">
              <tr>
                <th className="px-3 py-1.5 text-left">Carrier</th>
                <th className="px-3 py-1.5 text-left">Account #</th>
                <th className="px-3 py-1.5 text-left">Environment</th>
                <th className="px-3 py-1.5 text-left">Default?</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-100">
              {addedAccounts.map((a) => (
                <tr key={a.id ?? `${a.carrierCode}-${a.accountNumber}`} className="bg-white">
                  <td className="px-3 py-1.5 font-semibold text-slate-800">{a.carrierCode}</td>
                  <td className="px-3 py-1.5 font-mono text-slate-700">{a.accountNumber}</td>
                  <td className="px-3 py-1.5 text-slate-600">{a.environment || 'SANDBOX'}</td>
                  <td className="px-3 py-1.5">
                    {a.clientDefault ? (
                      <span className="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[11px] font-bold text-emerald-700">
                        Default
                      </span>
                    ) : (
                      <span className="text-slate-400">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {/* ---- Collapsed CTA vs open form ---- */}
      {!form.open ? (
        <button
          type="button"
          onClick={() => setForm((c) => ({ ...c, open: true }))}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
        >
          + Add another carrier account
        </button>
      ) : (
        <div className="rounded-lg border border-slate-200 bg-slate-50 p-4">
          <div className="mb-3 flex items-center justify-between">
            <p className="text-[12.5px] font-semibold text-slate-700">
              {addedAccounts.length === 0 ? 'New carrier account' : `Add another account (${addedAccounts.length} saved)`}
            </p>
            {addedAccounts.length > 0 ? (
              <button
                type="button"
                onClick={() => setForm((c) => ({ ...c, open: false }))}
                className="text-[11.5px] text-slate-500 hover:underline"
                title="Collapse this form"
              >
                Cancel
              </button>
            ) : null}
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Carrier</Label>
              <Select value={form.carrierCode} onChange={(e) => set('carrierCode')(e.target.value)}>
                <option value="UPS">UPS</option>
                <option value="FEDEX">FedEx</option>
                <option value="USPS">USPS</option>
                <option value="DHL">DHL Express</option>
              </Select>
            </div>
            <div>
              <Label>Environment</Label>
              <Select value={form.environment} onChange={(e) => set('environment')(e.target.value as CarrierEnvironment)}>
                {carrierEnvironmentOptions.map((env) => (
                  <option key={env} value={env}>{env}</option>
                ))}
              </Select>
            </div>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-3">
            <div>
              <Label required>Account number</Label>
              <input ref={inputRef} type="text" value={form.accountNumber} onChange={(e) => set('accountNumber')(e.target.value)} className={inputCls} />
            </div>
            <div>
              <Label>Account name (optional)</Label>
              <input type="text" value={form.accountName} onChange={(e) => set('accountName')(e.target.value)} className={inputCls} placeholder="e.g. Acme main UPS" />
            </div>
          </div>
          <div className="mt-3 grid grid-cols-2 gap-3">
            <div>
              <Label required>Client ID</Label>
              <input type="text" value={form.clientId} onChange={(e) => set('clientId')(e.target.value)} className={inputCls} />
            </div>
            <div>
              <Label required>Client secret</Label>
              <input type="password" value={form.clientSecret} onChange={(e) => set('clientSecret')(e.target.value)} className={inputCls} />
            </div>
          </div>
          <div className="mt-3 flex items-center justify-end gap-2">
            <button
              type="button"
              onClick={() => void onAdd()}
              disabled={adding}
              className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50"
            >
              {adding ? 'Saving…' : 'Save account'}
            </button>
          </div>
        </div>
      )}

      <p className="text-[11px] text-slate-400">
        Missing creds? Save what you have and use Skip / Next to move on — more accounts can be added anytime from the Edit screen.
      </p>
    </div>
  )
}

function WarehouseAllowlistStep({
  clientCode,
  warehouseChoice, setWarehouseChoice,
  existingWarehouses, selectedExistingCode, setSelectedExistingCode,
  newWarehouse, setNewWarehouse,
  allowedCarriers, setAllowedCarriers,
  shipFromPreview,
}: {
  clientCode: string
  warehouseChoice: WarehouseChoice
  setWarehouseChoice: (c: WarehouseChoice) => void
  existingWarehouses: Warehouse[]
  selectedExistingCode: string
  setSelectedExistingCode: (v: string) => void
  newWarehouse: { code: string; name: string; address: Address }
  setNewWarehouse: React.Dispatch<React.SetStateAction<{ code: string; name: string; address: Address }>>
  allowedCarriers: Set<string>
  setAllowedCarriers: React.Dispatch<React.SetStateAction<Set<string>>>
  shipFromPreview: Address | null | undefined
}) {
  const toggleCarrier = (c: string) => {
    const next = new Set(allowedCarriers)
    if (next.has(c)) next.delete(c)
    else next.add(c)
    setAllowedCarriers(next)
  }
  const setNewAddr = (k: keyof Address) => (v: string) =>
    setNewWarehouse((cur) => ({ ...cur, address: { ...cur.address, [k]: v } }))

  const carrierOptions = useMemo(
    () => ['UPS', 'FEDEX', 'USPS', 'DHL'] as const,
    [],
  )

  return (
    <div className="max-w-2xl space-y-5">
      {/* ---- Warehouse ---- */}
      <section>
        <h4 className="mb-1 text-[13px] font-semibold text-slate-900">First warehouse</h4>
        <p className="mb-3 text-[12px] text-slate-500">
          Client <strong>{clientCode}</strong> ships from this location by default.
        </p>
        <div className="grid grid-cols-3 gap-2">
          <ChoiceCard
            active={warehouseChoice === 'reuse'}
            onClick={() => setWarehouseChoice('reuse')}
            title="Use the ship-from address"
            hint="Fastest — reuses what you entered on step 2."
          />
          <ChoiceCard
            active={warehouseChoice === 'existing'}
            onClick={() => setWarehouseChoice('existing')}
            title="Attach an existing warehouse"
            hint="Pick a platform warehouse."
          />
          <ChoiceCard
            active={warehouseChoice === 'new'}
            onClick={() => setWarehouseChoice('new')}
            title="Create a brand-new warehouse"
            hint="Fill in a fresh warehouse."
          />
        </div>

        {warehouseChoice === 'reuse' ? (
          <div className="mt-3 rounded-md border border-slate-200 bg-slate-50 p-3 text-[12.5px]">
            {hasAddressValue(shipFromPreview)
              ? (
                <>
                  <div className="font-semibold text-slate-800">
                    {shipFromPreview?.line1}
                  </div>
                  <div className="text-slate-600">
                    {shipFromPreview?.city}, {shipFromPreview?.state} {shipFromPreview?.zip} · {shipFromPreview?.country}
                  </div>
                </>
              )
              : (
                <span className="italic text-amber-700">
                  Ship-from address is empty. Go back to step 2 first, or pick another option.
                </span>
              )}
          </div>
        ) : null}

        {warehouseChoice === 'existing' ? (
          <div className="mt-3">
            <Select value={selectedExistingCode} onChange={(e) => setSelectedExistingCode(e.target.value)}>
              <option value="">— pick a warehouse —</option>
              {existingWarehouses.map((w) => (
                <option key={w.id} value={w.code}>
                  {w.code} — {w.name}
                </option>
              ))}
            </Select>
            {existingWarehouses.length === 0 ? (
              <p className="mt-2 text-[11px] italic text-slate-500">
                No platform warehouses configured yet.
              </p>
            ) : null}
          </div>
        ) : null}

        {warehouseChoice === 'new' ? (
          <div className="mt-3 space-y-2">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label required>Code</Label>
                <input
                  type="text"
                  value={newWarehouse.code}
                  onChange={(e) => setNewWarehouse((c) => ({ ...c, code: e.target.value.toUpperCase() }))}
                  className={inputCls}
                  placeholder="MAIN-DEPOT"
                />
              </div>
              <div>
                <Label required>Name</Label>
                <input
                  type="text"
                  value={newWarehouse.name}
                  onChange={(e) => setNewWarehouse((c) => ({ ...c, name: e.target.value }))}
                  className={inputCls}
                  placeholder="Main depot"
                />
              </div>
            </div>
            <div className="grid grid-cols-4 gap-3">
              <div className="col-span-2">
                <Label>Street</Label>
                <input type="text" value={newWarehouse.address.line1 || ''} onChange={(e) => setNewAddr('line1')(e.target.value)} className={inputCls} />
              </div>
              <div>
                <Label>City</Label>
                <input type="text" value={newWarehouse.address.city || ''} onChange={(e) => setNewAddr('city')(e.target.value)} className={inputCls} />
              </div>
              <div>
                <Label>ZIP</Label>
                <input type="text" value={newWarehouse.address.zip || ''} onChange={(e) => setNewAddr('zip')(e.target.value)} className={inputCls} />
              </div>
            </div>
          </div>
        ) : null}
      </section>

      {/* ---- Allowlist ---- */}
      <section>
        <h4 className="mb-1 text-[13px] font-semibold text-slate-900">Allowed carriers</h4>
        <p className="mb-3 text-[12px] text-slate-500">
          Tick the carriers this client can ship with. All active services from those carriers are
          auto-allowed; narrow the list later on the Edit screen.
        </p>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {carrierOptions.map((c) => (
            <label
              key={c}
              className={`flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-[12.5px] font-semibold transition ${
                allowedCarriers.has(c) ? 'border-[#1f150c] bg-[#faf7f0] text-[#1f150c]' : 'border-slate-200 bg-white text-slate-600'
              }`}
            >
              <input
                type="checkbox"
                checked={allowedCarriers.has(c)}
                onChange={() => toggleCarrier(c)}
              />
              {c}
            </label>
          ))}
        </div>
      </section>
    </div>
  )
}

function ChoiceCard({
  active, onClick, title, hint,
}: {
  active: boolean
  onClick: () => void
  title: string
  hint: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`rounded-lg border p-3 text-left transition ${
        active ? 'border-[#1f150c] bg-[#faf7f0]' : 'border-slate-200 bg-white hover:border-slate-300'
      }`}
    >
      <div className="text-[13px] font-semibold text-slate-900">{title}</div>
      <div className="mt-0.5 text-[11.5px] text-slate-500">{hint}</div>
    </button>
  )
}

// ==========================================================================
// Small helpers
// ==========================================================================

function Label({ children, required }: { children: React.ReactNode; required?: boolean }) {
  return (
    <span className="mb-1 flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
      {children}
      {required ? <span className="text-rose-500">*</span> : null}
    </span>
  )
}

const inputCls =
  'w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]'

function hasAddressValue(a: Address | null | undefined): boolean {
  return Boolean(a?.line1?.trim() && a?.city?.trim())
}

/** Auto-generated warehouse code when the wizard reuses the ship-from address. */
function generateWarehouseCode(clientCode: string): string {
  return `${clientCode}-WH-${Date.now().toString(36).slice(-4).toUpperCase()}`
}

/**
 * After the user adds an account, pre-select the next unpicked carrier
 * so a UPS + FedEx + USPS run doesn't require three dropdown clicks.
 */
function nextCarrierAfter(current: string): string {
  const order = ['UPS', 'FEDEX', 'USPS', 'DHL']
  const i = order.indexOf(current)
  return i === -1 || i === order.length - 1 ? order[0] : order[i + 1]
}

/**
 * The set of carrier codes the wizard has touched so far — accounts
 * added in Step 4 + carriers allowlisted in Step 5. Empty set = user
 * skipped both, in which case the alias steps show the whole catalog.
 */
function carriersInScope(
  accounts: CarrierAccountRef[],
  allowlisted: Set<string>,
): Set<string> {
  const out = new Set<string>()
  accounts.forEach((a) => a.carrierCode && out.add(a.carrierCode.toUpperCase()))
  allowlisted.forEach((c) => out.add(c.toUpperCase()))
  return out
}

// ==========================================================================
// Step 6 — ERP service aliases (SHIPVIA + SERVICE)
// ==========================================================================

function ServiceAliasStep({
  clientCode, carriersInScope: scope, services,
  shipviaMaps, serviceMaps,
  shipviaDraft, setShipviaDraft,
  serviceDraft, setServiceDraft,
  adding, onAdd, onRemove,
}: {
  clientCode: string
  carriersInScope: Set<string>
  services: ShippingServiceItem[]
  shipviaMaps: ClientCodeMap[]
  serviceMaps: ClientCodeMap[]
  shipviaDraft: { erpCode: string; targetId: string }
  setShipviaDraft: React.Dispatch<React.SetStateAction<{ erpCode: string; targetId: string }>>
  serviceDraft: { erpCode: string; targetId: string }
  setServiceDraft: React.Dispatch<React.SetStateAction<{ erpCode: string; targetId: string }>>
  adding: boolean
  onAdd: (kind: 'SHIPVIA' | 'SERVICE') => Promise<boolean>
  onRemove: (kind: 'SHIPVIA' | 'SERVICE', row: ClientCodeMap) => Promise<void>
}) {
  const svcOptions = useMemo(() => {
    const filtered = scope.size === 0
      ? services
      : services.filter((s) => scope.has(s.carrier.toUpperCase()))
    return filtered
      .filter((s) => s.enabled)
      .slice()
      .sort((a, b) => a.carrier.localeCompare(b.carrier) || a.name.localeCompare(b.name))
  }, [services, scope])

  return (
    <div className="max-w-3xl space-y-5">
      <p className="text-[12.5px] text-slate-500">
        When incoming orders for <strong>{clientCode}</strong> land, the ERP's
        ship-via and service codes are translated on arrival using these
        aliases. The target dropdown is filtered to services from the carriers
        picked in Steps 4 & 5.
      </p>

      <AliasList
        kind="SHIPVIA"
        heading="ERP ship-via codes"
        subheading='e.g. "P80" → UPS Ground. Overrides the platform-wide ShipViaMapping rules.'
        erpPlaceholder="P80"
        rows={shipviaMaps}
        options={svcOptions.map((s) => ({
          value: String(s.id),
          label: `${formatCarrierName(s.carrier)} · ${s.serviceCode} — ${s.name}`,
        }))}
        draft={shipviaDraft}
        setDraft={setShipviaDraft}
        adding={adding}
        onAdd={() => onAdd('SHIPVIA')}
        onRemove={(row) => onRemove('SHIPVIA', row)}
      />

      <AliasList
        kind="SERVICE"
        heading="ERP service codes"
        subheading='e.g. "F77-2DAY" → FedEx 2Day. Same target catalog as ship-via.'
        erpPlaceholder="F77-2DAY"
        rows={serviceMaps}
        options={svcOptions.map((s) => ({
          value: String(s.id),
          label: `${formatCarrierName(s.carrier)} · ${s.serviceCode} — ${s.name}`,
        }))}
        draft={serviceDraft}
        setDraft={setServiceDraft}
        adding={adding}
        onAdd={() => onAdd('SERVICE')}
        onRemove={(row) => onRemove('SERVICE', row)}
      />
    </div>
  )
}

// ==========================================================================
// Step 7 — ERP package aliases (PACKAGE)
// ==========================================================================

function PackageAliasStep({
  clientCode, carriersInScope: scope, presets,
  packageMaps, packageDraft, setPackageDraft,
  adding, onAdd, onRemove,
}: {
  clientCode: string
  carriersInScope: Set<string>
  presets: PackagePreset[]
  packageMaps: ClientCodeMap[]
  packageDraft: { erpCode: string; targetId: string }
  setPackageDraft: React.Dispatch<React.SetStateAction<{ erpCode: string; targetId: string }>>
  adding: boolean
  onAdd: () => Promise<boolean>
  onRemove: (row: ClientCodeMap) => Promise<void>
}) {
  const pkgOptions = useMemo(() => {
    // Show:
    //   - CUSTOM boxes (usable with any carrier)
    //   - CARRIER presets from carriers in scope
    // If the scope is empty (user skipped Steps 4 & 5) show the full list.
    const filtered = scope.size === 0
      ? presets
      : presets.filter((p) => {
        if (!p.carrier) return true  // custom / carrier-agnostic
        return scope.has(p.carrier.toUpperCase())
      })
    return filtered.slice().sort((a, b) => a.name.localeCompare(b.name))
  }, [presets, scope])

  return (
    <div className="max-w-3xl space-y-5">
      <p className="text-[12.5px] text-slate-500">
        Translate the ERP's package codes on <strong>{clientCode}</strong>'s
        incoming orders into platform package presets. Filtered to presets
        from the carriers you picked in Steps 4 & 5, plus custom boxes.
      </p>

      <AliasList
        kind="PACKAGE"
        heading="ERP package codes"
        subheading='e.g. "BOX-A" → 12x9x3 mailer'
        erpPlaceholder="BOX-A"
        rows={packageMaps}
        options={pkgOptions
          .filter((p) => p.id != null)
          .map((p) => ({
            value: String(p.id),
            label: `${p.name}${p.carrier ? ` · ${formatCarrierName(p.carrier)}` : ''}${p.kind ? ` · ${p.kind}` : ''}`,
          }))}
        draft={packageDraft}
        setDraft={setPackageDraft}
        adding={adding}
        onAdd={onAdd}
        onRemove={onRemove}
      />
    </div>
  )
}

// ==========================================================================
// Shared alias-list primitive (used by both Steps 6 & 7)
// ==========================================================================

function AliasList({
  heading, subheading, erpPlaceholder, rows, options,
  draft, setDraft, adding, onAdd, onRemove,
}: {
  kind: CodeMapKind
  heading: string
  subheading: string
  erpPlaceholder: string
  rows: ClientCodeMap[]
  options: Array<{ value: string; label: string }>
  draft: AliasDraft
  setDraft: React.Dispatch<React.SetStateAction<AliasDraft>>
  adding: boolean
  onAdd: () => Promise<boolean>
  onRemove: (row: ClientCodeMap) => void | Promise<void>
}) {
  return (
    <section>
      <h4 className="text-[13px] font-semibold text-slate-900">{heading}</h4>
      <p className="mt-0.5 text-[11.5px] text-slate-500">{subheading}</p>

      {rows.length > 0 ? (
        <div className="mt-2 overflow-hidden rounded-lg border border-emerald-200">
          <table className="min-w-full text-[12.5px]">
            <thead className="bg-emerald-50 text-[10.5px] font-bold uppercase tracking-[0.14em] text-emerald-800">
              <tr>
                <th className="px-3 py-1.5 text-left">ERP code</th>
                <th className="px-3 py-1.5 text-left">Destination</th>
                <th className="px-3 py-1.5 text-left">Platform target</th>
                <th className="px-3 py-1.5 w-8"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-100">
              {rows.map((row) => (
                <tr key={row.id} className="bg-white">
                  <td className="px-3 py-1.5 font-mono text-slate-800">{row.erpCode}</td>
                  <td className="px-3 py-1.5 text-slate-600">{destSummary(row.destCountry, row.destRegion)}</td>
                  <td className="px-3 py-1.5 text-slate-700">{row.targetLabel ?? row.targetId ?? '—'}</td>
                  <td className="px-3 py-1.5 text-right">
                    <button
                      type="button"
                      onClick={() => void onRemove(row)}
                      className="rounded p-1 text-rose-500 hover:bg-rose-50"
                      aria-label="Remove alias"
                    >
                      <FiTrash2 />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <div className="mt-2 space-y-2 rounded-lg border border-slate-200 bg-slate-50 p-3">
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-2">
          <input
            type="text"
            value={draft.erpCode}
            onChange={(e) => setDraft((c) => ({ ...c, erpCode: e.target.value }))}
            placeholder={erpPlaceholder}
            className={inputCls}
          />
          <Select
            value={draft.targetId}
            onChange={(e) => setDraft((c) => ({ ...c, targetId: e.target.value }))}
          >
            <option value="">— pick a platform target —</option>
            {options.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </Select>
        </div>
        <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] gap-2">
          <DestinationRegionSelect
            value={draft.destRegion}
            onChange={(v) => setDraft((c) => ({ ...c, destRegion: v, destCountry: '' }))}
          />
          <DestinationCountrySelect
            value={draft.destCountry}
            region={draft.destRegion}
            onChange={(v) => setDraft((c) => ({ ...c, destCountry: v }))}
          />
          <button
            type="button"
            onClick={() => void onAdd()}
            disabled={adding || !draft.erpCode.trim() || !draft.targetId}
            className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50"
          >
            <FiPlus /> Add
          </button>
        </div>
        <p className="text-[10.5px] text-slate-500">
          Leave both destination fields blank for the fallback (applies to any destination). Country takes precedence when both are set.
        </p>
      </div>
    </section>
  )
}

// ==========================================================================
// Destination pickers (used by AliasList)
// ==========================================================================

function DestinationRegionSelect({
  value, onChange,
}: {
  value: string
  onChange: (v: string) => void
}) {
  return (
    <Select value={value} onChange={(e) => onChange(e.target.value)} aria-label="Destination region">
      <option value="">Any region</option>
      {REGIONS.map((r) => (
        <option key={r} value={r}>{r}</option>
      ))}
    </Select>
  )
}

function DestinationCountrySelect({
  value, region, onChange,
}: {
  value: string
  region: string
  onChange: (v: string) => void
}) {
  const options = useMemo(() => {
    const list = region ? COUNTRIES.filter((c) => c.region === region) : COUNTRIES
    return list.slice().sort((a, b) => a.name.localeCompare(b.name))
  }, [region])
  return (
    <Select value={value} onChange={(e) => onChange(e.target.value)} aria-label="Destination country">
      <option value="">{region ? `Any country in ${region}` : 'Any country'}</option>
      {options.map((c) => (
        <option key={c.code} value={c.code}>{c.code} — {c.name}</option>
      ))}
    </Select>
  )
}

function destSummary(country: string | null, region: string | null): string {
  const c = country?.trim()
  const r = region?.trim()
  if (c) {
    const name = COUNTRIES.find((x) => x.code === c)?.name
    return name ? `${c} · ${name}` : c
  }
  if (r) return r
  return 'Any'
}
