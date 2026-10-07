/**
 * A4.2 — /settings/notification-templates admin page.
 *
 * Two-column layout: left is the template list, right is the editor for
 * the currently-selected key. Preview panel renders the current draft
 * against a JSON var map without sending. Save persists the edit.
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext, useSearchParams } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  notificationTemplateService,
  type NotificationTemplate,
  type NotificationTemplatePreviewResponse,
} from '../api/notificationTemplateService'
import Modal, { ModalActions } from './common/Modal'

/** Reasonable starter var-maps per key so operators aren't hunting for
 *  variable names on first open. New template keys default to `{}`. */
const DEFAULT_VARS: Record<string, Record<string, unknown>> = {
  'AUTH.VERIFY_EMAIL': { verifyLink: 'https://app.example/verify-email?token=xyz', ttlHours: 24 },
  'AUTH.PASSWORD_RESET': { resetLink: 'https://app.example/reset-password?token=xyz', ttlMinutes: 30 },
  'AUTH.USER_INVITE': {
    acceptLink: 'https://app.example/invite/xyz',
    role: 'USER',
    clientCode: 'ACME',
    ttlDays: 7,
    invitedBy: 'admin@example.com',
  },
}

export default function NotificationTemplatesPage() {
  const outlet = useOutletContext<SettingsOutletContext>()
  const [searchParams] = useSearchParams()

  const [templates, setTemplates] = useState<NotificationTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  // New-key modal state.
  const [newModalOpen, setNewModalOpen] = useState(false)
  const [newKeyInput, setNewKeyInput] = useState('')
  const [draft, setDraft] = useState<NotificationTemplate | null>(null)
  const [varsJson, setVarsJson] = useState('{}')
  const [preview, setPreview] = useState<NotificationTemplatePreviewResponse | null>(null)
  const [saving, setSaving] = useState(false)
  const [previewing, setPreviewing] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const list = await notificationTemplateService.list()
      setTemplates(list)
      // Deep-link precedence: ?key=X wins if present + resolvable.
      // Then keep existing selection if still valid.
      // Otherwise fall back to the first row.
      const deepLink = searchParams.get('key')
      if (deepLink && list.some((t) => t.templateKey === deepLink)) {
        setSelectedKey(deepLink)
      } else if (selectedKey && !list.some((t) => t.templateKey === selectedKey)) {
        setSelectedKey(list[0]?.templateKey ?? null)
      } else if (!selectedKey && list.length > 0) {
        setSelectedKey(list[0].templateKey)
      }
    } catch (err) {
      notify.apiError(err, 'Failed to load templates.')
    } finally {
      setLoading(false)
    }
  }, [selectedKey, searchParams])

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  // Hydrate the editor whenever the selected key changes.
  useEffect(() => {
    if (!selectedKey) {
      setDraft(null)
      setVarsJson('{}')
      setPreview(null)
      return
    }
    const found = templates.find((t) => t.templateKey === selectedKey) ?? null
    setDraft(found ? { ...found } : null)
    setVarsJson(JSON.stringify(DEFAULT_VARS[selectedKey] ?? {}, null, 2))
    setPreview(null)
  }, [selectedKey, templates])

  const parsedVars = useMemo(() => {
    try {
      return JSON.parse(varsJson || '{}')
    } catch {
      return null  // preview button will surface the parse error
    }
  }, [varsJson])

  const save = async () => {
    if (!draft) return
    if (!draft.subjectTemplate.trim() || !draft.bodyTemplate.trim()) {
      notify.error('Subject and body are required.')
      return
    }
    setSaving(true)
    try {
      const saved = await notificationTemplateService.upsert(draft.templateKey, {
        description: draft.description ?? undefined,
        subjectTemplate: draft.subjectTemplate,
        bodyTemplate: draft.bodyTemplate,
        optOutAllowed: draft.optOutAllowed,
      })
      notify.success('Template saved.')
      // Refresh list + keep selection.
      setTemplates((prev) =>
        prev.some((t) => t.templateKey === saved.templateKey)
          ? prev.map((t) => (t.templateKey === saved.templateKey ? saved : t))
          : [...prev, saved],
      )
      setDraft(saved)
    } catch (err) {
      notify.apiError(err, 'Save failed.')
    } finally {
      setSaving(false)
    }
  }

  const runPreview = async () => {
    if (!draft) return
    if (parsedVars === null) {
      notify.error('Vars must be valid JSON.')
      return
    }
    setPreviewing(true)
    try {
      const res = await notificationTemplateService.preview({
        subjectTemplate: draft.subjectTemplate,
        bodyTemplate: draft.bodyTemplate,
        vars: parsedVars as Record<string, unknown>,
      })
      setPreview(res)
    } catch (err) {
      notify.apiError(err, 'Preview failed.')
      setPreview(null)
    } finally {
      setPreviewing(false)
    }
  }

  const openNewModal = () => {
    setNewKeyInput('')
    setNewModalOpen(true)
  }
  const confirmNewKey = () => {
    const normalized = newKeyInput.trim().toUpperCase()
    if (!normalized) {
      notify.error('Template key is required.')
      return
    }
    if (!/^[A-Z0-9._]+$/.test(normalized)) {
      notify.error('Use uppercase letters, digits, dots and underscores only.')
      return
    }
    if (templates.some((t) => t.templateKey === normalized)) {
      notify.error('That key already exists.')
      return
    }
    setTemplates((prev) => [
      ...prev,
      {
        templateKey: normalized,
        description: '',
        subjectTemplate: '',
        bodyTemplate: '',
        optOutAllowed: false,
        updatedAt: null,
        updatedBy: null,
      },
    ])
    setSelectedKey(normalized)
    setNewModalOpen(false)
  }

  const remove = async (key: string) => {
    if (!(await notify.confirm(`Delete template ${key}? Callers referencing it will fail.`))) return
    try {
      await notificationTemplateService.remove(key)
      notify.success('Template deleted.')
      if (selectedKey === key) setSelectedKey(null)
      await load()
    } catch (err) {
      notify.apiError(err, 'Delete failed.')
    }
  }

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-[16rem_1fr]">
      {/* Left column: template list */}
      <aside className="rounded-xl border border-slate-200 bg-white p-2 shadow-sm">
        <div className="mb-2 flex items-center justify-between px-2 pt-1">
          <span className="text-[12px] font-semibold text-slate-800">Templates</span>
          <button
            type="button"
            onClick={openNewModal}
            title="New template key"
            className="rounded border border-slate-300 px-2 py-0.5 text-[11px] hover:bg-slate-50"
          >
            + New
          </button>
        </div>
        {loading ? (
          <div className="px-2 py-4 text-center text-[12px] text-slate-500">Loading…</div>
        ) : templates.length === 0 ? (
          <div className="px-2 py-4 text-center text-[12px] text-slate-500">No templates.</div>
        ) : (
          <ul className="space-y-1">
            {templates.map((t) => (
              <li key={t.templateKey}>
                <button
                  type="button"
                  onClick={() => setSelectedKey(t.templateKey)}
                  className={`block w-full rounded px-2 py-1.5 text-left text-[12px] font-mono ${
                    selectedKey === t.templateKey
                      ? 'bg-slate-900 text-white'
                      : 'text-slate-700 hover:bg-slate-100'
                  }`}
                  title={t.description ?? ''}
                >
                  {t.templateKey}
                </button>
              </li>
            ))}
          </ul>
        )}
      </aside>

      {/* Right column: editor + preview */}
      <section className="space-y-4">
        {draft ? (
          <>
            <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <div className="mb-3 flex items-center justify-between">
                <div className="font-mono text-[13px] font-semibold text-slate-800">
                  {draft.templateKey}
                </div>
                {draft.updatedAt && (
                  <div className="text-[11px] text-slate-500">
                    Updated {new Date(draft.updatedAt).toLocaleString()}
                    {draft.updatedBy ? ` by ${draft.updatedBy}` : ''}
                  </div>
                )}
              </div>

              <label className="block text-[12px] font-semibold text-slate-700">
                Description
                <input
                  value={draft.description ?? ''}
                  onChange={(e) => setDraft({ ...draft, description: e.target.value })}
                  className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 text-[13px]"
                />
              </label>

              <label className="mt-3 flex items-start gap-2 text-[12px] font-semibold text-slate-700">
                <input
                  type="checkbox"
                  checked={draft.optOutAllowed}
                  onChange={(e) => setDraft({ ...draft, optOutAllowed: e.target.checked })}
                  className="mt-0.5 h-4 w-4 rounded border-slate-400"
                />
                <span>
                  Users can opt out
                  <span className="ml-1 font-normal text-slate-500">
                    — visible under /settings/notifications with an on/off toggle.
                    Keep OFF for transactional events (invite, verify, password reset).
                  </span>
                </span>
              </label>

              <label className="mt-3 block text-[12px] font-semibold text-slate-700">
                Subject template (Handlebars)
                <input
                  value={draft.subjectTemplate}
                  onChange={(e) => setDraft({ ...draft, subjectTemplate: e.target.value })}
                  className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-1.5 font-mono text-[12px]"
                />
              </label>

              <label className="mt-3 block text-[12px] font-semibold text-slate-700">
                Body template (Handlebars)
                <textarea
                  value={draft.bodyTemplate}
                  onChange={(e) => setDraft({ ...draft, bodyTemplate: e.target.value })}
                  rows={10}
                  className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 font-mono text-[12px]"
                />
              </label>

              <div className="mt-3 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => void remove(draft.templateKey)}
                  className="rounded border border-rose-300 px-3 py-1.5 text-[12px] text-rose-700 hover:bg-rose-50"
                >
                  Delete
                </button>
                <button
                  type="button"
                  onClick={() => void save()}
                  disabled={saving}
                  className="rounded bg-slate-900 px-3 py-1.5 text-[12px] font-semibold text-white hover:bg-slate-700 disabled:opacity-60"
                >
                  {saving ? 'Saving…' : 'Save'}
                </button>
              </div>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
              <div className="mb-3 flex items-center justify-between">
                <div className="text-[13px] font-semibold text-slate-800">Preview</div>
                <button
                  type="button"
                  onClick={() => void runPreview()}
                  disabled={previewing}
                  className="rounded border border-slate-300 px-3 py-1 text-[12px] hover:bg-slate-50 disabled:opacity-60"
                >
                  {previewing ? 'Rendering…' : 'Render preview'}
                </button>
              </div>

              <label className="block text-[12px] font-semibold text-slate-700">
                Variables (JSON)
                <textarea
                  value={varsJson}
                  onChange={(e) => setVarsJson(e.target.value)}
                  rows={5}
                  className={`mt-1 block w-full rounded-lg border px-3 py-2 font-mono text-[12px] ${
                    parsedVars === null
                      ? 'border-rose-400 bg-rose-50'
                      : 'border-slate-300'
                  }`}
                />
              </label>

              {preview && (
                <div className="mt-3 rounded border border-slate-200 bg-slate-50 p-3">
                  <div className="text-[11px] uppercase tracking-wide text-slate-500">Subject</div>
                  <div className="mb-3 font-semibold text-slate-800">{preview.subject}</div>
                  <div className="text-[11px] uppercase tracking-wide text-slate-500">Body</div>
                  <pre className="mt-1 whitespace-pre-wrap text-[13px] text-slate-800">{preview.body}</pre>
                </div>
              )}
            </div>
          </>
        ) : (
          <div className="rounded-xl border border-dashed border-slate-300 p-8 text-center text-[13px] text-slate-500">
            {loading ? 'Loading…' : 'Pick a template on the left to edit, or create a new one.'}
          </div>
        )}
      </section>

      {/* New-key modal (replaces window.prompt). */}
      <Modal
        open={newModalOpen}
        onClose={() => setNewModalOpen(false)}
        title="New notification template"
        subtitle="Key uses UPPERCASE.DOTTED convention (e.g. OPS.LOW_FUNDS)."
        size="md"
        footer={
          <ModalActions
            onCancel={() => setNewModalOpen(false)}
            onConfirm={confirmNewKey}
            confirmLabel="Create"
          />
        }
      >
        <label className="block text-[12px] font-semibold text-[#5a4526]">
          Template key
          <input
            autoFocus
            value={newKeyInput}
            onChange={(e) => setNewKeyInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') confirmNewKey()
            }}
            placeholder="DOMAIN.EVENT"
            className="mt-1 block w-full rounded-lg border border-[#e3d9c4] px-3 py-2 font-mono text-[13px] text-[#1f150c]"
          />
        </label>
        <p className="mt-2 text-[11.5px] text-[#5a4526]">
          After creating, fill in the subject + body templates on the right and click Save. Uppercase letters,
          digits, dots and underscores only.
        </p>
      </Modal>
    </div>
  )
}
