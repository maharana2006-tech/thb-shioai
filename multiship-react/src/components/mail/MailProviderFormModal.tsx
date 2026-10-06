/**
 * Themed Add/Edit modal for /settings/mail providers. Wraps the same
 * fields the inline editor used to show, plus a Preset picker that
 * pre-fills host/port/tls/etc. for common services.
 *
 * Same component handles both flows:
 *   - Add: pass initial=null; kind picker + preset picker both live.
 *   - Edit: pass initial={row}; kind is locked (can't move a row's kind).
 */
import { useEffect, useMemo, useState } from 'react'
import Modal, { ModalActions } from '../common/Modal'
import { notify } from '../../utils/notify'
import type {
  MailKindDescriptor,
  MailProviderSummary,
} from '../../api/mailProviderService'
import { presetsFor } from './mailProviderPresets'

const REDACTED = '•••'

export interface MailProviderFormModalProps {
  open: boolean
  onClose: () => void
  /** null → Add flow; row → Edit flow. */
  initial: MailProviderSummary | null
  kinds: MailKindDescriptor[]
  onSubmit: (payload: {
    id?: number
    kind: string
    displayName: string
    config: Record<string, string>
  }) => Promise<void>
}

export default function MailProviderFormModal({
  open,
  onClose,
  initial,
  kinds,
  onSubmit,
}: MailProviderFormModalProps) {
  const isEdit = initial !== null
  const [kind, setKind] = useState<string>('')
  const [displayName, setDisplayName] = useState('')
  const [presetId, setPresetId] = useState<string>('custom')
  const [config, setConfig] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)

  // Reset the form each time the modal is opened.
  useEffect(() => {
    if (!open) return
    if (initial) {
      setKind(initial.kind)
      setDisplayName(initial.displayName)
      setConfig({ ...initial.config })
      setPresetId('custom')  // no way to know which preset the row came from
    } else {
      const firstKind = kinds[0]?.kind ?? ''
      setKind(firstKind)
      setDisplayName('')
      setConfig({})
      setPresetId('custom')
    }
    setSaving(false)
  }, [open, initial, kinds])

  const kindDescriptor = useMemo(
    () => kinds.find((k) => k.kind === kind) ?? null,
    [kinds, kind],
  )

  const presets = useMemo(() => presetsFor(kind), [kind])

  const applyPreset = (id: string) => {
    setPresetId(id)
    const p = presets.find((x) => x.id === id)
    if (!p) return
    // Overwrite keys the preset provides; preserve anything the user typed
    // that the preset doesn't set (e.g. username / from_address).
    setConfig((prev) => ({ ...prev, ...p.values }))
    // Suggest a display name if the user hasn't typed one.
    if (!displayName.trim() && p.id !== 'custom') {
      setDisplayName(p.label)
    }
  }

  const save = async () => {
    if (!kind || !displayName.trim()) {
      notify.error('Kind and display name are required.')
      return
    }
    // Never resend the redacted marker back to the server.
    const cleanConfig: Record<string, string> = {}
    for (const [k, v] of Object.entries(config)) {
      if (v === REDACTED) continue
      cleanConfig[k] = v
    }
    setSaving(true)
    try {
      await onSubmit({
        id: initial?.id,
        kind,
        displayName: displayName.trim(),
        config: cleanConfig,
      })
      onClose()
    } catch {
      // onSubmit surfaces its own toast; keep modal open so user can fix.
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={isEdit ? `Edit provider #${initial?.id}` : 'Add mail provider'}
      subtitle={
        isEdit
          ? 'Change display name or update config. Kind cannot be moved.'
          : 'Pick a preset to fill common defaults, then add your credentials.'
      }
      size="lg"
      footer={
        <ModalActions
          onCancel={onClose}
          onConfirm={() => void save()}
          confirmLabel={isEdit ? 'Save changes' : 'Add provider'}
          confirmLoading={saving}
        />
      }
    >
      <div className="space-y-4">
        {/* Kind + display name */}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="text-[12px] font-semibold text-[var(--e-5a4526)]">
            Kind
            <select
              value={kind}
              onChange={(e) => {
                setKind(e.target.value)
                setConfig({})  // kinds have different config schemas
                setPresetId('custom')
              }}
              disabled={isEdit}
              className="mt-1 block w-full rounded-lg border border-[var(--e-e3d9c4)] bg-white px-3 py-2 text-[13px] text-[var(--e-1f150c)] disabled:bg-[var(--e-faf7f0)]"
            >
              {kinds.length === 0 && <option value="">— no providers registered —</option>}
              {kinds.map((k) => (
                <option key={k.kind} value={k.kind}>
                  {k.kind}
                </option>
              ))}
            </select>
          </label>
          <label className="text-[12px] font-semibold text-[var(--e-5a4526)]">
            Display name
            <input
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              placeholder="e.g. Company Gmail"
              className="mt-1 block w-full rounded-lg border border-[var(--e-e3d9c4)] px-3 py-2 text-[13px] text-[var(--e-1f150c)]"
            />
          </label>
        </div>

        {/* Preset picker */}
        {presets.length > 1 && !isEdit && (
          <div>
            <label className="text-[12px] font-semibold text-[var(--e-5a4526)]">
              Preset
              <select
                value={presetId}
                onChange={(e) => applyPreset(e.target.value)}
                className="mt-1 block w-full rounded-lg border border-[var(--e-e3d9c4)] bg-white px-3 py-2 text-[13px] text-[var(--e-1f150c)]"
              >
                {presets.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </select>
            </label>
            {presets.find((p) => p.id === presetId)?.description && (
              <p className="mt-1 text-[11.5px] text-[var(--e-5a4526)]">
                {presets.find((p) => p.id === presetId)!.description}
              </p>
            )}
          </div>
        )}

        {/* Config inputs */}
        {kindDescriptor && (
          <fieldset className="space-y-2 rounded-xl border border-[var(--e-eee6d6)] bg-[var(--e-faf7f0)]/40 p-3">
            <legend className="px-1 text-[11px] font-semibold uppercase tracking-wide text-[var(--e-5a4526)]">
              Configuration
            </legend>
            {kindDescriptor.requiredKeys.map((key) => (
              <ConfigInput
                key={key}
                keyName={key}
                isSecret={false}
                value={config[key] ?? ''}
                onChange={(v) => setConfig({ ...config, [key]: v })}
              />
            ))}
            {kindDescriptor.secretKeys.map((key) => (
              <ConfigInput
                key={key}
                keyName={key}
                isSecret={true}
                value={config[key] ?? ''}
                onChange={(v) => setConfig({ ...config, [key]: v })}
              />
            ))}
          </fieldset>
        )}
      </div>
    </Modal>
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
      <span className="font-mono text-[var(--e-5a4526)]">
        {keyName}
        {isSecret && (
          <span className="ml-1 rounded bg-amber-100 px-1 text-[10px] text-amber-800">secret</span>
        )}
      </span>
      <input
        type={isSecret ? 'password' : 'text'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={isSecret && value === REDACTED ? 'unchanged — type to replace' : ''}
        className="rounded-lg border border-[var(--e-e3d9c4)] px-3 py-1.5 font-mono text-[12px] text-[var(--e-1f150c)]"
      />
    </label>
  )
}
