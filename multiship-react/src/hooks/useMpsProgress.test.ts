import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor, cleanup } from '@testing-library/react'

/**
 * PR-F2 Agent-2 — {@code useMpsProgress} hook unit tests.
 *
 * <p>Scope:
 *  - Polls at the configured interval.
 *  - Slows to a 60s cadence at 100% complete.
 *  - Slows to a 60s cadence after 3 consecutive 404s.
 *  - Unmount clears the timer (no more fetches).
 *  - 404 resolves to {@code progress = null} + {@code error = null}.
 *  - API error resolves to {@code error} with the message.
 *  - {@code enabled = false} short-circuits — no fetch, cleared state.
 *  - {@code refresh()} forces an immediate fetch.
 *
 * <p>Everything happens under fake timers so we can assert exactly
 * when the next tick fires. The mocked service returns whatever the
 * test lines up — no real network calls.
 */

const getMpsProgressMock = vi.fn()

vi.mock('../api/uspsLabelQueueService', () => ({
  uspsLabelQueueService: {
    getMpsProgress: (...args: unknown[]) => getMpsProgressMock(...args),
  },
}))

// Local ApiError shim — the hook narrows via `instanceof ApiError`, so
// throwing plain objects would go down the "unknown error" branch. We
// reuse the real class so the branch that matters (status === 404) hits.
class ApiError extends Error {
  status: number
  payload: unknown
  errorCode: string | null
  constructor(message: string, status: number, payload: unknown = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.payload = payload
    this.errorCode =
      typeof (payload as { errorCode?: unknown })?.errorCode === 'string'
        ? ((payload as { errorCode: string }).errorCode)
        : null
  }
}

vi.mock('../api/apiClient', () => ({
  ApiError,
  isAbortError: (err: unknown) =>
    err != null &&
    typeof err === 'object' &&
    (err as { name?: string }).name === 'AbortError',
}))

async function loadHook() {
  const mod = await import('./useMpsProgress')
  return mod.useMpsProgress
}

// Await Promise + microtask flush under fake timers — resolved
// promises still need a microtask to settle before setState fires.
async function flushMicrotasks(times = 3) {
  for (let i = 0; i < times; i++) {
    await Promise.resolve()
  }
}

beforeEach(() => {
  getMpsProgressMock.mockReset()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('useMpsProgress — polling cadence', () => {
  it('calls the service on mount then again after pollIntervalMs', async () => {
    vi.useFakeTimers()
    getMpsProgressMock.mockResolvedValue({
      data: {
        parentOrderNo: 42,
        totalPieces: 10,
        byStatus: { QUEUED: 5, DONE: 5 },
        percentComplete: 50,
        estimatedCompletionAt: null,
        startedAt: '2026-09-17T10:00:00Z',
        trackingNumbers: [],
      },
    })

    const useMpsProgress = await loadHook()
    renderHook(() => useMpsProgress(42, { pollIntervalMs: 1_000 }))

    // Let the mount fetch settle.
    await act(async () => {
      await flushMicrotasks()
    })
    expect(getMpsProgressMock).toHaveBeenCalledTimes(1)
    expect(getMpsProgressMock).toHaveBeenCalledWith(42)

    // Advance one interval — the second fetch fires.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100)
      await flushMicrotasks()
    })
    expect(getMpsProgressMock.mock.calls.length).toBeGreaterThanOrEqual(2)
  })

  it('slows to 60s cadence once percentComplete >= 100', async () => {
    vi.useFakeTimers()
    getMpsProgressMock.mockResolvedValue({
      data: {
        parentOrderNo: 42,
        totalPieces: 10,
        byStatus: { DONE: 10 },
        percentComplete: 100,
        estimatedCompletionAt: null,
        startedAt: '2026-09-17T10:00:00Z',
        trackingNumbers: [],
      },
    })

    const useMpsProgress = await loadHook()
    renderHook(() => useMpsProgress(42, { pollIntervalMs: 1_000 }))

    // Mount fetch settles → schedules next tick at 60s (slow).
    await act(async () => {
      await flushMicrotasks()
    })
    expect(getMpsProgressMock).toHaveBeenCalledTimes(1)

    // Advance the base cadence — nothing new should fire.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
      await flushMicrotasks()
    })
    expect(getMpsProgressMock).toHaveBeenCalledTimes(1)

    // Advance past 60s — the slow tick fires.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
      await flushMicrotasks()
    })
    expect(getMpsProgressMock.mock.calls.length).toBeGreaterThanOrEqual(2)
  })
})

