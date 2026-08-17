import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import {
  FiAlertTriangle,
  FiCheck,
  FiCopy,
  FiKey,
  FiPlus,
  FiRefreshCw,
  FiSlash,
  FiX,
} from 'react-icons/fi'
import { apiKeyService, type ApiKey } from '../api/apiKeyService'
import type { SettingsOutletContext } from './layout/SettingsLayout'

/** Short relative time for the created / last-used columns. */
const relTime = (iso: string | null | undefined): string => {
  if (!iso) return '—'
  const secs = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (secs < 60) return 'just now'
  const mins = Math.round(secs / 60)
  if (mins < 60) return `${mins}m ago`
  const hrs = Math.round(mins / 60)
  if (hrs < 24) return `${hrs}h ago`
  return `${Math.round(hrs / 24)}d ago`
}

/** Audit A3 — expiry badge tone matches the backend spec: green >30d,
 *  amber ≤30d, red past-expiry. Null expiresAt (platform-only keys)
 *  renders as a discreet "never expires" indicator. */
const expiryTone = (iso: string | null | undefined): { text: string; cls: string } => {
  if (!iso) return { text: 'no expiry', cls: 'bg-slate-100 text-slate-500 ring-slate-200' }
  const daysLeft = Math.round((new Date(iso).getTime() - Date.now()) / 86_400_000)
  if (daysLeft < 0) return { text: `expired ${-daysLeft}d ago`, cls: 'bg-rose-50 text-rose-700 ring-rose-100' }
  if (daysLeft <= 30) return { text: `${daysLeft}d left`, cls: 'bg-amber-50 text-amber-700 ring-amber-100' }
  return { text: `${daysLeft}d left`, cls: 'bg-emerald-50 text-emerald-700 ring-emerald-100' }
}

/** clientCode "*" mints a platform-wide (WMS) key that ships for any client. */
const ALL_CLIENTS = '*'

const emptyForm = { name: '', environment: 'live' }

/**
 * External API Keys — mint the `msk_...` tokens client systems use to call the
 * public shipping API (/api/v1/external). The full token is shown exactly once
 * at issue time; the list only ever shows the masked form. Revocation is
 * immediate and permanent (keys cannot be re-activated).
 */
