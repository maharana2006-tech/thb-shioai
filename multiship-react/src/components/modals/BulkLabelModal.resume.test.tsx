import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import BulkLabelModal from './BulkLabelModal'

/**
 * BUG-037 / BUG-038 — pluralisation + state-persistence coverage.
 *
 * The modal used to render "Generate 1 labels" and lose every piece
 * of its state on close. These tests pin the plural strings and the
 * localStorage resume path: reopening the modal with the same order
 * set hydrates the previous run's job via bulkLabelService.status.
 */

const submit = vi.fn()
const status = vi.fn()
vi.mock('../../api/bulkLabelService', () => ({
  bulkLabelService: {
    submit: (...a: unknown[]) => submit(...a),
    status: (...a: unknown[]) => status(...a),
    cancel: vi.fn(),
    download: vi.fn(),
  },
  parseFailureDetails: () => [],
}))

vi.mock('../../api/uspsLabelQueueService', () => ({
  uspsLabelQueueService: {
    getMetrics: vi.fn().mockResolvedValue({
      data: { depth: 0, processing: 0, estimatedWaitSeconds: 0, totalPerHourCap: 55, tenantCode: null },
    }),
    getMpsProgress: vi.fn(),
  },
}))

vi.mock('../../api/apiClient', () => ({
  isAbortError: (err: unknown) =>
    err != null && typeof err === 'object' && (err as { name?: string }).name === 'AbortError',
}))

vi.mock('../../hooks/useFocusTrap', () => ({ useFocusTrap: vi.fn() }))
vi.mock('../../hooks/useEventStream', () => ({ useEventStream: () => ({ status: 'closed' }) }))
vi.mock('../../hooks/useMpsProgress', () => ({
  useMpsProgress: () => ({ progress: null, loading: false, error: null, refresh: vi.fn() }),
}))

const notifyInfo = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
vi.mock('../../utils/notify', () => ({
  notify: {
    info: (...a: unknown[]) => notifyInfo(...a),
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
  },
}))

vi.mock('../orders/BulkLabelQueueBadge', () => ({ default: () => null }))

beforeEach(() => {
  window.localStorage.clear()
  submit.mockReset()
  status.mockReset()
})
afterEach(() => { cleanup(); vi.clearAllMocks() })

describe('BulkLabelModal · plural + resume (BUG-037 / BUG-038)', () => {
  it('singular header: "Generate 1 label", not "Generate 1 labels"', () => {
    render(<BulkLabelModal onClose={vi.fn()} orderNumbers={[900001]} />)
    expect(screen.getByRole('heading', { level: 3 }).textContent).toMatch(/Generate 1 label$/)
  })

  it('plural header: "Generate 7 labels"', () => {
    render(<BulkLabelModal onClose={vi.fn()} orderNumbers={[1, 2, 3, 4, 5, 6, 7]} />)
    expect(screen.getByRole('heading', { level: 3 }).textContent).toMatch(/Generate 7 labels$/)
  })

  it('resumes a prior run from localStorage when reopened with the same orders', async () => {
    // Signature is "sorted order #s joined with comma".
    window.localStorage.setItem(
      'bulk-label-modal:last-job-v1',
      JSON.stringify({ signature: '900001,900002', jobId: 42 }),
    )
    status.mockResolvedValueOnce({
      data: { id: 42, status: 'COMPLETED', successfulCount: 2, failedCount: 0, totalCount: 2, downloadable: true },
    })

    render(<BulkLabelModal onClose={vi.fn()} orderNumbers={[900002, 900001]} />)

    await waitFor(() => expect(status).toHaveBeenCalledWith(42))
    // After hydration the submit CTA is gone — the Progress / Download
    // branch takes over.
    expect(screen.queryByRole('button', { name: /Generate 2 labels/ })).toBeNull()
  })

  it('does NOT resume when the stored signature belongs to a different order set', async () => {
    window.localStorage.setItem(
      'bulk-label-modal:last-job-v1',
      JSON.stringify({ signature: '100,200', jobId: 99 }),
    )
    render(<BulkLabelModal onClose={vi.fn()} orderNumbers={[900001]} />)
    // No status() call because the signature mismatched.
    expect(status).not.toHaveBeenCalled()
  })
})
