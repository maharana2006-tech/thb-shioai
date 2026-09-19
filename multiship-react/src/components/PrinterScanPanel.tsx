import { useCallback, useEffect, useRef, useState } from 'react'
import {
  FiCheckCircle, FiChevronDown, FiChevronRight, FiCopy, FiDownload, FiKey,
  FiRadio, FiRefreshCw, FiRotateCcw, FiTrash2, FiWifi,
} from 'react-icons/fi'
import DiscoveredPickerModal from './DiscoveredPickerModal'
import { BASE_URL } from '../api/apiClient'
import { printerService, type Printer, type PrinterScanAgent } from '../api/printerService'
import { notify } from '../utils/notify'

/**
 * PR-Printer-P1.6 (extended by R1 revoke, R2 SSE, R4 wiring) — admin
 * panel on {@code /settings/printers → Scanners} tab.
 *
 * <p>R4 surface adds:
 * <ul>
 *   <li>Live discovery pill via {@link EventSource} on
 *       {@code /printers/discovered/stream} (R2). Increments on every
 *       {@code discovered} event; click opens the picker.</li>
 *   <li>Prominent Refresh button (P4-audit finding — user could not
 *       find the existing one inside the picker modal).</li>
 *   <li>Revoked-agents collapsible section with Unrevoke button
 *       (R1 endpoint). Confirm dialog surfaces the security caveat.</li>
 *   <li>Pre-check on Scan-for-printers: disabled + tooltip when no
 *       active agents (was firing the API only to toast "no scanner").</li>
 *   <li>Modal-state reset on {@code tenantCode} change (audit finding).</li>
 * </ul>
 */
