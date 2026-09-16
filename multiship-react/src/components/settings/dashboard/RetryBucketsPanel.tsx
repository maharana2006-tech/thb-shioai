import { useMemo } from 'react'
import { FiBarChart2 } from 'react-icons/fi'
import type {
  UspsRetryBucketEntry,
  UspsRetryBuckets,
} from '../../../api/uspsLabelQueueService'

/**
 * PR-F4 Agent-2 — Retry / Failure History panel.
 *
 * <p>Inline-SVG bar chart, one bar per hour bucket (24 by default).
 * Each bar has three vertically stacked segments:
 * <ul>
 *   <li>Attempts (blue) — total request count that hour.</li>
 *   <li>Retries (amber) — how many of those needed a retry.</li>
 *   <li>Failures (red) — how many never succeeded.</li>
 * </ul>
 *
 * <p>Bars share a common Y scale so the operator can compare hours
 * directly; the scale is the max {@code attempts} across all buckets
 * (retries/failures are always ≤ attempts). Zero-attempt hours render
 * as an empty column so the axis density stays honest.
 *
 * <p>No chart library — {@code package.json} doesn't ship one and this
 * chart is small enough that a hand-rolled SVG (a) stays lean, (b) has
 * zero SSR / hydration surface, (c) leaves us free to add tooltips
 * without wrestling with defaults.
 */
export interface RetryBucketsPanelProps {
  buckets: UspsRetryBuckets | null
  loading?: boolean
  error?: string | null
}

const CHART_WIDTH = 480
const CHART_HEIGHT = 140
const CHART_PADDING = { top: 8, right: 8, bottom: 20, left: 32 }
const BAR_GAP = 2

const COLOR_ATTEMPTS = '#6366f1' // indigo-500
const COLOR_RETRIES = '#f59e0b' // amber-500
const COLOR_FAILURES = '#ef4444' // red-500

export default function RetryBucketsPanel({
  buckets,
  loading = false,
  error = null,
}: RetryBucketsPanelProps) {
  // Memoise entries so the maxY memo below has a stable reference —
  // eslint's react-hooks/exhaustive-deps rule flags a logical-expr
  // dep (`buckets?.buckets ?? []`) as "may change on every render".
  const entries = useMemo<UspsRetryBucketEntry[]>(
    () => buckets?.buckets ?? [],
    [buckets],
  )
  const hoursLookback = buckets?.hoursLookback ?? 24

  const maxY = useMemo(() => {
    if (entries.length === 0) return 0
    return entries.reduce((acc, b) => Math.max(acc, b.attempts), 0)
  }, [entries])

  const hasAnyData = maxY > 0

  return (
    <section
      aria-label="USPS retry and failure history"
      data-testid="retry-buckets-panel"
      className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm"
    >
      <header className="mb-4 flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-amber-50 text-amber-600"
          >
            <FiBarChart2 className="h-4 w-4" />
          </span>
          <div>
            <h3 className="text-[13px] font-bold uppercase tracking-[0.12em] text-slate-500">
              Retry / Failure History
            </h3>
            <p className="text-[11.5px] text-slate-500">
              Per-hour breakdown, last {hoursLookback}h.
            </p>
          </div>
        </div>
        <Legend />
      </header>

      {loading && !buckets && !error ? (
        <div
          role="status"
          aria-live="polite"
          data-testid="retry-buckets-loading"
          className="animate-pulse space-y-2"
        >
          <div className="h-32 rounded-lg bg-slate-100" />
        </div>
      ) : error && !buckets ? (
        <div
          role="alert"
          data-testid="retry-buckets-error"
          className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-[12.5px] text-rose-700"
        >
          Couldn&apos;t load retry buckets: {error}
        </div>
      ) : entries.length === 0 || !hasAnyData ? (
        <p
          data-testid="retry-buckets-empty"
          className="rounded-lg border border-dashed border-slate-200 bg-slate-50 px-3 py-6 text-center text-[12.5px] text-slate-500"
        >
          No USPS activity in the last {hoursLookback}h.
        </p>
      ) : (
        <BarChart entries={entries} maxY={maxY} />
      )}
    </section>
  )
}

/** Compact legend rendered in the header right side. */
function Legend() {
  return (
    <ul className="flex flex-wrap items-center gap-3 text-[11px] text-slate-600">
      <LegendChip color={COLOR_ATTEMPTS} label="Attempts" />
      <LegendChip color={COLOR_RETRIES} label="Retries" />
      <LegendChip color={COLOR_FAILURES} label="Failures" />
    </ul>
  )
}