export default function ApiKeysPage() {
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [loading, setLoading] = useState(true)

  /** Issue modal state. */
  const [issueOpen, setIssueOpen] = useState(false)
  const [form, setForm] = useState(emptyForm)
  const [issuing, setIssuing] = useState(false)

  /** The freshly issued key — its `token` is shown once in the reveal modal. */
  const [issued, setIssued] = useState<ApiKey | null>(null)
  const [copied, setCopied] = useState(false)

  /** Key pending revoke confirmation. */
  const [revoking, setRevoking] = useState<ApiKey | null>(null)
  const [revokeBusy, setRevokeBusy] = useState(false)

  /** Audit A1 — key pending rotate confirmation + in-flight guard. */
  const [rotating, setRotating] = useState<ApiKey | null>(null)
  const [rotateBusy, setRotateBusy] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await apiKeyService.list()
      setKeys(Array.isArray(res.data) ? res.data : [])
    } catch (e) {
      notify.apiError(e, 'Failed to load API keys.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on mount; load() sets loading + result state
    void load()
  }, [load])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const activeCount = useMemo(() => keys.filter((k) => k.active).length, [keys])

  const openIssue = () => {
    setForm(emptyForm)
    setIssueOpen(true)
  }

  const submitIssue = async () => {
    if (!form.name.trim()) {
      notify.error('A key name is required.')
      return
    }
    setIssuing(true)
    try {
      const res = await apiKeyService.issue({
        name: form.name.trim(),
        clientCode: ALL_CLIENTS,
        environment: form.environment,
      })
      setIssueOpen(false)
      setCopied(false)
      setIssued(res.data ?? null)
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to issue the API key.')
    } finally {
      setIssuing(false)
    }
  }

  const copyToken = async () => {
    if (!issued?.token) return
    try {
      await navigator.clipboard.writeText(issued.token)
      setCopied(true)
      notify.success('Token copied to clipboard.')
    } catch {
      notify.error('Copy failed — select the token text and copy it manually.')
    }
  }

  const confirmRevoke = async () => {
    if (!revoking) return
    setRevokeBusy(true)
    try {
      await apiKeyService.revoke(revoking.id)
      notify.success(`API key "${revoking.name}" revoked.`)
      setRevoking(null)
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to revoke the API key.')
    } finally {
      setRevokeBusy(false)
    }
  }

  /** Audit A1 — mints a fresh key inheriting the old key's client + env
   *  + scopes. The old key stays valid for 24h (backend adds Deprecation
   *  + Sunset headers during grace). New plaintext token shown once via
   *  the same reveal modal used for issue. */
  const confirmRotate = async () => {
    if (!rotating) return
    setRotateBusy(true)
    try {
      const res = await apiKeyService.rotate(rotating.id)
      setRotating(null)
      setCopied(false)
      setIssued(res.data ?? null)
      notify.success('New key minted — old key still authenticates for 24h.')
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to rotate the API key.')
    } finally {
      setRotateBusy(false)
    }
  }

  return (
    <div className="space-y-4 pb-16">
      {/* actions bar */}
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={openIssue}
          className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15]"
        >
          <FiPlus className="h-3.5 w-3.5" />
          Issue API key
        </button>
        <p className="text-[11.5px] text-slate-500">
          Send the key as <span className="font-mono font-semibold text-slate-600">X-API-Key</span> on calls to{' '}
          <span className="font-mono font-semibold text-slate-600">/api/v1/external/…</span> — every call is scoped to
          the key's client.
        </p>
      </div>

      {/* health strip */}
      <section className="grid grid-cols-2 gap-3">
        {[
          { label: 'Active keys', value: activeCount, unit: 'keys', icon: FiKey, tone: 'border-emerald-200 bg-emerald-50 text-emerald-600' },
          { label: 'Revoked', value: keys.length - activeCount, unit: 'keys', icon: FiSlash, tone: 'border-rose-200 bg-rose-50 text-rose-500' },
        ].map((c, idx) => (
          <div key={c.label} className="rounded-2xl border border-slate-200 bg-white shadow-sm">
            <div className="flex items-start justify-between gap-2 px-4 pt-3.5">
              <p className="flex min-w-0 items-baseline gap-2">
                <span className="shrink-0 font-mono text-[9px] font-bold tracking-widest text-slate-300">
                  {String(idx + 1).padStart(2, '0')}
                </span>
                <span className="truncate text-[10px] font-bold uppercase tracking-[0.16em] text-slate-400">{c.label}</span>
              </p>
              <span className={`inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full border ${c.tone}`}>
                <c.icon className="h-4 w-4" />
              </span>
            </div>
            <p className="flex items-baseline gap-1.5 px-4 pb-4 pt-1">
              <span className="text-[28px] font-semibold leading-none tabular-nums tracking-tight text-slate-950">{c.value}</span>
              <span className="font-mono text-[9.5px] font-semibold uppercase tracking-[0.18em] text-slate-400">{c.unit}</span>
            </p>
          </div>
        ))}
      </section>

      {/* key ledger */}
      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex items-center gap-2.5 bg-[#1f150c] px-4 py-2.5">
          <span className="flex h-6 w-9 items-center justify-center rounded bg-[#e1dcc9]/15 font-mono text-[9px] font-black tracking-wider text-[#e1dcc9]">
            KEY
          </span>
          <span className="text-[10px] font-black uppercase tracking-[0.2em] text-[#e1dcc9]">External API keys</span>
        </div>

        {loading && !keys.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading keys…</p>
        ) : !keys.length ? (
          <div className="px-4 py-10 text-center">
            <p className="text-[12.5px] font-medium text-slate-500">No API keys yet.</p>
            <button
              type="button"
              onClick={openIssue}
              className="mt-2.5 inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15]"
            >
              <FiPlus className="h-3.5 w-3.5" />
              Issue the first key
            </button>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead>
                <tr className="border-b border-slate-200 bg-[#faf9f7]">
                  {['Name', 'Client', 'Token', 'Env', 'Scopes', 'Status', 'Expiry', 'Created', 'Last used', ''].map((h) => (
                    <th key={h} className="whitespace-nowrap px-4 py-2 text-[9.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-dashed divide-slate-200">
                {keys.map((k) => (
                  <tr key={k.id} className={k.active ? '' : 'opacity-55'}>
                    <td className="max-w-[180px] truncate px-4 py-2.5 text-[12.5px] font-semibold text-slate-900" title={k.name}>
                      {k.name}
                    </td>
                    <td className="px-4 py-2.5">
                      {k.clientCode === ALL_CLIENTS ? (
                        <span
                          title="Platform-wide key — ships for any client; each call names the client in its body."
                          className="rounded-full bg-violet-50 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-violet-700 ring-1 ring-violet-100"
                        >
                          All clients
                        </span>
                      ) : (
                        <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] font-semibold text-slate-600">
                          {k.clientCode}
                        </span>
                      )}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2.5 font-mono text-[11px] text-slate-500">{k.masked}</td>
                    <td className="px-4 py-2.5">
                      <span
                        className={`rounded-full px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ring-1 ${
                          k.environment === 'live'
                            ? 'bg-emerald-50 text-emerald-700 ring-emerald-100'
                            : 'bg-amber-50 text-amber-700 ring-amber-100'
                        }`}
                      >
                        {k.environment}
                      </span>
                    </td>
                    <td className="max-w-[220px] truncate px-4 py-2.5 font-mono text-[10.5px] text-slate-400" title={k.scopes}>
                      {k.scopes}
                    </td>
                    <td className="px-4 py-2.5">
                      <span
                        className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ring-1 ${
                          k.active ? 'bg-emerald-50 text-emerald-700 ring-emerald-100' : 'bg-rose-50 text-rose-600 ring-rose-100'
                        }`}
                      >
                        {k.active ? <FiCheck className="h-2.5 w-2.5" /> : <FiSlash className="h-2.5 w-2.5" />}
                        {k.active ? 'Active' : 'Revoked'}
                      </span>
                      {/* Audit A3/A4 — surface the 24h rotation grace window so
                          operators can tell "in-flight rotation" from a plain
                          Active key. */}
                      {k.active && k.lastRotatedAt ? (
                        <span
                          title={`Rotated ${relTime(k.lastRotatedAt)} — old key stops working 24h after rotation`}
                          className="ml-1 inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-amber-700 ring-1 ring-amber-100"
                        >
                          <FiRefreshCw className="h-2.5 w-2.5" />
                          Grace
                        </span>
                      ) : null}
                    </td>
                    <td className="px-4 py-2.5">
                      {(() => {
                        const t = expiryTone(k.expiresAt)
                        return (
                          <span
                            title={k.expiresAt ?? undefined}
                            className={`inline-flex items-center rounded-full px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ring-1 ${t.cls}`}
                          >
                            {t.text}
                          </span>
                        )
                      })()}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-[11.5px] text-slate-500" title={k.createdAt ?? undefined}>
                      {relTime(k.createdAt)}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2.5 text-[11.5px] text-slate-500" title={k.lastUsedAt ?? undefined}>
                      {relTime(k.lastUsedAt)}
                    </td>
                    <td className="px-4 py-2.5 text-right">
                      {k.active ? (
                        <div className="inline-flex items-center gap-1">
                          {/* Audit A1 — Rotate: mints a fresh key, old stays
                              valid 24h. Disabled if we're already inside the
                              grace window so operators don't accidentally
                              re-rotate before the sunset. */}
                          <button
                            type="button"
                            onClick={() => setRotating(k)}
                            disabled={!!k.lastRotatedAt}
                            title={k.lastRotatedAt
                              ? 'Already inside the 24h rotation grace window'
                              : 'Rotate — mints a new token; old one stays valid for 24h'}
                            className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10.5px] font-bold text-slate-600 transition hover:bg-slate-50 disabled:opacity-40 disabled:cursor-not-allowed"
                          >
                            <FiRefreshCw className="h-3 w-3" />
                            Rotate
                          </button>
                          <button
                            type="button"
                            onClick={() => setRevoking(k)}
                            className="inline-flex items-center gap-1 rounded-lg border border-rose-200 bg-rose-50 px-2 py-1 text-[10.5px] font-bold text-rose-600 transition hover:bg-rose-100"
                          >
                            <FiSlash className="h-3 w-3" />
                            Revoke
                          </button>
                        </div>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* issue modal */}
      {issueOpen ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={() => setIssueOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-1 flex items-start justify-between">
              <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
                <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600">
                  <FiKey className="h-4 w-4" />
                </span>
                Issue API key
              </h3>
              <button
                type="button"
                onClick={() => setIssueOpen(false)}
                className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
                aria-label="Close"
              >
                <FiX className="h-4 w-4" />
              </button>
            </div>
            <p className="text-[12px] text-slate-500">
              The key works for <span className="font-semibold text-slate-700">all clients</span> — every external call
              names the client it ships for via <span className="font-mono font-semibold text-slate-700">clientCode</span>{' '}
              in the request body. The full token is shown{' '}
              <span className="font-semibold text-slate-700">once</span>, right after issue.
            </p>

            <div className="mt-4 space-y-3">
              <label className="block">
                <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Key name</span>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))}
                  placeholder="e.g. ACME WMS integration"
                  className="mt-1 w-full rounded-xl border border-slate-200 px-3 py-2 text-[13px] text-slate-900 outline-none transition focus:border-slate-400"
                />
              </label>

              <div className="rounded-xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11.5px] text-slate-500">
                This key works for <span className="font-semibold text-slate-700">all clients</span>. Each external call
                names the client it ships for via <span className="font-mono font-semibold text-slate-700">clientCode</span>{' '}
                in the request body.
              </div>

              <div>
                <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">Environment</span>
                <div className="mt-1 flex gap-2">
                  {(['live', 'test'] as const).map((env) => (
                    <button
                      key={env}
                      type="button"
                      onClick={() => setForm((f) => ({ ...f, environment: env }))}
                      className={`flex-1 rounded-xl border px-3 py-2 text-[12.5px] font-semibold uppercase tracking-wide transition ${
                        form.environment === env
                          ? 'border-[#1f150c] bg-[#1f150c] text-white'
                          : 'border-slate-200 bg-white text-slate-500 hover:bg-slate-50'
                      }`}
                    >
                      {env}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <div className="mt-4 flex items-center justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={() => setIssueOpen(false)}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void submitIssue()}
                disabled={issuing}
                className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:opacity-50"
              >
                {issuing ? 'Issuing…' : 'Issue key'}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {/* token reveal modal — the ONLY time the full token is visible */}
      {issued ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm" role="dialog" aria-modal="true">
          <div className="w-full max-w-lg rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]">
            <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
              <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600">
                <FiCheck className="h-4 w-4" />
              </span>
              API key issued
            </h3>
            <p className="mt-1 text-[12px] text-slate-500">
              <span className="font-semibold text-slate-700">{issued.name}</span> · client{' '}
              <span className="font-mono font-semibold text-slate-700">
                {issued.clientCode === ALL_CLIENTS ? 'ALL (platform-wide)' : issued.clientCode}
              </span>{' '}
              · <span className="uppercase">{issued.environment}</span>
            </p>

            <div className="mt-3 flex items-center gap-2 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2">
              <FiAlertTriangle className="h-4 w-4 shrink-0 text-amber-500" />
              <p className="text-[11.5px] font-medium text-amber-800">
                Copy the token now — it is shown only once and cannot be recovered afterwards.
              </p>
            </div>

            <div className="mt-3 flex items-stretch gap-2">
              <code className="flex-1 select-all break-all rounded-xl border border-slate-200 bg-[#faf9f7] px-3 py-2.5 font-mono text-[11.5px] leading-relaxed text-slate-800">
                {issued.token}
              </code>
              <button
                type="button"
                onClick={() => void copyToken()}
                className={`inline-flex shrink-0 items-center gap-1.5 rounded-xl px-3 text-[12px] font-semibold transition ${
                  copied
                    ? 'border border-emerald-200 bg-emerald-50 text-emerald-700'
                    : 'bg-[#1f150c] text-white hover:bg-[#412d15]'
                }`}
              >
                {copied ? <FiCheck className="h-3.5 w-3.5" /> : <FiCopy className="h-3.5 w-3.5" />}
                {copied ? 'Copied' : 'Copy'}
              </button>
            </div>

            <p className="mt-2 text-[10.5px] text-slate-400">
              Use it as <span className="font-mono">X-API-Key: {issued.token?.slice(0, 12)}…</span> (or{' '}
              <span className="font-mono">Authorization: Bearer</span>) on <span className="font-mono">/api/v1/external</span> calls.
            </p>

            <div className="mt-4 flex items-center justify-end border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={() => setIssued(null)}
                className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15]"
              >
                Done — I saved the token
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {/* Audit A1 — rotate confirmation */}
      {rotating ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={() => setRotating(null)}
        >
          <div
            className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
              <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-slate-100 text-slate-700">
                <FiRefreshCw className="h-4 w-4" />
              </span>
              Rotate this key?
            </h3>
            <p className="mt-2 text-[12.5px] text-slate-600">
              A fresh token will be minted for <span className="font-semibold">{rotating.name}</span>.
              The old key ({rotating.masked}) keeps working for <span className="font-semibold">24 hours</span> so
              you have time to swap integrations, then stops.
            </p>
            <div className="mt-4 flex items-center justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={() => setRotating(null)}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void confirmRotate()}
                disabled={rotateBusy}
                className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:opacity-50"
              >
                {rotateBusy ? 'Rotating…' : 'Rotate key'}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {/* revoke confirmation */}
      {revoking ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
          role="dialog"
          aria-modal="true"
          onClick={() => setRevoking(null)}
        >
          <div
            className="w-full max-w-sm rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="inline-flex items-center gap-2 text-[15px] font-semibold text-slate-950">
              <span className="inline-flex h-7 w-7 items-center justify-center rounded-lg bg-rose-50 text-rose-500">
                <FiSlash className="h-4 w-4" />
              </span>
              Revoke this key?
            </h3>
            <p className="mt-2 text-[12.5px] text-slate-600">
              <span className="font-semibold">{revoking.name}</span> ({revoking.masked}) will stop working immediately.
              Revocation is permanent — the client system will need a new key.
            </p>
            <div className="mt-4 flex items-center justify-end gap-2 border-t border-slate-100 pt-4">
              <button
                type="button"
                onClick={() => setRevoking(null)}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void confirmRevoke()}
                disabled={revokeBusy}
                className="rounded-xl bg-rose-600 px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-rose-700 disabled:opacity-50"
              >
                {revokeBusy ? 'Revoking…' : 'Revoke key'}
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}
