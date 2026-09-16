import { FiCheckCircle, FiClock, FiRefreshCw, FiXCircle } from 'react-icons/fi'
import type { UspsReconciliationRollup } from '../../../api/uspsLabelQueueService'
import { formatCurrency } from './dashboardFormat'

/**
 * PR-F4 Agent-2 — Void Reconciliation panel.
 *
 * <p>Voided shipments are on the "cost recovery" side of USPS Direct:
 * every one is a refund USPS should credit back to us. This panel
 * gives the admin a single-glance view:
 * <ul>
 *   <li>How many shipments were voided in the lookback window.</li>
 *   <li>How many USPS reconciled (approved + denied), and how many
 *       remain unreconciled — highlighted amber when &gt; 0 so the
 *       operator notices the backlog.</li>
 *   <li>The still-in-flight refund value (sum of expected credits).</li>
 *   <li>When the reconciler last ran (null → "never" copy).</li>
 * </ul>
 *
 * <p>Currency formatting uses {@link formatCurrency} (which delegates
 * to {@code Intl.NumberFormat} with the DTO's {@code currency} field)
 * so multi-currency tenants don't see a hard-coded "$".
 */
export interface ReconciliationRollupPanelProps {
  rollup: UspsReconciliationRollup | null
  loading?: boolean
  error?: string | null
}

export default function ReconciliationRollupPanel({
  rollup,
  loading = false,
  error = null,
}: ReconciliationRollupPanelProps) {
  return (
    <section
      aria-label="USPS void reconciliation"
      data-testid="reconciliation-rollup-panel"
      className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
    >
      <header className="mb-4 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-sky-50 text-sky-600"
          >
            <FiRefreshCw className="h-4 w-4" />
          </span>
          <div>
            <h3 className="text-[13px] font-bold uppercase tracking-[0.12em] text-slate-500">
              Void Reconciliation
            </h3>
            <p className="text-[11.5px] text-slate-500">
              Last {rollup?.lookbackDays ?? 30} days · USPS-approved refunds
              vs. still-pending.
            </p>
          </div>
        </div>
      </header>

      {loading && !rollup && !error ? (
        <div
          role="status"
          aria-live="polite"
          data-testid="reconciliation-rollup-loading"
          className="animate-pulse space-y-3"
        >
          <div className="grid grid-cols-3 gap-3">
            <div className="h-16 rounded-lg bg-slate-100" />
            <div className="h-16 rounded-lg bg-slate-100" />
            <div className="h-16 rounded-lg bg-slate-100" />
          </div>
          <div className="h-10 rounded-md bg-slate-100" />
        </div>
      ) : error && !rollup ? (
        <div
          role="alert"
          data-testid="reconciliation-rollup-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-[12.5px] text-rose-700"
        >
          Couldn&apos;t load reconciliation data: {error}
        </div>
      ) : !rollup ? (
        <p
          data-testid="reconciliation-rollup-empty"
          className="text-[12.5px] text-slate-500"
        >
          No reconciliation data available.
        </p>
      ) : (
        <>
          <p
            data-testid="reconciliation-rollup-voided"
            className="text-[12.5px] text-slate-600"
          >
            <span className="font-bold tabular-nums text-slate-800">
              {rollup.voidedShipmentsInWindow.toLocaleString()}
            </span>{' '}
            shipment{rollup.voidedShipmentsInWindow === 1 ? '' : 's'} voided in
            window
          </p>

          <dl className="mt-3 grid grid-cols-3 gap-3">
            <MetricCell
              testId="reconciliation-rollup-approved"
              icon={
                <FiCheckCircle
                  className="h-3.5 w-3.5"
                  aria-hidden="true"
                />
              }
              tone="emerald"
              label="Approved"
              value={rollup.reconciledApproved}
            />
            <MetricCell
              testId="reconciliation-rollup-denied"
              icon={
                <FiXCircle className="h-3.5 w-3.5" aria-hidden="true" />
              }
              tone="rose"
              label="Denied"
              value={rollup.reconciledDenied}
            />
            <MetricCell
              testId="reconciliation-rollup-pending"
              icon={
                <FiClock className="h-3.5 w-3.5" aria-hidden="true" />
              }
              tone={rollup.notYetReconciled > 0 ? 'amber' : 'slate'}
              label="Pending"
              value={rollup.notYetReconciled}
            />
          </dl>

          <div className="mt-4 rounded-lg border border-slate-100 bg-slate-50 px-3 py-2">
            <p className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-slate-500">
              Pending refund value
            </p>
            <p
              data-testid="reconciliation-rollup-refund-value"
              className="mt-0.5 text-[18px] font-semibold tabular-nums text-slate-800"
            >
              {formatCurrency(rollup.pendingRefundValue, rollup.currency)}
            </p>
          </div>

          <p
            data-testid="reconciliation-rollup-last-run"
            className="mt-3 text-[11.5px] text-slate-500"
          >
            Last reconciliation:{' '}
            {rollup.lastReconciliationAt ? (
              <span
                className="font-medium text-slate-700"
                title={rollup.lastReconciliationAt}
              >
                {formatDateTime(rollup.lastReconciliationAt)}
              </span>
            ) : (
              <span className="italic text-slate-500">never run</span>
            )}
          </p>
        </>
      )}
    </section>
  )
}

type Tone = 'emerald' | 'rose' | 'amber' | 'slate'

const TONE_CLASSES: Record<Tone, { bg: string; border: string; text: string }> = {
  emerald: {
    bg: 'bg-emerald-50',
    border: 'border-emerald-200',
    text: 'text-emerald-700',
  },
  rose: {
    bg: 'bg-rose-50',
    border: 'border-rose-200',
    text: 'text-rose-700',
  },
  amber: {
    bg: 'bg-amber-50',
    border: 'border-amber-200',
    text: 'text-amber-700',
  },
  slate: {
    bg: 'bg-slate-50',
    border: 'border-slate-200',
    text: 'text-slate-700',
  },
}

function MetricCell({
  testId,
  icon,
  tone,
  label,
  value,
}: {
  testId: string
  icon: React.ReactNode
  tone: Tone
  label: string
  value: number
}) {
  const cls = TONE_CLASSES[tone]
  return (
    <div
      data-testid={testId}
      className={`rounded-lg border ${cls.border} ${cls.bg} px-3 py-2`}
    >
      <dt
        className={`flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.12em] ${cls.text}`}
      >
        {icon}
        {label}
      </dt>
      <dd className={`mt-0.5 text-[18px] font-semibold tabular-nums ${cls.text}`}>
        {value.toLocaleString()}
      </dd>
    </div>
  )
}

/**
 * ISO datetime → local "YYYY-MM-DD HH:MM". Falls back to the raw
 * ISO on parse failure so we never render "Invalid Date". Kept local
 * (not exported) so the file stays Fast-Refresh clean.
 */
function formatDateTime(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mi = String(d.getMinutes()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd} ${hh}:${mi}`
}
