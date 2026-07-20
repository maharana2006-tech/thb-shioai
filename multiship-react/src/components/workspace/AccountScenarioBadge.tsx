import { FiAlertCircle, FiCheckCircle, FiEdit3, FiPackage, FiXCircle } from 'react-icons/fi'
import type { OrderAccountResolution } from '../../api/accountRefService'
import { formatCarrierName } from '../../utils/carrierUtils'

/**
 * Colored badge showing which carrier account an order will ship with:
 * green = client's own account, teal = saved reference, blue = company default,
 * amber = details needed, red = nothing available.
 */
export default function AccountScenarioBadge({
  resolution,
}: {
  resolution: OrderAccountResolution | undefined
}) {
  if (!resolution) {
    return (
      <span className="inline-flex items-center rounded-full bg-slate-100 px-2.5 py-1 text-[11px] font-semibold text-slate-400">
        Checking…
      </span>
    )
  }

  const carrier = formatCarrierName(resolution.carrierCode)

  switch (resolution.scenario) {
    case 'ORDER':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-100 px-2.5 py-1 text-[11px] font-semibold text-emerald-700">
          <FiCheckCircle className="h-3 w-3" />
          Client account • {carrier} {resolution.accountNumber}
        </span>
      )
    case 'REFERENCE':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-teal-100 px-2.5 py-1 text-[11px] font-semibold text-teal-700">
          <FiCheckCircle className="h-3 w-3" />
          Saved account • {resolution.accountName || `${carrier} ${resolution.accountNumber}`}
        </span>
      )
    case 'CLIENT_DEFAULT':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-sky-100 px-2.5 py-1 text-[11px] font-semibold text-sky-700">
          <FiCheckCircle className="h-3 w-3" />
          Client default • {resolution.accountName || `${carrier} ${resolution.accountNumber}`}
        </span>
      )
    case 'CHOOSE_ACCOUNT':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-sky-100 px-2.5 py-1 text-[11px] font-semibold text-sky-700">
          <FiEdit3 className="h-3 w-3" />
          Pick an account
        </span>
      )
    case 'CLIENT_MISSING':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-violet-100 px-2.5 py-1 text-[11px] font-semibold text-violet-700">
          <FiAlertCircle className="h-3 w-3" />
          Client not registered
        </span>
      )
    case 'CLIENT_INACTIVE':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-200 px-2.5 py-1 text-[11px] font-semibold text-slate-600">
          <FiXCircle className="h-3 w-3" />
          Client inactive
        </span>
      )
    case 'DEFAULT':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-sky-100 px-2.5 py-1 text-[11px] font-semibold text-sky-700">
          <FiPackage className="h-3 w-3" />
          Default • {resolution.accountName || `${carrier} ${resolution.accountNumber}`}
        </span>
      )
    case 'NEEDS_DETAILS':
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-amber-100 px-2.5 py-1 text-[11px] font-semibold text-amber-700">
          <FiEdit3 className="h-3 w-3" />
          Needs details{resolution.accountNumber ? ` • ${resolution.accountNumber}` : ''}
        </span>
      )
    case 'NO_DEFAULT':
    default:
      return (
        <span className="inline-flex items-center gap-1.5 rounded-full bg-rose-100 px-2.5 py-1 text-[11px] font-semibold text-rose-700">
          {resolution.scenario === 'NO_DEFAULT' ? <FiXCircle className="h-3 w-3" /> : <FiAlertCircle className="h-3 w-3" />}
          No default set
        </span>
      )
  }
}
