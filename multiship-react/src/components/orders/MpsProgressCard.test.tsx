import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react'

/**
 * PR-F2 Agent-2 — {@code MpsProgressCard} unit tests.
 *
 * <p>Scope:
 *  - In-progress render → progress bar width + status counts + %.
 *  - 100% render → green complete badge + "Download all labels" button.
 *  - Non-zero FAILED count → dedicated failure pill.
 *  - CANCELLED count → dedicated cancel pill.
 *  - Hook returning {@code progress = null} → "No MPS progress found"
 *    empty-state fallback.
 *  - Hook error → red error banner.
 *  - Tracking-numbers expand + collapse + "Copy all" writes to the
 *    clipboard.
 *
 * <p>{@link useMpsProgress} is mocked at the module boundary so this
 * suite doesn't depend on the polling loop; the hook's own behavior
 * is covered by {@code useMpsProgress.test.ts}.
 */

const useMpsProgressMock = vi.fn()

vi.mock('../../hooks/useMpsProgress', () => ({
  useMpsProgress: (
    orderNo: number | null,
    opts?: { pollIntervalMs?: number; enabled?: boolean },
  ) => useMpsProgressMock(orderNo, opts),
}))

// Silence the toast side effects — the card fires notify.success on
// "Copy all"; we assert against the mock, not against the toast DOM.
const notifyMock = {
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  apiError: vi.fn(),
}
vi.mock('../../utils/notify', () => ({
  notify: notifyMock,
}))

// Import AFTER the mocks so the component picks them up.
async function loadCard() {
  const mod = await import('./MpsProgressCard')
  return mod.default
}

function baseProgress(overrides: Partial<{
  parentOrderNo: number
  totalPieces: number
  byStatus: Record<string, number>
  percentComplete: number
  estimatedCompletionAt: string | null
  startedAt: string | null
  trackingNumbers: string[]
}> = {}) {
  return {
    parentOrderNo: 12345,
    totalPieces: 1000,
    byStatus: { QUEUED: 595, PROCESSING: 5, DONE: 400 },
    percentComplete: 40.0,
    estimatedCompletionAt: '2026-09-17T14:32:00Z',
    startedAt: '2026-09-17T13:00:00Z',
    trackingNumbers: Array.from({ length: 20 }, (_, i) => `9400100000000000000${i.toString().padStart(2, '0')}`),
    ...overrides,
  }
}

beforeEach(() => {
  useMpsProgressMock.mockReset()
  notifyMock.success.mockReset()
  notifyMock.error.mockReset()
  notifyMock.info.mockReset()
  notifyMock.apiError.mockReset()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('MpsProgressCard — in-progress render', () => {
  it('renders progress bar, status pills, and % complete', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress(),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    const card = screen.getByTestId('mps-progress-card')
    expect(card).toBeTruthy()

    // Header includes the parent order number.
    expect(card.textContent).toContain('Order #12345')

    // Bar renders at ~40% width — jsdom keeps inline style intact.
    const fill = screen.getByTestId('mps-progress-bar-fill') as HTMLElement
    expect(fill.style.width).toBe('40%')

    // Percent + count text visible.
    expect(screen.getByTestId('mps-percent').textContent).toBe('40.0%')
    expect(card.textContent).toContain('400 of 1000')

    // Status counts appear in their pills.
    expect(card.textContent).toContain('595')  // Queued
    expect(card.textContent).toContain('Queued')
    expect(card.textContent).toContain('Processing')
    expect(card.textContent).toContain('Done')

    // FAILED and CANCELLED pills are hidden when count is 0.
    expect(screen.queryByTestId('mps-failed-pill')).toBeNull()
    expect(screen.queryByTestId('mps-cancelled-pill')).toBeNull()

    // No "Download all labels" button until complete.
    expect(screen.queryByTestId('mps-download-all')).toBeNull()
  })

  it('progressbar role exposes aria-valuenow', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({ percentComplete: 33.3 }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    const bar = screen.getByRole('progressbar')
    // Rounded from 33.3 → 33 (Math.round).
    expect(bar.getAttribute('aria-valuenow')).toBe('33')
    expect(bar.getAttribute('aria-valuemin')).toBe('0')
    expect(bar.getAttribute('aria-valuemax')).toBe('100')
  })
})

describe('MpsProgressCard — completion + download', () => {
  it('renders green complete pill and Download-all button at 100%', async () => {
    const onDownload = vi.fn()
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({
        percentComplete: 100,
        byStatus: { DONE: 1000 },
      }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} onDownloadAllLabels={onDownload} />)

    // Complete badge appears (text "Complete").
    const card = screen.getByTestId('mps-progress-card')
    expect(card.textContent).toContain('Complete')

    // ETA shows "Done" instead of a future timestamp.
    expect(screen.getByTestId('mps-eta').textContent).toBe('Done')

    // Bar fill flips to emerald.
    const fill = screen.getByTestId('mps-progress-bar-fill') as HTMLElement
    expect(fill.className).toContain('bg-emerald-500')

    // Download button is present + wired to the callback.
    const btn = screen.getByTestId('mps-download-all')
    fireEvent.click(btn)
    expect(onDownload).toHaveBeenCalledTimes(1)
  })

  it('hides the Download-all button when onDownloadAllLabels is omitted', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({ percentComplete: 100, byStatus: { DONE: 1000 } }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    // Even at 100% the button is opt-in — no callback means no button.
    expect(screen.queryByTestId('mps-download-all')).toBeNull()
  })
})