export default function PrinterScanPanel({
  tenantCode,
  existingPrinters = [],
  onImported,
}: {
  tenantCode: string
  existingPrinters?: Printer[]
  onImported?: () => void | Promise<void>
}) {
  const [agents, setAgents] = useState<PrinterScanAgent[]>([])
  const [revoked, setRevoked] = useState<PrinterScanAgent[]>([])
  const [revokedOpen, setRevokedOpen] = useState(false)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [enrollOpen, setEnrollOpen] = useState(false)
  const [pickerOpen, setPickerOpen] = useState(false)
  const [scanning, setScanning] = useState(false)
  const [revokingId, setRevokingId] = useState<number | null>(null)
  const [unrevokingId, setUnrevokingId] = useState<number | null>(null)
  // PR-Printer-R2/R4 — count of `event: discovered` frames received
  // since the current tenantCode mounted. Resets when the tenant
  // changes or when the admin explicitly opens the picker.
  const [liveDiscoveredCount, setLiveDiscoveredCount] = useState(0)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [activeRes, revokedRes] = await Promise.all([
        printerService.listScanAgents(tenantCode),
        printerService.listRevokedScanAgents(tenantCode),
      ])
      setAgents(activeRes.data ?? [])
      setRevoked(revokedRes.data ?? [])
    } catch (err) {
      notify.apiError(err, 'Could not load scan agents')
    } finally {
      setLoading(false)
    }
  }, [tenantCode])

  // Initial fetch on mount + when tenantCode changes. Deferred one
  // microtask so the effect returns before load()'s synchronous
  // setLoading fires (react-hooks/set-state-in-effect lint).
  useEffect(() => {
    let cancelled = false
    queueMicrotask(() => { if (!cancelled) void load() })
    return () => { cancelled = true }
  }, [load])

  // PR-Printer-R4 — audit-BLOCKER note: reset-on-tenant-change is
  // handled at the parent by keying the panel with `key={tenantCode}`.
  // That auto-remounts (fresh state) instead of leaving stale
  // enrollOpen/pickerOpen/liveDiscoveredCount from the previous tenant.

  // PR-Printer-R2/R4 — SSE subscription. EventSource sends the
  // httpOnly cookie same-origin by default; withCredentials makes it
  // work under cross-origin dev too. Auto-reconnects on network
  // hiccup (browser-native). Detach on unmount / tenant change.
  const esRef = useRef<EventSource | null>(null)
  useEffect(() => {
    if (!tenantCode) return
    const url = `${BASE_URL}/tenants/${encodeURIComponent(tenantCode)}/printers/discovered/stream`
    const es = new EventSource(url, { withCredentials: true })
    esRef.current = es
    const onDiscovered = () => setLiveDiscoveredCount((n) => n + 1)
    es.addEventListener('discovered', onDiscovered)
    // Silently drop errors — the browser reconnects on its own; a
    // permanent 401/403 shows up as onerror but there's no auth
    // recovery path we can take from here.
    es.onerror = () => { /* noop — browser retries */ }
    return () => {
      es.removeEventListener('discovered', onDiscovered)
      es.close()
      esRef.current = null
    }
  }, [tenantCode])

  const handleRefresh = async () => {
    if (refreshing) return
    setRefreshing(true)
    try {
      await load()
    } finally {
      setRefreshing(false)
    }
  }

  const handleRevoke = async (agent: PrinterScanAgent) => {
    if (revokingId !== null) return
    const ok = await notify.confirm(
      `Revoke ${agent.agentId}? The scanner will stop working within 5 seconds. `
        + `You'll need to re-enroll and update the container env to resume discovery for this warehouse.`,
      {
        title: 'Revoke scanner',
        confirmLabel: 'Revoke',
        cancelLabel: 'Keep',
        danger: true,
      },
    )
    if (!ok) return
    setRevokingId(agent.id)
    try {
      await printerService.revokeScanAgent(tenantCode, agent.id)
      notify.success({ title: `${agent.agentId} revoked`, body: 'The scanner’s key is now invalid.' })
      await load()
    } catch (err) {
      notify.apiError(err, `Could not revoke ${agent.agentId}`)
    } finally {
      setRevokingId(null)
    }
  }

  const handleUnrevoke = async (agent: PrinterScanAgent) => {
    if (unrevokingId !== null) return
    // PR-Printer-R1 — surface the security caveat prominently in the
    // confirm copy: unrevoke re-arms the ORIGINAL key. If the revoke
    // was in response to a leak, re-enroll instead.
    const ok = await notify.confirm(
      `Reactivate ${agent.agentId}? The original scanner key is re-armed — the customer's `
        + `container resumes on its next poll (~5s). Do NOT unrevoke if the previous revoke was a leak `
        + `response; use "Enroll scanner" with the same id to rotate the key instead.`,
      {
        title: 'Reactivate scanner',
        confirmLabel: 'Reactivate',
        cancelLabel: 'Cancel',
      },
    )
    if (!ok) return
    setUnrevokingId(agent.id)
    try {
      await printerService.unrevokeScanAgent(tenantCode, agent.id)
      notify.success({ title: `${agent.agentId} reactivated`, body: 'The scanner is active again.' })
      await load()
    } catch (err) {
      notify.apiError(err, `Could not reactivate ${agent.agentId}`)
    } finally {
      setUnrevokingId(null)
    }
  }

  const handleScanNow = async () => {
    if (scanning) return
    setScanning(true)
    try {
      const res = await printerService.scanNow(tenantCode)
      const nudged = res.data?.agentsNudged ?? 0
      if (nudged === 0) {
        notify.error({
          title: 'No active scanner',
          body: 'Enroll a LAN scanner first — no agent is registered for this tenant.',
        })
      } else {
        notify.success({
          title: 'Scan requested',
          body: `Nudged ${nudged} scanner${nudged === 1 ? '' : 's'}. Watch the live count below or click Refresh.`,
        })
      }
    } catch (err) {
      notify.apiError(err, 'Could not request a scan')
    } finally {
      setScanning(false)
    }
  }

  const openPickerAndClearBadge = () => {
    setPickerOpen(true)
    setLiveDiscoveredCount(0)
  }

  const hasActiveAgents = agents.length > 0
  const scanDisabled = scanning || !hasActiveAgents

  return (
    <section className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="flex items-start justify-between gap-4">
        <div>
          <h3 className="flex items-center gap-2 text-[14px] font-semibold text-slate-950">
            <FiWifi className="h-4 w-4 text-slate-500" />
            Auto-detect (LAN scanner)
          </h3>
          <p className="mt-1 max-w-[70ch] text-[12px] text-slate-500">
            Deploy the <span className="font-mono">multiship-lan-scanner</span> Docker image inside your
            warehouse LAN and it&apos;ll announce every network printer it finds — no more typing IPs
            by hand. USB printers attached to operator PCs still use manual entry above.
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button
            type="button"
            onClick={() => void handleRefresh()}
            disabled={refreshing || loading}
            aria-label="Refresh scanners"
            title="Reload the list of enrolled and revoked scanners"
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-2.5 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <FiRotateCcw className={`h-3.5 w-3.5 ${refreshing ? 'animate-spin' : ''}`} />
            Refresh
          </button>
          <button
            type="button"
            onClick={() => setEnrollOpen(true)}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
          >
            <FiKey className="h-3.5 w-3.5" />
            Enroll scanner
          </button>
          <button
            type="button"
            onClick={() => void handleScanNow()}
            disabled={scanDisabled}
            title={hasActiveAgents
              ? 'Nudge every active scanner to run a fresh discovery pass'
              : 'Enroll a LAN scanner first — nothing to nudge.'}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <FiRadio className={`h-3.5 w-3.5 ${scanning ? 'animate-pulse' : ''}`} />
            Scan for printers
          </button>
          <button
            type="button"
            onClick={openPickerAndClearBadge}
            className="relative inline-flex items-center gap-1.5 rounded-md bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-slate-700"
          >
            <FiDownload className="h-3.5 w-3.5" />
            Pick from scan
            {liveDiscoveredCount > 0 ? (
              <span
                title={`${liveDiscoveredCount} new since page load`}
                className="ml-1 inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-emerald-500 px-1 text-[10px] font-bold leading-none text-white"
              >
                {liveDiscoveredCount > 99 ? '99+' : liveDiscoveredCount}
              </span>
            ) : null}
          </button>
        </div>
      </div>

      {/* Live discovery pill — appears when new rows arrive via SSE
          but the picker hasn't been opened yet to clear the count. */}
      {liveDiscoveredCount > 0 ? (
        <button
          type="button"
          onClick={openPickerAndClearBadge}
          className="mt-3 inline-flex items-center gap-2 rounded-full bg-emerald-50 px-3 py-1 text-[12px] font-semibold text-emerald-800 hover:bg-emerald-100"
        >
          <FiRefreshCw className="h-3 w-3" />
          {liveDiscoveredCount} new printer{liveDiscoveredCount === 1 ? '' : 's'} discovered — click to review
        </button>
      ) : null}

      {loading ? (
        <p className="mt-3 border-t border-slate-100 pt-3 text-[12px] text-slate-500">
          Loading scanners…
        </p>
      ) : !hasActiveAgents ? (
        <p className="mt-3 border-t border-slate-100 pt-3 text-[12px] text-slate-500">
          No scanners enrolled yet. Click <span className="font-semibold">Enroll scanner</span> to add
          the first one for this tenant.
        </p>
      ) : (
        <ul className="mt-3 space-y-1 border-t border-slate-100 pt-3 text-[12px]">
          {agents.map((a) => (
            <li key={a.id} className="flex items-center justify-between gap-3 text-slate-600">
              <span className="flex items-center gap-1.5 min-w-0">
                <FiCheckCircle className="h-3.5 w-3.5 shrink-0 text-emerald-600" />
                <span className="font-semibold text-slate-800">{a.agentId}</span>
                {a.hostname ? <span className="truncate text-slate-400">· {a.hostname}</span> : null}
              </span>
              <span className="flex items-center gap-2 shrink-0">
                <span className="text-[11px] text-slate-400">
                  {a.lastSeenAt ? `last seen ${formatSince(a.lastSeenAt)}` : 'never contacted'}
                </span>
                <button
                  type="button"
                  onClick={() => void handleRevoke(a)}
                  disabled={revokingId !== null}
                  aria-label={`Revoke ${a.agentId}`}
                  title="Revoke this scanner — invalidates its key immediately"
                  className="rounded-md border border-slate-200 bg-white p-1 text-slate-500 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-700 disabled:opacity-40"
                >
                  {revokingId === a.id
                    ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                    : <FiTrash2 className="h-3 w-3" />}
                </button>
              </span>
            </li>
          ))}
        </ul>
      )}

      {/* Revoked-agents collapsible — only shown when the tenant has
          at least one revoked row. Newest-revoked first (server-side
          ordering). Unrevoke re-arms the ORIGINAL key; the confirm
          dialog spells out the leak-vs-accidental trade-off. */}
      {!loading && revoked.length > 0 ? (
        <div className="mt-3 border-t border-slate-100 pt-3">
          <button
            type="button"
            onClick={() => setRevokedOpen((v) => !v)}
            aria-expanded={revokedOpen}
            className="inline-flex items-center gap-1 text-[11.5px] font-semibold text-slate-500 hover:text-slate-800"
          >
            {revokedOpen ? <FiChevronDown className="h-3 w-3" /> : <FiChevronRight className="h-3 w-3" />}
            Revoked ({revoked.length})
          </button>
          {revokedOpen ? (
            <ul className="mt-2 space-y-1 text-[12px]">
              {revoked.map((a) => (
                <li key={a.id} className="flex items-center justify-between gap-3 text-slate-500">
                  <span className="flex items-center gap-1.5 min-w-0">
                    <FiTrash2 className="h-3.5 w-3.5 shrink-0 text-slate-400" />
                    <span className="font-semibold text-slate-700 line-through">{a.agentId}</span>
                    {a.hostname ? <span className="truncate text-slate-400">· {a.hostname}</span> : null}
                  </span>
                  <span className="flex items-center gap-2 shrink-0">
                    <span className="text-[11px] text-slate-400">
                      {a.revokedAt ? `revoked ${formatSince(a.revokedAt)}` : 'revoked'}
                    </span>
                    <button
                      type="button"
                      onClick={() => void handleUnrevoke(a)}
                      disabled={unrevokingId !== null}
                      aria-label={`Reactivate ${a.agentId}`}
                      title="Reactivate — re-arms the original key. Use only for accidental revokes."
                      className="rounded-md border border-slate-200 bg-white p-1 text-slate-500 hover:border-emerald-300 hover:bg-emerald-50 hover:text-emerald-700 disabled:opacity-40"
                    >
                      {unrevokingId === a.id
                        ? <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
                        : <FiRotateCcw className="h-3 w-3" />}
                    </button>
                  </span>
                </li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}

      {enrollOpen ? (
        <EnrollModal
          tenantCode={tenantCode}
          onClose={() => { setEnrollOpen(false); void load() }}
        />
      ) : null}

      {pickerOpen ? (
        <DiscoveredPickerModal
          tenantCode={tenantCode}
          existingPrinters={existingPrinters}
          onClose={() => setPickerOpen(false)}
          onImported={async () => { if (onImported) await onImported() }}
        />
      ) : null}
    </section>
  )
}

/**
 * Two-step modal: (1) collect agentId + hostname; (2) show the raw key
 * ONCE with a copy-to-clipboard button + "I've saved it" acknowledge.
 * Closing the modal at step 2 is the only path forward — no back-button
 * to step 1, no key persisted in state past the close.
 */
function EnrollModal({ tenantCode, onClose }: { tenantCode: string; onClose: () => void }) {
  const [agentId, setAgentId] = useState('')
  const [hostname, setHostname] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [rawKey, setRawKey] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)

  const submit = async () => {
    if (submitting || !agentId.trim()) return
    setSubmitting(true)
    try {
      const res = await printerService.enrollScanAgent(
        tenantCode, agentId.trim(), hostname.trim() || null,
      )
      const key = res.data?.rawKey
      if (!key) {
        notify.error({ title: 'Enroll failed', body: 'Server did not return a scanner key.' })
        return
      }
      setRawKey(key)
    } catch (err) {
      notify.apiError(err, 'Could not enroll the scanner')
    } finally {
      setSubmitting(false)
    }
  }

  const copyKey = async () => {
    if (!rawKey) return
    try {
      await navigator.clipboard.writeText(rawKey)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 2000)
    } catch {
      notify.error({ title: 'Copy failed', body: 'Select the key and copy manually.' })
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="printer-scan-enroll-heading"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      onClick={rawKey ? undefined : onClose}
    >
      <div
        className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        {rawKey ? (
          <>
            <h3 id="printer-scan-enroll-heading" className="text-[15px] font-semibold text-slate-950">
              Scanner key (shown once)
            </h3>
            <p className="mt-1 text-[12px] text-slate-500">
              Paste this into the Docker agent&apos;s <span className="font-mono">MULTISHIP_AGENT_KEY</span>
              &nbsp;env variable. We do not store the raw key anywhere retrievable — losing it means
              revoking + re-enrolling.
            </p>
            <div className="mt-4 flex items-center gap-2">
              <code className="flex-1 truncate rounded-lg border border-slate-200 bg-slate-50 px-3 py-2 font-mono text-[11px] text-slate-800">
                {rawKey}
              </code>
              <button
                type="button"
                onClick={() => void copyKey()}
                className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-2.5 py-2 text-[12px] font-semibold text-slate-700 hover:bg-slate-50"
              >
                <FiCopy className="h-3.5 w-3.5" />
                {copied ? 'Copied' : 'Copy'}
              </button>
            </div>
            <div className="mt-5 flex justify-end">
              <button
                type="button"
                onClick={onClose}
                className="inline-flex items-center gap-1.5 rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700"
              >
                I&apos;ve saved it
              </button>
            </div>
          </>
        ) : (
          <>
            <h3 id="printer-scan-enroll-heading" className="text-[15px] font-semibold text-slate-950">
              Enroll a LAN scanner
            </h3>
            <p className="mt-1 text-[12px] text-slate-500">
              One row per warehouse. Give each scanner a memorable id
              (e.g. <span className="font-mono">warehouse-north</span>). Hostname is optional.
            </p>
            <div className="mt-4 space-y-3">
              <label className="block">
                <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Scanner id</span>
                <input
                  type="text"
                  value={agentId}
                  onChange={(e) => setAgentId(e.target.value)}
                  placeholder="warehouse-north"
                  className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-[13px] text-slate-900 outline-none focus:border-slate-400"
                />
              </label>
              <label className="block">
                <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Hostname (optional)</span>
                <input
                  type="text"
                  value={hostname}
                  onChange={(e) => setHostname(e.target.value)}
                  placeholder="scanner-01.acme.local"
                  className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-[13px] text-slate-900 outline-none focus:border-slate-400"
                />
              </label>
            </div>
            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={onClose}
                className="inline-flex items-center rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void submit()}
                disabled={submitting || !agentId.trim()}
                className="inline-flex items-center gap-1.5 rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                <FiKey className="h-3.5 w-3.5" />
                {submitting ? 'Enrolling…' : 'Enroll'}
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

function formatSince(iso: string): string {
  const then = new Date(iso).getTime()
  if (!Number.isFinite(then)) return iso
  const ms = Date.now() - then
  if (ms < 60_000) return 'just now'
  const mins = Math.floor(ms / 60_000)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.floor(hrs / 24)}d ago`
}
