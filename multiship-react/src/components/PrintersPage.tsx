import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  FiAlertTriangle, FiCheckCircle, FiCheckSquare, FiCopy, FiEdit2, FiFileText, FiPlus, FiPower,
  FiPrinter, FiRadio, FiSearch, FiSquare, FiTag, FiTrash2, FiWifi, FiX,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import { clientService, type Client } from '../api/clientService'
import AssignmentMatrix from './AssignmentMatrix'
import InvoiceCopiesMatrix from './InvoiceCopiesMatrix'
import PrinterScanPanel from './PrinterScanPanel'
import {
  CONNECTION_LABEL,
  notifyPrinterProblem,
  PAPER_LABEL,
  printerService,
  type Printer,
  type PrinterAssignment,
  type PrinterConnection,
  type PrinterFormat,
  type PrinterInput,
  type PrinterPaper,
} from '../api/printerService'

// PR-Printer-R3 + R7b — tabbed page shell. R7b split the old
// "Assignments" tab into three because label printers, invoice
// printers, and per-carrier copy counts are three distinct concerns
// admins configure independently.
//   - Printers tab: register + edit network printers, run test prints
//   - Scanners tab: LAN scanner enrollment + auto-discovery
//   - Labels tab:   which printer prints each client's shipping labels
//   - Invoices tab: which (PDF) printer prints each client's commercial invoices
//   - Copies tab:   how many invoice copies per (client, carrier)
// Deep-linkable via ?tab=. Default = printers (Add-printer surface).
const TAB_KEYS = ['printers', 'scanners', 'labels', 'invoices', 'copies'] as const
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
  labels: {
    label: 'Labels',
    icon: FiTag,
    help: 'Route each client’s shipping labels to a specific printer. Clients without their own routing use the Default row.',
  },
  invoices: {
    label: 'Invoices',
    icon: FiFileText,
    help: 'Route each client’s commercial invoices to a specific PDF printer. ZPL printers can’t print invoices and are hidden.',
  },
  copies: {
    label: 'Copies',
    icon: FiCopy,
    help: 'How many physical copies of the commercial invoice to print per (client, carrier). Default row = tenant-wide default per carrier; missing rule = 1.',
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
  // PR-Printer-R5 — was single scalar (testingId), which blocked
  // every Test button while one test was in flight. Now a Set so
  // per-row spinners work AND bulk Test-N can run concurrently.
  const [testingIds, setTestingIds] = useState<Set<number>>(new Set())
  // Rows appended by the "Add a client" dropdown on the Assignments tab
  // so a client with no current assignments can still show up in the
  // matrix. Persisted only in-memory (fresh on each tab visit).
  const [extraClients, setExtraClients] = useState<string[]>([])
  // PR-Printer-R5 — search over name + host + location; multi-select
  // driving the sticky bulk-action bar (Test N / Deactivate N / Delete N).
  const [search, setSearch] = useState('')
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [bulkOp, setBulkOp] = useState<'test' | 'deactivate' | 'delete' | null>(null)
  // PR-Printer-P1.7 — tenant selector for the LAN scan panel. Reads the
  // already-loaded client list (this page is ADMIN-only via /printers'
  // hasRole('ADMIN'), so the admin sees every tenant regardless of
  // AccessScopePolicy). Remembers the last selection in localStorage so
  // returning to this page doesn't force the admin to re-pick every visit.
  const [scanTenant, setScanTenant] = useState<string>(() => readLastScanTenant())
  // PR-Printer-R8b — flat tags-by-printer-id map + distinct-tag list
  // (autocomplete). Both loaded once at mount; refreshed after a save.
  const [tagsByPrinter, setTagsByPrinter] = useState<Map<number, string[]>>(new Map())
  const [distinctTags, setDistinctTags] = useState<string[]>([])

  const load = useCallback(() => {
    return Promise.all([
      printerService.list(),
      printerService.listAssignments(),
      loadAllClients(),
      printerService.listDistinctPrinterTags().catch(() => ({ data: [] as string[] })),
    ])
      .then(([p, a, c, tRes]) => {
        const printers = p.data ?? []
        setPrinters(printers)
        setAssignments(a.data ?? [])
        setClients(c)
        setDistinctTags(tRes.data ?? [])
        // R8b — fetch each printer's tags in parallel. N small
        // requests is simpler than a batch endpoint (which the
        // backend service supports via tagsByPrinterId but doesn't
        // expose over REST yet). Fine for tenants with dozens, not
        // hundreds, of printers.
        return Promise.all(
          printers.map((pr) =>
            printerService.listPrinterTags(pr.id)
              .then((r) => [pr.id, (r.data ?? []).map((t) => t.tag)] as [number, string[]])
              .catch(() => [pr.id, [] as string[]] as [number, string[]]),
          ),
        ).then((entries) => setTagsByPrinter(new Map(entries)))
      })
      .catch((e) => notifyPrinterProblem('Printers did not load', e, 'Could not load printers. Refresh to try again.'))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const runTest = async (p: Printer) => {
    setTestingIds((cur) => new Set(cur).add(p.id))
    try {
      const res = await printerService.test(p.id)
      const updated = res.data
      if (updated) setPrinters((list) => list.map((x) => (x.id === updated.id ? updated : x)))
      if (updated?.lastTestOk) notify.success(`${p.name}: ${updated.lastTestMessage ?? 'test job sent.'}`)
      else notify.error({ title: `${p.name} did not print`, body: updated?.lastTestMessage ?? 'The test job failed.', durationMs: 10_000 })
    } catch (e) {
      notifyPrinterProblem(`${p.name} did not print`, e, 'The test print could not be sent.')
    } finally {
      setTestingIds((cur) => {
        const next = new Set(cur)
        next.delete(p.id)
        return next
      })
    }
  }

  // PR-Printer-R5 — bulk ops. All three call the existing per-printer
  // endpoints N times in parallel (no dedicated batch endpoint on the
  // backend, matching the P3 picker's bulk-add pattern). Failures
  // don't abort the batch; a summary toast reports added-vs-failed.
  const runBulkTest = async () => {
    if (bulkOp || selectedIds.size === 0) return
    const targets = printers.filter((p) => selectedIds.has(p.id))
    setBulkOp('test')
    setTestingIds((cur) => {
      const next = new Set(cur)
      targets.forEach((p) => next.add(p.id))
      return next
    })
    let ok = 0
    const failures: string[] = []
    const settled = await Promise.allSettled(targets.map((p) => printerService.test(p.id)))
    settled.forEach((r, i) => {
      const p = targets[i]
      if (r.status === 'fulfilled' && r.value.data?.lastTestOk) ok++
      else failures.push(`${p.name} — ${r.status === 'rejected' && r.reason instanceof Error ? r.reason.message : 'test failed'}`)
    })
    // Optimistic reload — every test mutates lastTestAt/lastTestOk/lastTestMessage.
    await load()
    setTestingIds(new Set())
    setBulkOp(null)
    if (ok > 0) notify.success({ title: `Test print sent to ${ok}`, body: failures.length > 0 ? `${failures.length} failed — see below.` : 'All test prints were accepted.' })
    if (failures.length > 0) notify.error({
      title: `${failures.length} test${failures.length === 1 ? '' : 's'} failed`,
      body: failures.slice(0, 3).join('\n') + (failures.length > 3 ? `\n…and ${failures.length - 3} more` : ''),
      durationMs: 15_000,
    })
  }

  const runBulkDeactivate = async () => {
    if (bulkOp || selectedIds.size === 0) return
    const targets = printers.filter((p) => selectedIds.has(p.id) && p.active)
    if (targets.length === 0) {
      notify.info('Nothing to deactivate — all selected printers are already switched off.')
      return
    }
    const okConfirm = await notify.confirm(
      `Deactivate ${targets.length} printer${targets.length === 1 ? '' : 's'}? They'll stay in the list but stop receiving print jobs.`,
      { title: 'Deactivate printers', confirmLabel: `Deactivate ${targets.length}`, cancelLabel: 'Keep active' },
    )
    if (!okConfirm) return
    setBulkOp('deactivate')
    let ok = 0
    const failures: string[] = []
    const settled = await Promise.allSettled(targets.map((p) => printerService.update(p.id, {
      name: p.name, location: p.location, connection: p.connection, host: p.host, port: p.port,
      queuePath: p.queuePath, format: p.format, paper: p.paper, active: false,
    })))
    settled.forEach((r, i) => {
      if (r.status === 'fulfilled') ok++
      else failures.push(`${targets[i].name} — ${r.reason instanceof Error ? r.reason.message : 'failed'}`)
    })
    await load()
    setSelectedIds(new Set())
    setBulkOp(null)
    if (ok > 0) notify.success(`Deactivated ${ok} printer${ok === 1 ? '' : 's'}.`)
    if (failures.length > 0) notify.error({
      title: `${failures.length} failed`,
      body: failures.slice(0, 3).join('\n'),
      durationMs: 15_000,
    })
  }

  const runBulkDelete = async () => {
    if (bulkOp || selectedIds.size === 0) return
    const targets = printers.filter((p) => selectedIds.has(p.id))
    const totalAssignmentImpact = assignments.filter((a) => selectedIds.has(a.printerId)).length
    const okConfirm = await notify.confirm(
      totalAssignmentImpact > 0
        ? `Delete ${targets.length} printer${targets.length === 1 ? '' : 's'}? ${totalAssignmentImpact} client assignment${totalAssignmentImpact === 1 ? '' : 's'} will be removed with them.`
        : `Delete ${targets.length} printer${targets.length === 1 ? '' : 's'}?`,
      { title: 'Delete printers', confirmLabel: `Delete ${targets.length}`, cancelLabel: 'Keep', danger: true },
    )
    if (!okConfirm) return
    setBulkOp('delete')
    let ok = 0
    const failures: string[] = []
    const settled = await Promise.allSettled(targets.map((p) => printerService.remove(p.id)))
    settled.forEach((r, i) => {
      if (r.status === 'fulfilled') ok++
      else failures.push(`${targets[i].name} — ${r.reason instanceof Error ? r.reason.message : 'failed'}`)
    })
    await load()
    setSelectedIds(new Set())
    setBulkOp(null)
    if (ok > 0) notify.success(`Removed ${ok} printer${ok === 1 ? '' : 's'}.`)
    if (failures.length > 0) notify.error({
      title: `${failures.length} failed`,
      body: failures.slice(0, 3).join('\n'),
      durationMs: 15_000,
    })
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

  // PR-Printer-R6 — assignmentFor / setAssignment / savingKey /
  // printerById moved into AssignmentMatrix. This shell just passes
  // the raw assignments list down and reloads on onChanged().

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

  // PR-Printer-R5 (+ R8b tags) — filter for the Printers-table search
  // input. Case-insensitive substring across name / host / location /
  // tag. Empty search = full list (perf: no filter cost).
  const filteredPrinters = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return printers
    return printers.filter((p) => {
      const tags = tagsByPrinter.get(p.id) ?? []
      return p.name.toLowerCase().includes(q)
        || p.host.toLowerCase().includes(q)
        || (p.location ?? '').toLowerCase().includes(q)
        || tags.some((t) => t.toLowerCase().includes(q))
    })
  }, [printers, search, tagsByPrinter])

  const allFilteredSelected = filteredPrinters.length > 0
    && filteredPrinters.every((p) => selectedIds.has(p.id))
  const toggleSelectAll = () => {
    setSelectedIds((cur) => {
      if (allFilteredSelected) {
        // Clear only the filtered set — preserve any selections outside
        // the current filter view.
        const next = new Set(cur)
        filteredPrinters.forEach((p) => next.delete(p.id))
        return next
      }
      const next = new Set(cur)
      filteredPrinters.forEach((p) => next.add(p.id))
      return next
    })
  }
  const toggleSelect = (id: number) => {
    setSelectedIds((cur) => {
      const next = new Set(cur)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

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
      <>
        {/* PR-Printer-R5 — search bar + selection counter above table. */}
        <section className="flex flex-wrap items-center gap-2">
          <label className="relative flex-1 min-w-[240px]">
            <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
            <input
              type="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search printers by name, host, or location…"
              className="w-full rounded-md border border-slate-200 bg-white pl-8 pr-3 py-1.5 text-[13px] text-slate-900 outline-none focus:border-slate-400"
            />
          </label>
          {search ? (
            <span className="text-[12px] text-slate-500">
              {filteredPrinters.length} of {printers.length}
            </span>
          ) : null}
        </section>

        <section className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
          <table className="min-w-full text-[13px]">
            <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-3 py-2 w-8">
                  <button
                    type="button"
                    onClick={toggleSelectAll}
                    disabled={filteredPrinters.length === 0}
                    aria-label={allFilteredSelected ? 'Clear selection' : 'Select all filtered'}
                    className="inline-flex items-center text-slate-500 hover:text-slate-800 disabled:opacity-40"
                  >
                    {allFilteredSelected
                      ? <FiCheckSquare className="h-4 w-4" />
                      : <FiSquare className="h-4 w-4" />}
                  </button>
                </th>
                <th className="px-3 py-2">Printer</th>
                <th className="px-3 py-2">Connection</th>
                <th className="px-3 py-2">Address</th>
                <th className="px-3 py-2">Prints</th>
                <th className="px-3 py-2">Tags</th>
                <th className="px-3 py-2">Status</th>
                <th className="sticky right-0 bg-slate-50 px-3 py-2 text-right shadow-[-8px_0_8px_-8px_rgba(15,23,42,0.15)]">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading ? (
                <tr><td colSpan={8} className="px-3 py-6 text-center text-slate-500">Loading…</td></tr>
              ) : printers.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-3 py-8 text-center text-slate-500">
                    No printers yet. Add your label printer and your invoice printer to start routing documents.
                  </td>
                </tr>
              ) : filteredPrinters.length === 0 ? (
                <tr>
                  <td colSpan={8} className="px-3 py-8 text-center text-slate-500">
                    No printers match “{search}”. <button type="button" onClick={() => setSearch('')} className="ml-1 underline">Clear search</button>
                  </td>
                </tr>
              ) : (
                filteredPrinters.map((p) => (
                  <tr key={p.id} className={p.active ? '' : 'opacity-60'}>
                    <td className="px-3 py-2.5">
                      <input
                        type="checkbox"
                        checked={selectedIds.has(p.id)}
                        onChange={() => toggleSelect(p.id)}
                        aria-label={`Select ${p.name}`}
                        disabled={bulkOp !== null}
                      />
                    </td>
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
                      {/* PR-Printer-R8b — tag chips. Empty state shows a
                          neutral dash so the column doesn't feel broken. */}
                      {(() => {
                        const tags = tagsByPrinter.get(p.id) ?? []
                        return tags.length === 0
                          ? <span className="text-[11px] text-slate-400">—</span>
                          : (
                            <span className="flex flex-wrap gap-1">
                              {tags.map((t) => (
                                <span key={t} className="rounded-full bg-indigo-100 px-2 py-0.5 text-[10.5px] font-semibold text-indigo-800">
                                  {t}
                                </span>
                              ))}
                            </span>
                          )
                      })()}
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
                          // PR-Printer-R5 — per-row disable only. Was
                          // testingId (single-slot mutex) → blocked every
                          // Test button while one test was in flight.
                          disabled={testingIds.has(p.id) || bulkOp !== null}
                          className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2 py-1 text-[12px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
                        >
                          {testingIds.has(p.id)
                            ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                            : <FiRadio className="h-3 w-3" />}
                          Test print
                        </button>
                        <button type="button" onClick={() => setEditing(p)} aria-label={`Edit ${p.name}`}
                          disabled={bulkOp !== null}
                          className="rounded-md border border-slate-300 bg-white p-1.5 text-slate-600 hover:bg-slate-50 disabled:opacity-50">
                          <FiEdit2 className="h-3.5 w-3.5" />
                        </button>
                        <button type="button" onClick={() => void removePrinter(p)} aria-label={`Delete ${p.name}`}
                          disabled={bulkOp !== null}
                          className="rounded-md border border-slate-300 bg-white p-1.5 text-slate-600 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-700 disabled:opacity-50">
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

        {/* PR-Printer-R5 — bulk-action bar. Sticky-bottom on narrow
            viewports (mobile), inline above nothing on desktop (still
            fixed at the bottom of the screen so scrolling through a
            long list keeps it in reach). Only rendered when a
            selection exists. */}
        {selectedIds.size > 0 ? (
          <div className="fixed bottom-0 left-0 right-0 z-40 border-t border-slate-200 bg-white shadow-[0_-8px_24px_-8px_rgba(15,23,42,0.15)] sm:sticky sm:bottom-4 sm:mx-auto sm:max-w-3xl sm:rounded-xl sm:border sm:shadow-lg">
            <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5">
              <span className="text-[13px] font-semibold text-slate-900">
                {selectedIds.size} selected
              </span>
              <span className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={() => void runBulkTest()}
                  disabled={bulkOp !== null}
                  className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {bulkOp === 'test'
                    ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                    : <FiRadio className="h-3.5 w-3.5" />}
                  Test {selectedIds.size}
                </button>
                <button
                  type="button"
                  onClick={() => void runBulkDeactivate()}
                  disabled={bulkOp !== null}
                  className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {bulkOp === 'deactivate'
                    ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                    : <FiPower className="h-3.5 w-3.5" />}
                  Deactivate {selectedIds.size}
                </button>
                <button
                  type="button"
                  onClick={() => void runBulkDelete()}
                  disabled={bulkOp !== null}
                  className="inline-flex items-center gap-1.5 rounded-md border border-rose-300 bg-rose-50 px-3 py-1.5 text-[12.5px] font-semibold text-rose-700 hover:bg-rose-100 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {bulkOp === 'delete'
                    ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-rose-300 border-t-rose-700" />
                    : <FiTrash2 className="h-3.5 w-3.5" />}
                  Delete {selectedIds.size}
                </button>
                <button
                  type="button"
                  onClick={() => setSelectedIds(new Set())}
                  disabled={bulkOp !== null}
                  aria-label="Clear selection"
                  className="rounded-md border border-slate-200 bg-white p-1.5 text-slate-500 hover:bg-slate-50 disabled:opacity-50"
                >
                  <FiX className="h-3.5 w-3.5" />
                </button>
              </span>
            </div>
          </div>
        ) : null}
      </>
      ) : null}

      {activeTab === 'labels' ? (
        <section className="space-y-3">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h3 className="text-[14px] font-semibold text-slate-900">Which printer prints each client’s labels</h3>
              <p className="mt-0.5 text-[12px] text-slate-500">
                Click a cell to route shipping labels
                (<span className="rounded-full bg-sky-100 px-1.5 py-0.5 text-[10px] font-semibold text-sky-800">L</span>)
                through that printer. Default row is the fallback for any client without its own routing.
              </p>
            </div>
            <AddClientDropdown
              unassignedClients={unassignedClients}
              onAdd={(code) => setExtraClients((l) => [...l, code])}
            />
          </div>
          <AssignmentMatrix
            docType="LABEL"
            clients={clientRows}
            printers={printers.filter((p) => p.active)}
            assignments={assignments}
            onChanged={load}
          />
        </section>
      ) : null}

      {activeTab === 'invoices' ? (
        <section className="space-y-3">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h3 className="text-[14px] font-semibold text-slate-900">Which printer prints each client’s commercial invoices</h3>
              <p className="mt-0.5 text-[12px] text-slate-500">
                Click a cell to route commercial invoices
                (<span className="rounded-full bg-violet-100 px-1.5 py-0.5 text-[10px] font-semibold text-violet-800">I</span>)
                through that printer. Only PDF-capable printers are shown (ZPL can’t render invoice pages).
              </p>
            </div>
            <AddClientDropdown
              unassignedClients={unassignedClients}
              onAdd={(code) => setExtraClients((l) => [...l, code])}
            />
          </div>
          <AssignmentMatrix
            docType="COMMERCIAL_INVOICE"
            clients={clientRows}
            printers={printers.filter((p) => p.active && p.format === 'PDF')}
            assignments={assignments}
            onChanged={load}
          />
        </section>
      ) : null}

      {activeTab === 'copies' ? (
        <section className="space-y-3">
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h3 className="text-[14px] font-semibold text-slate-900">How many copies per (client, carrier)</h3>
              <p className="mt-0.5 text-[12px] text-slate-500">
                Enter a number 1..20 to override, leave empty to inherit the Default row (or 1 if the
                Default is also empty). Carriers come from the tenant’s connected accounts.
              </p>
            </div>
            <AddClientDropdown
              unassignedClients={unassignedClients}
              onAdd={(code) => setExtraClients((l) => [...l, code])}
            />
          </div>
          <InvoiceCopiesMatrix clients={clientRows} onChanged={load} />
        </section>
      ) : null}

      {editing ? (
        <PrinterEditor
          printer={editing === 'new' ? null : editing}
          initialTags={editing === 'new' ? [] : (tagsByPrinter.get(editing.id) ?? [])}
          distinctTags={distinctTags}
          onClose={() => setEditing(null)}
          onSaved={async () => { setEditing(null); await load() }}
        />
      ) : null}
    </div>
  )
}

// PR-Printer-R7b — the same Add-a-client dropdown appears on all
// three assignment-family tabs. Extracted here so its markup + a11y
// stay consistent across Labels / Invoices / Copies.
function AddClientDropdown({
  unassignedClients,
  onAdd,
}: {
  unassignedClients: string[]
  onAdd: (code: string) => void
}) {
  return (
    <label className="flex items-center gap-2 text-[12.5px] text-slate-600">
      Add a client
      <select
        value=""
        onChange={(e) => { if (e.target.value) onAdd(e.target.value) }}
        disabled={unassignedClients.length === 0}
        className="rounded-md border border-slate-300 bg-white px-2 py-1 text-[13px] disabled:opacity-50"
      >
        <option value="">{unassignedClients.length === 0 ? 'All clients listed' : 'Choose…'}</option>
        {unassignedClients.map((code) => <option key={code} value={code}>{code}</option>)}
      </select>
    </label>
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

function PrinterEditor({
  printer, initialTags, distinctTags, onClose, onSaved,
}: {
  printer: Printer | null
  /** PR-R8b — starting tag chips (from parent's tagsByPrinter map). */
  initialTags: string[]
  /** PR-R8b — full distinct-tag list for autocomplete. */
  distinctTags: string[]
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const [form, setForm] = useState<PrinterInput>(() => printer
    ? {
        name: printer.name, location: printer.location, connection: printer.connection, host: printer.host,
        port: printer.port, queuePath: printer.queuePath, format: printer.format, paper: printer.paper, active: printer.active,
      }
    : { name: '', location: '', connection: 'RAW_9100', host: '', port: null, queuePath: '', format: 'ZPL', paper: 'LABEL_4X6', active: true })
  const [tags, setTags] = useState<string[]>(initialTags)
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
      let savedId: number
      if (printer) {
        await printerService.update(printer.id, payload)
        savedId = printer.id
      } else {
        const created = await printerService.create(payload)
        savedId = created.data?.id ?? 0
      }
      // PR-R8b — replace-all tags after the printer save. Failure here
      // is surfaced as a warning toast but does NOT roll back the
      // printer save (backend enforces uniqueness so a repeat click
      // fixes any partial-persist).
      if (savedId > 0) {
        try {
          await printerService.replacePrinterTags(savedId, tags)
        } catch (tagErr) {
          notify.error({
            title: 'Printer saved, tags did not',
            body: tagErr instanceof Error && tagErr.message ? tagErr.message : 'Try again from the edit dialog.',
            durationMs: 10_000,
          })
        }
      }
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
          {/* PR-R8b — tag chips with autocomplete off the distinct-tag list. */}
          <div className="col-span-2">
            <span className={labelCls}>Tags</span>
            <TagsInput
              value={tags}
              suggestions={distinctTags}
              onChange={setTags}
              disabled={saving}
            />
          </div>
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

/**
 * PR-Printer-R8b — chip-style tag input.
 *
 * <ul>
 *   <li>Current tags render as removable chips.</li>
 *   <li>Text input at the end. Enter or comma commits; Backspace on
 *       an empty input removes the last chip.</li>
 *   <li>Autocomplete dropdown filters `suggestions` by prefix + hides
 *       already-picked tags. Click a suggestion to add it.</li>
 *   <li>Duplicates are silently deduped (case-insensitive).</li>
 * </ul>
 */
function TagsInput({
  value,
  suggestions,
  onChange,
  disabled,
}: {
  value: string[]
  suggestions: string[]
  onChange: (next: string[]) => void
  disabled?: boolean
}) {
  const [text, setText] = useState('')
  const [focused, setFocused] = useState(false)

  const addTag = (raw: string) => {
    const t = raw.trim().toLowerCase()
    if (!t) return
    if (value.some((v) => v.toLowerCase() === t)) return
    onChange([...value, t])
    setText('')
  }
  const removeTag = (t: string) => onChange(value.filter((v) => v !== t))

  const filteredSuggestions = suggestions.filter((s) => {
    const q = text.trim().toLowerCase()
    if (!q) return false
    if (!s.toLowerCase().startsWith(q)) return false
    if (value.some((v) => v.toLowerCase() === s.toLowerCase())) return false
    return true
  }).slice(0, 6)

  return (
    <div className="relative">
      <div className={
        'flex flex-wrap items-center gap-1 rounded-md border px-2 py-1.5 '
        + (focused ? 'border-slate-500' : 'border-slate-300')
        + (disabled ? ' opacity-50' : '')
      }>
        {value.map((t) => (
          <span key={t} className="inline-flex items-center gap-1 rounded-full bg-indigo-100 px-2 py-0.5 text-[11.5px] font-semibold text-indigo-800">
            {t}
            <button
              type="button"
              onClick={() => removeTag(t)}
              disabled={disabled}
              aria-label={`Remove tag ${t}`}
              className="text-indigo-600 hover:text-rose-700 disabled:opacity-40"
            >
              <FiX className="h-3 w-3" />
            </button>
          </span>
        ))}
        <input
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => window.setTimeout(() => setFocused(false), 120)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); addTag(text) }
            else if (e.key === 'Backspace' && !text && value.length > 0) { removeTag(value[value.length - 1]) }
          }}
          disabled={disabled}
          placeholder={value.length === 0 ? 'add a tag…' : ''}
          className="flex-1 min-w-[80px] bg-transparent text-[12.5px] text-slate-800 outline-none disabled:cursor-not-allowed"
        />
      </div>
      {focused && filteredSuggestions.length > 0 ? (
        <ul className="absolute left-0 top-full z-10 mt-1 w-56 rounded-md border border-slate-200 bg-white py-1 shadow-lg">
          {filteredSuggestions.map((s) => (
            <li key={s}>
              <button
                type="button"
                // onMouseDown so the click fires BEFORE the input's
                // onBlur → focused=false → dropdown unmount race.
                onMouseDown={(e) => { e.preventDefault(); addTag(s) }}
                className="w-full px-3 py-1.5 text-left text-[12.5px] text-slate-700 hover:bg-slate-50"
              >
                {s}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      <p className="mt-1 text-[10.5px] text-slate-400">
        Type + press Enter or comma. Suggestions match every tag already used across your printers.
      </p>
    </div>
  )
}