describe('MpsProgressCard — failure + cancel pills', () => {
  it('shows red FAILED pill when count > 0', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({
        byStatus: { QUEUED: 100, DONE: 890, FAILED: 10 },
        percentComplete: 89.0,
      }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    const failed = screen.getByTestId('mps-failed-pill')
    expect(failed.className).toContain('text-rose-700')
    expect(failed.textContent).toContain('10')
    expect(failed.textContent).toContain('Failed')
  })

  it('shows gray CANCELLED pill when count > 0', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({
        byStatus: { QUEUED: 100, DONE: 890, CANCELLED: 10 },
        percentComplete: 89.0,
      }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    const cancelled = screen.getByTestId('mps-cancelled-pill')
    expect(cancelled.className).toContain('text-slate-500')
    expect(cancelled.textContent).toContain('10')
    expect(cancelled.textContent).toContain('Cancelled')
  })
})

describe('MpsProgressCard — empty + error branches', () => {
  it('renders "No MPS progress found" when hook returns null progress', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: null,
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={98765} />)

    const empty = screen.getByTestId('mps-progress-card-empty')
    expect(empty.textContent).toContain('No MPS progress found')
    // Includes the specific order number so operators can double-check
    // they're looking at the right thing.
    expect(empty.textContent).toContain('98765')
  })

  it('renders "Checking USPS Direct queue…" while loading before the first response', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: null,
      loading: true,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    const empty = screen.getByTestId('mps-progress-card-empty')
    expect(empty.textContent).toContain('Checking USPS Direct queue…')
  })

  it('renders an error banner when the hook returns an error', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: null,
      loading: false,
      error: 'Internal Server Error',
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    const banner = screen.getByTestId('mps-progress-card-error')
    expect(banner.getAttribute('role')).toBe('alert')
    expect(banner.textContent).toContain('Internal Server Error')
  })

  it('renders nothing when orderNo is null', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: null,
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    const { container } = render(<Card orderNo={null} />)

    expect(container.firstChild).toBeNull()
    expect(screen.queryByTestId('mps-progress-card')).toBeNull()
    expect(screen.queryByTestId('mps-progress-card-empty')).toBeNull()
  })
})

describe('MpsProgressCard — tracking-numbers expand + copy', () => {
  it('reveals the list when the toggle is clicked and hides it again', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({
        trackingNumbers: ['9400100000000000000001', '9400100000000000000002'],
      }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    // Collapsed initially — list absent, toggle shows "Show".
    expect(screen.queryByTestId('mps-tracking-list')).toBeNull()
    const toggle = screen.getByTestId('mps-tracking-toggle')
    expect(toggle.textContent).toContain('Show')

    fireEvent.click(toggle)
    // Expanded — list present, toggle shows "Hide".
    const list = await waitFor(() => screen.getByTestId('mps-tracking-list'))
    expect(list.textContent).toContain('9400100000000000000001')
    expect(list.textContent).toContain('9400100000000000000002')
    expect(screen.getByTestId('mps-tracking-toggle').textContent).toContain('Hide')

    // Collapse again.
    fireEvent.click(screen.getByTestId('mps-tracking-toggle'))
    await waitFor(() => expect(screen.queryByTestId('mps-tracking-list')).toBeNull())
  })

  it('writes the joined list to the clipboard on "Copy all"', async () => {
    const trackingNumbers = ['TN-1', 'TN-2', 'TN-3']
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({ trackingNumbers }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    // Stub the clipboard API — jsdom exposes navigator.clipboard as
    // read-only, so we redefine the property.
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    fireEvent.click(screen.getByTestId('mps-tracking-toggle'))
    const copyBtn = await waitFor(() => screen.getByTestId('mps-tracking-copy'))
    fireEvent.click(copyBtn)

    await waitFor(() => expect(writeText).toHaveBeenCalledTimes(1))
    expect(writeText).toHaveBeenCalledWith('TN-1\nTN-2\nTN-3')
    await waitFor(() =>
      expect(notifyMock.success).toHaveBeenCalledWith('Copied 3 tracking numbers.'),
    )
  })

  it('shows an error toast when clipboard API is unavailable', async () => {
    useMpsProgressMock.mockReturnValue({
      progress: baseProgress({ trackingNumbers: ['X'] }),
      loading: false,
      error: null,
      refresh: vi.fn(),
    })

    // Redefine the clipboard slot as undefined to force the fallback branch.
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    })

    const Card = await loadCard()
    render(<Card orderNo={12345} />)

    fireEvent.click(screen.getByTestId('mps-tracking-toggle'))
    const copyBtn = await waitFor(() => screen.getByTestId('mps-tracking-copy'))
    fireEvent.click(copyBtn)

    await waitFor(() =>
      expect(notifyMock.error).toHaveBeenCalledWith(
        'Clipboard API is unavailable in this browser.',
      ),
    )
  })
})
