import { useEffect, useState } from 'react'
import toast from 'react-hot-toast'
import { FiX } from 'react-icons/fi'
import { accountRefService, type CarrierAccountRef } from '../../api/accountRefService'
import { formatCarrierName } from '../../utils/carrierUtils'
import CarrierLogo from '../workspace/CarrierLogo'

interface AccountPickerModalProps {
  orderNo: number
  clientCode?: string | null
  /** The order's carrier — the client's accounts on it are shown first. */
  carrierCode?: string | null
  /** The client's per-carrier default account number, pre-highlighted. */
  suggestedAccountNumber?: string | null
  /** Called with the chosen account; the caller generates with it. */
  onPick: (account: CarrierAccountRef) => void
  onClose: () => void
}

/**
 * Manual account selection at generation time. When the order's client has
 * several accounts on the carrier, those are listed first (the client's
 * per-carrier default pre-highlighted), so the operator picks which one to
 * bill. Other usable accounts remain available below.
 */
export default function AccountPickerModal({
  orderNo,
  clientCode,
  carrierCode,
  suggestedAccountNumber,
  onPick,
  onClose,
}: AccountPickerModalProps) {
  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    accountRefService
      .listAccounts()
      .then((all) => {
        const code = clientCode?.toUpperCase()
        const carrier = carrierCode?.toUpperCase()
        // ONLY the order's client's own accounts, and ONLY on the order's
        // ship-via carrier. Platform accounts and other clients' accounts are
        // never offered — the platform (house) account is used in the
        // background by the server when the client has no account here.
        const own = all.filter(
          (account) =>
            account.complete &&
            account.active &&
            !!code &&
            account.customerNo?.toUpperCase() === code &&
            (!carrier || account.carrierCode?.toUpperCase() === carrier),
        )
        // The client's per-carrier default first.
        own.sort((a, b) => (b.clientDefault ? 1 : 0) - (a.clientDefault ? 1 : 0))
        setAccounts(own)
      })
      .catch((error) => {
        toast.error(error instanceof Error ? error.message : 'Failed to load carrier accounts.')
      })
      .finally(() => setLoading(false))
  }, [clientCode, carrierCode])

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/40 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label={`Choose a carrier account for order ${orderNo}`}
      onClick={onClose}
    >
      <div
        className="max-h-[85vh] w-full max-w-md overflow-y-auto rounded-2xl border border-slate-200 bg-white p-5 shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 pb-3">
          <div>
            <p className="text-[11px] font-semibold uppercase tracking-[0.16em] text-[#412d15]">Choose account</p>
            <h3 className="mt-1 text-base font-semibold text-[#1f150c]">
              Ship order #{orderNo}
              {carrierCode ? <span className="text-slate-400"> · {carrierCode}</span> : null} with…
            </h3>
            <p className="mt-1 text-xs leading-5 text-slate-500">
              {clientCode
                ? `${clientCode} has more than one ${carrierCode || 'carrier'} account — pick which one to bill.`
                : 'Pick the carrier account for this label.'}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
            aria-label="Close"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-4 space-y-2">
          {accounts.map((account) => {
            const isSuggested =
              !!suggestedAccountNumber && account.accountNumber?.toUpperCase() === suggestedAccountNumber.toUpperCase()

            return (
              <button
                key={account.id}
                type="button"
                onClick={() => onPick(account)}
                className={`flex w-full items-center gap-3 rounded-xl border px-3.5 py-3 text-left transition hover:border-[#412d15] hover:bg-[#412d15]/5 ${
                  isSuggested ? 'border-[#412d15]/50 bg-[#412d15]/[0.04] ring-1 ring-[#412d15]/20' : 'border-slate-200 bg-white'
                }`}
              >
                <CarrierLogo carrierId={account.carrierCode} size={22} className="rounded-sm" />
                <span className="min-w-0 flex-1">
                  <span className="flex items-center gap-1.5 truncate text-[13px] font-semibold text-[#1f150c]">
                    <span className="truncate">{account.accountName || formatCarrierName(account.carrierCode)}</span>
                    {account.clientDefault ? (
                      <span className="shrink-0 rounded bg-amber-100 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-amber-700">
                        ★ Default
                      </span>
                    ) : null}
                  </span>
                  <span className="block truncate text-[11.5px] text-slate-500">
                    {account.accountNumber} · {account.environment || 'SANDBOX'}
                  </span>
                </span>
              </button>
            )
          })}

          {!accounts.length ? (
            <p className="rounded-xl border border-dashed border-slate-200 px-3 py-8 text-center text-[12.5px] text-slate-500">
              {loading
                ? 'Loading accounts…'
                : `No ${clientCode || 'client'} ${carrierCode || 'carrier'} accounts — add one on the Carrier page.`}
            </p>
          ) : null}
        </div>
      </div>
    </div>
  )
}
