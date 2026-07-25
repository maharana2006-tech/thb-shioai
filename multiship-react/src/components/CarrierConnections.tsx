import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiActivity,
  FiAlertTriangle,
  FiBox,
  FiCheckCircle,
  FiEdit2,
  FiKey,
  FiMoreVertical,
  FiPlus,
  FiRefreshCw,
  FiServer,
  FiStar,
  FiFilter,
  FiShield,
  FiTrash2,
  FiTruck,
  FiUser,
  FiUsers,
  FiX,
  FiXCircle,
} from 'react-icons/fi'
import type { ColumnDef } from '@tanstack/react-table'
import {
  accountRefService,
  type AccountRefUpsertPayload,
  type CarrierAccountRef,
} from '../api/accountRefService'
import { clientService } from '../api/clientService'
import {
  carrierEnvironmentOptions,
  formatCarrierName,
  normalizeCarrierCode,
  type CarrierEnvironment,
} from '../utils/carrierUtils'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import CarrierLogo from './workspace/CarrierLogo'
import PortalMenu from './workspace/PortalMenu'
import Select from './workspace/Select'

/** Which drawer input receives focus when opened via cell click. */
type DrawerFocusField = 'accountName' | 'environment' | null

const carrierOptions = [
  { code: 'UPS', id: 'ups', label: 'UPS' },
  { code: 'FEDEX', id: 'fedex', label: 'FedEx' },
  { code: 'USPS', id: 'usps', label: 'USPS' },
]

/**
 * OAuth credential terminology by carrier. UPS's Developer Portal calls the
 * two OAuth values "Consumer Key" and "Consumer Secret"; using the standard
 * OAuth names ("Client ID" / "Client Secret") for UPS confuses users hunting
 * on developer.ups.com, so the drawer relabels dynamically.
 */
const credentialLabelsFor = (carrierCode: string) => {
  const normalized = carrierCode?.trim().toUpperCase()
  if (normalized === 'UPS') {
    return {
      idLong: 'Consumer Key',
      secretLong: 'Consumer Secret',
      idShort: 'Consumer Key',
      secretShort: 'Consumer Secret',
      helper: 'Find these on developer.ups.com under your app — "Consumer Key" and "Consumer Secret".',
    }
  }
  return {
    idLong: 'Client ID',
    secretLong: 'Client Secret',
    idShort: 'client ID',
    secretShort: 'client secret',
    helper: null as string | null,
  }
}

const isPlatform = (a: CarrierAccountRef) => !a.customerNo || !a.customerNo.trim()