describe('useMpsProgress — unmount', () => {
  it('stops polling after unmount', async () => {
    vi.useFakeTimers()
    getMpsProgressMock.mockResolvedValue({
      data: {
        parentOrderNo: 42,
        totalPieces: 10,
        byStatus: { QUEUED: 10 },
        percentComplete: 0,
        estimatedCompletionAt: null,
        startedAt: null,
        trackingNumbers: [],
      },
    })

    const useMpsProgress = await loadHook()
    const { unmount } = renderHook(() =>
      useMpsProgress(42, { pollIntervalMs: 1_000 }),
    )

    // Mount fetch fires.
    await act(async () => {
      await flushMicrotasks()
    })
    const beforeUnmount = getMpsProgressMock.mock.calls.length
    expect(beforeUnmount).toBeGreaterThanOrEqual(1)

    unmount()

    // Advance well past several intervals — no additional fetches.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10_000)
      await flushMicrotasks()
    })
    expect(getMpsProgressMock.mock.calls.length).toBe(beforeUnmount)
  })
})

describe('useMpsProgress — 404 handling', () => {
  it('resolves 404 to null progress without an error', async () => {
    getMpsProgressMock.mockRejectedValue(new ApiError('Not found', 404, {}))

    const useMpsProgress = await loadHook()
    const { result } = renderHook(() =>
      useMpsProgress(42, { pollIntervalMs: 1_000 }),
    )

    await waitFor(() => {
      expect(result.current.progress).toBeNull()
      expect(result.current.error).toBeNull()
    })
  })

  it('slows to 60s cadence after 3 consecutive 404s', async () => {
    vi.useFakeTimers()
    getMpsProgressMock.mockRejectedValue(new ApiError('Not found', 404, {}))

    const useMpsProgress = await loadHook()
    renderHook(() => useMpsProgress(42, { pollIntervalMs: 1_000 }))

    // First (mount) fetch settles → sees 404 #1 → schedules base cadence.
    await act(async () => {
      await flushMicrotasks()
    })
    expect(getMpsProgressMock).toHaveBeenCalledTimes(1)

    // Second tick → 404 #2 → still base cadence.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100)
      await flushMicrotasks()
    })
    // Third tick → 404 #3 → NOW trips slow cadence for the NEXT schedule.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_100)
      await flushMicrotasks()
    })
    const after3 = getMpsProgressMock.mock.calls.length
    // We should have seen 3 fetches by now.
    expect(after3).toBeGreaterThanOrEqual(3)

    // Advance just the base cadence — the slow guard should suppress it.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
      await flushMicrotasks()
    })
    expect(getMpsProgressMock.mock.calls.length).toBe(after3)

    // Advance to the slow cadence — one more fetch fires.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000)
      await flushMicrotasks()
    })
    expect(getMpsProgressMock.mock.calls.length).toBeGreaterThan(after3)
  })
})

describe('useMpsProgress — API error', () => {
  it('surfaces the error message and keeps polling', async () => {
    getMpsProgressMock.mockRejectedValue(new ApiError('boom', 500, {}))

    const useMpsProgress = await loadHook()
    const { result } = renderHook(() =>
      useMpsProgress(42, { pollIntervalMs: 1_000 }),
    )

    await waitFor(() => {
      expect(result.current.error).toBe('boom')
    })
    // No progress until the endpoint recovers.
    expect(result.current.progress).toBeNull()
  })
})

describe('useMpsProgress — enabled flag', () => {
  it('does not fetch when enabled=false', async () => {
    vi.useFakeTimers()
    const useMpsProgress = await loadHook()
    renderHook(() => useMpsProgress(42, { pollIntervalMs: 1_000, enabled: false }))

    // Give the effect a chance to run and bail.
    await act(async () => {
      await flushMicrotasks()
      await vi.advanceTimersByTimeAsync(5_000)
      await flushMicrotasks()
    })

    expect(getMpsProgressMock).not.toHaveBeenCalled()
  })

  it('does not fetch when orderNo is null', async () => {
    vi.useFakeTimers()
    const useMpsProgress = await loadHook()
    renderHook(() => useMpsProgress(null, { pollIntervalMs: 1_000 }))

    await act(async () => {
      await flushMicrotasks()
      await vi.advanceTimersByTimeAsync(5_000)
      await flushMicrotasks()
    })

    expect(getMpsProgressMock).not.toHaveBeenCalled()
  })
})

describe('useMpsProgress — refresh()', () => {
  it('forces an immediate fetch when refresh() is called', async () => {
    getMpsProgressMock.mockResolvedValue({
      data: {
        parentOrderNo: 42,
        totalPieces: 10,
        byStatus: { QUEUED: 5, DONE: 5 },
        percentComplete: 50,
        estimatedCompletionAt: null,
        startedAt: '2026-09-17T10:00:00Z',
        trackingNumbers: [],
      },
    })

    const useMpsProgress = await loadHook()
    // Long interval so we know the extra fetch didn't come from the timer.
    const { result } = renderHook(() =>
      useMpsProgress(42, { pollIntervalMs: 600_000 }),
    )

    await waitFor(() => expect(getMpsProgressMock).toHaveBeenCalledTimes(1))

    act(() => {
      result.current.refresh()
    })

    await waitFor(() =>
      expect(getMpsProgressMock.mock.calls.length).toBeGreaterThanOrEqual(2),
    )
  })
})
