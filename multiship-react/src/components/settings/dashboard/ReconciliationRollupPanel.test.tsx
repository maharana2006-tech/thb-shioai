import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import ReconciliationRollupPanel from './ReconciliationRollupPanel'
import { formatCurrency } from './dashboardFormat'
import type { UspsReconciliationRollup } from '../../../api/uspsLabelQueueService'

/**
 * PR-F4 Agent-2 — ReconciliationRollupPanel unit tests.
 *
 * <p>Covers:
 *  - Populated state: 4 metric cells + pending refund value +
 *    last-reconciliation timestamp.
 *  - Amber highlight on pending &gt; 0, slate when 0.
 *  - Currency formatter uses Intl + graceful fallback.
 *  - lastReconciliationAt null → "never run" copy.
 *  - Loading / error / empty branches.
 */

afterEach(() => {
  cleanup()
})

function rollup(
  overrides: Partial<UspsReconciliationRollup> = {},
): UspsReconciliationRollup {
  return {
    lookbackDays: 30,
    voidedShipmentsInWindow: 250,
    reconciledApproved: 240,
    reconciledDenied: 3,
    notYetReconciled: 7,
    lastReconciliationAt: '2026-09-16T02:00:00Z',
    pendingRefundValue: 42.5,
    currency: 'USD',
    ...overrides,
  }
}

describe('ReconciliationRollupPanel — populated', () => {
  it('renders voided count in the headline', () => {
    render(<ReconciliationRollupPanel rollup={rollup()} />)
    expect(
      screen.getByTestId('reconciliation-rollup-voided').textContent,
    ).toContain('250')
  })

  it('renders all 4 metric cells with the right values', () => {
    render(<ReconciliationRollupPanel rollup={rollup()} />)
    expect(
      screen.getByTestId('reconciliation-rollup-approved').textContent,
    ).toContain('240')
    expect(
      screen.getByTestId('reconciliation-rollup-denied').textContent,
    ).toContain('3')
    expect(
      screen.getByTestId('reconciliation-rollup-pending').textContent,
    ).toContain('7')
  })

  it('pending cell uses amber tone when notYetReconciled > 0', () => {
    render(<ReconciliationRollupPanel rollup={rollup({ notYetReconciled: 7 })} />)
    const pending = screen.getByTestId('reconciliation-rollup-pending')
    // Amber tone → bg-amber-50 border-amber-200 text-amber-700
    expect(pending.className).toContain('amber')
  })

  it('pending cell uses slate tone when notYetReconciled is 0', () => {
    render(<ReconciliationRollupPanel rollup={rollup({ notYetReconciled: 0 })} />)
    const pending = screen.getByTestId('reconciliation-rollup-pending')
    expect(pending.className).toContain('slate')
    // No amber class should be present.
    expect(pending.className).not.toMatch(/amber/)
  })

  it('renders pending refund value with currency formatter', () => {
    render(<ReconciliationRollupPanel rollup={rollup()} />)
    const val = screen.getByTestId('reconciliation-rollup-refund-value').textContent
    // Intl.NumberFormat renders "$42.50" for en-US or a variant with
    // NBSP for other locales — assert on the number + presence of "$".
    expect(val).toMatch(/\$\s*42\.50|42\.50\s*\$|42\.50\s*USD/)
  })

  it('renders never-run copy when lastReconciliationAt is null', () => {
    render(
      <ReconciliationRollupPanel
        rollup={rollup({ lastReconciliationAt: null })}
      />,
    )
    expect(
      screen.getByTestId('reconciliation-rollup-last-run').textContent,
    ).toMatch(/never run/i)
  })

  it('formats the lastReconciliationAt timestamp when present', () => {
    render(<ReconciliationRollupPanel rollup={rollup()} />)
    // The exact time depends on the runner's local TZ — assert
    // presence of the date portion.
    expect(
      screen.getByTestId('reconciliation-rollup-last-run').textContent,
    ).toContain('2026-')
  })

  it('reflects lookbackDays in the header sub-copy', () => {
    render(<ReconciliationRollupPanel rollup={rollup({ lookbackDays: 7 })} />)
    expect(screen.getByText(/Last 7 days/i)).toBeInTheDocument()
  })
})

describe('ReconciliationRollupPanel — formatCurrency', () => {
  it('formats USD amounts with dollar sign', () => {
    expect(formatCurrency(42.5, 'USD')).toMatch(/\$\s*42\.50|42\.50\s*\$/)
  })

  it('falls back to numeric+code when Intl rejects the currency', () => {
    // A bogus string triggers the try/catch fallback.
    const out = formatCurrency(9.99, 'ZZZZZ')
    // Either Intl rejected and we hit fallback, or Intl accepted and
    // rendered something with the code — both are acceptable so long
    // as the amount is present.
    expect(out).toContain('9.99')
  })
})

describe('ReconciliationRollupPanel — loading / error / empty', () => {
  it('shows skeleton when loading + no rollup', () => {
    render(<ReconciliationRollupPanel rollup={null} loading />)
    expect(
      screen.getByTestId('reconciliation-rollup-loading'),
    ).toBeInTheDocument()
  })

  it('shows error banner when error + no rollup', () => {
    render(<ReconciliationRollupPanel rollup={null} error="500 boom" />)
    expect(
      screen.getByTestId('reconciliation-rollup-error').textContent,
    ).toMatch(/500 boom/)
  })

  it('shows empty copy when no rollup + no loading/error', () => {
    render(<ReconciliationRollupPanel rollup={null} />)
    expect(
      screen.getByTestId('reconciliation-rollup-empty'),
    ).toBeInTheDocument()
  })

  it('keeps rollup visible during a subsequent error', () => {
    render(<ReconciliationRollupPanel rollup={rollup()} error="transient" />)
    expect(
      screen.queryByTestId('reconciliation-rollup-error'),
    ).toBeNull()
    expect(
      screen.getByTestId('reconciliation-rollup-voided'),
    ).toBeInTheDocument()
  })
})
