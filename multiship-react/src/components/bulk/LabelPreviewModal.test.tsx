import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const getLabelPdf = vi.fn()
vi.mock('../../api/orderService', () => ({ orderService: { getLabelPdf: (...a: unknown[]) => getLabelPdf(...a) } }))
vi.mock('../../utils/notify', () => ({ notify: { apiError: vi.fn() } }))
vi.mock('../../utils/printPdf', () => ({ printPdfBlob: vi.fn() }))

import LabelPreviewModal from './LabelPreviewModal'

beforeEach(() => {
  // jsdom has no object URLs; the API's PDF answers X-Frame-Options: DENY, so the
  // iframe must show a blob: URL, never the API path.
  URL.createObjectURL = vi.fn(() => 'blob:label-906976')
  URL.revokeObjectURL = vi.fn()
  getLabelPdf.mockResolvedValue(new Blob(['%PDF-1.6'], { type: 'application/pdf' }))
})
afterEach(cleanup)

describe('LabelPreviewModal', () => {
  it('shows the fetched PDF from an object URL and closes on Escape', async () => {
    const onClose = vi.fn()
    render(<LabelPreviewModal orderNo={906976} onClose={onClose} />)
    expect(getLabelPdf).toHaveBeenCalledWith(906976)
    const frame = await screen.findByTitle('Label for order 906976')
    expect(frame).toHaveAttribute('src', 'blob:label-906976')
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
  })

  it('says so when the label cannot be loaded', async () => {
    getLabelPdf.mockRejectedValue(new Error('Label PDF is unavailable (HTTP 404)'))
    render(<LabelPreviewModal orderNo={1} onClose={() => {}} />)
    await waitFor(() => expect(screen.getByText(/Label PDF is unavailable/)).toBeInTheDocument())
    expect(screen.queryByTitle('Label for order 1')).toBeNull()
  })
})
