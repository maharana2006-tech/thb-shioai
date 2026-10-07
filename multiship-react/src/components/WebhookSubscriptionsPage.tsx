import { useCallback, useEffect, useId, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { FiEdit3, FiPlus, FiTrash2, FiX, FiZap } from 'react-icons/fi'
import { notify } from '../utils/notify'
import { apiKeyService, type ApiKey } from '../api/apiKeyService'
import {
  webhookSubscriptionService,
  type WebhookEventType,
  type WebhookSubscription,
} from '../api/webhookSubscriptionService'
import Select from './workspace/Select'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'

const EVENTS: { value: WebhookEventType; label: string; hint: string }[] = [
  { value: 'LABEL_GENERATED',  label: 'LABEL_GENERATED',  hint: 'A shipment label was successfully created.' },
  { value: 'TRACKING_UPDATED', label: 'TRACKING_UPDATED', hint: 'A tracking status changed.' },
  { value: 'EXCEPTION',        label: 'EXCEPTION',        hint: 'A carrier reported an exception (delivery attempt failed, address issue, etc).' },
  { value: 'RULE_BLOCKED',     label: 'RULE_BLOCKED',     hint: 'A routing rule blocked generation for an order.' },
]

/**
 * Sprint 46 — admin management of external webhook subscriptions.
 * Ties into the ApiKey book: each subscription belongs to one API key.
 * Delivery uses HMAC-SHA256 signing (X-Multiship-Signature header) with
 * up to 3 attempts and exponential backoff.
 */
export default function WebhookSubscriptionsPage() {
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  const { role } = useAppSession()
  // Audit W1 — backend @PreAuthorize on POST + DELETE is ADMIN-only. USER
  // role can only read the list. Hide write buttons so USERs don't hit a
  // 403 after clicking (was silently confusing: modal opened, save failed,
  // no path forward).
  const canWrite = normalizeRole(role) === 'ADMIN'
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [subs, setSubs] = useState<WebhookSubscription[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<WebhookSubscription | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [ks, ss] = await Promise.all([
        apiKeyService.list(),
        webhookSubscriptionService.list(),
      ])
      setKeys(Array.isArray(ks.data) ? ks.data : [])
      setSubs(ss)
    } catch (err: unknown) {
      notify.error(err instanceof Error ? err.message : 'Failed to load webhook subscriptions.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on mount / manual refresh; load() sets loading + subs state
    load()
  }, [load, reloadToken])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  const del = async (s: WebhookSubscription) => {
    if (!s.id) return
    if (!window.confirm(`Delete subscription for ${s.event} → ${s.url}?`)) return
    try {
      await webhookSubscriptionService.remove(s.id)
      notify.success('Subscription deleted.')
      refresh()
    } catch (err: unknown) {
      notify.error(err instanceof Error ? err.message : 'Delete failed.')
    }
  }

  const keyLabel = (id: number) => {
    const k = keys.find((x) => x.id === id)
    return k ? `${k.name} (${k.clientCode})` : `#${id}`
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-[15px] font-semibold text-slate-900">Webhook subscriptions</h2>
          <p className="text-[12px] text-slate-500">
            External apps subscribe to platform events via an API key. Delivery is HMAC-SHA256
            signed and retried on failure.
          </p>
        </div>
        {canWrite ? (
          <button
            type="button"
            onClick={() => setEditing({
              apiKeyId: keys[0]?.id ?? 0,
              event: 'LABEL_GENERATED',
              url: '',
              secret: '',
              active: true,
            })}
            disabled={keys.length === 0}
            className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50"
          >
            <FiPlus /> New subscription
          </button>
        ) : null}
      </div>

      <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <table className="min-w-full text-[12.5px]">
          <thead className="bg-slate-50 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
            <tr>
              <th className="px-4 py-2 text-left">Event</th>
              <th className="px-4 py-2 text-left">API key</th>
              <th className="px-4 py-2 text-left">URL</th>
              <th className="px-4 py-2 text-left">Secret</th>
              <th className="px-4 py-2 text-left">Active</th>
              <th className="px-4 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-400">Loading…</td></tr>
            ) : subs.length === 0 ? (
              <tr><td colSpan={6} className="px-4 py-6 text-center text-slate-400">
                No subscriptions yet. External apps can also POST to <code>/api/v2/external/webhooks</code> with their own token to self-serve.
              </td></tr>
            ) : subs.map((s) => (
              <tr key={s.id} className="hover:bg-slate-50 align-top">
                <td className="px-4 py-2"><span className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] font-bold text-slate-700">{s.event}</span></td>
                <td className="px-4 py-2 text-slate-800">{keyLabel(s.apiKeyId)}</td>
                <td className="px-4 py-2 font-mono text-[11.5px] text-slate-700">{s.url}</td>
                <td className="px-4 py-2 font-mono text-[11.5px] text-slate-400">{s.secret}</td>
                <td className="px-4 py-2 text-[11.5px]">
                  {s.active ? <span className="text-emerald-700">Active</span> : <span className="text-rose-600">Inactive</span>}
                </td>
                <td className="px-4 py-2 text-right">
                  {canWrite ? (
                    <div className="inline-flex gap-1">
                      <button title="Edit" onClick={() => setEditing({ ...s })}
                        className="rounded p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-800"><FiEdit3 /></button>
                      <button title="Delete" onClick={() => del(s)}
                        className="rounded p-1 text-rose-500 hover:bg-rose-50"><FiTrash2 /></button>
                    </div>
                  ) : (
                    <span className="text-[11px] text-slate-400">read-only</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editing ? (
        <SubscriptionEditor
          sub={editing}
          keys={keys}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); refresh() }}
        />
      ) : null}
    </div>
  )
}

