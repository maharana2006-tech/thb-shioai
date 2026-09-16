import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import QueueDepthPanel from './QueueDepthPanel'
import type { UspsLabelQueueMetrics } from '../../../api/uspsLabelQueueService'

/**
 * PR-F4 Agent-2 — QueueDepthPanel unit tests.
 *
 * <p>Panel is a pure presenter — the page level owns the fetch — so
 * these tests just render with the four canonical states:
 *  - Populated (with + without per-tenant breakdown)
 *  - Loading skeleton
 *  - Error banner
 *  - Empty (null metrics without loading/error)
 *
 * <p>We also assert the tenant-sort keeps the top-5 desc by depth and
 * filters zero-depth tenants so the ranking doesn't include tenants
 * that just finished processing.
 */

afterEach(() => {
  cleanup()
})

function metrics(
  overrides: Partial<UspsLabelQueueMetrics> = {},
): UspsLabelQueueMetrics {
  return {
    depth: 42,
    processing: 5,
    estimatedWaitSeconds: 45 * 60,
    totalPerHourCap: 55,
    tenantCode: null,
    perTenantDepth: null,
    ...overrides,
  }
}

describe('QueueDepthPanel — populated', () => {
  it('renders depth, processing and wait time from the metrics', () => {
    render(<QueueDepthPanel metrics={metrics()} />)

    expect(screen.getByTestId('queue-depth-count').textContent).toContain('42')
    expect(screen.getByTestId('queue-depth-processing').textContent).toContain(
      '5',
    )
    // 45 min stays as minutes (mirrors formatQueueDuration).
    expect(screen.getByTestId('queue-depth-wait').textContent).toContain(
      '45 min',
    )
  })

  it('singularises the "item pending" label at depth=1', () => {
    render(<QueueDepthPanel metrics={metrics({ depth: 1 })} />)
    // "1" is in a <span>, "item pending" is in the following text
    // node — textContent collapses whitespace between them, so we
    // pattern-match with a flexible space and confirm the singular
    // form is used.
    const panel = screen.getByTestId('queue-depth-panel')
    expect(panel.textContent).toMatch(/1\s*item pending/)
    // And that it's NOT the plural form.
    expect(panel.textContent).not.toMatch(/1\s*items pending/)
  })

  it('pluralises the "items pending" label at depth=2', () => {
    render(<QueueDepthPanel metrics={metrics({ depth: 2 })} />)
    const panel = screen.getByTestId('queue-depth-panel')
    expect(panel.textContent).toMatch(/2\s*items pending/)
  })

  it('lists top tenants desc + caps at five entries', () => {
    render(
      <QueueDepthPanel
        metrics={metrics({
          perTenantDepth: {
            ACME: 30,
            GLOBEX: 8,
            INITECH: 3,
            OSCORP: 1,
            SOYLENT: 0, // filtered
            UMBRELLA: 20,
            WAYNE: 12,
            STARK: 4,
          },
        })}
      />,
    )

    const tenantList = screen.getByTestId('queue-depth-tenants')
    // Top-5 by depth (SOYLENT filtered because 0):
    // ACME 30 · UMBRELLA 20 · WAYNE 12 · GLOBEX 8 · STARK 4
    const rows = tenantList.querySelectorAll('li')
    expect(rows.length).toBe(5)
    const texts = Array.from(rows).map((r) => r.textContent ?? '')
    expect(texts[0]).toContain('ACME')
    expect(texts[0]).toContain('30')
    expect(texts[1]).toContain('UMBRELLA')
    expect(texts[4]).toContain('STARK')
    // SOYLENT (depth 0) never rendered.
    expect(tenantList.textContent).not.toContain('SOYLENT')
  })

  it('shows the per-tenant empty hint when backend omits perTenantDepth', () => {
    render(<QueueDepthPanel metrics={metrics({ perTenantDepth: null })} />)
    expect(
      screen.getByTestId('queue-depth-tenants-empty').textContent,
    ).toMatch(/Per-tenant breakdown unavailable/i)
  })
})

describe('QueueDepthPanel — loading state', () => {
  it('renders skeleton when loading + no metrics yet', () => {
    render(<QueueDepthPanel metrics={null} loading />)
    expect(screen.getByTestId('queue-depth-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('queue-depth-count')).toBeNull()
  })

  it('keeps existing metrics visible during a refresh (loading + snapshot)', () => {
    render(<QueueDepthPanel metrics={metrics()} loading />)
    // Skeleton NOT rendered when we already have data; the page's own
    // spinner in the toolbar signals the refresh.
    expect(screen.queryByTestId('queue-depth-loading')).toBeNull()
    expect(screen.getByTestId('queue-depth-count').textContent).toContain('42')
  })
})

describe('QueueDepthPanel — error state', () => {
  it('renders the error banner when error + no metrics yet', () => {
    render(<QueueDepthPanel metrics={null} error="server exploded" />)
    const err = screen.getByTestId('queue-depth-error')
    expect(err.textContent).toMatch(/server exploded/i)
  })

  it('prefers keeping the last snapshot over the error banner when both present', () => {
    render(<QueueDepthPanel metrics={metrics()} error="transient" />)
    // Data still on screen — the page's own stale-warning banner is
    // where the error surfaces at that point.
    expect(screen.queryByTestId('queue-depth-error')).toBeNull()
    expect(screen.getByTestId('queue-depth-count').textContent).toContain('42')
  })
})

describe('QueueDepthPanel — empty state', () => {
  it('renders "No queue metrics" copy when metrics is null + no error/loading', () => {
    render(<QueueDepthPanel metrics={null} />)
    expect(screen.getByTestId('queue-depth-empty')).toBeInTheDocument()
  })

  it('shows the depth=0 tenants empty hint copy', () => {
    render(
      <QueueDepthPanel
        metrics={metrics({ depth: 0, perTenantDepth: {} })}
      />,
    )
    expect(
      screen.getByTestId('queue-depth-tenants-empty').textContent,
    ).toMatch(/No pending requests/i)
  })
})
