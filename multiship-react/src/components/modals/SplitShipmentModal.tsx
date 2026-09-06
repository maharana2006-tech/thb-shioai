import { useState } from 'react'
import type { SplitRequiredPayload, SplitStrategy } from '../../api/orderService'

/**
 * Commodity auto-split strategy picker. Opened when the manual-shipment
 * submit returns 422 `SPLIT_REQUIRED` because the shipment's commodity
 * count exceeds the carrier's cap. The operator picks a distribution
 * strategy; the parent re-submits the same payload with `splitStrategy`
 * populated to run the split pipeline.
 *
 * See docs/plans/commodity_autosplit.md for the full flow.
 */
export function SplitShipmentModal({
  payload,
  onPick,
  onCancel,
}: {
  payload: SplitRequiredPayload
  onPick: (strategy: SplitStrategy) => void
  onCancel: () => void
}) {
  const [pending, setPending] = useState<SplitStrategy | null>(null)

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="split-modal-title"
    >
      <div className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-2xl bg-white p-6 shadow-2xl">
        <h2
          id="split-modal-title"
          className="mb-1 text-lg font-bold text-slate-800"
        >
          Split shipment across multiple {payload.carrier} labels
        </h2>
        <p className="mb-5 text-sm text-slate-600">
          {payload.carrier} caps the paperless invoice at{' '}
          <strong>{payload.carrierCap}</strong> commodity lines per shipment,
          but this order has <strong>{payload.actualCommodityCount}</strong>.
          Splitting into <strong>{payload.requiredSplitCount}</strong> sub-shipments;
          pick how packages should be distributed.
        </p>

        <div className="space-y-3">
          {payload.strategies.map((s) => (
            <button
              key={s.code}
              type="button"
              disabled={pending !== null}
              onClick={() => {
                setPending(s.code)
                onPick(s.code)
              }}
              className={[
                'block w-full rounded-lg border p-4 text-left transition',
                pending === s.code
                  ? 'border-amber-500 bg-amber-50'
                  : 'border-slate-200 bg-white hover:border-amber-400 hover:bg-amber-50/60',
                pending !== null && pending !== s.code
                  ? 'opacity-50 cursor-not-allowed'
                  : '',
              ].join(' ')}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1">
                  <div className="font-semibold text-slate-800">{s.label}</div>
                  <div className="mt-1 text-sm text-slate-600">{s.note}</div>
                </div>
                <span className="shrink-0 rounded-full bg-amber-100 px-2.5 py-1 text-xs font-bold text-amber-800">
                  {s.trackingCount} tracking{s.trackingCount === 1 ? '' : 's'}
                </span>
              </div>
            </button>
          ))}
        </div>

        <div className="mt-6 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={pending !== null}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>
  )
}