function SubscriptionEditor({
  sub, keys, onClose, onSaved,
}: {
  sub: WebhookSubscription
  keys: ApiKey[]
  onClose: () => void
  onSaved: () => void
}) {
  const [draft, setDraft] = useState<WebhookSubscription>({ ...sub })
  const [saving, setSaving] = useState(false)
  // A11y audit A1 — see idPrefix pattern in ReportsPage / RoutingRulesPage.
  const idPrefix = useId()
  const idFor = useCallback((k: string) => `${idPrefix}-${k}`, [idPrefix])

  const set = <K extends keyof WebhookSubscription>(k: K, v: WebhookSubscription[K]) =>
    setDraft((d) => ({ ...d, [k]: v }))

  const save = async () => {
    if (!draft.apiKeyId) { notify.error('Pick an API key.'); return }
    if (!draft.url.trim()) { notify.error('URL is required.'); return }
    if (!draft.secret.trim() || draft.secret.startsWith('•')) {
      notify.error('Enter a plaintext HMAC secret.'); return
    }
    setSaving(true)
    try {
      await webhookSubscriptionService.save(draft)
      notify.success('Subscription saved.')
      onSaved()
    } catch (err) {
      notify.apiError(err, 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  const eventDetail = useMemo(
    () => EVENTS.find((e) => e.value === draft.event),
    [draft.event],
  )

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center">
      <div className="absolute inset-0 bg-slate-950/45 backdrop-blur-sm" onClick={onClose} />
      <div className="relative z-10 w-full max-w-lg overflow-hidden rounded-2xl bg-white shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4">
          <h3 className="text-base font-semibold text-slate-900">{draft.id ? 'Edit subscription' : 'New subscription'}</h3>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-800"><FiX /></button>
        </div>

        <div className="max-h-[70vh] space-y-3 overflow-y-auto px-6 py-5">
          <div>
            <label htmlFor={idFor('apiKey')} className={fieldLabel}>API key</label>
            <Select id={idFor('apiKey')} value={String(draft.apiKeyId)} onChange={(e) => set('apiKeyId', Number(e.target.value))}>
              <option value="0">— pick an API key —</option>
              {keys.map((k) => <option key={k.id} value={k.id}>{k.name} ({k.clientCode})</option>)}
            </Select>
          </div>
          <div>
            <label htmlFor={idFor('event')} className={fieldLabel}>Event</label>
            <Select id={idFor('event')} value={draft.event} onChange={(e) => set('event', e.target.value as WebhookEventType)}>
              {EVENTS.map((e) => <option key={e.value} value={e.value}>{e.label}</option>)}
            </Select>
            {eventDetail ? <p className="mt-1 text-[11px] text-slate-500">{eventDetail.hint}</p> : null}
          </div>
          <div>
            <label htmlFor={idFor('url')} className={fieldLabel}>URL</label>
            <input id={idFor('url')} value={draft.url} onChange={(e) => set('url', e.target.value)} className={inputCls}
              placeholder="https://api.partner.example/multiship-webhooks" />
          </div>
          <div>
            <label htmlFor={idFor('secret')} className={fieldLabel}>HMAC secret</label>
            <input id={idFor('secret')} value={draft.secret.startsWith('•') ? '' : draft.secret}
              onChange={(e) => set('secret', e.target.value)}
              placeholder={draft.secret.startsWith('•') ? 'Enter a NEW secret to rotate' : 'shared with the receiver'}
              className={`${inputCls} font-mono`} />
            <p className="mt-1 text-[11px] text-slate-500">
              Used to sign delivered payloads via HMAC-SHA256 on the <code>X-Multiship-Signature</code> header.
            </p>
          </div>
          <label className="inline-flex items-center gap-2 text-[13px]">
            <input type="checkbox" checked={draft.active !== false} onChange={(e) => set('active', e.target.checked)} />
            Active
          </label>
          <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-[11.5px] text-slate-600">
            <FiZap className="mr-1 inline text-amber-500" />
            Delivery pattern: RestClient POST with <code>Content-Type: application/json</code>,{' '}
            <code>X-Multiship-Event</code>, and HMAC-SHA256 signature. 3 attempts with exponential backoff.
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-slate-200 px-6 py-3">
          <button onClick={onClose} className="rounded-md border border-slate-200 px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50">Cancel</button>
          <button onClick={save} disabled={saving} className="rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50">
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}

const fieldLabel = 'mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500'
const inputCls =
  'w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]'
