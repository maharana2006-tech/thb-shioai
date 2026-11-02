import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'

/**
 * BUG-126 — printers hidden by the search must not count as selected:
 * the action bar and its bulk buttons only see rows that are on screen.
 */

vi.mock('../api/printerService', async (orig) => {
  const actual = await orig<typeof import('../api/printerService')>()
  return {
    ...actual,
    printerService: {
      list: vi.fn().mockResolvedValue({ data: [
        { id: 1, name: 'Dock A', host: '192.0.2.10', port: 9100, connection: 'RAW_9100', format: 'ZPL', paper: 'LABEL_4X6', active: true, location: 'Dock' },
        { id: 2, name: 'Office B', host: '192.0.2.11', port: 9100, connection: 'RAW_9100', format: 'PDF', paper: 'A4', active: true, location: 'Office' },
      ] }),
      listAssignments: vi.fn().mockResolvedValue({ data: [] }),
      listDistinctPrinterTags: vi.fn().mockResolvedValue({ data: [] }),
      listPrinterTags: vi.fn().mockResolvedValue({ data: [] }),
      listAllPrinterTags: vi.fn().mockResolvedValue({ data: {} }),
    },
    notifyPrinterProblem: vi.fn(),
  }
})
vi.mock('../api/clientService', () => ({
  clientService: { listClients: vi.fn().mockResolvedValue({ data: { content: [], totalPages: 1 } }) },
}))
vi.mock('../utils/notify', () => ({
  notify: { confirm: vi.fn(), success: vi.fn(), error: vi.fn(), apiError: vi.fn(), info: vi.fn() },
}))
vi.mock('../api/apiClient', () => ({
  BASE_URL: '/api/v1',
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))
vi.mock('./AssignmentMatrix', () => ({ default: () => null }))
vi.mock('./InvoiceCopiesMatrix', () => ({ default: () => null }))
vi.mock('./PrinterScanPanel', () => ({ default: () => null }))
vi.mock('./PrinterDetailsPanel', () => ({ default: () => null }))

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
})
afterEach(() => { cleanup(); vi.restoreAllMocks() })

async function renderPage() {
  const Page = (await import('./PrintersPage')).default
  render(
    <MemoryRouter initialEntries={['/settings/printers?tab=printers']}>
      <Routes>
        <Route element={<Outlet context={{ registerRefresh: vi.fn() }} />}>
          <Route path="*" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
  await waitFor(() => expect(screen.getByLabelText('Select Dock A')).toBeInTheDocument())
}

describe('PrintersPage — selection follows the search (BUG-126)', () => {
  it('a printer hidden by the search leaves the count and comes back when the search clears', async () => {
    await renderPage()
    await userEvent.click(screen.getByLabelText('Select Dock A'))
    await userEvent.click(screen.getByLabelText('Select Office B'))
    expect(screen.getByText('2 selected')).toBeInTheDocument()

    await userEvent.type(screen.getByPlaceholderText(/Search printers/i), 'Office')
    expect(screen.queryByLabelText('Select Dock A')).not.toBeInTheDocument()
    expect(screen.getByText('1 selected')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Delete 1$/ })).toBeInTheDocument()

    await userEvent.clear(screen.getByPlaceholderText(/Search printers/i))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
  })
})
