import type { ShipmentRateQuote } from '../api/shipmentValidationService'

const money = (amount: number | null | undefined, currency: string | null | undefined) =>
  amount == null ? '—' : `${currency ?? ''} ${Number(amount).toFixed(2)}`.trim()

const formatDate = (iso: string) => {
  const d = new Date(`${iso}T00:00:00`)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
}

/**
 * The "what it costs and how long it takes" part of a Validate result:
 * price for the picked service (carrier cost and the client's price),
 * transit time, and billable weight.
 */
export default function ValidationQuote({ quote }: { quote: ShipmentRateQuote }) {
  const unit = (quote.weightUnit ?? 'LB').toLowerCase()
  const priced = quote.status === 'QUOTED'
  return (
    <div data-testid="validation-quote" className="mt-2 rounded-lg border border-[#e3d9c4] bg-white/70 px-3 py-2 text-[11.5px] text-slate-700">
      <p className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-600">
        Price &amp; delivery{quote.serviceName || quote.serviceCode ? ` · ${quote.serviceName || quote.serviceCode}` : ''}
      </p>
      <dl className="mt-1 grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 tabular-nums">
        {priced ? (
          <>
            <dt className="text-slate-500">Price</dt>
            <dd className="font-semibold text-slate-900">
              {money(quote.amount, quote.currency)}
              {quote.carrierAmount != null && quote.amount != null && Number(quote.carrierAmount) !== Number(quote.amount) ? (
                <span className="ml-1.5 font-normal text-slate-500">
                  (carrier {money(quote.carrierAmount, quote.currency)}{quote.markup ? ` + markup ${quote.markup.toLowerCase()}` : ''})
                </span>
              ) : null}
            </dd>
            <dt className="text-slate-500">Delivery</dt>
            <dd>
              {quote.transitDays != null ? `${quote.transitDays} business day${quote.transitDays === 1 ? '' : 's'}` : 'Transit time not given'}
              {quote.estimatedDelivery ? ` · by ${formatDate(quote.estimatedDelivery)}` : ''}
            </dd>
          </>
        ) : null}
        {quote.billableWeight != null ? (
          <>
            <dt className="text-slate-500">Billed weight</dt>
            <dd>
              {Number(quote.billableWeight)} {unit}
              {quote.dimensional ? (
                <span className="ml-1.5 text-amber-700">
                  — charged on box size, not the {Number(quote.actualWeight)} {unit} it weighs
                </span>
              ) : null}
            </dd>
          </>
        ) : null}
      </dl>
      {!priced && quote.message ? (
        <p className={`mt-1 ${quote.status === 'NOT_OFFERED' ? 'text-amber-800' : 'text-slate-500'}`}>{quote.message}</p>
      ) : null}
      {priced && quote.otherServices?.length ? (
        <details className="mt-1">
          <summary className="cursor-pointer text-slate-500 hover:text-slate-700">
            Other {quote.carrierCode ?? ''} services on this lane ({quote.otherServices.length})
          </summary>
          <ul className="mt-0.5 list-disc space-y-0.5 pl-5 text-slate-600">
            {quote.otherServices.map((s) => <li key={s}>{s}</li>)}
          </ul>
        </details>
      ) : null}
    </div>
  )
}
