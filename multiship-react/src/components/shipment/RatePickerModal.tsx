import { useEffect, useMemo, useState } from 'react'
import {
  FiAlertCircle,
  FiCheckCircle,
  FiRefreshCw,
  FiTruck,
  FiX,
} from 'react-icons/fi'
import {
  rateShopService,
  type CarrierRateStatus,
  type RateOption,
  type RateShopRequest,
} from '../../api/rateShopService'
import { notify } from '../../utils/notify'

/**
 * Compare rates across every configured carrier for one shipment. Fetches
 * from {@code POST /api/v1/rate-shop} on mount and renders:
 *   - Options grouped by carrier badge, sorted cheapest-first (backend
 *     already sorts; we render as received).
 *   - Cheapest option highlighted with a subtle emerald ring.
 *   - Per-carrier status footer explaining STUB / ERROR entries so a
 *     missing carrier isn't silently dropped.
 *
 * <p>Selection: the picker is a leaf component — the parent supplies a
 * {@code onSelect(option)} callback. Selection is local until the parent
 * confirms; escape / clicking outside closes without selecting.
 */
export interface RatePickerModalProps {
  request: RateShopRequest
  onClose: () => void
  onSelect?: (option: RateOption) => void
}

export default function RatePickerModal({ request, onClose, onSelect }: RatePickerModalProps) {
  const [options, setOptions] = useState<RateOption[]>([])
  const [carrierResults, setCarrierResults] = useState<CarrierRateStatus[]>([])
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      const response = await rateShopService.quote(request)
      setOptions(response.data?.options ?? [])
      setCarrierResults(response.data?.carrierResults ?? [])
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to load rates.'
      setError(msg)
      notify.error(msg)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- on-mount data fetch; load() drives loading/error/result state that only exists after the async call
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const cheapestKey = useMemo(() => (options.length ? optionKey(options[0]) : null), [options])

  const selected = useMemo(
    () => options.find((o) => optionKey(o) === selectedKey) ?? null,
    [options, selectedKey],
  )

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Compare shipping rates"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(760px,92vh)] w-full max-w-[720px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Compare rates
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Live carrier pricing
            </h3>
            <p className="mt-1 text-[11.5px] text-slate-500">
              Cheapest first · pick any option to use in the shipment.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => void load()}
              disabled={loading}
              aria-label="Refresh"
              title="Re-quote"
              className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50 disabled:opacity-40"
            >
              <FiRefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
            >
              <FiX className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading && options.length === 0 ? (
            <p className="py-10 text-center text-[12.5px] text-slate-500">
              Asking every carrier for a quote…
            </p>
          ) : error && options.length === 0 ? (
            <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-3 text-[12px] text-rose-700">
              <FiAlertCircle className="mr-1.5 inline h-3.5 w-3.5" />
              {error}
            </div>
          ) : options.length === 0 ? (
            <NoOptionsFallback carrierResults={carrierResults} />
          ) : (
            <ul className="space-y-2">
              {options.map((o, i) => {
                const key = optionKey(o)
                const isCheapest = key === cheapestKey
                const isSelected = key === selectedKey
                return (
                  <li key={i}>
                    <button
                      type="button"
                      onClick={() => setSelectedKey(key)}
                      className={`w-full rounded-2xl border p-3 text-left transition ${
                        isSelected
                          ? 'border-slate-950 bg-slate-50 ring-2 ring-slate-950'
                          : isCheapest
                            ? 'border-emerald-300 bg-emerald-50/40 hover:bg-emerald-50'
                            : 'border-slate-200 bg-white hover:bg-slate-50'
                      }`}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <div className="flex items-center gap-2">
                            <span
                              className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-[10.5px] font-bold uppercase tracking-[0.1em] text-slate-500"
                            >
                              {o.carrierCode}
                            </span>
                            {isCheapest && !isSelected ? (
                              <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em] text-emerald-700">
                                <FiCheckCircle className="h-2.5 w-2.5" />
                                Cheapest
                              </span>
                            ) : null}
                            {isSelected ? (
                              <span className="inline-flex items-center gap-1 rounded-full bg-slate-950 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-[0.14em] text-white">
                                Selected
                              </span>
                            ) : null}
                          </div>
                          <p className="mt-1.5 text-[13px] font-semibold text-slate-950">
                            {o.serviceName ?? o.serviceCode}
                          </p>
                          <p className="mt-0.5 flex items-center gap-1 text-[11px] text-slate-500">
                            <FiTruck className="h-2.5 w-2.5 text-slate-400" />
                            {formatTransit(o)}
                          </p>
                          {o.routingOutcome === 'REROUTE' ? (
                            <p className="mt-1 rounded-md bg-amber-50 px-1.5 py-0.5 text-[10.5px] font-semibold text-amber-800">
                              ⚠ Rule '{o.routingRuleName}' will reroute at label time
                              {o.routingTargetCarrier
                                ? ` → ${o.routingTargetCarrier}${o.routingTargetServiceCode ? ` · ${o.routingTargetServiceCode}` : ''}`
                                : ''}
                              {o.routingTargetWarehouseId
                                ? ` (warehouse #${o.routingTargetWarehouseId})`
                                : ''}
                            </p>
                          ) : null}
                          {o.routingOutcome === 'BLOCK' ? (
                            <p className="mt-1 rounded-md bg-rose-50 px-1.5 py-0.5 text-[10.5px] font-semibold text-rose-800">
                              ⛔ Rule '{o.routingRuleName}' will BLOCK: {o.routingBlockReason}
                            </p>
                          ) : null}
                        </div>
                        <div className="text-right">
                          <p className="text-[16px] font-semibold text-slate-950">
                            {formatPrice(o)}
                          </p>
                          <p className="mt-0.5 text-[10px] font-medium uppercase tracking-[0.14em] text-slate-400">
                            {o.currency}
                          </p>
                        </div>
                      </div>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}

          {carrierResults.length > 0 && options.length > 0 ? (
            <CarrierStatusFooter carrierResults={carrierResults} />
          ) : null}
        </div>

        {onSelect ? (
          <div className="flex items-center justify-end gap-2 border-t border-slate-100 px-5 py-3">
            <button
              type="button"
              onClick={onClose}
              className="inline-flex items-center rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              Cancel
            </button>
            <button
              type="button"
              disabled={!selected}
              onClick={() => {
                if (selected) {
                  onSelect(selected)
                  onClose()
                }
              }}
              className="inline-flex items-center rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40"
            >
              Use this rate
            </button>
          </div>
        ) : null}
      </div>
    </div>
  )
}

function optionKey(o: RateOption): string {
  return `${o.carrierCode}::${o.serviceCode}`
}

function formatPrice(o: RateOption): string {
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: o.currency || 'USD',
      minimumFractionDigits: 2,
    }).format(o.totalAmount)
  } catch {
    return `${o.currency} ${o.totalAmount.toFixed(2)}`
  }
}

