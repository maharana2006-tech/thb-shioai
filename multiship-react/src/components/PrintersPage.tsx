import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  FiAlertTriangle, FiCheckCircle, FiEdit2, FiLink, FiPlus, FiPrinter, FiRadio, FiTrash2, FiWifi, FiX,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import { clientService, type Client } from '../api/clientService'
import PrinterScanPanel from './PrinterScanPanel'
import {
  CONNECTION_LABEL,
  notifyPrinterProblem,
  PAPER_LABEL,
  printerService,
  type PrintDocType,
  type Printer,
  type PrinterAssignment,
  type PrinterConnection,
  type PrinterFormat,
  type PrinterInput,
  type PrinterPaper,
} from '../api/printerService'

// PR-Printer-R3 — tabbed page shell. Splits three concerns that used
// to be stacked vertically on one page:
//   - Printers tab: register + edit network printers, run test prints
//   - Scanners tab: LAN scanner enrollment + auto-discovery
//   - Assignments tab: which printer prints which client's docs
// Deep-linkable via ?tab=. Default = printers (Add-printer surface).
const TAB_KEYS = ['printers', 'scanners', 'assignments'] as const
type TabKey = typeof TAB_KEYS[number]

const TAB_META: Record<TabKey, { label: string; icon: typeof FiPrinter; help: string }> = {
  printers: {
    label: 'Printers',
    icon: FiPrinter,
    help: 'Register network printers and run test prints. Label printers usually take ZPL on port 9100; office printers take PDF over IPP.',
  },
  scanners: {
    label: 'Scanners',
    icon: FiWifi,
    help: 'Enroll a LAN scan agent per warehouse so new printers auto-discover instead of being typed by hand.',
  },
  assignments: {
    label: 'Assignments',
    icon: FiLink,
    help: 'Route each client’s labels and commercial invoices to a specific printer. Clients without their own printer use the Default row.',
  },
}

function isTabKey(v: string | null): v is TabKey {
  return v != null && (TAB_KEYS as readonly string[]).includes(v)
}

/**
 * Settings → Printers. Register network printers once, then choose which
 * printer each client's labels and commercial invoices go to. The Default row
 * covers every client without its own printer. ADMIN-only.
 *
 * PR-Printer-R3 restructured into three tabs (Printers / Scanners /
 * Assignments); deep-linkable via {@code ?tab=}.
 */
