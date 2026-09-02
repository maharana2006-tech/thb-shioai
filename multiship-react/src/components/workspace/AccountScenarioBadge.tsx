import { FiAlertCircle, FiCheckCircle, FiEdit3, FiPackage, FiXCircle } from 'react-icons/fi'
import type { ReactNode } from 'react'
import type { OrderAccountResolution } from '../../api/accountRefService'
import { formatCarrierName } from '../../utils/carrierUtils'

/** Single-line chip: icon stays fixed, label truncates with an ellipsis + hover title. */
function Chip({ tone, icon, label }: { tone: string; icon?: ReactNode; label: string }) {
  return (
    <span
      title={label}
      className={`inline-flex max-w-full items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-semibold ${tone}`}
    >
      {icon ? <span className="shrink-0">{icon}</span> : null}
      <span className="truncate">{label}</span>
    </span>
  )
}

/**
 * Colored chip showing which carrier account an order will ship with:
 * green = client's own account, teal = saved reference, blue = default,
 * amber = details needed, red = nothing available. Always one line — the
 * label truncates so table columns stay aligned.
 */
export default function AccountScenarioBadge({
  resolution,
}: {
  resolution: OrderAccountResolution | undefined
}) {
  if (!resolution) {
    return <Chip tone="bg-slate-100 text-slate-400" label="Checking…" />
  }

  const carrier = formatCarrierName(resolution.carrierCode)
  const named = resolution.accountName || `${carrier} ${resolution.accountNumber}`

  switch (resolution.scenario) {
    case 'GENERATED':
      // Sprint 51 — order already labelled; show the account it was billed on
      // (from the tracking row), not the "pick an account" cascade.
      return (
        <Chip
          tone="bg-emerald-100 text-emerald-700"
          icon={<FiCheckCircle className="h-3 w-3" />}
          label={`Billed • ${resolution.accountName || `${carrier} ${resolution.accountNumber}`}`}
        />
      )
    case 'ORDER':
      return (
        <Chip
          tone="bg-emerald-100 text-emerald-700"
          icon={<FiCheckCircle className="h-3 w-3" />}
          label={`Client account • ${carrier} ${resolution.accountNumber}`}
        />
      )
    case 'REFERENCE':
      return (
        <Chip
          tone="bg-teal-100 text-teal-700"
          icon={<FiCheckCircle className="h-3 w-3" />}
          label={`Saved account • ${named}`}
        />
      )
    case 'CLIENT_DEFAULT':
      return (
        <Chip
          tone="bg-sky-100 text-sky-700"
          icon={<FiCheckCircle className="h-3 w-3" />}
          label={`Client default • ${named}`}
        />
      )
    case 'FAILED':
      // The label attempt failed AT THE CARRIER — the account it was billed
      // to is a persisted fact from the tracking row, not a pending choice.
      return (
        <Chip
          tone="bg-rose-100 text-rose-700"
          icon={<FiAlertCircle className="h-3 w-3" />}
          label={`Attempted • ${named}`}
        />
      )
    case 'VOIDED':
      // Historically billed, then cancelled — a record of what happened,
      // never a shipment awaiting account setup.
      return (
        <Chip
          tone="bg-slate-200 text-slate-600"
          icon={<FiXCircle className="h-3 w-3" />}
          label={`Voided • ${named}`}
        />
      )
    case 'CHOOSE_ACCOUNT':
      // Descriptive, not imperative — this chip is a status readout, not a
      // button (the actionable "Choose Account" control lives in Actions).
      return (
        <Chip tone="bg-sky-100 text-sky-700" icon={<FiEdit3 className="h-3 w-3" />} label="No account chosen" />
      )
    case 'CLIENT_MISSING':
      return (
        <Chip
          tone="bg-violet-100 text-violet-700"
          icon={<FiAlertCircle className="h-3 w-3" />}
          label="Client not registered"
        />
      )
    case 'CLIENT_INACTIVE':
      return (
        <Chip tone="bg-slate-200 text-slate-600" icon={<FiXCircle className="h-3 w-3" />} label="Client inactive" />
      )
    case 'DEFAULT':
      return (
        <Chip tone="bg-sky-100 text-sky-700" icon={<FiPackage className="h-3 w-3" />} label={`Default • ${named}`} />
      )
    case 'NEEDS_DETAILS':
      return (
        <Chip
          tone="bg-amber-100 text-amber-700"
          icon={<FiEdit3 className="h-3 w-3" />}
          label={`Needs details${resolution.accountNumber ? ` • ${resolution.accountNumber}` : ''}`}
        />
      )
    case 'NO_DEFAULT':
      return <Chip tone="bg-rose-100 text-rose-700" icon={<FiXCircle className="h-3 w-3" />} label="No default set" />
    default:
      return (
        <Chip tone="bg-rose-100 text-rose-700" icon={<FiAlertCircle className="h-3 w-3" />} label="No default set" />
      )
  }
}
