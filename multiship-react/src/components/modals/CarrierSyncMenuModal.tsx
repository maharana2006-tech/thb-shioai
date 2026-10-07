import { useEffect, useMemo, useRef, useState } from 'react'
import { FiDownloadCloud, FiX } from 'react-icons/fi'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import { useModalDismiss } from '../../hooks/useModalDismiss'
import { accountRefService, type SyncEligibleAccount } from '../../api/accountRefService'
import { notify } from '../../utils/notify'
import { formatCarrierName } from '../../utils/carrierUtils'
import { countryName } from '../../utils/countries'

/**
 * Sync-menu modal for /settings/shipping-catalog.
 *
 * <p>Operator picks an environment (SANDBOX / PRODUCTION) first, then a
 * verified account (platform or client) narrowed to that env. Confirm
 * calls back with the chosen accountId — the parent invokes the sync
 * API with `accountId=...` so the backend routes the OAuth token to the
 * matching carrier host (F-MODE-1 fix). The env toggle is a
 * pre-filter guardrail; the account's stored env is the source of
 * truth server-side.
 *
 * <p>When zero verified accounts exist for the carrier we show a
 * "connect + verify first" empty state instead of the picker so the
 * operator isn't left staring at a disabled dropdown wondering why.
 */
export interface CarrierSyncMenuModalProps {
  carrier: string
  originCountry: string
  /** Modal title suffix — "services" or "packaging" depending on caller. */
  kind: 'services' | 'packaging'
  onClose: () => void
  onConfirm: (accountId: number, environment: string) => Promise<void> | void
}

type EnvChoice = 'SANDBOX' | 'PRODUCTION'

export default function CarrierSyncMenuModal({
  carrier,
  originCountry,
  kind,
  onClose,
  onConfirm,
}: CarrierSyncMenuModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  useModalDismiss(true, dialogRef, onClose)
  useFocusTrap(true, dialogRef)

  const [accounts, setAccounts] = useState<SyncEligibleAccount[]>([])
  const [loading, setLoading] = useState(true)
  const [env, setEnv] = useState<EnvChoice>('SANDBOX')
  const [pickedId, setPickedId] = useState<number | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    accountRefService
      .listSyncEligible(carrier)
      .then((rows) => {
        setAccounts(rows)
        // Default env → whichever bucket has an account (prefer SANDBOX
        // for safety). Prevents "select an env with no accounts" dead-end.
        const hasSandbox = rows.some((r) => normEnv(r.environment) === 'SANDBOX')
        const hasProd = rows.some((r) => normEnv(r.environment) === 'PRODUCTION')
        const initialEnv: EnvChoice = hasSandbox ? 'SANDBOX' : hasProd ? 'PRODUCTION' : 'SANDBOX'
        setEnv(initialEnv)
      })
      .catch((e) => {
        notify.apiError(e, `Failed to load ${carrier} accounts.`)
      })
      .finally(() => setLoading(false))
  }, [carrier])

  const filtered = useMemo(
    () => accounts.filter((a) => normEnv(a.environment) === env),
    [accounts, env],
  )

  /**
   * Effective picked account = the operator's manual pick when it's still
   * in the filtered list, else the first eligible account. Derived at
   * render time (not stored via useEffect) so switching env auto-picks
   * with a single click without triggering the react-hooks/set-state-in-
   * effect lint rule.
   */
  const effectivePickedId = useMemo<number | null>(() => {
    if (pickedId != null && filtered.some((a) => a.id === pickedId)) return pickedId
    return filtered[0]?.id ?? null
  }, [filtered, pickedId])

  const submit = async () => {
    if (effectivePickedId == null) return
    setSubmitting(true)
    try {
      await onConfirm(effectivePickedId, env)
      onClose()
    } catch (e) {
      notify.apiError(e, 'Sync failed.')
    } finally {
      setSubmitting(false)
    }
  }

  const label = kind === 'services' ? 'services' : 'packaging'
  const carrierName = formatCarrierName(carrier)

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 px-4">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="sync-menu-title"
        className="w-full max-w-md overflow-hidden rounded-xl bg-white shadow-xl"
      >
        <div className="flex items-center justify-between border-b border-[#eee6d6] bg-[#1f150c] px-4 py-3">
          <p id="sync-menu-title" className="flex items-center gap-2 text-[12px] font-bold uppercase tracking-[0.14em] text-[#e1dcc9]">
            <FiDownloadCloud className="h-3.5 w-3.5" />
            Sync {carrierName} {label}
          </p>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="rounded p-1 text-[#e1dcc9] transition hover:bg-white/10"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="space-y-4 px-5 py-4">
          <p className="text-[11.5px] leading-relaxed text-slate-600">
            Pulling {label} from <span className="font-mono font-semibold">{originCountry}</span> · {countryName(originCountry)}.
            Pick the environment and account whose credentials should authenticate the call.
          </p>

          {/* Environment toggle */}
          <div>
            <p className="mb-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-500">
              Environment
            </p>
            <div className="flex overflow-hidden rounded-lg border border-[#e3d9c4]">
              {(['SANDBOX', 'PRODUCTION'] as const).map((option) => (
                <button
                  key={option}
                  type="button"
                  onClick={() => setEnv(option)}
                  className={`flex-1 px-3 py-1.5 text-[11.5px] font-semibold transition ${
                    env === option
                      ? 'bg-[#1f150c] text-white'
                      : 'bg-white text-[#5a4526] hover:bg-[#faf7f0]'
                  }`}
                >
                  {option === 'SANDBOX' ? 'Sandbox' : 'Production'}
                </button>
              ))}
            </div>
          </div>

          {/* Account dropdown */}
          <div>
            <p className="mb-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-500">
              Account
            </p>
            {loading ? (
              <p className="text-[11.5px] text-slate-500">Loading accounts…</p>
            ) : filtered.length === 0 ? (
              <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-[11.5px] text-amber-900">
                No verified {env === 'SANDBOX' ? 'sandbox' : 'production'} {carrierName} accounts.{' '}
                {accounts.length > 0
                  ? `Switch to ${env === 'SANDBOX' ? 'Production' : 'Sandbox'} or verify a ${env.toLowerCase()} account in Settings › Carriers first.`
                  : 'Connect and verify a ' + carrierName + ' account in Settings › Carriers first.'}
              </div>
            ) : (
              <select
                value={effectivePickedId ?? ''}
                onChange={(e) => setPickedId(Number(e.target.value))}
                className="w-full rounded-lg border border-[#e3d9c4] bg-white px-3 py-2 text-[12px] font-medium text-[#412d15]"
              >
                {filtered.map((a) => (
                  <option key={a.id} value={a.id}>
                    {formatAccountOption(a)}
                  </option>
                ))}
              </select>
            )}
          </div>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-[#eee6d6] bg-[#faf7f0] px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="inline-flex items-center rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#412d15] hover:bg-[#faf7f0]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void submit()}
            disabled={effectivePickedId == null || submitting}
            className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:opacity-40"
          >
            <FiDownloadCloud className="h-3 w-3" />
            {submitting ? 'Syncing…' : 'Sync'}
          </button>
        </div>
      </div>
    </div>
  )
}

function normEnv(env: string | null | undefined): EnvChoice {
  return (env ?? '').toUpperCase() === 'PRODUCTION' ? 'PRODUCTION' : 'SANDBOX'
}

function formatAccountOption(a: SyncEligibleAccount): string {
  const scope = a.isPlatform ? 'Platform' : `Client ${a.customerNo ?? '?'}`
  const name = a.accountName ? ` · ${a.accountName}` : ''
  return `${scope} · ${a.accountNumber}${name}`
}
