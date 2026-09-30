/**
 * A4.1 — /settings/mail admin page.
 *
 * Ops picks the active mail provider, edits its config, and sends a live
 * test email. Secret values never come back from the server (redacted to
 * "•••"); editing a secret field with a blank input leaves it unchanged,
 * a new value replaces it.
 *
 * Later phases: A4.2 templates + backfill; A4.3 SendGrid/SES/Postmark
 * connectors; A4.4 delivery log; A4.5 per-user × per-template subs.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  mailProviderService,
  type MailKindDescriptor,
  type MailProviderSummary,
} from '../api/mailProviderService'

const REDACTED = '•••'

export default function MailSettingsPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [kinds, setKinds] = useState<MailKindDescriptor[]>([])
  const [providers, setProviders] = useState<MailProviderSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)

  // Provider being edited. `null` = "add new provider" form; a number = existing id.
  const [selectedId, setSelectedId] = useState<number | 'new' | null>(null)
  const [draftKind, setDraftKind] = useState<string>('')
  const [draftDisplayName, setDraftDisplayName] = useState<string>('')
  const [draftConfig, setDraftConfig] = useState<Record<string, string>>({})

  // Test-send form
  const [testTo, setTestTo] = useState('')
  const [testSubject, setTestSubject] = useState('')
  const [testBody, setTestBody] = useState('')

  const activeProvider = useMemo(() => providers.find((p) => p.active) ?? null, [providers])

  const kindDescriptor = useMemo(
    () => kinds.find((k) => k.kind === draftKind) ?? null,
    [kinds, draftKind],
  )

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [kindsResp, listResp] = await Promise.all([
        mailProviderService.listKinds(),
        mailProviderService.list(),
      ])
      setKinds(kindsResp)
      setProviders(listResp)
    } catch (err) {
      notify.apiError(err, 'Failed to load mail providers.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  /** Populate the edit form when the operator picks an existing row. */
  const openExisting = (p: MailProviderSummary) => {
    setSelectedId(p.id)
    setDraftKind(p.kind)
    setDraftDisplayName(p.displayName)
    setDraftConfig({ ...p.config })
  }

  const openNew = () => {
    setSelectedId('new')
    setDraftKind(kinds[0]?.kind ?? '')
    setDraftDisplayName('')
    setDraftConfig({})
  }

  const cancelEdit = () => {
    setSelectedId(null)
    setDraftKind('')
    setDraftDisplayName('')
    setDraftConfig({})
  }

  const save = async () => {
    if (!draftKind || !draftDisplayName.trim()) {
      notify.error('Kind and display name are required.')
      return
    }
    // Never resend the redacted marker back to the server — it would
    // overwrite a real secret with the placeholder.
    const cleanConfig: Record<string, string> = {}
    for (const [k, v] of Object.entries(draftConfig)) {
      if (v === REDACTED) continue
      cleanConfig[k] = v
    }
    setSaving(true)
    try {
      const saved = await mailProviderService.upsert({
        id: selectedId === 'new' ? undefined : (selectedId as number),
        kind: draftKind,
        displayName: draftDisplayName.trim(),
        config: cleanConfig,
      })
      notify.success('Provider saved.')
      await load()
      openExisting(saved)
    } catch (err) {
      notify.apiError(err, 'Failed to save provider.')
    } finally {
      setSaving(false)
    }
  }

  const activate = async (id: number) => {
    try {
      await mailProviderService.activate(id)
      notify.success('Provider activated.')
      await load()
    } catch (err) {
      notify.apiError(err, 'Failed to activate.')
    }
  }

  const remove = async (id: number) => {
    if (!(await notify.confirm('Delete this provider and all its config rows?'))) return
    try {
      await mailProviderService.remove(id)
      notify.success('Provider deleted.')
      if (selectedId === id) cancelEdit()
      await load()
    } catch (err) {
      notify.apiError(err, 'Failed to delete.')
    }
  }

  const runTestSend = async () => {
    if (!testTo.trim()) {
      notify.error('Recipient email required.')
      return
    }
    setTesting(true)
    try {
      const res = await mailProviderService.testSend({
        to: testTo.trim(),
        subject: testSubject.trim() || undefined,
        body: testBody.trim() || undefined,
      })
      if (res.delivered) {
        notify.success(`Test email sent to ${testTo.trim()}.`)
      } else {
        notify.error('Test send returned but reported not delivered — check the delivery log (coming in A4.4).')
      }
    } catch (err) {
      notify.apiError(err, 'Test send failed.')
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="space-y-4">
      {/* Header banner: active provider at a glance. */}
      <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="text-[13px] font-semibold text-slate-800">Active mail provider</div>
            {activeProvider ? (
              <div className="mt-1 text-[13px] text-slate-600">
                <span className="rounded bg-emerald-100 px-2 py-0.5 font-semibold text-emerald-800">
                  {activeProvider.kind}
                </span>{' '}
                {activeProvider.displayName}
              </div>
            ) : (
              <div className="mt-1 text-[13px] text-amber-700">
                No provider active — outbound emails are logged only. Configure one below.
              </div>
            )}
          </div>
          <button
            type="button"
            onClick={openNew}
            className="inline-flex items-center gap-1 rounded-lg bg-slate-900 px-3 py-1.5 text-[12px] font-semibold text-white hover:bg-slate-700"
          >
            + Add provider
          </button>
        </div>
      </section>

      {/* Provider list */}
      <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <div className="border-b border-slate-200 px-4 py-2 text-[13px] font-semibold text-slate-800">
          Registered providers
        </div>
        {loading ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
        ) : providers.length === 0 ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">No providers yet.</div>
        ) : (
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-slate-500">
                <th className="px-4 py-2 font-semibold">Kind</th>
                <th className="px-4 py-2 font-semibold">Name</th>
                <th className="px-4 py-2 font-semibold">Active</th>
                <th className="px-4 py-2 font-semibold">Updated</th>
                <th className="px-4 py-2 text-right font-semibold">Actions</th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <tr key={p.id} className="border-t border-slate-100">
                  <td className="px-4 py-2 font-semibold text-slate-800">{p.kind}</td>
                  <td className="px-4 py-2 text-slate-700">{p.displayName}</td>
                  <td className="px-4 py-2">
                    {p.active ? (
                      <span className="rounded bg-emerald-100 px-2 py-0.5 text-emerald-800">Active</span>
                    ) : (
                      <span className="rounded bg-slate-100 px-2 py-0.5 text-slate-600">Inactive</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-slate-500">
                    {p.updatedAt ? new Date(p.updatedAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <div className="inline-flex gap-2">
                      <button
                        type="button"
                        onClick={() => openExisting(p)}
                        className="rounded border border-slate-300 px-2 py-1 text-[12px] hover:bg-slate-50"
                      >
                        Edit
                      </button>
                      {!p.active && (
                        <button
                          type="button"
                          onClick={() => void activate(p.id)}
                          className="rounded border border-emerald-300 px-2 py-1 text-[12px] text-emerald-700 hover:bg-emerald-50"
                        >
                          Activate
                        </button>
                      )}
                      <button
                        type="button"
                        onClick={() => void remove(p.id)}
                        className="rounded border border-rose-300 px-2 py-1 text-[12px] text-rose-700 hover:bg-rose-50"
                      >
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {/* Editor */}
      {selectedId !== null && (
        <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
          <div className="mb-3 flex items-center justify-between">
            <div className="text-[13px] font-semibold text-slate-800">
              {selectedId === 'new' ? 'New provider' : `Edit provider #${selectedId}`}
            </div>
            <button
              type="button"
              onClick={cancelEdit}
              className="text-[12px] text-slate-500 hover:text-slate-800"
            >
              Cancel
            </button>
          </div>

          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="text-[12px] font-semibold text-slate-700">
              Kind
              <select
                value={draftKind}
                onChange={(e) => {
                  setDraftKind(e.target.value)
                  setDraftConfig({})  // config keys are kind-specific
                }}
                disabled={selectedId !== 'new'}
                className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-[13px]"
              >
                {kinds.map((k) => (
                  <option key={k.kind} value={k.kind}>
                    {k.kind}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-[12px] font-semibold text-slate-700">
              Display name
              <input
                value={draftDisplayName}
                onChange={(e) => setDraftDisplayName(e.target.value)}
                className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-[13px]"
                placeholder="e.g. Company SMTP"
              />
            </label>
          </div>

          {kindDescriptor && (
            <div className="mt-4 space-y-2">
              <div className="text-[12px] font-semibold text-slate-700">Configuration</div>
              {kindDescriptor.requiredKeys.map((key) => (
                <ConfigInput
                  key={key}
                  keyName={key}
                  isSecret={false}
                  value={draftConfig[key] ?? ''}
                  onChange={(v) => setDraftConfig({ ...draftConfig, [key]: v })}
                />
              ))}
              {kindDescriptor.secretKeys.map((key) => (
                <ConfigInput
                  key={key}
                  keyName={key}
                  isSecret={true}
                  value={draftConfig[key] ?? ''}
                  onChange={(v) => setDraftConfig({ ...draftConfig, [key]: v })}
                />
              ))}
            </div>
          )}

          <div className="mt-4 flex justify-end">
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving}
              className="inline-flex items-center gap-1 rounded-lg bg-slate-900 px-4 py-2 text-[12px] font-semibold text-white hover:bg-slate-700 disabled:opacity-60"
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </section>
      )}

      {/* Test send */}
      <section className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
        <div className="mb-3 text-[13px] font-semibold text-slate-800">
          Send test email — via the currently active provider
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <label className="text-[12px] font-semibold text-slate-700">
            To
            <input
              type="email"
              value={testTo}
              onChange={(e) => setTestTo(e.target.value)}
              placeholder="you@example.com"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-[13px]"
            />
          </label>
          <label className="text-[12px] font-semibold text-slate-700 sm:col-span-2">
            Subject (optional)
            <input
              value={testSubject}
              onChange={(e) => setTestSubject(e.target.value)}
              placeholder="shioai mail test"
              className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-[13px]"
            />
          </label>
        </div>
        <label className="mt-3 block text-[12px] font-semibold text-slate-700">
          Body (optional)
          <textarea
            value={testBody}
            onChange={(e) => setTestBody(e.target.value)}
            rows={3}
            placeholder="This is a test message sent from /settings/mail — you can delete it."
            className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-[13px]"
          />
        </label>
        <div className="mt-3 flex items-center justify-between">
          <div className="text-[11px] text-slate-500">
            {activeProvider
              ? `Will send via ${activeProvider.kind} — ${activeProvider.displayName}.`
              : 'No active provider — configure one above first.'}
          </div>
          <button
            type="button"
            onClick={() => void runTestSend()}
            disabled={testing || !activeProvider}
            className="inline-flex items-center gap-1 rounded-lg bg-emerald-600 px-4 py-2 text-[12px] font-semibold text-white hover:bg-emerald-700 disabled:opacity-60"
          >
            {testing ? 'Sending…' : 'Send test'}
          </button>
        </div>
      </section>
    </div>
  )
}

function ConfigInput({
  keyName,
  isSecret,
  value,
  onChange,
}: {
  keyName: string
  isSecret: boolean
  value: string
  onChange: (v: string) => void
}) {
  return (
    <label className="grid grid-cols-[10rem_1fr] items-center gap-3 text-[12px]">
      <span className="font-mono text-slate-600">
        {keyName}
        {isSecret && <span className="ml-1 rounded bg-amber-100 px-1 text-[10px] text-amber-800">secret</span>}
      </span>
      <input
        type={isSecret ? 'password' : 'text'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={isSecret && value === REDACTED ? 'unchanged — type to replace' : ''}
        className="rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]"
      />
    </label>
  )
}
