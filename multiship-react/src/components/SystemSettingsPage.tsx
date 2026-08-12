import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import toast from 'react-hot-toast'
import { notify } from '../utils/notify'
import { FiCheck, FiLock, FiSave } from 'react-icons/fi'
import { systemSettingsService, type SystemSetting } from '../api/systemSettingsService'
import type { SettingsOutletContext } from './layout/SettingsLayout'

/**
 * Sprint 49 Tier 0 — admin surface for encrypted system secrets.
 *
 * <p>Renders each known setting from the backend registry (currently
 * just {@code openai.api-key}) with its masked preview and a plain
 * text input to rotate the value. Values are encrypted at rest via
 * AES-GCM; the input clears on save and only the masked value is ever
 * re-fetched.
 */
export default function SystemSettingsPage() {
  const [items, setItems] = useState<SystemSetting[]>([])
  const [loading, setLoading] = useState(true)
  const [inputs, setInputs] = useState<Record<string, string>>({})
  const [savingKey, setSavingKey] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await systemSettingsService.list()
      setItems(Array.isArray(res) ? res : [])
    } catch (e) {
      notify.apiError(e, 'Failed to load system settings.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const save = async (key: string) => {
    const value = (inputs[key] ?? '').trim()
    if (!value) {
      toast.error('Enter a value to save.')
      return
    }
    setSavingKey(key)
    try {
      await systemSettingsService.update(key, value)
      toast.success('Setting updated.')
      setInputs((prev) => ({ ...prev, [key]: '' }))
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to update the setting.')
    } finally {
      setSavingKey(null)
    }
  }

  return (
    <div className="space-y-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <FiLock className="h-4 w-4 text-slate-500" />
            System settings
          </h2>
          <p className="mt-1 text-[12.5px] text-slate-500">
            Admin-managed secrets stored encrypted at rest (AES-GCM). Values overlay the
            corresponding environment variables so you can rotate a key without a redeploy.
          </p>
        </div>
      </header>

      {loading ? (
        <div className="rounded-xl border border-slate-200 bg-white p-6 text-[13px] text-slate-500">
          Loading…
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-xl border border-slate-200 bg-white p-6 text-[13px] text-slate-500">
          No settings registered.
        </div>
      ) : (
        <div className="space-y-4">
          {items.map((item) => {
            const inputValue = inputs[item.key] ?? ''
            const isSaving = savingKey === item.key
            return (
              <section
                key={item.key}
                className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0">
                    <p className="font-mono text-[13px] font-semibold text-slate-900">
                      {item.key}
                    </p>
                    <p className="mt-0.5 text-[12px] text-slate-500">
                      {item.description}
                    </p>
                  </div>
                  <div className="flex shrink-0 items-center gap-2 text-[12px]">
                    {item.hasValue ? (
                      <>
                        <FiCheck className="h-3.5 w-3.5 text-emerald-600" />
                        <span className="font-mono text-slate-600">{item.maskedValue}</span>
                      </>
                    ) : (
                      <span className="text-slate-400">not set</span>
                    )}
                  </div>
                </div>

                <div className="mt-3 flex gap-2">
                  <input
                    type="password"
                    autoComplete="new-password"
                    placeholder={item.hasValue ? 'Replace stored value' : 'Enter new value'}
                    className="flex-1 rounded-lg border border-slate-300 bg-slate-50 px-3 py-1.5 text-[13px] outline-none focus:border-slate-500"
                    value={inputValue}
                    onChange={(e) =>
                      setInputs((prev) => ({ ...prev, [item.key]: e.target.value }))
                    }
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') void save(item.key)
                    }}
                  />
                  <button
                    type="button"
                    onClick={() => void save(item.key)}
                    disabled={isSaving || !inputValue.trim()}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-slate-900 bg-slate-900 px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {isSaving ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-500 border-t-white" />
                    ) : (
                      <FiSave className="h-3.5 w-3.5" />
                    )}
                    Save
                  </button>
                </div>
              </section>
            )
          })}
        </div>
      )}

      <p className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-[11.5px] text-amber-800">
        <strong>Deployment prerequisite:</strong> the backend needs the{' '}
        <code className="rounded bg-amber-100 px-1 py-0.5 font-mono text-[11px]">SECRETS_ENCRYPTION_KEY</code>{' '}
        environment variable set to a base64-encoded 32-byte random value. Without it, save
        will fail with a "not configured" error.
      </p>
    </div>
  )
}
