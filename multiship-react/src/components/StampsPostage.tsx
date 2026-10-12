/**
 * USPS postage on Stamps.com: the balance pill on a USPS account row and the
 * "Add funds" dialog. Stamps.com charges the payment method saved on its own
 * account — this screen never asks for card details.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { FiAlertTriangle, FiCreditCard, FiExternalLink, FiMail, FiX } from 'react-icons/fi'
import { notify } from '../utils/notify'
import { ApiError } from '../api/apiClient'
import type { CarrierAccountRef } from '../api/accountRefService'
import {
  stampsPostageService,
  type PostageBalance,
  type PostageFunding,
  type StampsPage,
} from '../api/stampsPostageService'

const PRESETS = [25, 50, 100, 250]
/** Stamps.com's smallest purchase. */
const MIN_TOPUP = 10
/** Below this the pill turns amber. */
const LOW_BALANCE = 25

const usd = (v: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(v)
const errorText = (e: unknown) => (e instanceof Error && e.message ? e.message : 'Something went wrong. Try again.')

/** Opens a one-use Stamps.com link. The tab opens on the click itself so pop-up blockers allow it. */
async function openStampsPage(accountId: number, page: StampsPage) {
  const tab = window.open('', '_blank')
  try {
    const url = await stampsPostageService.pageUrl(accountId, page)
    if (tab) {
      tab.opener = null
      tab.location.href = url
    } else {
      window.open(url, '_blank', 'noopener,noreferrer')
    }
  } catch (e) {
    tab?.close()
    notify.apiError(e, "Couldn't open Stamps.com.")
  }
}

/** The balance on a USPS row; click it to add funds. Re-reads when `version` changes. */
export function StampsBalancePill({ accountId, version, onOpen }: {
  accountId: number
  version: number
  onOpen: () => void
}) {
  const [balance, setBalance] = useState<PostageBalance | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let gone = false
    stampsPostageService.balance(accountId)
      .then((b) => { if (!gone) { setBalance(b); setFailed(false) } })
      .catch(() => { if (!gone) setFailed(true) })
    return () => { gone = true }
  }, [accountId, version])

  const available = balance?.available ?? null
  const low = available !== null && available < LOW_BALANCE
  const amount = failed ? '—' : available === null ? '…' : usd(available)
  return (
    <button
      type="button"
      onClick={(e) => { e.stopPropagation(); onOpen() }}
      title={failed
        ? "Couldn't read the postage balance from Stamps.com. Click to try again or add funds."
        : `Postage balance${low ? ' is low' : ''}. Click to add funds.`}
      aria-label={`Postage balance ${amount}${low ? ', low' : ''}. Add funds`}
      className={`inline-flex shrink-0 items-center rounded-full px-2 py-0.5 transition ${
        low ? 'bg-amber-100 text-amber-800 hover:bg-amber-200' : 'bg-slate-100 text-slate-700 hover:bg-slate-200'}`}
    >
      {/* Text styles on the span: the app's global button rule outranks utility classes on <button>. */}
      <span className="inline-flex items-center gap-1 whitespace-nowrap text-[10px] font-bold leading-4">
        {low
          ? <FiAlertTriangle className="h-3 w-3" aria-hidden="true" />
          : <FiMail className="h-3 w-3" aria-hidden="true" />}
        {amount}
      </span>
    </button>
  )
}