export default function PrintersPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tabFromUrl = searchParams.get('tab')
  const activeTab: TabKey = isTabKey(tabFromUrl) ? tabFromUrl : 'printers'
  const setTab = (next: TabKey) => {
    // Preserve any other query params (there aren't any today, but be safe).
    const params = new URLSearchParams(searchParams)
    if (next === 'printers') params.delete('tab')
    else params.set('tab', next)
    setSearchParams(params, { replace: true })
  }
  const [printers, setPrinters] = useState<Printer[]>([])
  const [assignments, setAssignments] = useState<PrinterAssignment[]>([])
  const [clients, setClients] = useState<Client[]>([])
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<Printer | 'new' | null>(null)
  const [testingId, setTestingId] = useState<number | null>(null)
  const [savingKey, setSavingKey] = useState<string | null>(null)
  const [extraClients, setExtraClients] = useState<string[]>([])
  // PR-Printer-P1.7 — tenant selector for the LAN scan panel. Reads the
  // already-loaded client list (this page is ADMIN-only via /printers'
  // hasRole('ADMIN'), so the admin sees every tenant regardless of
  // AccessScopePolicy). Remembers the last selection in localStorage so
  // returning to this page doesn't force the admin to re-pick every visit.
  const [scanTenant, setScanTenant] = useState<string>(() => readLastScanTenant())

  const load = useCallback(() => {
    return Promise.all([printerService.list(), printerService.listAssignments(), loadAllClients()])
      .then(([p, a, c]) => {
        setPrinters(p.data ?? [])
        setAssignments(a.data ?? [])
        setClients(c)
      })
      .catch((e) => notifyPrinterProblem('Printers did not load', e, 'Could not load printers. Refresh to try again.'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const printerById = useMemo(() => new Map(printers.map((p) => [p.id, p])), [printers])

  const runTest = async (p: Printer) => {
    setTestingId(p.id)
    try {
      const res = await printerService.test(p.id)
      const updated = res.data
      if (updated) setPrinters((list) => list.map((x) => (x.id === updated.id ? updated : x)))
      if (updated?.lastTestOk) notify.success(`${p.name}: ${updated.lastTestMessage ?? 'test job sent.'}`)
      else notify.error({ title: `${p.name} did not print`, body: updated?.lastTestMessage ?? 'The test job failed.', durationMs: 10_000 })
    } catch (e) {
      notifyPrinterProblem(`${p.name} did not print`, e, 'The test print could not be sent.')
    } finally {
      setTestingId(null)
    }
  }

  const removePrinter = async (p: Printer) => {
    const used = assignments.filter((a) => a.printerId === p.id).length
    const ok = await notify.confirm(
      used > 0
        ? `${p.name} is assigned to ${used} client document${used === 1 ? '' : 's'}. Those assignments are removed with it.`
        : `Remove ${p.name}?`,
      { title: 'Delete printer', confirmLabel: 'Delete', cancelLabel: 'Keep', danger: true },
    )
    if (!ok) return
    try {
      await printerService.remove(p.id)
      notify.success(`${p.name} removed.`)
      await load()
    } catch (e) {
      notifyPrinterProblem(`${p.name} was not deleted`, e, 'Could not delete the printer.')
    }
  }

  /** Rows: Default first, then every client with an assignment or added here. */
  const clientRows = useMemo(() => {
    const codes = new Set<string>()
    for (const a of assignments) if (a.clientCode) codes.add(a.clientCode.toUpperCase())
    for (const c of extraClients) codes.add(c)
    return [null, ...[...codes].sort()] as Array<string | null>
  }, [assignments, extraClients])

  const assignmentFor = (client: string | null, docType: PrintDocType) =>
    assignments.find((a) => (a.clientCode ?? null)?.toUpperCase?.() === client?.toUpperCase?.() && a.docType === docType)
    ?? (client === null ? assignments.find((a) => a.clientCode === null && a.docType === docType) : undefined)

  const setAssignment = async (client: string | null, docType: PrintDocType, printerId: string) => {
    const key = `${client ?? '*'}-${docType}`
    const current = assignmentFor(client, docType)
    setSavingKey(key)
    try {
      if (!printerId) {
        if (current) await printerService.unassign(current.id)
      } else {
        await printerService.assign(client, docType, Number(printerId))
      }
      const a = await printerService.listAssignments()
      setAssignments(a.data ?? [])
    } catch (e) {
      notifyPrinterProblem('Printer choice not saved', e, 'Could not save the assignment.')
    } finally {
      setSavingKey(null)
    }
  }

  const unassignedClients = clients
    .map((c) => c.clientCode.toUpperCase())
    .filter((code) => !clientRows.includes(code))

  // PR-Printer-P1.7 — dedupe + sort clientCodes for the scan-tenant
  // selector. Uppercased for stability with the PrinterScanController
  // path variable (tenantCode is a case-insensitive lookup on the
  // backend but the DB row is stored uppercase).
  const tenantChoices = useMemo(() => {
    const seen = new Set<string>()
    for (const c of clients) {
      const code = c.clientCode?.trim().toUpperCase()
      if (code) seen.add(code)
    }
    return [...seen].sort()
  }, [clients])

  const labelChoices = printers.filter((p) => p.active)
  const invoiceChoices = printers.filter((p) => p.active && p.format === 'PDF')

  const ActiveIcon = TAB_META[activeTab].icon

  return (
    <div className="space-y-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <ActiveIcon className="h-4 w-4 text-slate-500" />
            Printers
          </h2>
          <p className="mt-1 max-w-[70ch] text-[12.5px] text-slate-500">
            {TAB_META[activeTab].help}
          </p>
        </div>
        {activeTab === 'printers' ? (
          <button
            type="button"
            onClick={() => setEditing('new')}
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700"
          >
            <FiPlus className="h-3.5 w-3.5" />
            Add printer
          </button>
        ) : null}
      </header>

      {/* PR-Printer-R3 — tab strip. Deep-link via ?tab=; the Printers
          tab is the default (no query param). */}
      <nav
        role="tablist"
        aria-label="Printer settings sections"
        className="flex items-center gap-1 border-b border-slate-200"
      >
        {TAB_KEYS.map((key) => {
          const meta = TAB_META[key]
          const Icon = meta.icon
          const selected = key === activeTab
          return (
            <button
              key={key}
              type="button"
              role="tab"
              aria-selected={selected}
              onClick={() => setTab(key)}
              className={
                'inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-[13px] font-semibold transition-colors '
                + (selected
                  ? 'border-slate-900 text-slate-900'
                  : 'border-transparent text-slate-500 hover:text-slate-800')
              }
            >
              <Icon className="h-3.5 w-3.5" />
              {meta.label}
            </button>
          )
        })}
      </nav>

      {activeTab === 'scanners' ? (
        <>
          {/* PR-Printer-P1.7 — tenant selector. Sourced from the already-
              loaded client list (this page is ADMIN-only, so all tenants
              are visible regardless of AccessScopePolicy). Empty option
              shows nothing below; picking a tenant persists to localStorage. */}
          <section className="rounded-xl border border-slate-200 bg-white p-4">
            <div className="flex items-center gap-3">
              <label className="flex flex-1 items-center gap-2 text-[12px] font-semibold text-slate-600">
                Tenant
                <select
                  value={scanTenant}
                  onChange={(e) => {
                    const next = e.target.value
                    setScanTenant(next)
                    writeLastScanTenant(next)
                  }}
                  disabled={loading || tenantChoices.length === 0}
                  className="w-56 rounded-md border border-slate-200 bg-white px-2 py-1 text-[12.5px] text-slate-900 outline-none focus:border-slate-400 disabled:opacity-50"
                >
                  <option value="">
                    {tenantChoices.length === 0
                      ? (loading ? 'Loading tenants…' : 'No tenants available')
                      : 'Choose a tenant…'}
                  </option>
                  {tenantChoices.map((code) => (
                    <option key={code} value={code}>{code}</option>
                  ))}
                </select>
              </label>
              <span className="text-[11px] text-slate-400">
                Pick the tenant to enroll a LAN scanner for.
              </span>
            </div>
          </section>

          {scanTenant.trim() ? (
            // PR-Printer-R4 — key by tenantCode so a tenant switch
            // remounts the panel with fresh state (closes any open
            // enroll/picker modal, clears the live-discovered badge,
            // collapses the revoked section). Cleaner than manually
            // resetting all state in a useEffect.
            <PrinterScanPanel
              key={scanTenant.trim()}
              tenantCode={scanTenant.trim()}
              existingPrinters={printers}
              onImported={load}
            />
          ) : null}
        </>
      ) : null}

      {activeTab === 'printers' ? (
      <section className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="min-w-full text-[13px]">
          <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2">Printer</th>
              <th className="px-3 py-2">Connection</th>
              <th className="px-3 py-2">Address</th>
              <th className="px-3 py-2">Prints</th>
              <th className="px-3 py-2">Status</th>
              <th className="sticky right-0 bg-slate-50 px-3 py-2 text-right shadow-[-8px_0_8px_-8px_rgba(15,23,42,0.15)]">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr><td colSpan={6} className="px-3 py-6 text-center text-slate-500">Loading…</td></tr>
            ) : printers.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-8 text-center text-slate-500">
                  No printers yet. Add your label printer and your invoice printer to start routing documents.
                </td>
              </tr>
            ) : (
              printers.map((p) => (
                <tr key={p.id} className={p.active ? '' : 'opacity-60'}>
                  <td className="px-3 py-2.5">
                    <span className="block font-semibold text-slate-900">{p.name}</span>
                    {p.location ? <span className="block text-[11.5px] text-slate-500">{p.location}</span> : null}
                  </td>
                  <td className="px-3 py-2.5 text-slate-700">{CONNECTION_LABEL[p.connection] ?? p.connection}</td>
                  <td className="px-3 py-2.5 font-mono text-[12px] text-slate-700">
                    {p.host}:{p.port}{p.connection === 'IPP' && p.queuePath ? `/${p.queuePath}` : ''}
                  </td>
                  <td className="px-3 py-2.5">
                    <span className="inline-flex items-center gap-1.5">
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-700">{p.format}</span>
                      <span className="text-[12px] text-slate-500">{PAPER_LABEL[p.paper] ?? p.paper}</span>
                    </span>
                  </td>
                  <td className="px-3 py-2.5">
                    {!p.active ? (
                      <span className="text-[12px] text-slate-500">Switched off</span>
                    ) : p.lastTestOk === true ? (
                      <span className="inline-flex items-center gap-1 text-[12px] font-semibold text-emerald-700" title={p.lastTestMessage ?? undefined}>
                        <FiCheckCircle className="h-3.5 w-3.5" /> Test OK
                      </span>
                    ) : p.lastTestOk === false ? (
                      <span className="inline-flex max-w-[260px] items-center gap-1 truncate text-[12px] font-semibold text-rose-700" title={p.lastTestMessage ?? undefined}>
                        <FiAlertTriangle className="h-3.5 w-3.5 shrink-0" /> Test failed
                      </span>
                    ) : (
                      <span className="text-[12px] text-slate-500">Not tested</span>
                    )}
                  </td>
                  <td className="sticky right-0 bg-white px-3 py-2.5 shadow-[-8px_0_8px_-8px_rgba(15,23,42,0.15)]">
                    <span className="flex items-center justify-end gap-1.5">
                      <button
                        type="button"
                        onClick={() => void runTest(p)}
                        disabled={testingId !== null}
                        className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2 py-1 text-[12px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                      >
                        {testingId === p.id
                          ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                          : <FiRadio className="h-3 w-3" />}
                        Test print
                      </button>
                      <button type="button" onClick={() => setEditing(p)} aria-label={`Edit ${p.name}`}
                        className="rounded-md border border-slate-300 bg-white p-1.5 text-slate-600 hover:bg-slate-50">
                        <FiEdit2 className="h-3.5 w-3.5" />
                      </button>
                      <button type="button" onClick={() => void removePrinter(p)} aria-label={`Delete ${p.name}`}
                        className="rounded-md border border-slate-300 bg-white p-1.5 text-slate-600 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-700">
                        <FiTrash2 className="h-3.5 w-3.5" />
                      </button>
                    </span>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </section>
      ) : null}

      {activeTab === 'assignments' ? (
      <section className="rounded-xl border border-slate-200 bg-white">
        <div className="flex flex-wrap items-end justify-between gap-3 border-b border-slate-100 px-4 py-3">
          <div>
            <h3 className="text-[14px] font-semibold text-slate-900">Where each client prints</h3>
            <p className="mt-0.5 text-[12px] text-slate-500">
              Invoices need a PDF printer. Changes save as soon as you pick a printer.
            </p>
          </div>
          <label className="flex items-center gap-2 text-[12.5px] text-slate-600">
            Add a client
            <select
              value=""
              onChange={(e) => { if (e.target.value) setExtraClients((l) => [...l, e.target.value]) }}
              disabled={unassignedClients.length === 0}
              className="rounded-md border border-slate-300 bg-white px-2 py-1 text-[13px] disabled:opacity-50"
            >
              <option value="">{unassignedClients.length === 0 ? 'All clients listed' : 'Choose…'}</option>
              {unassignedClients.map((code) => <option key={code} value={code}>{code}</option>)}
            </select>
          </label>
        </div>
        <div className="overflow-x-auto">
          <table className="min-w-full text-[13px]">
            <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-2">Client</th>
                <th className="px-4 py-2">Labels print on</th>
                <th className="px-4 py-2">Commercial invoices print on</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {clientRows.map((client) => (
                <tr key={client ?? 'default'}>
                  <td className="px-4 py-2.5">
                    {client === null ? (
                      <span>
                        <span className="block font-semibold text-slate-900">Default</span>
                        <span className="block text-[11.5px] text-slate-500">Every client without its own printer</span>
                      </span>
                    ) : (
                      <span className="font-semibold text-slate-900">{client}</span>
                    )}
                  </td>
                  {(['LABEL', 'COMMERCIAL_INVOICE'] as PrintDocType[]).map((docType) => {
                    const current = assignmentFor(client, docType)
                    const choices = docType === 'LABEL' ? labelChoices : invoiceChoices
                    const key = `${client ?? '*'}-${docType}`
                    const missing = current && !printerById.get(current.printerId)?.active
                    return (
                      <td key={docType} className="px-4 py-2.5">
                        <span className="inline-flex items-center gap-2">
                          <select
                            value={current ? String(current.printerId) : ''}
                            onChange={(e) => void setAssignment(client, docType, e.target.value)}
                            disabled={savingKey !== null || choices.length === 0}
                            className="min-w-[200px] rounded-md border border-slate-300 bg-white px-2 py-1 text-[13px] disabled:opacity-50"
                          >
                            <option value="">
                              {client === null ? 'No default printer' : 'Use the default'}
                            </option>
                            {current && !choices.some((p) => p.id === current.printerId) ? (
                              <option value={String(current.printerId)}>
                                {printerById.get(current.printerId)?.name ?? `Printer ${current.printerId}`} (switched off)
                              </option>
                            ) : null}
                            {choices.map((p) => (
                              <option key={p.id} value={String(p.id)}>{p.name} · {p.format}</option>
                            ))}
                          </select>
                          {savingKey === key ? (
                            <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                          ) : missing ? (
                            <span className="text-[11.5px] font-semibold text-amber-700" title="This printer is switched off, so the default is used instead">
                              switched off
                            </span>
                          ) : null}
                        </span>
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
      ) : null}

      {editing ? (
        <PrinterEditor
          printer={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => { setEditing(null); await load() }}
        />
      ) : null}
    </div>
  )
}

// PR-Printer-P1.7 — remember the last-picked scan tenant across page
// visits. The key is registered in utils/session.ts so clearAppStorage()
// wipes it on logout — a shared machine must not hand the next admin a
// stale tenant scope on the printers page.
const SCAN_TENANT_STORAGE_KEY = 'multiship_scan_tenant'

function readLastScanTenant(): string {
  if (typeof window === 'undefined') return ''
  try { return window.localStorage.getItem(SCAN_TENANT_STORAGE_KEY) ?? '' }
  catch { return '' }
}

function writeLastScanTenant(value: string): void {
  if (typeof window === 'undefined') return
  try {
    if (value.trim()) window.localStorage.setItem(SCAN_TENANT_STORAGE_KEY, value)
    else window.localStorage.removeItem(SCAN_TENANT_STORAGE_KEY)
  } catch { /* private mode / disabled storage — silently drop */ }
}

/** Every client, a page of 100 at a time (the list endpoint's cap). */
async function loadAllClients(): Promise<Client[]> {
  const all: Client[] = []
  try {
    for (let page = 0; page < 50; page++) {
      const res = await clientService.listClients({ page, size: 100, sortBy: 'code' })
      const data = res.data
      all.push(...(data?.content ?? []))
      if (!data || page + 1 >= data.totalPages) break
    }
  } catch {
    // The assignment grid still works for clients already assigned.
  }
  return all
}

function PrinterEditor({ printer, onClose, onSaved }: { printer: Printer | null; onClose: () => void; onSaved: () => Promise<void> }) {
  const [form, setForm] = useState<PrinterInput>(() => printer
    ? {
        name: printer.name, location: printer.location, connection: printer.connection, host: printer.host,
        port: printer.port, queuePath: printer.queuePath, format: printer.format, paper: printer.paper, active: printer.active,
      }
    : { name: '', location: '', connection: 'RAW_9100', host: '', port: null, queuePath: '', format: 'ZPL', paper: 'LABEL_4X6', active: true })
  const [saving, setSaving] = useState(false)
  const set = <K extends keyof PrinterInput>(key: K, value: PrinterInput[K]) => setForm((f) => ({ ...f, [key]: value }))

  const setConnection = (c: PrinterConnection) => setForm((f) => ({
    ...f,
    connection: c,
    // IPP printers take PDF; a label printer on 9100 usually takes ZPL.
    format: c === 'IPP' ? 'PDF' : f.format,
    paper: c === 'IPP' && f.paper === 'LABEL_4X6' ? 'LETTER' : f.paper,
    queuePath: c === 'IPP' ? (f.queuePath || 'ipp/print') : f.queuePath,
  }))
  const setFormat = (fmt: PrinterFormat) => setForm((f) => ({
    ...f,
    format: fmt,
    paper: fmt === 'ZPL' ? 'LABEL_4X6' : f.paper === 'LABEL_4X6' ? 'LETTER' : f.paper,
  }))

  const [errors, setErrors] = useState<{ name?: string; host?: string; port?: string; form?: string }>({})
  const clearError = (key: 'name' | 'host' | 'port') => setErrors((cur) => ({ ...cur, [key]: undefined, form: undefined }))

  const save = async () => {
    const found: typeof errors = {}
    if (!form.name.trim()) found.name = 'Give the printer a name, e.g. "Dock 1 Zebra".'
    if (!form.host.trim()) found.host = "Enter the printer's IP address or hostname."
    else if (/^[a-z]+:\/\//i.test(form.host.trim())) found.host = 'Enter just the IP address or hostname, without http:// or ipp://.'
    if (form.port != null && (form.port < 1 || form.port > 65535)) found.port = 'Port must be between 1 and 65535.'
    setErrors(found)
    if (Object.keys(found).length > 0) return

    setSaving(true)
    try {
      const payload: PrinterInput = { ...form, port: form.port ? Number(form.port) : null }
      if (printer) await printerService.update(printer.id, payload)
      else await printerService.create(payload)
      notify.success(`${form.name.trim()} saved.`)
      await onSaved()
    } catch (e) {
      // Show the server's reason next to the field it is about.
      const message = e instanceof Error && e.message ? e.message : 'Could not save the printer.'
      const lower = message.toLowerCase()
      if (lower.includes('name')) setErrors({ name: message })
      else if (lower.includes('hostname') || lower.includes('ip address')) setErrors({ host: message })
      else if (lower.includes('port')) setErrors({ port: message })
      else setErrors({ form: message })
    } finally {
      setSaving(false)
    }
  }
  const fieldError = (msg?: string) => msg
    ? <span role="alert" className="mt-1 block text-[11.5px] font-medium text-rose-700">{msg}</span>
    : null

  const input = 'w-full rounded-md border border-slate-300 px-2.5 py-1.5 text-[13px] outline-none focus:border-slate-500'
  // Separate class sets: two border colours on one element fight in the cascade.
  const fieldCls = (invalid: boolean, extra = '') => invalid
    ? `w-full rounded-md border border-rose-400 bg-rose-50/40 px-2.5 py-1.5 text-[13px] outline-none focus:border-rose-500 ${extra}`
    : `${input} ${extra}`
  const labelCls = 'mb-1 block text-[11.5px] font-semibold uppercase tracking-wide text-slate-500'

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={onClose}>
      <div className="w-full max-w-[560px] rounded-xl bg-white shadow-xl" onClick={(e) => e.stopPropagation()}
        role="dialog" aria-modal="true" aria-label={printer ? `Edit ${printer.name}` : 'Add printer'}>
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3">
          <h3 className="text-[15px] font-semibold text-slate-900">{printer ? `Edit ${printer.name}` : 'Add printer'}</h3>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-md p-1 text-slate-500 hover:bg-slate-100">
            <FiX className="h-4 w-4" />
          </button>
        </div>
        <div className="grid grid-cols-2 gap-3 px-5 py-4">
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>Name</span>
            <input className={fieldCls(!!errors.name)} value={form.name} aria-invalid={!!errors.name}
              onChange={(e) => { set('name', e.target.value); clearError('name') }} placeholder="Dock 1 Zebra" />
            {fieldError(errors.name)}
          </label>
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>Location</span>
            <input className={input} value={form.location ?? ''} onChange={(e) => set('location', e.target.value)} placeholder="Warehouse A, packing bay 1" />
          </label>
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>Connection</span>
            <select className={input} value={form.connection} onChange={(e) => setConnection(e.target.value as PrinterConnection)}>
              <option value="RAW_9100">Network port (9100)</option>
              <option value="IPP">IPP</option>
            </select>
          </label>
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>Prints</span>
            <select className={input} value={form.format} onChange={(e) => setFormat(e.target.value as PrinterFormat)}>
              <option value="ZPL" disabled={form.connection === 'IPP'}>ZPL (thermal label printer)</option>
              <option value="PDF">PDF</option>
            </select>
          </label>
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>IP address or hostname</span>
            <input className={fieldCls(!!errors.host, 'font-mono')} value={form.host} aria-invalid={!!errors.host}
              onChange={(e) => { set('host', e.target.value); clearError('host') }} placeholder="192.168.1.50" />
            {fieldError(errors.host)}
          </label>
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>Port</span>
            <input className={fieldCls(!!errors.port, 'font-mono')} aria-invalid={!!errors.port} inputMode="numeric" value={form.port ?? ''}
              onChange={(e) => { set('port', e.target.value === '' ? null : Number(e.target.value.replace(/\D/g, ''))); clearError('port') }}
              placeholder={form.connection === 'IPP' ? '631' : '9100'} />
            {fieldError(errors.port)}
          </label>
          {form.connection === 'IPP' ? (
            <label className="col-span-2">
              <span className={labelCls}>Queue path</span>
              <input className={`${input} font-mono`} value={form.queuePath ?? ''} onChange={(e) => set('queuePath', e.target.value)} placeholder="ipp/print" />
              <span className="mt-1 block text-[11.5px] text-slate-500">Printers usually use ipp/print; a CUPS server uses printers/&lt;queue name&gt;.</span>
            </label>
          ) : null}
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>Paper</span>
            <select className={input} value={form.paper ?? ''} onChange={(e) => set('paper', e.target.value as PrinterPaper)} disabled={form.format === 'ZPL'}>
              <option value="LABEL_4X6" disabled={form.format !== 'ZPL'}>4×6 label</option>
              <option value="LETTER">Letter</option>
              <option value="A4">A4</option>
            </select>
          </label>
          <label className="col-span-2 flex items-center gap-2 self-end sm:col-span-1">
            <input type="checkbox" checked={form.active} onChange={(e) => set('active', e.target.checked)} />
            <span className="text-[13px] text-slate-700">Active</span>
          </label>
          {errors.form ? (
            <p role="alert" className="col-span-2 rounded-md bg-rose-50 px-3 py-2 text-[12.5px] text-rose-800">{errors.form}</p>
          ) : null}
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button type="button" onClick={onClose} className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 hover:bg-slate-50">
            Cancel
          </button>
          <button type="button" onClick={() => void save()} disabled={saving}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700 disabled:opacity-50">
            {saving ? 'Saving…' : 'Save printer'}
          </button>
        </div>
      </div>
    </div>
  )
}