function formatTransit(o: RateOption): string {
  if (o.transitDays != null) {
    return o.transitDays === 1 ? '1 business day' : `${o.transitDays} business days`
  }
  if (o.estimatedDelivery) {
    const d = new Date(o.estimatedDelivery)
    if (!isNaN(d.getTime())) {
      return `Arrives ${d.toLocaleDateString(undefined, { dateStyle: 'medium' })}`
    }
  }
  return 'Transit time not published'
}

function NoOptionsFallback({ carrierResults }: { carrierResults: CarrierRateStatus[] }) {
  const hasError = carrierResults.some((c) => c.source === 'ERROR')
  return (
    <div className="space-y-2">
      <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-3 text-[12px] text-amber-800">
        No rate options came back for this lane. See below for what each carrier
        reported.
      </div>
      {hasError ? null : (
        <p className="text-[11.5px] text-slate-500">
          Tip: make sure the shipment envelope has an origin + destination
          postal code and a weight so carriers can quote.
        </p>
      )}
      <CarrierStatusFooter carrierResults={carrierResults} />
    </div>
  )
}

function CarrierStatusFooter({ carrierResults }: { carrierResults: CarrierRateStatus[] }) {
  return (
    <div className="mt-4 rounded-xl border border-slate-100 bg-slate-50 px-3 py-2.5">
      <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-slate-400">
        Carrier responses
      </p>
      <ul className="mt-1.5 space-y-1">
        {carrierResults.map((c) => (
          <li key={c.carrierCode} className="flex items-center justify-between gap-2 text-[11.5px]">
            <span className="inline-flex items-center gap-1.5 font-mono uppercase tracking-[0.1em] text-slate-500">
              {c.carrierCode}
              {c.source === 'CACHE' ? (
                <span
                  title="Served from the rate cache — refresh to hit the carrier"
                  className="rounded-full bg-sky-100 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-[0.14em] text-sky-700"
                >
                  Cached
                </span>
              ) : null}
            </span>
            <span
              className={
                c.source === 'LIVE'
                  ? 'text-emerald-700'
                  : c.source === 'CACHE'
                    ? 'text-sky-700'
                    : c.source === 'ERROR'
                      ? 'text-rose-700'
                      : 'text-slate-500'
              }
            >
              {c.message}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
