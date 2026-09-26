import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import {
  FiActivity,
  FiCheckCircle,
  FiCornerUpLeft,
  FiEdit2,
  FiHelpCircle,
  FiKey,
  FiPlus,
  FiRefreshCw,
  FiTrash2,
  FiUsers,
  FiX,
  FiXCircle,
  FiZap,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import {
  externalSystemsService,
  type ClientLoginOverrideRow,
  type ConnectionDetail,
  type ConnectionSummary,
  type ConnectorSummary,
  type HealthSnapshot,
} from '../api/externalSystemsService'
import type { SettingsOutletContext } from './layout/SettingsLayout'

/**
 * S3 — /settings/external-systems admin page. Table of connections
 * + per-row Test / Health / Edit / Delete + edit drawer with a
 * nested Secrets sub-panel + Client-login-overrides sub-panel.
 *
 * <p>Health snapshots are fetched lazily per row on demand (Refresh
 * button in the row); no automatic polling to avoid dialling an
 * Oracle server on page load.
 */
export default function ExternalSystemsPage() {
  const [rows, setRows] = useState<ConnectionSummary[]>([])
  const [connectors, setConnectors] = useState<ConnectorSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [healthById, setHealthById] = useState<Record<number, HealthSnapshot>>({})
  const [busyHealthId, setBusyHealthId] = useState<number | null>(null)
  const [editing, setEditing] = useState<ConnectionDetail | 'new' | null>(null)
  const [testing, setTesting] = useState<{ conn: ConnectionSummary } | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [list, cons] = await Promise.all([
        externalSystemsService.list(),
        externalSystemsService.listConnectors(),
      ])
      setRows(list)
      setConnectors(cons)
    } catch (e) {
      notify.apiError(e, 'Could not load external-system connections.')
    } finally {
      setLoading(false)
    }
  }, [])

  // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on mount; load() sets loading + rows + connectors
  useEffect(() => { void load() }, [load])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const refreshHealth = useCallback(async (id: number) => {
    setBusyHealthId(id)
    try {
      const snap = await externalSystemsService.health(id)
      setHealthById((prev) => ({ ...prev, [id]: snap }))
    } catch (e) {
      notify.apiError(e, 'Health check failed.')
    } finally {
      setBusyHealthId(null)
    }
  }, [])

  const del = async (row: ConnectionSummary) => {
    if (!(await notify.confirm(
      `Delete connection "${row.name}"? Secrets + client-login overrides will cascade.`,
      { title: 'Delete external system', confirmLabel: 'Delete', danger: true },
    ))) return
    try {
      await externalSystemsService.delete(row.id)
      notify.success('Connection deleted.')
      await load()
    } catch (e) {
      notify.apiError(e, 'Delete failed.')
    }
  }

  return (
    <div className="space-y-4">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <FiActivity className="h-4 w-4 text-slate-500" />
            External systems
          </h2>
          <p className="mt-1 text-[12.5px] text-slate-500">
            DB-driven connections to systems outside multiship: Oracle WMS (NDS today),
            REST integrations, SFTP, etc. Registered connectors:{' '}
            <span className="font-mono text-[11px]">
              {connectors.length ? connectors.map((c) => c.systemType).join(', ') : '—'}
            </span>
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => void load()}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
          >
            <FiRefreshCw className="h-3.5 w-3.5" /> Reload
          </button>
          <button
            type="button"
            onClick={() => setEditing('new')}
            className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-slate-800"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add connection
          </button>
        </div>
      </header>

      {loading ? (
        <div className="rounded-xl border border-slate-200 bg-white p-6 text-[13px] text-slate-500">
          Loading…
        </div>
      ) : rows.length === 0 ? (
        <div className="rounded-xl border border-slate-200 bg-white p-6 text-[13px] text-slate-500">
          No connections yet. Click <b>Add connection</b> to create the first one.
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
          <table className="w-full text-[13px]">
            <thead className="bg-slate-50 text-[11px] font-bold uppercase tracking-[0.12em] text-slate-500">
              <tr>
                <th className="px-3 py-2 text-left">Name</th>
                <th className="px-3 py-2 text-left">System type</th>
                <th className="px-3 py-2 text-left">Active</th>
                <th className="px-3 py-2 text-left">Health</th>
                <th className="px-3 py-2 text-left">Updated</th>
                <th className="px-3 py-2 text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((row) => {
                const health = healthById[row.id]
                return (
                  <tr key={row.id} className="hover:bg-slate-50/60">
                    <td className="px-3 py-2 font-mono text-[12.5px] text-slate-800">{row.name}</td>
                    <td className="px-3 py-2">
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 font-mono text-[11px] font-semibold text-slate-700">
                        {row.systemType}
                      </span>
                    </td>
                    <td className="px-3 py-2">
                      {row.active ? (
                        <span className="inline-flex items-center gap-1 text-emerald-700">
                          <FiCheckCircle className="h-3.5 w-3.5" /> Active
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-slate-500">
                          <FiXCircle className="h-3.5 w-3.5" /> Inactive
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2">
                      <HealthCell
                        health={health}
                        busy={busyHealthId === row.id}
                        onRefresh={() => void refreshHealth(row.id)}
                      />
                    </td>
                    <td className="px-3 py-2 text-[11.5px] text-slate-500">
                      {row.updatedAt ? new Date(row.updatedAt).toLocaleString() : '—'}
                      {row.updatedBy ? <div className="text-[10.5px] text-slate-400">by {row.updatedBy}</div> : null}
                    </td>
                    <td className="px-3 py-2">
                      <div className="flex items-center justify-end gap-1.5">
                        <button
                          type="button"
                          onClick={() => setTesting({ conn: row })}
                          title="Dial the connection"
                          className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[11.5px] font-semibold text-slate-700 hover:bg-slate-50"
                        >
                          <FiZap className="h-3 w-3" /> Test
                        </button>
                        <button
                          type="button"
                          onClick={async () => {
                            try {
                              const detail = await externalSystemsService.get(row.id)
                              setEditing(detail)
                            } catch (e) {
                              notify.apiError(e, 'Could not load connection.')
                            }
                          }}
                          className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-600 hover:bg-slate-50"
                          title="Edit"
                        >
                          <FiEdit2 className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={() => void del(row)}
                          className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 hover:border-rose-100 hover:text-rose-600"
                          title="Delete"
                        >
                          <FiTrash2 className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {editing ? (
        <EditDrawer
          connectors={connectors}
          initial={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null)
            await load()
          }}
        />
      ) : null}

      {testing ? (
        <TestDrawer conn={testing.conn} onClose={() => setTesting(null)} />
      ) : null}
    </div>
  )
}

// ─────────────────────────── Health cell ────────────────────────────

function HealthCell({
  health, busy, onRefresh,
}: { health?: HealthSnapshot; busy: boolean; onRefresh: () => void }) {
  if (busy) {
    return <span className="text-[11.5px] text-slate-500">Checking…</span>
  }
  if (!health) {
    return (
      <button
        type="button"
        onClick={onRefresh}
        className="inline-flex items-center gap-1 text-[11.5px] font-semibold text-slate-500 hover:text-slate-800"
      >
        <FiHelpCircle className="h-3.5 w-3.5" /> Check
      </button>
    )
  }
  const cls =
    health.status === 'UP' ? 'text-emerald-700'
    : health.status === 'DOWN' ? 'text-rose-700'
    : 'text-amber-700'
  const Icon =
    health.status === 'UP' ? FiCheckCircle
    : health.status === 'DOWN' ? FiXCircle
    : FiHelpCircle
  return (
    <button
      type="button"
      onClick={onRefresh}
      title={health.message ?? ''}
      className={`inline-flex items-center gap-1 text-[11.5px] font-semibold ${cls} hover:underline`}
    >
      <Icon className="h-3.5 w-3.5" />
      {health.status}
    </button>
  )
}

// ─────────────────────────── Edit drawer ────────────────────────────

function EditDrawer({
  connectors, initial, onClose, onSaved,
}: {
  connectors: ConnectorSummary[]
  initial: ConnectionDetail | null
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const isNew = initial === null
  const [name, setName] = useState<string>(initial?.name ?? '')
  const [systemType, setSystemType] = useState<string>(
    initial?.systemType ?? (connectors[0]?.systemType ?? ''),
  )
  const [active, setActive] = useState<boolean>(initial?.active ?? true)
  const [configJson, setConfigJson] = useState<string>(initial?.configJson ?? '{}')
  const [saving, setSaving] = useState(false)
  const [tab, setTab] = useState<'main' | 'secrets' | 'overrides' | 'writeback'>('main')
  // V89 writeback flags — persist through save() alongside the main
  // fields so admins can flip them on the details view too if they
  // prefer (the dedicated tab is just clearer UX).
  const [wbTracking, setWbTracking] = useState<boolean>(initial?.writebackTracking ?? false)
  const [wbShipDate, setWbShipDate] = useState<boolean>(initial?.writebackShipDate ?? false)
  const [wbStatus, setWbStatus] = useState<boolean>(initial?.writebackStatus ?? false)
  const [wbCarrier, setWbCarrier] = useState<boolean>(initial?.writebackCarrier ?? false)
  const [wbService, setWbService] = useState<boolean>(initial?.writebackService ?? false)
  const [wbFreight, setWbFreight] = useState<boolean>(initial?.writebackFreight ?? false)

  const parseError = useMemo(() => {
    try { JSON.parse(configJson); return null }
    catch (e) { return (e as Error).message }
  }, [configJson])

  const save = async () => {
    if (parseError) {
      notify.error(`configJson is invalid: ${parseError}`)
      return
    }
    if (!name.trim() || !systemType.trim()) {
      notify.error('Name + system type are required.')
      return
    }
    setSaving(true)
    try {
      const payload = {
        name: name.trim(), systemType: systemType.trim(),
        active, configJson,
        writebackTracking: wbTracking,
        writebackShipDate: wbShipDate,
        writebackStatus: wbStatus,
        writebackCarrier: wbCarrier,
        writebackService: wbService,
        writebackFreight: wbFreight,
      }
      if (isNew) {
        await externalSystemsService.create(payload)
        notify.success('Connection created.')
      } else {
        await externalSystemsService.update(initial!.id, payload)
        notify.success('Connection updated.')
      }
      await onSaved()
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex" role="dialog" aria-label="Edit external-system connection">
      <div className="flex-1 bg-slate-950/40" onClick={onClose} />
      <div className="flex w-[560px] max-w-full flex-col border-l border-slate-200 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
          <h3 className="text-[15px] font-semibold text-slate-950">
            {isNew ? 'New connection' : `Edit "${initial!.name}"`}
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-slate-500 hover:bg-slate-100"
            aria-label="Close drawer"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        {!isNew ? (
          <div className="flex border-b border-slate-200 bg-slate-50">
            {(['main', 'writeback', 'secrets', 'overrides'] as const).map((t) => (
              <button
                key={t}
                type="button"
                onClick={() => setTab(t)}
                className={`flex-1 px-3 py-2 text-[12px] font-semibold ${
                  tab === t
                    ? 'border-b-2 border-slate-900 text-slate-950'
                    : 'text-slate-500 hover:text-slate-800'
                }`}
              >
                {t === 'main' ? 'Details'
                  : t === 'writeback' ? 'Writeback'
                  : t === 'secrets' ? 'Secrets'
                  : 'Client overrides'}
              </button>
            ))}
          </div>
        ) : null}

        <div className="flex-1 overflow-y-auto p-4">
          {tab === 'main' || isNew ? (
            <MainTab
              name={name} setName={setName}
              systemType={systemType} setSystemType={setSystemType}
              active={active} setActive={setActive}
              configJson={configJson} setConfigJson={setConfigJson}
              parseError={parseError}
              connectors={connectors}
              isNew={isNew}
            />
          ) : tab === 'writeback' ? (
            <WritebackTab
              connectionId={initial?.id ?? null}
              wbTracking={wbTracking} setWbTracking={setWbTracking}
              wbShipDate={wbShipDate} setWbShipDate={setWbShipDate}
              wbStatus={wbStatus} setWbStatus={setWbStatus}
              wbCarrier={wbCarrier} setWbCarrier={setWbCarrier}
              wbService={wbService} setWbService={setWbService}
              wbFreight={wbFreight} setWbFreight={setWbFreight}
            />
          ) : tab === 'secrets' ? (
            <SecretsTab id={initial!.id} />
          ) : (
            <OverridesTab id={initial!.id} />
          )}
        </div>

        {(tab === 'main' || tab === 'writeback' || isNew) ? (
          <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-4 py-3">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving || !!parseError}
              className="rounded-lg bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        ) : null}
      </div>
    </div>
  )
}

// ─────────────────────────── Drawer tabs ────────────────────────────

function MainTab({
  name, setName, systemType, setSystemType, active, setActive,
  configJson, setConfigJson, parseError, connectors, isNew,
}: {
  name: string; setName: (v: string) => void
  systemType: string; setSystemType: (v: string) => void
  active: boolean; setActive: (v: boolean) => void
  configJson: string; setConfigJson: (v: string) => void
  parseError: string | null
  connectors: ConnectorSummary[]
  isNew: boolean
}) {
  return (
    <div className="space-y-3">
      <label className="block">
        <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Name</span>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          disabled={!isNew}
          placeholder="e.g. nds-default"
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-[13px] disabled:bg-slate-50 disabled:text-slate-500 focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200"
        />
        {!isNew ? (
          <span className="mt-1 block text-[10.5px] text-slate-400">
            Name is immutable after create.
          </span>
        ) : null}
      </label>
      <label className="block">
        <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">System type</span>
        <select
          value={systemType}
          onChange={(e) => setSystemType(e.target.value)}
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-[13px] focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200"
        >
          {connectors.map((c) => (
            <option key={c.systemType} value={c.systemType}>
              {c.systemType} — {c.configType}
            </option>
          ))}
        </select>
      </label>
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          checked={active}
          onChange={(e) => setActive(e.target.checked)}
          className="h-4 w-4 rounded border-slate-300 text-slate-900 focus:ring-slate-300"
        />
        <span className="text-[12.5px] font-semibold text-slate-800">Active</span>
        <span className="text-[11px] text-slate-500">— inactive rows are loaded but never dispatched to.</span>
      </label>
      <label className="block">
        <span className="mb-1 flex items-center justify-between text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">
          <span>Config (JSON)</span>
          <span className={parseError ? 'normal-case tracking-normal text-rose-600' : 'normal-case tracking-normal text-slate-400'}>
            {parseError ? `⚠ ${parseError}` : 'valid JSON'}
          </span>
        </span>
        <textarea
          rows={12}
          value={configJson}
          onChange={(e) => setConfigJson(e.target.value)}
          spellCheck={false}
          className={`w-full resize-y rounded-lg border bg-white px-3 py-2 font-mono text-[12px] focus:outline-none focus:ring-2 ${
            parseError ? 'border-rose-300 focus:ring-rose-200' : 'border-slate-300 focus:ring-slate-200'
          }`}
        />
        <span className="mt-1 block text-[10.5px] text-slate-400">
          Shape depends on the connector — e.g. NDS_ORACLE expects host/port/serviceName/serverMode/productionUsername/clientCodePattern/pool params.
          Passwords go in the Secrets tab, not here.
        </span>
      </label>
    </div>
  )
}

/**
 * V89 — per-connection writeback flags. Six symmetric booleans that
 * gate BOTH the label-generate push and the label-void clear for
 * this external system. Same flag for both directions: turning off
 * "Carrier" means the carrier is neither sent on generate nor nulled
 * on void.
 *
 * <p>The Save button lives on the drawer footer (shared with Details);
 * this tab only mutates local state. The parent's save() sends all six
 * flags in the same PUT that saves the main fields.
 */
function WritebackTab({
  connectionId,
  wbTracking, setWbTracking,
  wbShipDate, setWbShipDate,
  wbStatus, setWbStatus,
  wbCarrier, setWbCarrier,
  wbService, setWbService,
  wbFreight, setWbFreight,
}: {
  connectionId: number | null
  wbTracking: boolean; setWbTracking: (v: boolean) => void
  wbShipDate: boolean; setWbShipDate: (v: boolean) => void
  wbStatus: boolean; setWbStatus: (v: boolean) => void
  wbCarrier: boolean; setWbCarrier: (v: boolean) => void
  wbService: boolean; setWbService: (v: boolean) => void
  wbFreight: boolean; setWbFreight: (v: boolean) => void
}) {
  const rows: Array<{
    key: string
    label: string
    desc: string
    checked: boolean
    onChange: (v: boolean) => void
  }> = [
    { key: 'tracking', label: 'Tracking number',
      desc: 'Carrier tracking number produced by the label.',
      checked: wbTracking, onChange: setWbTracking },
    { key: 'shipDate', label: 'Shipment date',
      desc: 'Timestamp the label was generated.',
      checked: wbShipDate, onChange: setWbShipDate },
    { key: 'status', label: 'Status',
      desc: 'SHIPPED on generate / VOIDED on clear.',
      checked: wbStatus, onChange: setWbStatus },
    { key: 'carrier', label: 'Carrier',
      desc: 'Carrier code (UPS, FEDEX, USPS, DHL, STAMPS).',
      checked: wbCarrier, onChange: setWbCarrier },
    { key: 'service', label: 'Service',
      desc: 'Carrier-side service code (FEDEX_GROUND, UPS_02, …).',
      checked: wbService, onChange: setWbService },
    { key: 'freight', label: 'Freight amount',
      desc: 'Final freight cost + currency.',
      checked: wbFreight, onChange: setWbFreight },
  ]

  return (
    <div className="space-y-3">
      <div className="flex items-start gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-[12px] text-slate-700">
        <FiCornerUpLeft className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-500" />
        <div>
          <p className="font-semibold text-slate-800">Write back to this system on ship + void</p>
          <p className="mt-1 text-slate-600">
            When enabled, multiship pushes the flagged fields to this
            external system after a label is generated and clears them
            after a label is voided. Same flag gates both sides —
            leaving a field off means we neither send it on generate
            nor null it on void.
          </p>
          <p className="mt-1 text-slate-500">
            All flags default off. Enabling any flag is a per-connection change.
          </p>
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 bg-white">
        {rows.map((r, i) => (
          <label
            key={r.key}
            htmlFor={`wb-${r.key}`}
            className={`flex cursor-pointer items-start gap-3 px-3 py-2.5 ${
              i > 0 ? 'border-t border-slate-100' : ''
            } hover:bg-slate-50`}
          >
            <input
              id={`wb-${r.key}`}
              type="checkbox"
              checked={r.checked}
              onChange={(e) => r.onChange(e.target.checked)}
              className="mt-0.5 h-4 w-4 rounded border-slate-300 text-slate-900 focus:ring-slate-300"
            />
            <div className="flex-1">
              <p className="text-[13px] font-semibold text-slate-800">{r.label}</p>
              <p className="text-[11.5px] text-slate-500">{r.desc}</p>
            </div>
          </label>
        ))}
      </div>

      {connectionId != null ? <RoutedTenantsSection connectionId={connectionId} /> : null}
    </div>
  )
}

/**
 * PR #750 follow-up — bind clients to this connection for writeback.
 * The dispatcher reads {@code tenant_settings[tenantCode].writebackConnection}
 * to decide which connection a client's writeback lands on; without a
 * route, the client's writeback silently no-ops. This section lets an
 * admin add / remove those routes from the connection editor.
 */
function RoutedTenantsSection({ connectionId }: { connectionId: number }) {
  const [routed, setRouted] = useState<string[]>([])
  const [adding, setAdding] = useState<string>('')
  const [busy, setBusy] = useState<boolean>(false)
  const [loadErr, setLoadErr] = useState<string | null>(null)

  const reload = async () => {
    try {
      const list = await externalSystemsService.listRoutedTenants(connectionId)
      setRouted(list)
      setLoadErr(null)
    } catch (e) {
      setLoadErr((e as Error).message ?? 'Failed to load')
    }
  }

  useEffect(() => { void reload() }, [connectionId])

  const add = async () => {
    const code = adding.trim().toUpperCase()
    if (!code) { notify.error('Enter a client code.'); return }
    if (routed.includes(code)) { notify.info(`${code} is already routed here.`); return }
    setBusy(true)
    try {
      await externalSystemsService.addRoutedTenant(connectionId, code)
      notify.success(`${code} routed to this connection.`)
      setAdding('')
      await reload()
    } catch (e) {
      notify.apiError(e, 'Route add failed.')
    } finally {
      setBusy(false)
    }
  }

  const remove = async (code: string) => {
    setBusy(true)
    try {
      await externalSystemsService.removeRoutedTenant(connectionId, code)
      notify.success(`${code} unrouted (falls back to default connection).`)
      await reload()
    } catch (e) {
      notify.apiError(e, 'Route remove failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mt-2 space-y-2">
      <div className="flex items-start gap-2 rounded-lg border border-slate-200 bg-slate-50 p-3 text-[12px] text-slate-700">
        <FiCornerUpLeft className="mt-0.5 h-3.5 w-3.5 shrink-0 text-slate-500" />
        <div>
          <p className="font-semibold text-slate-800">Routed clients</p>
          <p className="mt-1 text-slate-600">
            Clients listed here send their writeback to this connection. A client
            not on any connection's routing list falls back to the default
            connection (<code>nds-default</code>) or is silently skipped.
          </p>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <input
          type="text"
          value={adding}
          onChange={(e) => setAdding(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void add() } }}
          placeholder="Client code (e.g. ACME)"
          className="flex-1 rounded-lg border border-slate-200 px-3 py-1.5 text-[13px] focus:border-slate-400 focus:outline-none"
        />
        <button
          type="button"
          onClick={() => void add()}
          disabled={busy || !adding.trim()}
          className="rounded-lg bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
        >
          Add
        </button>
      </div>

      {loadErr ? (
        <p className="text-[12px] text-rose-600">Failed to load routings: {loadErr}</p>
      ) : routed.length === 0 ? (
        <p className="text-[12px] text-slate-500">No clients routed to this connection yet.</p>
      ) : (
        <div className="rounded-xl border border-slate-200 bg-white">
          {routed.map((code, i) => (
            <div
              key={code}
              className={`flex items-center justify-between px-3 py-2 ${i > 0 ? 'border-t border-slate-100' : ''}`}
            >
              <span className="font-mono text-[12.5px] text-slate-800">{code}</span>
              <button
                type="button"
                onClick={() => void remove(code)}
                disabled={busy}
                className="text-[12px] font-semibold text-rose-600 hover:underline disabled:cursor-not-allowed disabled:opacity-40"
              >
                Remove
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function SecretsTab({ id }: { id: number }) {
  // NDS connector expects one secret key: productionPassword. Other
  // connectors may add more — for MVP we treat this as a single-secret
  // form; expand to a table if a future connector needs many.
  const [key, setKey] = useState<string>('productionPassword')
  const [plaintext, setPlaintext] = useState<string>('')
  const [saving, setSaving] = useState(false)

  const save = async () => {
    if (!key.trim()) { notify.error('Secret key required.'); return }
    setSaving(true)
    try {
      const res = await externalSystemsService.putSecret(id, key.trim(), plaintext || null)
      notify.success(res.isSet ? `Secret "${res.secretKey}" saved.` : `Secret "${res.secretKey}" cleared.`)
      setPlaintext('')
    } catch (e) {
      notify.apiError(e, 'Save secret failed.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-3">
      <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-[12px] text-amber-900">
        Values are AES-256-GCM encrypted at rest. Existing secrets aren't shown; leaving the field empty and Saving deletes the secret row.
      </div>
      <label className="block">
        <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Secret key</span>
        <input
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder="e.g. productionPassword"
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 font-mono text-[12.5px]"
        />
      </label>
      <label className="block">
        <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Plaintext (never stored)</span>
        <input
          type="password"
          value={plaintext}
          onChange={(e) => setPlaintext(e.target.value)}
          placeholder="Type the new secret; empty to delete"
          autoComplete="new-password"
          className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 font-mono text-[12.5px]"
        />
      </label>
      <div className="flex items-center justify-end">
        <button
          type="button"
          onClick={() => void save()}
          disabled={saving}
          className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
        >
          <FiKey className="h-3.5 w-3.5" />
          {saving ? 'Saving…' : 'Save secret'}
        </button>
      </div>
    </div>
  )
}

function OverridesTab({ id }: { id: number }) {
  const [rows, setRows] = useState<ClientLoginOverrideRow[]>([])
  const [loading, setLoading] = useState(true)
  const [clientCode, setClientCode] = useState('')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await externalSystemsService.listClientOverrides(id))
    } catch (e) {
      notify.apiError(e, 'Could not load client overrides.')
    } finally {
      setLoading(false)
    }
  }, [id])

  // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch on drawer tab open
  useEffect(() => { void load() }, [load])

  const add = async () => {
    if (!clientCode.trim() || !username.trim() || !password) {
      notify.error('Client code, username, and password all required.')
      return
    }
    setSaving(true)
    try {
      await externalSystemsService.putClientOverride(id, clientCode.trim(), username.trim(), password)
      notify.success(`Override for ${clientCode.trim().toUpperCase()} saved.`)
      setClientCode(''); setUsername(''); setPassword('')
      await load()
    } catch (e) {
      notify.apiError(e, 'Save override failed.')
    } finally {
      setSaving(false)
    }
  }

  const del = async (row: ClientLoginOverrideRow) => {
    if (!(await notify.confirm(`Delete override for ${row.clientCode}?`, { danger: true }))) return
    try {
      await externalSystemsService.deleteClientOverride(id, row.clientCode)
      notify.success('Deleted.')
      await load()
    } catch (e) {
      notify.apiError(e, 'Delete failed.')
    }
  }

  return (
    <div className="space-y-3">
      <div className="rounded-lg border border-slate-200 bg-slate-50 p-3 text-[12px] text-slate-700">
        <FiUsers className="mr-1 inline h-3.5 w-3.5" />
        Per-tenant login overrides. Empty by default — the connector falls back to its own credential-derivation rule (e.g. NDS: <span className="font-mono">username = password = clientCode</span>).
      </div>

      {loading ? (
        <p className="text-[12.5px] text-slate-500">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="text-[12.5px] text-slate-500">No overrides set.</p>
      ) : (
        <table className="w-full text-[12.5px]">
          <thead className="text-[11px] font-bold uppercase tracking-[0.12em] text-slate-500">
            <tr>
              <th className="px-2 py-1 text-left">Client</th>
              <th className="px-2 py-1 text-left">Username</th>
              <th className="px-2 py-1 text-left">Updated</th>
              <th className="px-2 py-1 text-right"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((r) => (
              <tr key={r.clientCode}>
                <td className="px-2 py-1 font-mono">{r.clientCode}</td>
                <td className="px-2 py-1 font-mono">{r.username}</td>
                <td className="px-2 py-1 text-[11px] text-slate-500">
                  {r.updatedAt ? new Date(r.updatedAt).toLocaleString() : '—'}
                </td>
                <td className="px-2 py-1 text-right">
                  <button
                    type="button"
                    onClick={() => void del(r)}
                    className="inline-flex h-6 w-6 items-center justify-center rounded text-slate-400 hover:text-rose-600"
                    title="Delete"
                  >
                    <FiTrash2 className="h-3 w-3" />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div className="mt-4 rounded-lg border border-slate-200 p-3">
        <p className="mb-2 text-[11px] font-bold uppercase tracking-[0.12em] text-slate-500">Add override</p>
        <div className="grid grid-cols-3 gap-2">
          <input
            placeholder="Client code"
            value={clientCode}
            onChange={(e) => setClientCode(e.target.value)}
            className="rounded-lg border border-slate-300 bg-white px-2 py-1.5 font-mono text-[12px]"
          />
          <input
            placeholder="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="rounded-lg border border-slate-300 bg-white px-2 py-1.5 font-mono text-[12px]"
          />
          <input
            type="password"
            placeholder="Password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password"
            className="rounded-lg border border-slate-300 bg-white px-2 py-1.5 font-mono text-[12px]"
          />
        </div>
        <div className="mt-2 flex items-center justify-end">
          <button
            type="button"
            onClick={() => void add()}
            disabled={saving}
            className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-[12px] font-semibold text-white hover:bg-slate-800 disabled:opacity-40"
          >
            <FiPlus className="h-3 w-3" />
            {saving ? 'Saving…' : 'Add'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────── Test drawer ────────────────────────────

function TestDrawer({ conn, onClose }: { conn: ConnectionSummary; onClose: () => void }) {
  const [profile, setProfile] = useState<'PRODUCTION' | 'CLIENT'>('PRODUCTION')
  const [clientCode, setClientCode] = useState('')
  const [testing, setTesting] = useState(false)
  const [result, setResult] = useState<{ status: string; message: string; kind?: string } | null>(null)

  const run = async () => {
    setTesting(true)
    setResult(null)
    try {
      const res = await externalSystemsService.testConnection(conn.id, {
        loginProfile: profile,
        clientCode: profile === 'CLIENT' ? clientCode.trim() : undefined,
      })
      // SUCCESS shape: res.data = { status, message }
      const d = (res.data ?? {}) as Record<string, unknown>
      setResult({
        status: String(d.status ?? 'UP'),
        message: String(d.message ?? 'connect() succeeded'),
      })
    } catch (e) {
      const err = e as { status?: number; errorCode?: string; message?: string; payload?: unknown }
      const payloadData = (err.payload as { data?: Record<string, unknown> } | null | undefined)?.data
      setResult({
        status: 'DOWN',
        kind: err.errorCode ?? (payloadData?.kind as string | undefined),
        message: err.message ?? (payloadData?.message as string | undefined) ?? 'Test failed.',
      })
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-40 flex" role="dialog" aria-label="Test connection">
      <div className="flex-1 bg-slate-950/40" onClick={onClose} />
      <div className="flex w-[480px] max-w-full flex-col border-l border-slate-200 bg-white shadow-xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-4 py-3">
          <h3 className="text-[15px] font-semibold text-slate-950">
            Test <span className="font-mono text-[13px]">{conn.name}</span>
          </h3>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg p-1 text-slate-500 hover:bg-slate-100"
            aria-label="Close drawer"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 space-y-3 overflow-y-auto p-4">
          <label className="block">
            <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Login profile</span>
            <div className="inline-flex overflow-hidden rounded-lg border border-slate-300">
              {(['PRODUCTION', 'CLIENT'] as const).map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setProfile(p)}
                  className={`px-3 py-1.5 text-[12px] font-semibold ${
                    profile === p ? 'bg-slate-900 text-white' : 'bg-white text-slate-700 hover:bg-slate-50'
                  }`}
                >
                  {p}
                </button>
              ))}
            </div>
          </label>

          {profile === 'CLIENT' ? (
            <label className="block">
              <span className="mb-1 block text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">Client code</span>
              <input
                value={clientCode}
                onChange={(e) => setClientCode(e.target.value)}
                placeholder="e.g. MKL246"
                className="w-full rounded-lg border border-slate-300 bg-white px-3 py-2 font-mono text-[12.5px]"
              />
            </label>
          ) : null}

          <button
            type="button"
            onClick={() => void run()}
            disabled={testing || (profile === 'CLIENT' && !clientCode.trim())}
            className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-slate-800 disabled:opacity-40"
          >
            <FiZap className="h-3.5 w-3.5" />
            {testing ? 'Testing…' : 'Test connection'}
          </button>

          {result ? (
            <div className={`rounded-lg border p-3 text-[12.5px] ${
              result.status === 'UP'
                ? 'border-emerald-200 bg-emerald-50 text-emerald-900'
                : 'border-rose-200 bg-rose-50 text-rose-900'
            }`}>
              <p className="font-semibold">
                {result.status === 'UP' ? '✓ UP — connection succeeded' : '✗ DOWN'}
                {result.kind ? <span className="ml-1 font-mono text-[11px] opacity-70">({result.kind})</span> : null}
              </p>
              <p className="mt-1 whitespace-pre-wrap">{result.message}</p>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  )
}
