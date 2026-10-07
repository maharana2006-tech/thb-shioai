/**
 * A4.1 — /settings/mail admin page.
 *
 * Sections:
 *  1. Active-provider banner (green/amber).
 *  2. Provider list. Add/Edit both open MailProviderFormModal (themed).
 *  3. Notification templates — quick access to the notification_template
 *     rows without leaving the mail page. Deep edits still happen on
 *     /settings/notification-templates.
 *  4. Test-send — sends via the active provider through the delivery log.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  mailProviderService,
  type MailKindDescriptor,
  type MailProviderSummary,
} from '../api/mailProviderService'
import {
  notificationTemplateService,
  type NotificationTemplate,
} from '../api/notificationTemplateService'
import MailProviderFormModal from './mail/MailProviderFormModal'

export default function MailSettingsPage() {
  const outlet = useOutletContext<SettingsOutletContext>()
  const navigate = useNavigate()

  const [kinds, setKinds] = useState<MailKindDescriptor[]>([])
  const [providers, setProviders] = useState<MailProviderSummary[]>([])
  const [templates, setTemplates] = useState<NotificationTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [testing, setTesting] = useState(false)

  // Modal state.
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<MailProviderSummary | null>(null)

  // Test-send form.
  const [testTo, setTestTo] = useState('')
  const [testSubject, setTestSubject] = useState('')
  const [testBody, setTestBody] = useState('')

  const activeProvider = useMemo(() => providers.find((p) => p.active) ?? null, [providers])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [kindsResp, listResp, templatesResp] = await Promise.all([
        mailProviderService.listKinds(),
        mailProviderService.list(),
        notificationTemplateService.list().catch(() => [] as NotificationTemplate[]),
      ])
      setKinds(kindsResp)
      setProviders(listResp)
      setTemplates(templatesResp)
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

  const openAddModal = () => {
    setEditing(null)
    setModalOpen(true)
  }
  const openEditModal = (p: MailProviderSummary) => {
    setEditing(p)
    setModalOpen(true)
  }

  const submitForm = async (payload: {
    id?: number
    kind: string
    displayName: string
    config: Record<string, string>
  }) => {
    try {
      await mailProviderService.upsert(payload)
      notify.success(payload.id ? 'Provider updated.' : 'Provider added.')
      await load()
    } catch (err) {
      notify.apiError(err, 'Failed to save provider.')
      throw err
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
        notify.error('Test send returned but reported not delivered — check the delivery log.')
      }
    } catch (err) {
      notify.apiError(err, 'Test send failed.')
    } finally {
      setTesting(false)
    }
  }

  return (
    <div className="space-y-4">
      {/* Active-provider banner. */}
      <section className="rounded-xl border border-[#e3d9c4] bg-white p-4 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="text-[13px] font-semibold text-[#1f150c]">Active mail provider</div>
            {activeProvider ? (
              <div className="mt-1 text-[13px] text-[#5a4526]">
                <span className="rounded bg-emerald-100 px-2 py-0.5 font-semibold text-emerald-800">
                  {activeProvider.kind}
                </span>{' '}
                {activeProvider.displayName}
              </div>
            ) : (
              <div className="mt-1 text-[13px] text-amber-700">
                No provider active — outbound emails are logged only. Add + activate one below.
              </div>
            )}
          </div>
          <button
            type="button"
            onClick={openAddModal}
            className="inline-flex items-center gap-1 rounded-xl bg-[#1f150c] px-4 py-2 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            + Add provider
          </button>
        </div>
      </section>

      {/* Provider list. */}
      <section className="rounded-xl border border-[#e3d9c4] bg-white shadow-sm">
        <div className="border-b border-[#eee6d6] px-4 py-2 text-[13px] font-semibold text-[#1f150c]">
          Registered providers
        </div>
        {loading ? (
          <div className="px-4 py-6 text-center text-[13px] text-[#5a4526]">Loading…</div>
        ) : providers.length === 0 ? (
          <div className="px-4 py-6 text-center text-[13px] text-[#5a4526]">
            No providers yet. Click <span className="font-semibold">Add provider</span> above.
          </div>
        ) : (
          <table className="w-full text-[13px]">
            <thead>
              <tr className="text-left text-[#5a4526]">
                <th className="px-4 py-2 font-semibold">Kind</th>
                <th className="px-4 py-2 font-semibold">Name</th>
                <th className="px-4 py-2 font-semibold">Active</th>
                <th className="px-4 py-2 font-semibold">Updated</th>
                <th className="px-4 py-2 text-right font-semibold">Actions</th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p) => (
                <tr key={p.id} className="border-t border-[#eee6d6]">
                  <td className="px-4 py-2 font-semibold text-[#1f150c]">{p.kind}</td>
                  <td className="px-4 py-2 text-[#5a4526]">{p.displayName}</td>
                  <td className="px-4 py-2">
                    {p.active ? (
                      <span className="rounded bg-emerald-100 px-2 py-0.5 text-emerald-800">Active</span>
                    ) : (
                      <span className="rounded bg-[#faf7f0] px-2 py-0.5 text-[#5a4526]">Inactive</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-[#b6a684]">
                    {p.updatedAt ? new Date(p.updatedAt).toLocaleString() : '—'}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <div className="inline-flex gap-2">
                      <button
                        type="button"
                        onClick={() => openEditModal(p)}
                        className="rounded border border-[#e3d9c4] px-2 py-1 text-[12px] text-[#5a4526] hover:bg-[#faf7f0]"
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

      {/* Notification templates — quick view. */}
      <section className="rounded-xl border border-[#e3d9c4] bg-white shadow-sm">
        <div className="flex items-center justify-between border-b border-[#eee6d6] px-4 py-2">
          <div className="text-[13px] font-semibold text-[#1f150c]">Notification templates</div>
          <button
            type="button"
            onClick={() => navigate('/settings/notification-templates')}
            className="text-[11.5px] font-semibold text-[#412d15] hover:underline"
          >
            Open editor →
          </button>
        </div>
        {loading ? (
          <div className="px-4 py-4 text-center text-[13px] text-[#5a4526]">Loading…</div>
        ) : templates.length === 0 ? (
          <div className="px-4 py-4 text-center text-[13px] text-[#5a4526]">
            No templates yet. Templates seed with the first backend boot after V103.
          </div>
        ) : (
          <ul className="divide-y divide-[#eee6d6] text-[13px]">
            {templates.map((t) => (
              <li key={t.templateKey} className="flex items-center justify-between gap-4 px-4 py-2">
                <div className="min-w-0">
                  <div className="font-mono text-[12px] font-semibold text-[#1f150c]">{t.templateKey}</div>
                  {t.description && (
                    <div className="mt-0.5 truncate text-[11.5px] text-[#5a4526]" title={t.description}>
                      {t.description}
                    </div>
                  )}
                </div>
                <div className="flex shrink-0 items-center gap-2">
                  {t.optOutAllowed ? (
                    <span className="rounded bg-amber-100 px-2 py-0.5 text-[10px] text-amber-800">opt-out</span>
                  ) : (
                    <span className="rounded bg-[#faf7f0] px-2 py-0.5 text-[10px] text-[#5a4526]">
                      transactional
                    </span>
                  )}
                  <button
                    type="button"
                    onClick={() =>
                      navigate(`/settings/notification-templates?key=${encodeURIComponent(t.templateKey)}`)
                    }
                    className="rounded border border-[#e3d9c4] px-2 py-1 text-[11.5px] text-[#5a4526] hover:bg-[#faf7f0]"
                  >
                    Edit
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Test send. */}
      <section className="rounded-xl border border-[#e3d9c4] bg-white p-4 shadow-sm">
        <div className="mb-3 text-[13px] font-semibold text-[#1f150c]">
          Send test email — via the currently active provider
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <label className="text-[12px] font-semibold text-[#5a4526]">
            To
            <input
              type="email"
              value={testTo}
              onChange={(e) => setTestTo(e.target.value)}
              placeholder="you@example.com"
              className="mt-1 block w-full rounded-lg border border-[#e3d9c4] px-3 py-2 text-[13px] text-[#1f150c]"
            />
          </label>
          <label className="text-[12px] font-semibold text-[#5a4526] sm:col-span-2">
            Subject (optional)
            <input
              value={testSubject}
              onChange={(e) => setTestSubject(e.target.value)}
              placeholder="shioai mail test"
              className="mt-1 block w-full rounded-lg border border-[#e3d9c4] px-3 py-2 text-[13px] text-[#1f150c]"
            />
          </label>
        </div>
        <label className="mt-3 block text-[12px] font-semibold text-[#5a4526]">
          Body (optional)
          <textarea
            value={testBody}
            onChange={(e) => setTestBody(e.target.value)}
            rows={3}
            placeholder="This is a test message sent from /settings/mail — you can delete it."
            className="mt-1 block w-full rounded-lg border border-[#e3d9c4] px-3 py-2 text-[13px] text-[#1f150c]"
          />
        </label>
        <div className="mt-3 flex items-center justify-between">
          <div className="text-[11px] text-[#5a4526]">
            {activeProvider
              ? `Will send via ${activeProvider.kind} — ${activeProvider.displayName}.`
              : 'No active provider — add + activate one above first.'}
          </div>
          <button
            type="button"
            onClick={() => void runTestSend()}
            disabled={testing || !activeProvider}
            className="inline-flex items-center gap-1 rounded-xl bg-emerald-600 px-4 py-2 text-[12px] font-semibold text-white transition hover:bg-emerald-700 disabled:opacity-60"
          >
            {testing ? 'Sending…' : 'Send test'}
          </button>
        </div>
      </section>

      {/* Add/Edit modal. */}
      <MailProviderFormModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        initial={editing}
        kinds={kinds}
        onSubmit={submitForm}
      />
    </div>
  )
}
