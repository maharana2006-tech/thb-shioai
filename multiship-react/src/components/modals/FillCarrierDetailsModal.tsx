import { useState, type ReactNode } from 'react'
import { notify } from '../../utils/notify'
import { FiX, FiZap } from 'react-icons/fi'
import { accountRefService } from '../../api/accountRefService'
import type { OrderAccountResolution } from '../../api/accountRefService'
import { carrierEnvironmentOptions, formatCarrierName, type CarrierEnvironment } from '../../utils/carrierUtils'
import CarrierLogo from '../workspace/CarrierLogo'

interface FillCarrierDetailsModalProps {
  orderNo: number
  resolution: OrderAccountResolution
  /** Called after the account is saved; the caller re-triggers generation. */
  onSaved: () => Promise<void> | void
  onClose: () => void
}

const carrierOptions = [
  { code: 'UPS', label: 'UPS' },
  { code: 'FEDEX', label: 'FedEx' },
  { code: 'USPS', label: 'USPS' },
]

const inputClassName =
  'w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-sky-600 focus:ring-4 focus:ring-sky-100'

function Field({ label, children, required }: { label: string; children: ReactNode; required?: boolean }) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">
        {label}
        {required ? <span className="ml-1 text-rose-500">*</span> : null}
      </span>
      {children}
    </label>
  )
}

/**
 * Scenario 2 fill-up form: the order carries partial carrier details, the user
 * completes them once, and the account is saved to the reference book so every
 * future order with this account number generates automatically.
 */
export default function FillCarrierDetailsModal({
  orderNo,
  resolution,
  onSaved,
  onClose,
}: FillCarrierDetailsModalProps) {
  const [accountNumber, setAccountNumber] = useState(resolution.accountNumber || '')
  const [carrierCode, setCarrierCode] = useState(resolution.carrierCode || 'UPS')
  const [accountName, setAccountName] = useState(resolution.accountName || '')
  const [clientId, setClientId] = useState(resolution.prefillClientId || '')
  const [clientSecret, setClientSecret] = useState('')
  const [environment, setEnvironment] = useState<CarrierEnvironment>(
    resolution.environment === 'PRODUCTION' ? 'PRODUCTION' : 'SANDBOX'
  )
  const [saving, setSaving] = useState(false)

  const missing = new Set(resolution.missingFields || [])

  const handleSaveAndGenerate = async () => {
    if (!accountNumber.trim() || !clientId.trim() || !clientSecret.trim()) {
      notify.error('Account number, client ID, and client secret are required.')
      return
    }

    setSaving(true)

    try {
      await accountRefService.upsertAccount({
        accountNumber: accountNumber.trim(),
        carrierCode,
        accountName: accountName.trim() || undefined,
        clientId: clientId.trim(),
        clientSecret: clientSecret.trim(),
        environment,
      })

      notify.success(`Account ${accountNumber.trim()} saved — future orders will use it automatically.`)
      onClose()
      await onSaved()
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to save the carrier account.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={`Complete carrier details for order ${orderNo}`}
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 pb-3">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-amber-600">
              Carrier details needed
            </p>
            <h3 className="mt-1 text-base font-semibold text-slate-950">
              Complete the account for order #{orderNo}
            </h3>
            <p className="mt-1 text-xs leading-5 text-slate-500">
              This order references account{' '}
              <span className="font-semibold text-slate-800">{resolution.accountNumber || '—'}</span> but its
              credentials aren't saved yet. Fill them once — they're stored in the account book for all future
              orders.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
            aria-label="Close"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-4 space-y-3">
          <div className="grid grid-cols-[auto_1fr] items-end gap-3">
            <div className="rounded-2xl border border-slate-200 bg-slate-50 p-2.5">
              <CarrierLogo carrierId={carrierCode} size={22} className="rounded-sm" />
            </div>
            <Field label="Carrier" required>
              <select
                value={carrierCode}
                onChange={(event) => setCarrierCode(event.target.value)}
                className={inputClassName}
              >
                {carrierOptions.map((option) => (
                  <option key={option.code} value={option.code}>
                    {option.label}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <Field label="Account number" required>
            <input
              value={accountNumber}
              onChange={(event) => setAccountNumber(event.target.value)}
              className={inputClassName}
              placeholder="Carrier account number"
              readOnly={Boolean(resolution.accountNumber)}
            />
          </Field>

          <Field label="Account name (optional)">
            <input
              value={accountName}
              onChange={(event) => setAccountName(event.target.value)}
              className={inputClassName}
              placeholder={`e.g. Client ${formatCarrierName(carrierCode)} account`}
            />
          </Field>

          <Field label="Client ID" required>
            <input
              value={clientId}
              onChange={(event) => setClientId(event.target.value)}
              className={`${inputClassName} ${missing.has('clientId') ? 'border-amber-300 bg-amber-50/50' : ''}`}
              placeholder="OAuth client id"
              autoComplete="off"
            />
          </Field>

          <Field label="Client Secret" required>
            <input
              type="password"
              value={clientSecret}
              onChange={(event) => setClientSecret(event.target.value)}
              className={`${inputClassName} ${missing.has('clientSecret') ? 'border-amber-300 bg-amber-50/50' : ''}`}
              placeholder="OAuth client secret"
              autoComplete="off"
            />
          </Field>

          <Field label="Environment">
            <select
              value={environment}
              onChange={(event) => setEnvironment(event.target.value as CarrierEnvironment)}
              className={inputClassName}
            >
              {carrierEnvironmentOptions.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </Field>

          <button
            type="button"
            onClick={() => {
              void handleSaveAndGenerate()
            }}
            disabled={saving}
            className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-[#1f150c] px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            <FiZap className="h-4 w-4" />
            {saving ? 'Saving…' : 'Save & Generate Label'}
          </button>
        </div>
      </div>
    </div>
  )
}
