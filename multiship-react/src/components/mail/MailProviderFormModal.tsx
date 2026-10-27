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
import { serverFieldError } from '../../utils/serverFieldErrors'
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
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const clearErr = (k: string) => setFieldErrors((cur) => (cur[k] ? {} : cur))
  const fieldErr = (k: string) => fieldErrors[k]
    ? <p role="alert" className="mt-1 text-[11.5px] font-normal text-rose-700">{fieldErrors[k]}</p>
    : null

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
    setFieldErrors({})
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
      notify.invalid('Kind and display name are required.')
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
    } catch (e) {
      // onSubmit surfaces its own toast; keep modal open so user can fix.
      const fe = serverFieldError(e)
      if (fe) {
        const key = fe.field.replace(/^config\./, '')
        setFieldErrors({ [key === 'kind' || key === 'displayName' || key in config ? key : '_form']: fe.message })
      }
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
        {fieldErr('_form')}
        {fieldErr('kind')}
        {/* Kind + display name */}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="text-[12px] font-semibold text-[#5a4526]">
            Kind
            <select
              value={kind}
              onChange={(e) => {
                setKind(e.target.value)
                setConfig({})  // kinds have different config schemas
                setPresetId('custom')
                setFieldErrors({})
              }}
              disabled={isEdit}
              className="mt-1 block w-full rounded-lg border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] text-[#1f150c] disabled:bg-[#faf7f0]"
            >
              {kinds.length === 0 && <option value="">— no providers registered —</option>}
              {kinds.map((k) => (
                <option key={k.kind} value={k.kind}>
                  {k.kind}
                </option>
              ))}
            </select>
          </label>
          <label className="text-[12px] font-semibold text-[#5a4526]">
            Display name
            <input
              value={displayName}
              onChange={(e) => { setDisplayName(e.target.value); clearErr('displayName') }}
              placeholder="e.g. Company Gmail"
              aria-invalid={fieldErrors.displayName ? true : undefined}
              className="mt-1 block w-full rounded-lg border border-[#e3d9c4] px-3 py-2 text-[13px] text-[#1f150c]"
            />
            {fieldErr('displayName')}
          </label>
        </div>

        {/* Preset picker */}
        {presets.length > 1 && !isEdit && (
          <div>
            <label className="text-[12px] font-semibold text-[#5a4526]">
              Preset
              <select
                value={presetId}
                onChange={(e) => applyPreset(e.target.value)}
                className="mt-1 block w-full rounded-lg border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] text-[#1f150c]"
              >
                {presets.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </select>
            </label>
            {presets.find((p) => p.id === presetId)?.description && (
              <p className="mt-1 text-[11.5px] text-[#5a4526]">
                {presets.find((p) => p.id === presetId)!.description}
              </p>
            )}
          </div>
        )}

        {/* Config inputs */}
        {kindDescriptor && (
          <fieldset className="space-y-2 rounded-xl border border-[#eee6d6] bg-[#faf7f0]/40 p-3">
            <legend className="px-1 text-[11px] font-semibold uppercase tracking-wide text-[#5a4526]">
              Configuration
            </legend>
            {kindDescriptor.requiredKeys.map((key) => (
              <ConfigInput
                key={key}
                keyName={key}
                isSecret={false}
                value={config[key] ?? ''}
                error={fieldErrors[key]}
                onChange={(v) => { setConfig({ ...config, [key]: v }); clearErr(key) }}
              />
            ))}
            {kindDescriptor.secretKeys.map((key) => (
              <ConfigInput
                key={key}
                keyName={key}
                isSecret={true}
                value={config[key] ?? ''}
                error={fieldErrors[key]}
                onChange={(v) => { setConfig({ ...config, [key]: v }); clearErr(key) }}
              />
            ))}
          </fieldset>
        )}
      </div>
    </Modal>
  )
}

/** BUG-136 — plain-words labels for the backend setting keys. */
const CONFIG_LABELS: Record<string, string> = {
  from_address: 'From address', fromAddress: 'From address', serverToken: 'Server token', apiKey: 'API key',
  region: 'AWS region', accessKeyId: 'Access key ID', secretAccessKey: 'Secret access key',
  host: 'SMTP host', port: 'Port', username: 'Username', password: 'Password',
}
// eslint-disable-next-line react-refresh/only-export-components -- page copy helper, tested directly
export const configLabel = (key: string): string =>
  CONFIG_LABELS[key]
  ?? key.replace(/_/g, ' ').replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase().replace(/^\w/, (ch) => ch.toUpperCase())

function ConfigInput({
  keyName,
  isSecret,
  value,
  error,
  onChange,
}: {
  keyName: string
  isSecret: boolean
  value: string
  error?: string
  onChange: (v: string) => void
}) {
  return (
    <label className="grid grid-cols-[10rem_1fr] items-center gap-3 text-[12px]">
      <span className="text-[#5a4526]" title={keyName}>
        {configLabel(keyName)}
        {isSecret && (
          <span className="ml-1 rounded bg-amber-100 px-1 text-[10px] text-amber-800">secret</span>
        )}
      </span>
      {/* BUG-086 — Chrome ignores autoComplete="off" on password-type
          fields. autoComplete="new-password" plus a unique name attribute
          disables the heuristic that was pre-filling the admin's own
          credentials into the mail-provider fields. */}
      <input
        type={isSecret ? 'password' : 'text'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={isSecret && value === REDACTED ? 'unchanged — type to replace' : ''}
        name={`mail-provider-${keyName}`}
        autoComplete="new-password"
        data-lpignore="true"
        data-1p-ignore="true"
        aria-invalid={error ? true : undefined}
        className="rounded-lg border border-[#e3d9c4] px-3 py-1.5 font-mono text-[12px] text-[#1f150c]"
      />
      {error ? <p role="alert" className="col-start-2 text-[11.5px] text-rose-700">{error}</p> : null}
    </label>
  )
}