/** Add postage to a USPS account on Stamps.com, with its recent top-ups. */
export function StampsAddFundsDialog({ account, onClose }: {
  account: CarrierAccountRef
  onClose: (changed: boolean) => void
}) {
  const [balance, setBalance] = useState<PostageBalance | null>(null)
  const [balanceError, setBalanceError] = useState<string | null>(null)
  const [history, setHistory] = useState<PostageFunding[]>([])
  const [preset, setPreset] = useState<number | null>(50)
  const [custom, setCustom] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** One key per purchase: a retry of the same amount reuses it, so Stamps.com charges once. */
  const requestKey = useRef<string | null>(null)
  const changed = useRef(false)

  const loadHistory = useCallback(() => {
    stampsPostageService.history(account.id).then(setHistory).catch(() => setHistory([]))
  }, [account.id])

  useEffect(() => {
    stampsPostageService.balance(account.id)
      .then((b) => { setBalance(b); setBalanceError(null) })
      .catch((e) => setBalanceError(errorText(e)))
    loadHistory()
  }, [account.id, loadHistory])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) onClose(changed.current) }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, onClose])

  const available = balance?.available ?? null
  const room = balance?.maxBalance != null && available !== null ? balance.maxBalance - available : null
  const amount = custom.trim() ? Number(custom) : preset
  const problem = amount === null || Number.isNaN(amount) || amount <= 0
    ? 'Pick or enter an amount.'
    : amount < MIN_TOPUP
      ? `Add at least ${usd(MIN_TOPUP)}.`
      : Math.round(amount * 100) !== amount * 100
        ? 'Enter dollars and cents, like 25.50.'
        : room !== null && amount > room
          ? `That would go over Stamps.com's ${usd(balance?.maxBalance ?? 0)} balance limit. You can add up to ${usd(room)}.`
          : null

  const pickPreset = (v: number) => { setPreset(v); setCustom(''); setError(null); requestKey.current = null }
  const typeCustom = (v: string) => { setCustom(v); setPreset(null); setError(null); requestKey.current = null }

  const submit = async () => {
    if (problem || amount === null || busy) {
      setError(problem)
      return
    }
    requestKey.current ??= crypto.randomUUID()
    setBusy(true)
    setError(null)
    try {
      const res = await stampsPostageService.addFunds(account.id, amount, requestKey.current)
      changed.current = true
      requestKey.current = null
      // Clear the amount so a second click can't buy the same thing again by accident.
      setPreset(null)
      setCustom('')
      const next = res.balance ?? (await stampsPostageService.balance(account.id).catch(() => null))
      if (next) setBalance(next)
      notify.success(next?.available != null
        ? `Added ${usd(amount)}. New balance ${usd(next.available)}.`
        : `Added ${usd(amount)}.`)
    } catch (e) {
      // Rotate the (spent) key only when the charge definitely did NOT happen:
      // a client-side rejection (4xx), or a carrier rejection the backend tagged
      // CARRIER_FAILURE (Stamps responded "no"). A bare 5xx / network error is an
      // unknown outcome — keep the key so the retry is idempotent against a
      // charge that may still be in flight (avoids a double top-up).
      if (e instanceof ApiError && (e.status < 500 || e.errorCode === 'CARRIER_FAILURE')) {
        requestKey.current = null
      }
      setError(errorText(e))
    } finally {
      setBusy(false)
      loadHistory()
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="stamps-add-funds-title"
      onClick={() => { if (!busy) onClose(changed.current) }}
    >
      <div
        className="max-h-[90vh] w-full max-w-md overflow-y-auto rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-3 flex items-start justify-between gap-3">
          <div>
            <h3 id="stamps-add-funds-title" className="text-[15px] font-semibold text-slate-950">Add postage funds</h3>
            <p className="text-[12px] text-slate-500">
              USPS · {account.accountName || account.accountNumber} ({account.accountNumber})
              {account.environment === 'SANDBOX' ? ' · Sandbox (play money)' : ''}
            </p>
          </div>
          <button type="button" aria-label="Close" disabled={busy} onClick={() => onClose(changed.current)}
            className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600">
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="mb-4 grid grid-cols-2 gap-2">
          <div className="rounded-xl bg-slate-50 px-3 py-2">
            <p className="text-[11px] text-slate-500">Current balance</p>
            <p className="text-[18px] font-semibold text-slate-950">{available !== null ? usd(available) : balanceError ? '—' : '…'}</p>
          </div>
          <div className="rounded-xl bg-slate-50 px-3 py-2">
            <p className="text-[11px] text-slate-500">Can add up to</p>
            <p className="text-[18px] font-semibold text-slate-950">{room !== null ? usd(Math.max(room, 0)) : '—'}</p>
          </div>
        </div>
        {balanceError ? <p className="mb-3 text-[12px] text-amber-700">{balanceError}</p> : null}

        <p className="mb-1.5 text-[12px] font-semibold text-slate-700" id="stamps-amount-label">Amount</p>
        <div role="radiogroup" aria-labelledby="stamps-amount-label" className="mb-2 grid grid-cols-4 gap-1.5">
          {PRESETS.map((v) => {
            const on = preset === v && !custom.trim()
            return (
              <button key={v} type="button" role="radio" aria-checked={on} onClick={() => pickPreset(v)}
                autoFocus={v === 50}
                className={`rounded-lg border py-2 text-[13px] transition ${on
                  ? 'border-[#1f150c] bg-[#1f150c] font-semibold text-white'
                  : 'border-slate-200 text-slate-700 hover:bg-slate-50'}`}>
                {usd(v).replace('.00', '')}
              </button>
            )
          })}
        </div>
        <input
          type="number" inputMode="decimal" min={MIN_TOPUP} step="0.01" value={custom}
          onChange={(e) => typeCustom(e.target.value)}
          placeholder={`Other amount (${usd(MIN_TOPUP)} minimum)`}
          aria-label="Other amount"
          className="mb-1 h-9 w-full rounded-lg border border-slate-200 px-3 text-[13px] outline-none focus:border-[#412d15]"
        />
        <p className="mb-3 min-h-[18px] text-[12px] text-rose-700" aria-live="polite">{error ?? (custom.trim() ? problem : null)}</p>

        <div className="mb-4 rounded-xl bg-slate-50 px-3 py-2.5 text-[13px]">
          <div className="flex justify-between"><span className="text-slate-500">You'll be charged</span>
            <span className="font-semibold">{amount && !problem ? usd(amount) : '—'}</span></div>
          <div className="flex justify-between"><span className="text-slate-500">New balance</span>
            <span className="font-semibold">{amount && !problem && available !== null ? usd(available + amount) : '—'}</span></div>
          <p className="mt-1.5 flex items-center gap-1.5 text-[11px] text-slate-500">
            <FiCreditCard className="h-3 w-3" aria-hidden="true" />
            Charged to the default payment method on your Stamps.com account.
          </p>
        </div>

        <div className="mb-4 flex justify-end gap-2">
          <button type="button" disabled={busy} onClick={() => onClose(changed.current)}
            className="rounded-lg border border-slate-200 px-3 py-1.5 text-[13px] text-slate-700 hover:bg-slate-50">Cancel</button>
          <button type="button" onClick={() => void submit()} aria-busy={busy}
            className="rounded-lg bg-[#1f150c] px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-[#412d15] disabled:opacity-60"
            disabled={busy}>
            {busy ? 'Adding…' : amount && !problem ? `Add ${usd(amount)}` : 'Add funds'}
          </button>
        </div>

        <div className="border-t border-slate-100 pt-3">
          <p className="mb-1.5 text-[12px] font-semibold text-slate-700">Recent top-ups</p>
          {history.length === 0 ? (
            <p className="text-[12px] text-slate-500">None yet.</p>
          ) : (
            <ul className="divide-y divide-slate-100 text-[12px]">
              {history.map((f) => (
                <li key={f.id} className="flex items-center justify-between gap-2 py-1.5">
                  <span className="text-slate-600">
                    {new Date(f.createdAt).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })}
                    {f.createdBy ? ` · ${f.createdBy}` : ''}
                  </span>
                  <span className="font-semibold text-slate-800">{usd(f.amount)}</span>
                  <span className={f.status === 'ADDED' ? 'text-emerald-700' : f.status === 'PENDING' ? 'text-amber-700' : 'text-rose-700'} title={f.message ?? undefined}>
                    {f.status === 'ADDED' ? 'Added' : f.status === 'PENDING' ? 'Pending' : 'Failed'}
                  </span>
                </li>
              ))}
            </ul>
          )}
          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[12px]">
            {([
              ['store_default_payment_methods', 'Payment settings on Stamps.com'],
              ['reports_balances', 'Full statement'],
            ] as const).map(([page, label]) => (
              <button key={page} type="button" onClick={() => void openStampsPage(account.id, page)}
                className="inline-flex items-center gap-1 font-semibold text-[#412d15] underline-offset-2 hover:underline">
                {label} <FiExternalLink className="h-3 w-3" aria-hidden="true" />
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