function LegendChip({ color, label }: { color: string; label: string }) {
  return (
    <li className="inline-flex items-center gap-1.5">
      <span
        aria-hidden="true"
        className="inline-block h-2.5 w-2.5 rounded-sm"
        style={{ backgroundColor: color }}
      />
      {label}
    </li>
  )
}

/**
 * Inline SVG bar chart. Bars share the same Y scale (max
 * {@code attempts} across all buckets) so hours are directly
 * comparable. Each bucket renders three stacked bars (drawn back-to-
 * front: attempts, then retries, then failures) so the tallest
 * (attempts) sits behind the smaller two. A transparent hover rect
 * per bucket carries the aria tooltip.
 */
function BarChart({
  entries,
  maxY,
}: {
  entries: UspsRetryBucketEntry[]
  maxY: number
}) {
  const innerWidth = CHART_WIDTH - CHART_PADDING.left - CHART_PADDING.right
  const innerHeight = CHART_HEIGHT - CHART_PADDING.top - CHART_PADDING.bottom
  const barWidth = Math.max(
    2,
    (innerWidth - BAR_GAP * (entries.length - 1)) / entries.length,
  )

  const yScale = (v: number): number =>
    maxY <= 0 ? 0 : Math.round((v / maxY) * innerHeight)

  return (
    <div className="relative w-full">
      <svg
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        role="img"
        aria-label={`Retry bucket bar chart with ${entries.length} hourly buckets`}
        data-testid="retry-buckets-chart"
        className="h-40 w-full"
      >
        {/* Axis baseline */}
        <line
          x1={CHART_PADDING.left}
          y1={CHART_PADDING.top + innerHeight}
          x2={CHART_PADDING.left + innerWidth}
          y2={CHART_PADDING.top + innerHeight}
          stroke="#e2e8f0"
          strokeWidth={1}
        />
        {/* Y-axis max label */}
        <text
          x={CHART_PADDING.left - 4}
          y={CHART_PADDING.top + 6}
          textAnchor="end"
          className="fill-slate-400"
          fontSize={9}
        >
          {maxY.toLocaleString()}
        </text>
        <text
          x={CHART_PADDING.left - 4}
          y={CHART_PADDING.top + innerHeight}
          textAnchor="end"
          className="fill-slate-400"
          fontSize={9}
        >
          0
        </text>

        {entries.map((bucket, idx) => {
          const x = CHART_PADDING.left + idx * (barWidth + BAR_GAP)
          const attemptsH = yScale(bucket.attempts)
          const retriesH = yScale(bucket.retries)
          const failuresH = yScale(bucket.failures)
          const baseY = CHART_PADDING.top + innerHeight
          const tooltip = tooltipText(bucket)
          return (
            <g
              key={`${bucket.hourStart}-${idx}`}
              data-testid={`retry-bucket-${idx}`}
            >
              {/* attempts (background) */}
              {attemptsH > 0 ? (
                <rect
                  x={x}
                  y={baseY - attemptsH}
                  width={barWidth}
                  height={attemptsH}
                  fill={COLOR_ATTEMPTS}
                  opacity={0.35}
                  rx={1}
                />
              ) : null}
              {/* retries (foreground) — narrower so attempts still peek */}
              {retriesH > 0 ? (
                <rect
                  x={x + barWidth * 0.15}
                  y={baseY - retriesH}
                  width={Math.max(1, barWidth * 0.7)}
                  height={retriesH}
                  fill={COLOR_RETRIES}
                  opacity={0.7}
                  rx={1}
                />
              ) : null}
              {/* failures (most-critical foreground) */}
              {failuresH > 0 ? (
                <rect
                  x={x + barWidth * 0.3}
                  y={baseY - failuresH}
                  width={Math.max(1, barWidth * 0.4)}
                  height={failuresH}
                  fill={COLOR_FAILURES}
                  rx={1}
                />
              ) : null}
              {/* full-height hover target for the native browser tooltip */}
              <rect
                x={x}
                y={CHART_PADDING.top}
                width={barWidth}
                height={innerHeight}
                fill="transparent"
              >
                <title>{tooltip}</title>
              </rect>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

/**
 * Build the browser-native {@code <title>} tooltip. Format:
 * "14:00 — attempts N · retries M · failures K".
 */
function tooltipText(bucket: UspsRetryBucketEntry): string {
  const label = formatHourLabel(bucket.hourStart)
  return `${label} — attempts ${bucket.attempts} · retries ${bucket.retries} · failures ${bucket.failures}`
}

/**
 * ISO datetime → "HH:MM" in the operator's local time zone. Falls back
 * to the raw ISO on parse failure so we never render "Invalid Date".
 */
function formatHourLabel(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  })
}
