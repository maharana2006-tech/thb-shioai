/**
 * F5-C — carrier address-validation result banner (EXACT / CORRECTED /
 * NOT_SUPPORTED / other). Extracted from the bottom of
 * NewShipmentPage. Refuses to apply cross-country suggestions the
 * carrier occasionally returns (e.g. FedEx sandbox handing back a
 * Chilean address for a US shipment).
 */
import { FiX } from 'react-icons/fi'
import type { AddressValidationResponse } from '../../api/addressValidationService'

export interface CarrierAddressBannerProps {
  result: AddressValidationResponse
  /** The country the user actually typed — used to reject cross-country suggestions. */
  requestCountry?: string | null
  onApply: () => void
  onDismiss: () => void
}

export function CarrierAddressBanner({
  result,
  requestCountry,
  onApply,
  onDismiss,
}: CarrierAddressBannerProps) {
  const level = result.matchLevel
  const palette =
    level === 'EXACT'
      ? { border: 'border-emerald-200', bg: 'bg-emerald-50', text: 'text-emerald-800' }
      : level === 'CORRECTED' || level === 'AMBIGUOUS'
        ? { border: 'border-amber-200', bg: 'bg-amber-50', text: 'text-amber-800' }
        : level === 'NOT_SUPPORTED'
          ? { border: 'border-slate-200', bg: 'bg-slate-50', text: 'text-slate-700' }
          : { border: 'border-rose-200', bg: 'bg-rose-50', text: 'text-rose-800' }
  // Guard against the carrier returning a nonsensical suggestion in a different
  // country (seen from the FedEx sandbox: a Chilean address for a US shipment).
  // If the suggested country doesn't match what the user typed, don't offer it.
  const rawSuggested = result.suggested
  const countryMismatch =
    !!rawSuggested?.countryCode &&
    !!requestCountry &&
    rawSuggested.countryCode.toUpperCase() !== requestCountry.toUpperCase()
  const s = countryMismatch ? null : rawSuggested
  return (
    <div className={`mt-3 rounded-xl border ${palette.border} ${palette.bg} px-3 py-2 text-[12px] ${palette.text}`}>
      <div className="flex items-start justify-between gap-2">
        <div>
          <p className="flex items-center gap-1.5 font-semibold">
            <span className="rounded-full bg-white/60 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em]">
              {result.carrierCode} · {level}
            </span>
            {result.classification && result.classification !== 'UNKNOWN' ? (
              <span className="rounded-full bg-white/60 px-2 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em]">
                {result.classification}
              </span>
            ) : null}
          </p>
          <p className="mt-1">{result.message}</p>
          {countryMismatch ? (
            <p className="mt-1 text-[11px] italic opacity-80">
              The carrier suggested an address in a different country ({rawSuggested?.countryCode}) — ignored. Please verify the address manually.
            </p>
          ) : null}
          {s ? (
            <div className="mt-2 rounded-lg bg-white/60 px-2.5 py-1.5 font-mono text-[10.5px]">
              <p>{s.addressLine1}</p>
              {s.addressLine2 ? <p>{s.addressLine2}</p> : null}
              {s.addressLine3 ? <p>{s.addressLine3}</p> : null}
              <p>
                {s.city}, {s.state} {s.postalCode} {s.countryCode}
              </p>
            </div>
          ) : null}
          {result.warnings && result.warnings.length > 0 ? (
            <ul className="mt-1 list-disc space-y-0.5 pl-4 text-[11px]">
              {result.warnings.map((w, i) => (
                <li key={i}>{w}</li>
              ))}
            </ul>
          ) : null}
        </div>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          className="shrink-0 rounded p-1 hover:bg-white/40"
        >
          <FiX className="h-3 w-3" />
        </button>
      </div>
      {s ? (
        <button
          type="button"
          onClick={onApply}
          className="mt-2 inline-flex items-center gap-1.5 rounded-lg border border-current bg-white/60 px-2.5 py-1 text-[11px] font-semibold hover:bg-white/80"
        >
          Apply suggested address
        </button>
      ) : null}
    </div>
  )
}
