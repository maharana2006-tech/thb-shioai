import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, cleanup, act } from '@testing-library/react'
import QuotaHeadroomPanel from './QuotaHeadroomPanel'
import { utilizationColor } from './dashboardFormat'
import type { UspsQuotaHeadroom } from '../../../api/uspsLabelQueueService'

/**
 * PR-F4 Agent-2 — QuotaHeadroomPanel unit tests.
 *
 * <p>Covers:
 *  - Populated state (bar width, aria-valuenow, remaining/cap, tone).
 *  - Traffic-light color boundaries (green &lt; 60, amber 60–85,
 *    red &gt; 85) via {@link utilizationColor}.
 *  - Countdown decrements each second under fake timers.
 *  - Loading / error / empty branches.
 */

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

function quota(overrides: Partial<UspsQuotaHeadroom> = {}): UspsQuotaHeadroom {
  return {
    hourlyCap: 55,
    remainingTokens: 42,
    utilizationPercent: 23.6,
    lastReplenishAt: '2026-09-16T15:30:00Z',
    nextReplenishInSeconds: 47,
    ...overrides,
  }
}

describe('QuotaHeadroomPanel — populated', () => {
  it('shows remainingTokens / hourlyCap headline', () => {
    render(<QuotaHeadroomPanel quota={quota()} />)
    expect(screen.getByTestId('quota-headroom-remaining').textContent).toBe(
      '42',
    )
    // "/ 55 tokens available"
    expect(
      screen.getByText(/\/ 55 tokens available/i),
    ).toBeInTheDocument()
  })

  it('bar reflects utilization percent + aria-valuenow', () => {
    render(<QuotaHeadroomPanel quota={quota({ utilizationPercent: 55.5 })} />)
    const bar = screen.getByTestId('quota-headroom-bar')
    // Bar is the wrapper — check its aria-valuenow.
    expect(bar.getAttribute('aria-valuenow')).toBe('56') // rounded
    // Text shows raw percent with 1 dp.
    expect(screen.getByTestId('quota-headroom-percent').textContent).toContain(
      '55.5%',
    )
  })

  it('renders "in Ns" countdown for sub-minute values', () => {
    render(<QuotaHeadroomPanel quota={quota({ nextReplenishInSeconds: 47 })} />)
    expect(
      screen.getByTestId('quota-headroom-next-replenish').textContent,
    ).toContain('in 47s')
  })

  it('renders "in Xm YYs" countdown for over-a-minute values', () => {
    render(
      <QuotaHeadroomPanel
        quota={quota({ nextReplenishInSeconds: 125 })}
      />,
    )
    expect(
      screen.getByTestId('quota-headroom-next-replenish').textContent,
    ).toMatch(/in 2m 05s/)
  })

  it('renders "imminent" for zero seconds left', () => {
    render(<QuotaHeadroomPanel quota={quota({ nextReplenishInSeconds: 0 })} />)
    expect(
      screen.getByTestId('quota-headroom-next-replenish').textContent,
    ).toMatch(/imminent/i)
  })
})

describe('QuotaHeadroomPanel — utilizationColor thresholds', () => {
  it('green under 60%', () => {
    expect(utilizationColor(0)).toBe('bg-emerald-500')
    expect(utilizationColor(59.9)).toBe('bg-emerald-500')
  })
  it('amber 60–85%', () => {
    expect(utilizationColor(60)).toBe('bg-amber-500')
    expect(utilizationColor(84.9)).toBe('bg-amber-500')
  })
  it('red at 85%+', () => {
    expect(utilizationColor(85)).toBe('bg-rose-500')
    expect(utilizationColor(99)).toBe('bg-rose-500')
  })
  it('NaN treated as green (safe default)', () => {
    expect(utilizationColor(Number.NaN)).toBe('bg-emerald-500')
  })
})

describe('QuotaHeadroomPanel — countdown', () => {
  it('decrements the countdown by 1 each second', async () => {
    vi.useFakeTimers()
    render(<QuotaHeadroomPanel quota={quota({ nextReplenishInSeconds: 5 })} />)

    // Initial render — 5s.
    expect(
      screen.getByTestId('quota-headroom-next-replenish').textContent,
    ).toContain('in 5s')

    // Tick 2 seconds.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000)
    })

    // Should be at 3s now (or lower — timers can fire slightly early
    // in jsdom). Assert monotonic decrement without pinning an exact
    // value.
    const txt = screen.getByTestId('quota-headroom-next-replenish').textContent
    expect(txt).toMatch(/in [1-3]s|imminent/i)
  })

  it('resets countdown when a fresh quota snapshot arrives', () => {
    const { rerender } = render(
      <QuotaHeadroomPanel quota={quota({ nextReplenishInSeconds: 5 })} />,
    )
    expect(
      screen.getByTestId('quota-headroom-next-replenish').textContent,
    ).toContain('in 5s')

    rerender(
      <QuotaHeadroomPanel quota={quota({ nextReplenishInSeconds: 40 })} />,
    )
    expect(
      screen.getByTestId('quota-headroom-next-replenish').textContent,
    ).toContain('in 40s')
  })
})

describe('QuotaHeadroomPanel — loading / error / empty', () => {
  it('shows skeleton when loading + no quota', () => {
    render(<QuotaHeadroomPanel quota={null} loading />)
    expect(screen.getByTestId('quota-headroom-loading')).toBeInTheDocument()
  })

  it('shows error banner when error + no quota', () => {
    render(<QuotaHeadroomPanel quota={null} error="500 boom" />)
    const err = screen.getByTestId('quota-headroom-error')
    expect(err.textContent).toMatch(/500 boom/)
  })

  it('shows empty copy when no quota + no loading/error', () => {
    render(<QuotaHeadroomPanel quota={null} />)
    expect(screen.getByTestId('quota-headroom-empty')).toBeInTheDocument()
  })

  it('keeps last snapshot visible during a subsequent error', () => {
    render(<QuotaHeadroomPanel quota={quota()} error="transient" />)
    // Error branch is at the "no quota" fork — with a snapshot, we
    // still show the numbers.
    expect(screen.queryByTestId('quota-headroom-error')).toBeNull()
    expect(
      screen.getByTestId('quota-headroom-remaining').textContent,
    ).toBe('42')
  })
})
