import { useEffect, useState } from 'react'
import toast from 'react-hot-toast'
import { FiX } from 'react-icons/fi'
import { accountRefService, type CarrierAccountRef } from '../../api/accountRefService'
import { formatCarrierName } from '../../utils/carrierUtils'
import CarrierLogo from '../workspace/CarrierLogo'

interface AccountPickerModalProps {
  orderNo: number
  clientCode?: string | null
  /** Called with the chosen account; the caller generates with it. */
  onPick: (account: CarrierAccountRef) => void
  onClose: () => void
}

/**
 * Manual account selection at generation time: lists every usable account
 * from the book (client-linked accounts first) and hands the chosen one
 * back to the caller.
 */
export default function AccountPickerModal({ orderNo, clientCode, onPick, onClose }: AccountPickerModalProps) {
  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    accountRefService
      .listAccounts()
      .then((all) => {
        const usable = all.filter((account) => account.complete && account.active)
        const code = clientCode?.toUpperCase()
        // The order's own client's accounts first, then the rest.
        usable.sort((a, b) => {
          const aOwn = a.customerNo?.toUpperCase() === code ? 0 : 1
          const bOwn = b.customerNo?.toUpperCase() === code ? 0 : 1
          return aOwn - bOwn
        })
        setAccounts(usable)
      })
      .catch((error) => {
        toast.error(error instanceof Error ? error.message : 'Failed to load carrier accounts.')
      })
      .finally(() => setLoading(false))
  }, [clientCode])

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
            <h3 className="mt-1 text-base font-semibold text-[#1f150c]">Ship order #{orderNo} with…</h3>
            <p className="mt-1 text-xs leading-5 text-slate-500">
              No account resolved automatically — pick the carrier account for this label.
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
            const isClientAccount = clientCode && account.customerNo?.toUpperCase() === clientCode.toUpperCase()

            return (
              <button
                key={account.id}
                type="button"
                onClick={() => onPick(account)}
                className="flex w-full items-center gap-3 rounded-xl border border-slate-200 bg-white px-3.5 py-3 text-left transition hover:border-[#412d15] hover:bg-[#412d15]/5"
              >
                <CarrierLogo carrierId={account.carrierCode} size={22} className="rounded-sm" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-semibold text-[#1f150c]">
                    {account.accountName || formatCarrierName(account.carrierCode)}
                  </span>
                  <span className="block truncate text-[11.5px] text-slate-500">
                    {account.accountNumber} · {account.environment || 'SANDBOX'}
                  </span>
                </span>
                {isClientAccount ? (
                  <span className="shrink-0 rounded-full bg-[#412d15]/10 px-2 py-0.5 text-[10.5px] font-semibold text-[#412d15]">
                    {clientCode}
                  </span>
                ) : account.customerNo ? (
                  <span className="shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-[10.5px] font-semibold text-slate-500">
                    {account.customerNo}
                  </span>
                ) : null}
              </button>
            )
          })}

          {!accounts.length ? (
            <p className="rounded-xl border border-dashed border-slate-200 px-3 py-8 text-center text-[12.5px] text-slate-500">
              {loading ? 'Loading accounts…' : 'No usable accounts in the book — add one on the Carrier page.'}
            </p>
          ) : null}
        </div>
      </div>
    </div>
  )
}