const relativeTime = (value?: string | null) => {
  if (!value) return null
  const then = new Date(value).getTime()
  if (Number.isNaN(then)) return value
  const mins = Math.max(0, Math.round((Date.now() - then) / 60000))
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  const hours = Math.round(mins / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

interface DrawerState {
  editingId: number | null
  accountType: 'platform' | 'client'
  carrierCode: string
  accountNumber: string
  accountName: string
  clientId: string
  clientSecret: string
  customerNo: string
  environment: CarrierEnvironment
  clientDefault: boolean
}

const emptyDrawer: DrawerState = {
  editingId: null,
  accountType: 'platform',
  carrierCode: 'UPS',
  accountNumber: '',
  accountName: '',
  clientId: '',
  clientSecret: '',
  customerNo: '',
  environment: 'SANDBOX',
  clientDefault: false,
}

const inputClassName =
  'w-full rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10'

const filterLabelClass = 'mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'

/** Client row rendered in dropdowns as `CODE — Name`. */
interface ClientOption {
  code: string
  name: string
}

const formatClientOption = (c: ClientOption) => (c.name?.trim() ? `${c.code} — ${c.name}` : c.code)

export default function CarrierConnections() {
  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [clients, setClients] = useState<ClientOption[]>([])
  const [loading, setLoading] = useState(true)
  const [typeFilter, setTypeFilter] = useState('') // PLATFORM | CLIENT
  const [carrierFilter, setCarrierFilter] = useState('')
  const [clientFilter, setClientFilter] = useState('')
  const [statusFilter, setStatusFilter] = useState('') // ACTIVE | INACTIVE
  const [envFilter, setEnvFilter] = useState('') // SANDBOX | PRODUCTION
  const [verifiedFilter, setVerifiedFilter] = useState('') // YES | NO
  const [defaultFilter, setDefaultFilter] = useState('') // YES (client default) | NO
  const [showFilters, setShowFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)
  const [query, setQuery] = useState('')
  const [busyId, setBusyId] = useState<number | null>(null)

  // Close the Filters popover on outside click / Escape.
  useEffect(() => {
    if (!showFilters) return
    const onDocClick = (e: MouseEvent) => {
      if (!filtersRef.current?.contains(e.target as Node)) setShowFilters(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowFilters(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [showFilters])

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [drawer, setDrawer] = useState<DrawerState>(emptyDrawer)
  // One-shot signal: which drawer input to focus when the drawer opens next.
  // Ref (not state) so setting it doesn't cascade a re-render, and reading it
  // in the mount-focus effect doesn't trip react-hooks/set-state-in-effect.
  const drawerFocusRef = useRef<DrawerFocusField>(null)
  const accountNameRef = useRef<HTMLInputElement>(null)
  const environmentRef = useRef<HTMLSelectElement>(null)
  const [drawerCheck, setDrawerCheck] = useState<{ state: 'idle' | 'checking' | 'ok' | 'fail'; message?: string }>({ state: 'idle' })
  const [saving, setSaving] = useState(false)
  // When true, credential fields become editable on edit; when false (default
  // on edit), the drawer shows a masked read-only "credentials on file" note.
  const [rotatingCredentials, setRotatingCredentials] = useState(false)

  const loadAccounts = useCallback(async () => {
    setLoading(true)
    try {
      setAccounts(await accountRefService.listAccounts())
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to load the account book.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadAccounts()
    clientService
      .listClients({ size: 500 })
      .then((r) =>
        setClients(
          (r.data?.content ?? []).map((c) => ({ code: c.clientCode, name: c.name || '' })),
        ),
      )
      .catch(() => {})
  }, [loadAccounts])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(loadAccounts)
    return () => registerRefresh(null)
  }, [registerRefresh, loadAccounts])

  // Pre-fill Client ID / Secret from the platform account of the chosen
  // carrier when adding a NEW account. The shipper can override them.
  useEffect(() => {
    if (!drawerOpen || drawer.editingId !== null) return
    let cancelled = false
    accountRefService
      .getPlatformCredentials(drawer.carrierCode)
      .then((r) => {
        if (cancelled) return
        if (r.data?.found) {
          setDrawer((c) => ({ ...c, clientId: r.data.clientId || '', clientSecret: r.data.clientSecret || '' }))
        } else {
          setDrawer((c) => ({ ...c, clientId: '', clientSecret: '' }))
        }
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [drawerOpen, drawer.carrierCode, drawer.editingId])

  const readyCount = accounts.filter((a) => a.complete && a.active).length
  const unverifiedCount = accounts.filter((a) => a.active && a.complete && a.verified !== true).length
  const platformCount = accounts.filter(isPlatform).length
  const clientCount = accounts.length - platformCount

  const visibleAccounts = useMemo(() => {
    const q = query.trim().toLowerCase()
    return accounts
      .filter((a) => {
        if (typeFilter === 'PLATFORM' && !isPlatform(a)) return false
        if (typeFilter === 'CLIENT' && isPlatform(a)) return false
        if (carrierFilter && normalizeCarrierCode(a.carrierCode) !== carrierFilter) return false
        if (clientFilter && (a.customerNo || '').toUpperCase() !== clientFilter) return false
        if (statusFilter === 'ACTIVE' && !a.active) return false
        if (statusFilter === 'INACTIVE' && a.active) return false
        if (envFilter && (a.environment || '').toUpperCase() !== envFilter) return false
        if (verifiedFilter === 'YES' && a.verified !== true) return false
        if (verifiedFilter === 'NO' && a.verified === true) return false
        if (defaultFilter === 'YES' && !a.clientDefault) return false
        if (defaultFilter === 'NO' && a.clientDefault) return false
        if (!q) return true
        return [a.accountNumber, a.accountName || '', a.customerNo || '', a.carrierCode].join(' ').toLowerCase().includes(q)
      })
      // Stable display order that does NOT depend on default/updatedAt, so a
      // row never jumps under the cursor after a default-change reload.
      .sort((a, b) => {
        const ac = (a.customerNo || '').toUpperCase()
        const bc = (b.customerNo || '').toUpperCase()
        if (ac !== bc) return ac.localeCompare(bc)
        const acr = normalizeCarrierCode(a.carrierCode) ?? ''
        const bcr = normalizeCarrierCode(b.carrierCode) ?? ''
        if (acr !== bcr) return acr.localeCompare(bcr)
        return a.id - b.id
      })
  }, [accounts, typeFilter, carrierFilter, clientFilter, statusFilter, envFilter, verifiedFilter, defaultFilter, query])

  const filterOnlyCount =
    (typeFilter ? 1 : 0) +
    (carrierFilter ? 1 : 0) +
    (clientFilter ? 1 : 0) +
    (statusFilter ? 1 : 0) +
    (envFilter ? 1 : 0) +
    (verifiedFilter ? 1 : 0) +
    (defaultFilter ? 1 : 0)

  const clearFilters = () => {
    setTypeFilter('')
    setCarrierFilter('')
    setClientFilter('')
    setStatusFilter('')
    setEnvFilter('')
    setVerifiedFilter('')
    setDefaultFilter('')
  }

  const handleVerify = async (account: CarrierAccountRef) => {
    setBusyId(account.id)
    try {
      const response = await accountRefService.verifyAccount(account.id)
      response.data?.verified ? notify.success(response.message) : notify.error(response.message)
      await loadAccounts()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Verification failed.')
    } finally {
      setBusyId(null)
    }
  }

  const handleToggleActive = async (account: CarrierAccountRef) => {
    setBusyId(account.id)
    try {
      await accountRefService.toggleActive(account.id)
      notify.success(account.active ? 'Account deactivated.' : 'Account activated.')
      await loadAccounts()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to update the account.')
    } finally {
      setBusyId(null)
    }
  }

  const handleMakeClientDefault = async (account: CarrierAccountRef) => {
    setBusyId(account.id)
    try {
      await accountRefService.setClientDefault(account.id)
      notify.success(`${account.accountNumber} is now ${account.customerNo}'s default account.`)
      await loadAccounts()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to set the client default.')
    } finally {
      setBusyId(null)
    }
  }

  const openDrawer = (account?: CarrierAccountRef, focus: DrawerFocusField = null) => {
    setDrawerCheck({ state: 'idle' })
    if (account) {
      // Preserve the stored carrier code even if we don't recognize it — the
      // carrier selector will show a warning banner but the field itself stays
      // truthful about what's persisted. (Silently rewriting to UPS was a
      // longstanding bug that could corrupt rows on save.)
      const knownCarrier = carrierOptions.find(
        (c) => normalizeCarrierCode(c.code) === normalizeCarrierCode(account.carrierCode),
      )?.code
      setDrawer({
        editingId: account.id,
        accountType: isPlatform(account) ? 'platform' : 'client',
        carrierCode: knownCarrier || account.carrierCode || 'UPS',
        accountNumber: account.accountNumber,
        accountName: account.accountName || '',
        clientId: '',
        clientSecret: '',
        customerNo: account.customerNo || '',
        environment: account.environment === 'PRODUCTION' ? 'PRODUCTION' : 'SANDBOX',
        clientDefault: Boolean(account.clientDefault),
      })
      setRotatingCredentials(false)
    } else {
      setDrawer(emptyDrawer)
      setRotatingCredentials(true)
    }
    drawerFocusRef.current = focus
    setDrawerOpen(true)
  }

  // Prefill focus on the specific field when the drawer opens via a cell click.
  useEffect(() => {
    if (!drawerOpen || !drawerFocusRef.current) return
    const el = drawerFocusRef.current === 'accountName' ? accountNameRef.current : environmentRef.current
    if (el) {
      el.focus()
      if ('select' in el) el.select()
    }
    drawerFocusRef.current = null
  }, [drawerOpen])

  const runDrawerCheck = async () => {
    const credentialTerms = credentialLabelsFor(drawer.carrierCode)
    if (!drawer.clientId.trim() || !drawer.clientSecret.trim()) {
      notify.error(`Enter the ${credentialTerms.idShort} and ${credentialTerms.secretShort} first.`)
      return
    }
    setDrawerCheck({ state: 'checking' })
    try {
      const response = await accountRefService.verifyCredentials({
        carrierCode: drawer.carrierCode,
        clientId: drawer.clientId.trim(),
        clientSecret: drawer.clientSecret.trim(),
        // UPS uses this as x-merchant-id on the OAuth call; other carriers
        // ignore it. Only send when the user has typed one so we don't
        // pre-empt validation on a half-filled form.
        accountNumber: drawer.accountNumber.trim() || undefined,
        // UPS routes SANDBOX to wwwcie.ups.com and PRODUCTION to
        // onlinetools.ups.com — a Consumer Key issued for one 401s at the
        // other. Non-UPS carriers ignore this.
        environment: drawer.environment,
      })
      setDrawerCheck({ state: response.data?.verified ? 'ok' : 'fail', message: response.message })
    } catch (error) {
      setDrawerCheck({ state: 'fail', message: error instanceof Error ? error.message : 'Verification failed.' })
    }
  }

  const handleSave = async () => {
    const isEdit = drawer.editingId !== null
    const clientIdEntered = drawer.clientId.trim()
    const clientSecretEntered = drawer.clientSecret.trim()
    const credentialTerms = credentialLabelsFor(drawer.carrierCode)

    if (!drawer.accountNumber.trim()) {
      notify.error('Account number is required.')
      return
    }
    // On CREATE both credential fields are mandatory. On EDIT they're only
    // required when the user has chosen to rotate them (either field entered).
    if (!isEdit && (!clientIdEntered || !clientSecretEntered)) {
      notify.error(`${credentialTerms.idLong} and ${credentialTerms.secretLong} are required for a new account.`)
      return
    }
    if (isEdit && rotatingCredentials && (!clientIdEntered || !clientSecretEntered)) {
      notify.error(`Enter both the new ${credentialTerms.idShort} and ${credentialTerms.secretShort}, or cancel the rotation.`)
      return
    }
    if (drawer.accountType === 'client' && !drawer.customerNo.trim()) {
      notify.error('Choose a client for a client account.')
      return
    }

    // If the user ran the drawer check on brand-new credentials and it passed,
    // persist that verification after saving so the row lands with verified=true
    // instead of the transient null that upsertAccount sets on credential change.
    const shouldPersistVerified =
      drawerCheck.state === 'ok' && Boolean(clientIdEntered) && Boolean(clientSecretEntered)

    setSaving(true)
    try {
      const payload: AccountRefUpsertPayload = {
        accountNumber: drawer.accountNumber.trim(),
        carrierCode: drawer.carrierCode,
        accountName: drawer.accountName.trim() || undefined,
        // Empty string on edit means "keep the persisted value" (backend
        // treats blank as no-change). Send whatever the user typed on create.
        clientId: clientIdEntered || undefined,
        clientSecret: clientSecretEntered || undefined,
        environment: drawer.environment,
        customerNo: drawer.accountType === 'client' ? drawer.customerNo.trim() : undefined,
        clientDefault: drawer.accountType === 'client' ? drawer.clientDefault : undefined,
      }
      const response = await accountRefService.upsertAccount(payload)
      const savedId = response.data?.id
      if (shouldPersistVerified && savedId) {
        // Best-effort — verification failure here is non-fatal; the account
        // simply lands in the "unverified" state and the user can re-run.
        try {
          await accountRefService.verifyAccount(savedId)
        } catch {
          // swallow — the row is saved, verification is a secondary signal.
        }
      }
      notify.success(`Account ${payload.accountNumber} saved to the account book.`)
      setDrawerOpen(false)
      await loadAccounts()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to save the account.')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (account: CarrierAccountRef) => {
    if (
      !window.confirm(
        `Delete account ${account.accountNumber} (${formatCarrierName(account.carrierCode)})? This can't be undone.`,
      )
    ) {
      return
    }
    setBusyId(account.id)
    try {
      await accountRefService.deleteAccount(account.id)
      notify.success(`Account ${account.accountNumber} removed from the account book.`)
      await loadAccounts()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to delete the account.')
    } finally {
      setBusyId(null)
    }
  }

  const columns = useMemo<ColumnDef<CarrierAccountRef, unknown>[]>(
    () => [
      {
        id: 'carrier',
        accessorFn: (a) => formatCarrierName(a.carrierCode),
        header: 'Carrier',
        cell: ({ row }) => {
          const account = row.original
          const platform = isPlatform(account)
          return (
            <span className="inline-flex items-center gap-2.5">
              {platform ? (
                <span className="h-4 w-4 shrink-0" />
              ) : account.clientDefault && account.active ? (
                <span title={`Default account for ${account.customerNo}`}>
                  <FiStar className="h-4 w-4 shrink-0 fill-[#f2c94c] text-[#f2c94c]" />
                </span>
              ) : account.clientDefault && !account.active ? (
                <span title={`Was ${account.customerNo}'s default — inactive, not used`}>
                  <FiStar className="h-4 w-4 shrink-0 fill-slate-300 text-slate-300" />
                </span>
              ) : account.complete && account.active ? (
                <button
                  type="button"
                  onClick={(e) => { e.stopPropagation(); void handleMakeClientDefault(account) }}
                  disabled={busyId !== null}
                  title={`Make this ${account.customerNo}'s default account`}
                  className="shrink-0 text-slate-300 transition hover:text-[#f2c94c] disabled:opacity-50"
                >
                  <FiStar className="h-4 w-4" />
                </button>
              ) : (
                <span className="h-4 w-4 shrink-0" />
              )}
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-slate-200 bg-gradient-to-br from-white to-slate-100 shadow-sm ring-1 ring-slate-100">
                <CarrierLogo carrierId={account.carrierCode} size={22} className="rounded" />
              </span>
              <span className="min-w-0">
                <span className="block font-semibold text-slate-950">{formatCarrierName(account.carrierCode)}</span>
                <span className="block text-[11px] text-slate-400">{account.accountNumber}</span>
              </span>
            </span>
          )
        },
        meta: {
          headerLabel: 'Carrier',
          exportValue: (a: CarrierAccountRef) => `${formatCarrierName(a.carrierCode)} — ${a.accountNumber}`,
        },
      },
      {
        id: 'accountName',
        accessorFn: (a) => a.accountName || '',
        header: 'Account name',
        cell: ({ row }) =>
          row.original.accountName ? (
            <span className="text-[12.5px] text-slate-700">{row.original.accountName}</span>
          ) : (
            <span className="text-[11.5px] italic text-slate-400">Unnamed — click to add</span>
          ),
        meta: {
          headerLabel: 'Account name',
          editByPicker: (a: CarrierAccountRef) => openDrawer(a, 'accountName'),
        },
      },
      {
        id: 'type',
        accessorFn: (a) => (isPlatform(a) ? 'Platform' : a.customerNo || ''),
        header: 'Type',
        cell: ({ row }) => {
          const account = row.original
          return isPlatform(account) ? (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-semibold text-slate-600">
              <FiBox className="h-3 w-3" />
              Platform
            </span>
          ) : (
            <span className="inline-flex items-center gap-1.5 rounded-full bg-[#412d15]/10 py-1 pl-1 pr-2.5 text-[11px] font-semibold text-[#412d15]">
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-[#412d15] text-[9px] font-bold uppercase text-white">
                {(account.customerNo || '?').slice(0, 1)}
              </span>
              {account.customerNo}
            </span>
          )
        },
        meta: {
          headerLabel: 'Type',
          exportValue: (a: CarrierAccountRef) => (isPlatform(a) ? 'Platform' : `Client: ${a.customerNo || ''}`),
        },
      },
      {
        id: 'environment',
        accessorFn: (a) => a.environment || 'SANDBOX',
        header: 'Environment',
        cell: ({ row }) => (
          <span className="text-[12px] text-slate-500">{row.original.environment || 'SANDBOX'}</span>
        ),
        meta: {
          headerLabel: 'Environment',
          editByPicker: (a: CarrierAccountRef) => openDrawer(a, 'environment'),
        },
      },
      {
        id: 'status',
        // Sort key: verified true > pending > false so healthy accounts float up.
        accessorFn: (a) => (!a.complete ? -2 : !a.active ? -1 : a.verified === true ? 2 : a.verified === false ? 0 : 1),
        header: 'Status',
        cell: ({ row }) => {
          const account = row.original
          return (
            <div className="flex items-center gap-2.5">
              <ActiveToggle
                active={account.active}
                busy={busyId !== null}
                onToggle={(e) => { e.stopPropagation(); void handleToggleActive(account) }}
              />
              {!account.complete ? (
                <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-700">Incomplete</span>
              ) : !account.active ? (
                <span className="rounded-full bg-slate-200 px-2 py-0.5 text-[10px] font-bold text-slate-600">Inactive</span>
              ) : (
                <VerifiedChip account={account} />
              )}
            </div>
          )
        },
        meta: {
          headerLabel: 'Status',
          exportValue: (a: CarrierAccountRef) =>
            !a.complete
              ? 'Incomplete'
              : !a.active
                ? 'Inactive'
                : a.verified === true
                  ? 'Verified'
                  : a.verified === false
                    ? 'Check failed'
                    : 'Unverified',
        },
      },
      {
        id: 'usage',
        accessorFn: (a) => a.labelsGenerated || 0,
        header: 'Usage',
        cell: ({ row }) => {
          const account = row.original
          return (
            <div className="text-[11.5px] text-slate-500">
              <span className="block font-semibold tabular-nums text-slate-600">
                {account.labelsGenerated || 0} label{(account.labelsGenerated || 0) === 1 ? '' : 's'}
              </span>
              {account.lastUsedAt ? relativeTime(account.lastUsedAt) : 'never used'}
            </div>
          )
        },
        meta: {
          headerLabel: 'Usage',
          exportValue: (a: CarrierAccountRef) => `${a.labelsGenerated || 0} labels; last used ${a.lastUsedAt || 'never'}`,
        },
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="text-right">
            <RowActionsMenu
              account={row.original}
              busy={busyId !== null}
              onVerify={() => void handleVerify(row.original)}
              onEdit={() => openDrawer(row.original)}
              onDelete={() => void handleDelete(row.original)}
            />
          </div>
        ),
        meta: { headerLabel: 'Actions', hideable: false, exportable: false },
      },
    ],
    // Handlers (handleVerify / handleToggleActive / handleMakeClientDefault /
    // openDrawer) close over the latest state through their function bodies but
    // themselves aren't referentially stable — including them would rebuild the
    // column model every render. busyId is the one value the columns visually
    // depend on for disabled states.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [busyId],
  )

  return (
    <div className="space-y-4">
      {/* ===== health strip ===== */}
      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {[
          { label: 'Ready to ship', value: `${readyCount}/${accounts.length}`, tone: 'text-emerald-600', icon: FiCheckCircle, chip: 'bg-emerald-50 text-emerald-600' },
          { label: 'Unverified', value: unverifiedCount, tone: unverifiedCount ? 'text-amber-600' : 'text-slate-950', icon: FiAlertTriangle, chip: unverifiedCount ? 'bg-amber-50 text-amber-600' : 'bg-slate-100 text-slate-400' },
          { label: 'Platform accounts', value: platformCount, tone: 'text-slate-950', icon: FiBox, chip: 'bg-slate-100 text-slate-500' },
          { label: 'Client accounts', value: clientCount, tone: 'text-slate-950', icon: FiUsers, chip: 'bg-[#412d15]/10 text-[#412d15]' },
        ].map((card) => (
          <div key={card.label} className="flex items-start justify-between rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-500">{card.label}</p>
              <p className={`mt-1.5 text-2xl font-semibold tabular-nums ${card.tone}`}>{card.value}</p>
            </div>
            <span className={`inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${card.chip}`}>
              <card.icon className="h-4 w-4" />
            </span>
          </div>
        ))}
      </section>

      {/* ===== account table ===== */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !accounts.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading the account book…</p>
        ) : (
          <AdvancedDataTable<CarrierAccountRef>
            tableKey="carriers"
            columns={columns}
            data={visibleAccounts}
            search={{
              value: query,
              onChange: setQuery,
              placeholder: 'Search account #, name, client…',
            }}
            filterToggle={
              <div ref={filtersRef} className="relative">
                <button
                  type="button"
                  onClick={() => setShowFilters((cur) => !cur)}
                  aria-pressed={showFilters}
                  aria-expanded={showFilters}
                  aria-label="Filters"
                  title="Filters"
                  className={`inline-flex items-center gap-1.5 rounded-xl px-2.5 py-1.5 text-[12px] font-semibold transition md:px-3 ${
                    showFilters || filterOnlyCount
                      ? 'bg-[#1f150c] text-white'
                      : 'border border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  <FiFilter className="h-3.5 w-3.5" />
                  <span className="hidden md:inline">Filters</span>
                  {filterOnlyCount ? (
                    <span className="rounded-full bg-white/25 px-1.5 py-0.5 text-[10px] tabular-nums">{filterOnlyCount}</span>
                  ) : null}
                </button>
                {showFilters ? (
                  <div
                    role="dialog"
                    aria-label="Filter accounts"
                    className="absolute left-0 top-full z-30 mt-1.5 w-72 rounded-2xl border border-slate-200 bg-white p-3 shadow-[0_20px_60px_rgba(15,23,42,0.15)]"
                  >
                    <div className="space-y-2.5">
                      <div>
                        <span className={filterLabelClass}><FiUsers className="h-3 w-3 text-[#412d15]" />Account type</span>
                        <Select value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)} aria-label="Filter by account type">
                          <option value="">All accounts</option>
                          <option value="PLATFORM">Platform ({platformCount})</option>
                          <option value="CLIENT">Client ({clientCount})</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiTruck className="h-3 w-3 text-emerald-600" />Carrier</span>
                        <Select value={carrierFilter} onChange={(e) => setCarrierFilter(e.target.value)} aria-label="Filter by carrier">
                          <option value="">All carriers</option>
                          <option value="ups">UPS</option>
                          <option value="fedex">FedEx</option>
                          <option value="usps">USPS</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiUser className="h-3 w-3 text-[#412d15]" />Client</span>
                        <Select value={clientFilter} onChange={(e) => setClientFilter(e.target.value)} aria-label="Filter by client">
                          <option value="">All clients</option>
                          {clients.map((c) => (
                            <option key={c.code} value={c.code}>
                              {formatClientOption(c)}
                            </option>
                          ))}
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiActivity className="h-3 w-3 text-sky-600" />Status</span>
                        <Select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)} aria-label="Filter by status">
                          <option value="">Any status</option>
                          <option value="ACTIVE">Active</option>
                          <option value="INACTIVE">Inactive</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiServer className="h-3 w-3 text-violet-600" />Environment</span>
                        <Select value={envFilter} onChange={(e) => setEnvFilter(e.target.value)} aria-label="Filter by environment">
                          <option value="">Any environment</option>
                          <option value="SANDBOX">Sandbox</option>
                          <option value="PRODUCTION">Production</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiShield className="h-3 w-3 text-emerald-600" />Verification</span>
                        <Select value={verifiedFilter} onChange={(e) => setVerifiedFilter(e.target.value)} aria-label="Filter by verification">
                          <option value="">Any</option>
                          <option value="YES">Verified</option>
                          <option value="NO">Unverified</option>
                        </Select>
                      </div>
                      <div>
                        <span className={filterLabelClass}><FiStar className="h-3 w-3 text-amber-500" />Client default</span>
                        <Select value={defaultFilter} onChange={(e) => setDefaultFilter(e.target.value)} aria-label="Filter by client default">
                          <option value="">Any</option>
                          <option value="YES">Default</option>
                          <option value="NO">Not default</option>
                        </Select>
                      </div>
                    </div>

                    <div className="mt-3 flex items-center justify-end gap-2 border-t border-slate-100 pt-2.5">
                      {filterOnlyCount ? (
                        <button
                          type="button"
                          onClick={clearFilters}
                          className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
                        >
                          <FiX className="h-3.5 w-3.5" />
                          Clear
                        </button>
                      ) : null}
                      <button
                        type="button"
                        onClick={() => setShowFilters(false)}
                        className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15]"
                      >
                        Done
                      </button>
                    </div>
                  </div>
                ) : null}
              </div>
            }
            caption={
              <p className="text-[11.5px] text-slate-400">
                Showing {visibleAccounts.length} of {accounts.length} account{accounts.length === 1 ? '' : 's'}
              </p>
            }
            emptyState={
              accounts.length
                ? 'No accounts match the current filters.'
                : 'No accounts saved yet — add your first carrier account.'
            }
            csvFilename="carrier-accounts"
            toolbarActions={
              <button
                type="button"
                onClick={() => openDrawer()}
                className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
              >
                <FiPlus className="h-3.5 w-3.5" /> Add Account
              </button>
            }
          />
        )}
      </section>

      {/* ===== add / edit drawer ===== */}
      {drawerOpen ? (
        <>
          <div className="fixed inset-0 z-40 bg-slate-950/45" onClick={() => setDrawerOpen(false)} aria-hidden="true" />
          <aside
            role="dialog"
            aria-modal="true"
            aria-label={drawer.editingId ? 'Update carrier account' : 'Add carrier account'}
            className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[420px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
          >
            <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
              <div>
                <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">Account book</p>
                <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
                  {drawer.editingId ? `Update ${drawer.accountNumber}` : 'Add carrier account'}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setDrawerOpen(false)}
                aria-label="Close"
                className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
              >
                <FiX className="h-4 w-4" />
              </button>
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
              {/* Account type toggle */}
              <DrawerStep n="1" title={drawer.editingId ? 'Account type (locked)' : 'Account type'}>
                <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="Account type">
                  {(['platform', 'client'] as const).map((t) => {
                    const selected = drawer.accountType === t
                    const locked = drawer.editingId !== null
                    return (
                      <button
                        key={t}
                        type="button"
                        role="radio"
                        aria-checked={selected}
                        onClick={() => {
                          if (locked) return
                          setDrawer((c) => ({ ...c, accountType: t }))
                        }}
                        disabled={locked}
                        aria-disabled={locked}
                        title={
                          locked
                            ? selected
                              ? 'Account type is locked once the account is saved.'
                              : "Can't switch account type on an existing account — delete and recreate to change."
                            : undefined
                        }
                        className={`rounded-xl border px-3 py-2.5 text-[12px] font-semibold transition ${
                          selected
                            ? locked
                              ? 'cursor-not-allowed border-slate-300 bg-slate-100 text-slate-700'
                              : 'border-[#1f150c] bg-white text-[#1f150c]'
                            : locked
                              ? 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300 opacity-60'
                              : 'border-slate-200 bg-slate-50 text-slate-500 hover:bg-slate-100'
                        }`}
                      >
                        {t === 'platform' ? 'Platform account' : 'Client account'}
                      </button>
                    )
                  })}
                </div>
                {drawer.editingId !== null ? (
                  <p className="mt-2 text-[10.5px] text-slate-400">
                    Account type is set at creation time. To switch, delete this account and add a new one.
                  </p>
                ) : null}
                {drawer.accountType === 'client' ? (
                  <div className="mt-2.5">
                    <Field label={drawer.editingId ? 'Client (locked once saved)' : 'Client'}>
                      <Select
                        value={drawer.customerNo}
                        onChange={(e) => setDrawer((c) => ({ ...c, customerNo: e.target.value }))}
                        disabled={drawer.editingId !== null}
                      >
                        <option value="">Select a client…</option>
                        {clients.map((c) => (
                          <option key={c.code} value={c.code}>
                            {formatClientOption(c)}
                          </option>
                        ))}
                      </Select>
                    </Field>
                    {drawer.editingId !== null ? (
                      <p className="-mt-1 text-[10.5px] text-slate-400">
                        Re-assigning an account to a different client isn't supported — delete and recreate.
                      </p>
                    ) : null}
                  </div>
                ) : (
                  <p className="mt-2 text-[11px] text-slate-500">
                    A platform account is your own — pickable manually for any order.
                  </p>
                )}
              </DrawerStep>

              <DrawerStep n="2" title={drawer.editingId ? 'Carrier (locked)' : 'Carrier'}>
                <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-label="Carrier">
                  {carrierOptions.map((option) => {
                    const selected = drawer.carrierCode === option.code
                    const locked = drawer.editingId !== null
                    // On edit the whole row is read-only. We render every
                    // option so the picked carrier is still visible, but
                    // disable clicks and mute the unselected ones so it's
                    // obvious which one is locked in.
                    return (
                      <button
                        key={option.code}
                        type="button"
                        role="radio"
                        aria-checked={selected}
                        onClick={() => {
                          if (locked) return
                          setDrawer((c) => ({ ...c, carrierCode: option.code }))
                        }}
                        disabled={locked}
                        aria-disabled={locked}
                        title={
                          locked
                            ? selected
                              ? 'Carrier is locked once the account is saved.'
                              : "Can't change carrier on an existing account — delete and recreate to switch."
                            : undefined
                        }
                        className={`grid justify-items-center gap-1.5 rounded-2xl border px-2 py-2.5 text-[10.5px] font-bold transition ${
                          selected
                            ? locked
                              ? 'cursor-not-allowed border-slate-300 bg-slate-100 text-slate-700'
                              : 'border-slate-950 bg-white text-slate-950'
                            : locked
                              ? 'cursor-not-allowed border-slate-100 bg-slate-50 text-slate-300 opacity-60'
                              : 'border-slate-200 bg-slate-50 text-slate-500 hover:bg-slate-100'
                        }`}
                      >
                        <CarrierLogo
                          carrierId={option.id}
                          size={18}
                          className={`rounded-sm ${!selected && drawer.editingId ? 'opacity-50 grayscale' : ''}`}
                        />
                        {option.label}
                      </button>
                    )
                  })}
                </div>
                {drawer.editingId !== null ? (
                  <p className="mt-2 text-[10.5px] text-slate-400">
                    Carrier is set at creation time. To move this account to a different carrier, delete it and add a new one.
                  </p>
                ) : null}
                {!carrierOptions.some((o) => o.code === drawer.carrierCode) ? (
                  <p className="mt-2 flex items-start gap-1.5 rounded-xl border border-amber-200 bg-amber-50 px-2.5 py-1.5 text-[11px] text-amber-800">
                    <FiAlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
                    <span>
                      Stored carrier code <span className="font-semibold">{drawer.carrierCode}</span> isn't in the
                      supported list — delete and recreate to switch it.
                    </span>
                  </p>
                ) : null}
              </DrawerStep>

              <DrawerStep n="3" title="Credentials">
                <Field label="Account name">
                  <input
                    ref={accountNameRef}
                    value={drawer.accountName}
                    onChange={(e) => setDrawer((c) => ({ ...c, accountName: e.target.value }))}
                    className={inputClassName}
                    placeholder='Shown across the app, e.g. "Acme UPS"'
                  />
                </Field>
                <Field label={drawer.editingId ? 'Account number (locked)' : 'Account number'}>
                  <input
                    value={drawer.accountNumber}
                    onChange={(e) => setDrawer((c) => ({ ...c, accountNumber: e.target.value }))}
                    className={`${inputClassName} ${
                      drawer.editingId !== null
                        ? 'cursor-not-allowed !bg-slate-100 text-slate-500'
                        : ''
                    }`}
                    placeholder="e.g. 740561111"
                    readOnly={drawer.editingId !== null}
                  />
                </Field>

                {drawer.editingId !== null && !rotatingCredentials ? (
                  // Masked read-only view: credentials never come back from the
                  // API, so we show what we DO know (the clientId preview stored
                  // on the row) and expose an explicit Rotate button that
                  // switches the fields into edit mode.
                  <div className="mb-2.5 rounded-xl border border-slate-200 bg-slate-50 p-3">
                    <div className="mb-2 flex items-center justify-between gap-2">
                      <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-slate-600">
                        <FiKey className="h-3 w-3 text-emerald-600" />
                        Credentials on file
                      </span>
                      <button
                        type="button"
                        onClick={() => {
                          setRotatingCredentials(true)
                          setDrawer((c) => ({ ...c, clientId: '', clientSecret: '' }))
                          setDrawerCheck({ state: 'idle' })
                        }}
                        className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10.5px] font-semibold text-slate-600 transition hover:bg-slate-100"
                      >
                        <FiRefreshCw className="h-3 w-3" />
                        Rotate credentials
                      </button>
                    </div>
                    <p className="font-mono text-[11.5px] tracking-tight text-slate-500">
                      {credentialLabelsFor(drawer.carrierCode).idLong}: {(() => {
                        const preview = accounts.find((a) => a.id === drawer.editingId)?.clientIdPreview
                        return preview ? `${preview}` : '••••••••'
                      })()}
                    </p>
                    <p className="mt-0.5 font-mono text-[11.5px] tracking-tight text-slate-500">
                      {credentialLabelsFor(drawer.carrierCode).secretLong}: ••••••••••••
                    </p>
                    <p className="mt-2 text-[10.5px] leading-4 text-slate-400">
                      Credentials never leave the backend. Rotate them here if the carrier reissued keys.
                    </p>
                  </div>
                ) : (
                  <>
                    {drawer.editingId !== null ? (
                      <div className="mb-2.5 flex items-center justify-between rounded-xl border border-amber-200 bg-amber-50 px-2.5 py-1.5 text-[11px] text-amber-800">
                        <span className="inline-flex items-center gap-1.5">
                          <FiRefreshCw className="h-3 w-3" />
                          Entering new credentials — the current ones stay until you save.
                        </span>
                        <button
                          type="button"
                          onClick={() => {
                            setRotatingCredentials(false)
                            setDrawer((c) => ({ ...c, clientId: '', clientSecret: '' }))
                            setDrawerCheck({ state: 'idle' })
                          }}
                          className="text-[10.5px] font-semibold underline"
                        >
                          Cancel
                        </button>
                      </div>
                    ) : null}
                    <Field label={credentialLabelsFor(drawer.carrierCode).idLong}>
                      <input
                        value={drawer.clientId}
                        onChange={(e) => setDrawer((c) => ({ ...c, clientId: e.target.value }))}
                        className={inputClassName}
                        placeholder={credentialLabelsFor(drawer.carrierCode).idLong}
                        autoComplete="off"
                      />
                    </Field>
                    <Field label={credentialLabelsFor(drawer.carrierCode).secretLong}>
                      <input
                        type="password"
                        value={drawer.clientSecret}
                        onChange={(e) => setDrawer((c) => ({ ...c, clientSecret: e.target.value }))}
                        className={inputClassName}
                        placeholder={credentialLabelsFor(drawer.carrierCode).secretLong}
                        autoComplete="off"
                      />
                    </Field>
                    {credentialLabelsFor(drawer.carrierCode).helper ? (
                      <p className="-mt-1 mb-2 text-[10.5px] leading-4 text-slate-400">
                        {credentialLabelsFor(drawer.carrierCode).helper}
                      </p>
                    ) : null}
                  </>
                )}

                <Field label="Environment">
                  <Select
                    ref={environmentRef}
                    value={drawer.environment}
                    onChange={(e) => setDrawer((c) => ({ ...c, environment: e.target.value as CarrierEnvironment }))}
                  >
                    {carrierEnvironmentOptions.map((option) => (
                      <option key={option} value={option}>
                        {option}
                      </option>
                    ))}
                  </Select>
                </Field>
              </DrawerStep>

              <DrawerStep n="4" title="Verify & save">
                {(() => {
                  const verifyTerms = credentialLabelsFor(drawer.carrierCode)
                  const missing: string[] = []
                  if (!drawer.accountNumber.trim()) missing.push('Account number')
                  if (!drawer.clientId.trim()) missing.push(verifyTerms.idLong)
                  if (!drawer.clientSecret.trim()) missing.push(verifyTerms.secretLong)
                  const checking = drawerCheck.state === 'checking'
                  const disabled = checking || missing.length > 0
                  const tooltip = missing.length
                    ? `Fill in ${missing.join(', ')} to run a live check.`
                    : checking
                      ? 'Verification in progress…'
                      : undefined
                  return (
                    <>
                      <div className="flex items-center justify-between gap-2">
                        {checking ? (
                          <span className="inline-flex items-center gap-1.5 rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[10.5px] font-bold text-slate-500">
                            <span className="inline-block h-2.5 w-2.5 animate-spin rounded-full border-2 border-slate-400 border-t-transparent" />
                            Checking…
                          </span>
                        ) : drawerCheck.state === 'ok' ? (
                          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2.5 py-1 text-[10.5px] font-bold text-emerald-700">
                            <FiCheckCircle className="h-3 w-3" />
                            Verified
                          </span>
                        ) : drawerCheck.state === 'fail' ? (
                          <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2.5 py-1 text-[10.5px] font-bold text-rose-700">
                            <FiXCircle className="h-3 w-3" />
                            Check failed
                          </span>
                        ) : (
                          <span className="rounded-full border border-slate-200 bg-slate-50 px-2.5 py-1 text-[10.5px] font-bold text-slate-500">
                            Not verified yet
                          </span>
                        )}
                        <button
                          type="button"
                          onClick={() => void runDrawerCheck()}
                          disabled={disabled}
                          aria-disabled={disabled}
                          title={tooltip}
                          className={
                            disabled
                              ? 'inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-slate-100 px-3 py-1.5 text-[11px] font-semibold text-slate-400 cursor-not-allowed'
                              : 'inline-flex items-center gap-1.5 rounded-xl border border-emerald-600 bg-white px-3 py-1.5 text-[11px] font-semibold text-emerald-700 transition hover:bg-emerald-50 shadow-sm'
                          }
                        >
                          <FiShield className="h-3 w-3" />
                          Run verification
                        </button>
                      </div>
                      {drawerCheck.message ? (
                        <p className={`mt-2 text-[11px] leading-4 ${drawerCheck.state === 'ok' ? 'text-emerald-700' : 'text-rose-700'}`}>
                          {drawerCheck.message}
                        </p>
                      ) : missing.length ? (
                        <p className="mt-2 text-[10.5px] leading-4 text-slate-400">
                          Fill in <span className="font-semibold text-slate-500">{missing.join(', ')}</span> to run a live check against{' '}
                          {drawer.carrierCode === 'UPS' ? 'developer.ups.com' : formatCarrierName(drawer.carrierCode)}.
                        </p>
                      ) : null}
                    </>
                  )
                })()}
                {drawer.accountType === 'client' ? (
                  drawer.editingId !== null ? (
                    <div
                      className="mt-3 flex items-start gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-[12.5px] text-slate-500"
                      title="Change the client default from the star button on the row."
                    >
                      <input
                        type="checkbox"
                        checked={drawer.clientDefault}
                        disabled
                        aria-disabled
                        className="mt-0.5 h-4 w-4 cursor-not-allowed rounded border-slate-300 opacity-70"
                      />
                      <div>
                        <div>
                          {drawer.clientDefault
                            ? "This is the client's default account."
                            : "Not the client's default account."}
                        </div>
                        <div className="mt-0.5 text-[10.5px] text-slate-400">
                          Change the default from the star button on the row.
                        </div>
                      </div>
                    </div>
                  ) : (
                    <label className="mt-3 flex items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-2.5 text-[12.5px] text-slate-700">
                      <input
                        type="checkbox"
                        checked={drawer.clientDefault}
                        onChange={(e) => setDrawer((c) => ({ ...c, clientDefault: e.target.checked }))}
                        className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
                      />
                      Make this the client's default account
                    </label>
                  )
                ) : null}
              </DrawerStep>
            </div>

            <div className="flex gap-2 border-t border-slate-100 px-5 py-4">
              <button
                type="button"
                onClick={() => setDrawerOpen(false)}
                className="flex-1 rounded-xl border border-slate-200 bg-white px-3 py-2.5 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void handleSave()}
                disabled={saving}
                className="flex-1 rounded-xl bg-[#1f150c] px-3 py-2.5 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                {saving ? 'Saving…' : 'Save to Account Book'}
              </button>
            </div>
          </aside>
        </>
      ) : null}
    </div>
  )
}

function VerifiedChip({ account }: { account: CarrierAccountRef }) {
  if (account.verified === true) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-bold text-emerald-700">
        <FiCheckCircle className="h-2.5 w-2.5" />
        Verified
      </span>
    )
  }
  if (account.verified === false) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2 py-0.5 text-[10px] font-bold text-rose-700">
        <FiXCircle className="h-2.5 w-2.5" />
        Check failed
      </span>
    )
  }
  return <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold text-amber-700">⚠ Unverified</span>
}

/** Inline switch used in the Status cell to toggle account.active. */
function ActiveToggle({
  active,
  busy,
  onToggle,
}: {
  active: boolean
  busy: boolean
  onToggle: (event: React.MouseEvent<HTMLButtonElement>) => void
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={active}
      aria-label={active ? 'Deactivate account' : 'Activate account'}
      onClick={onToggle}
      disabled={busy}
      title={active ? 'Deactivate' : 'Activate'}
      className={`relative inline-flex h-4 w-7 shrink-0 items-center rounded-full transition disabled:opacity-50 ${
        active ? 'bg-emerald-500' : 'bg-slate-300'
      }`}
    >
      <span
        className={`inline-block h-3 w-3 transform rounded-full bg-white shadow transition ${
          active ? 'translate-x-3.5' : 'translate-x-0.5'
        }`}
      />
    </button>
  )
}

/**
 * Per-row kebab (Verify + Edit + Delete). Verify only shown when the account
 * is complete + active; Delete is available only when the account has never
 * generated a label (labelsGenerated = 0) — otherwise deactivation is the
 * right lever (preserves the audit trail).
 *
 * The popover is portaled to document.body because the enclosing
 * AdvancedDataTable wraps its rows in an `overflow: auto` scroller that would
 * otherwise clip a locally-positioned menu.
 */
function RowActionsMenu({
  account,
  busy,
  onVerify,
  onEdit,
  onDelete,
}: {
  account: CarrierAccountRef
  busy: boolean
  onVerify: () => void
  onEdit: () => void
  onDelete: () => void
}) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef<HTMLButtonElement>(null)

  const verifiable = account.complete && account.active
  const deletable = (account.labelsGenerated || 0) === 0

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        onClick={(e) => { e.stopPropagation(); setOpen((c) => !c) }}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Row actions"
        className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
      >
        <FiMoreVertical className="h-3.5 w-3.5" />
      </button>
      <PortalMenu open={open} anchorRef={buttonRef} onClose={() => setOpen(false)}>
        {verifiable ? (
          <button
            type="button"
            role="menuitem"
            onClick={() => { setOpen(false); onVerify() }}
            disabled={busy}
            className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
          >
            <FiShield className="h-3.5 w-3.5 text-emerald-600" />
            {account.verified === true ? 'Re-verify' : 'Verify'}
          </button>
        ) : null}
        <button
          type="button"
          role="menuitem"
          onClick={() => { setOpen(false); onEdit() }}
          className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50"
        >
          <FiEdit2 className="h-3.5 w-3.5 text-[#412d15]" />
          Edit
        </button>
        <button
          type="button"
          role="menuitem"
          onClick={() => { setOpen(false); onDelete() }}
          disabled={busy || !deletable}
          title={
            deletable
              ? 'Delete this account (never used)'
              : 'This account has generated labels — deactivate it instead to keep the audit trail.'
          }
          className="flex w-full items-center gap-2 border-t border-slate-100 px-3 py-2 text-[12px] font-semibold text-rose-600 transition hover:bg-rose-50 disabled:cursor-not-allowed disabled:text-slate-400 disabled:hover:bg-transparent"
        >
          <FiTrash2 className="h-3.5 w-3.5" />
          Delete
        </button>
      </PortalMenu>
    </>
  )
}

function DrawerStep({ n, title, children }: { n: string; title: string; children: ReactNode }) {
  return (
    <div className="rounded-2xl border border-slate-200 p-3.5">
      <div className="mb-3 flex items-center gap-2 text-[12.5px] font-semibold text-slate-950">
        <span className="grid h-5 w-5 place-items-center rounded-lg border border-slate-200 bg-slate-50 text-[10.5px] text-slate-500">
          {n}
        </span>
        {title}
      </div>
      {children}
    </div>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="mb-2.5 block">
      <span className="mb-1 block text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">{label}</span>
      {children}
    </label>
  )
}
