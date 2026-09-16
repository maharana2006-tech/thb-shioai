import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import RetryBucketsPanel from './RetryBucketsPanel'
import type { UspsRetryBuckets } from '../../../api/uspsLabelQueueService'

/**
 * PR-F4 Agent-2 — RetryBucketsPanel unit tests.
 *
 * <p>Covers:
 *  - SVG chart renders one bucket per entry, with a <title> tooltip.
 *  - Empty state when there are zero buckets OR every bucket is 0.
 *  - Loading skeleton before data.
 *  - Error banner branch.
 *  - Header echoes hoursLookback.
 */

afterEach(() => {
  cleanup()
})

function buckets(
  overrides: Partial<UspsRetryBuckets> = {},
): UspsRetryBuckets {
  return {
    hoursLookback: 24,
    buckets: [
      { hourStart: '2026-09-16T00:00:00Z', attempts: 10, retries: 2, failures: 1 },
      { hourStart: '2026-09-16T01:00:00Z', attempts: 20, retries: 4, failures: 0 },
      { hourStart: '2026-09-16T02:00:00Z', attempts: 5, retries: 0, failures: 3 },
    ],
    ...overrides,
  }
}

describe('RetryBucketsPanel — populated', () => {
  it('renders one bar group per bucket in the SVG', () => {
    render(<RetryBucketsPanel buckets={buckets()} />)
    const chart = screen.getByTestId('retry-buckets-chart')
    // One <g> per bucket with data-testid retry-bucket-i
    expect(chart.querySelectorAll('[data-testid^="retry-bucket-"]').length).toBe(3)
  })

  it('bucket <title> tooltip carries attempts/retries/failures counts', () => {
    render(<RetryBucketsPanel buckets={buckets()} />)
    const group = screen.getByTestId('retry-bucket-1')
    const title = group.querySelector('title')
    expect(title).not.toBeNull()
    expect(title?.textContent).toContain('attempts 20')
    expect(title?.textContent).toContain('retries 4')
    expect(title?.textContent).toContain('failures 0')
  })

  it('header echoes hoursLookback from the payload', () => {
    render(<RetryBucketsPanel buckets={buckets({ hoursLookback: 48 })} />)
    expect(screen.getByText(/last 48h/i)).toBeInTheDocument()
  })

  it('renders the compact legend (Attempts / Retries / Failures)', () => {
    render(<RetryBucketsPanel buckets={buckets()} />)
    expect(screen.getByText('Attempts')).toBeInTheDocument()
    expect(screen.getByText('Retries')).toBeInTheDocument()
    expect(screen.getByText('Failures')).toBeInTheDocument()
  })
})

describe('RetryBucketsPanel — empty / loading / error', () => {
  it('shows empty copy when there are no buckets', () => {
    render(<RetryBucketsPanel buckets={buckets({ buckets: [] })} />)
    expect(screen.getByTestId('retry-buckets-empty')).toBeInTheDocument()
    // Empty state still shows the lookback window in the copy.
    expect(
      screen.getByTestId('retry-buckets-empty').textContent,
    ).toMatch(/24h/i)
  })

  it('shows empty copy when every bucket has zero attempts', () => {
    render(
      <RetryBucketsPanel
        buckets={buckets({
          buckets: [
            { hourStart: '2026-09-16T00:00:00Z', attempts: 0, retries: 0, failures: 0 },
            { hourStart: '2026-09-16T01:00:00Z', attempts: 0, retries: 0, failures: 0 },
          ],
        })}
      />,
    )
    expect(screen.getByTestId('retry-buckets-empty')).toBeInTheDocument()
  })

  it('shows skeleton while loading + no data', () => {
    render(<RetryBucketsPanel buckets={null} loading />)
    expect(screen.getByTestId('retry-buckets-loading')).toBeInTheDocument()
  })

  it('shows error banner when error + no data', () => {
    render(<RetryBucketsPanel buckets={null} error="500 boom" />)
    expect(
      screen.getByTestId('retry-buckets-error').textContent,
    ).toMatch(/500 boom/)
  })

  it('shows null-metrics empty copy when no data / loading / error', () => {
    // With loading=false, error=null, buckets=null, we hit the entries
    // = [] branch → empty copy with default 24h lookback label.
    render(<RetryBucketsPanel buckets={null} />)
    expect(screen.getByTestId('retry-buckets-empty')).toBeInTheDocument()
  })

  it('keeps chart visible during a subsequent error', () => {
    render(<RetryBucketsPanel buckets={buckets()} error="transient" />)
    expect(screen.queryByTestId('retry-buckets-error')).toBeNull()
    expect(screen.getByTestId('retry-buckets-chart')).toBeInTheDocument()
  })
})
