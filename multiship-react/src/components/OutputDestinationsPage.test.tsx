import { describe, expect, it, vi, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'

/** BUG-132 — the client filter matches part of a code, in any case. */

const list = vi.fn()
vi.mock('../api/outputDestinationService', () => ({
  outputDestinationService: { list: (...a: unknown[]) => list(...a), remove: vi.fn(), test: vi.fn(), create: vi.fn(), update: vi.fn() },
}))
vi.mock('../utils/notify', () => ({
  notify: { error: vi.fn(), apiError: vi.fn(), success: vi.fn(), info: vi.fn(), confirm: vi.fn().mockResolvedValue(false) },
}))

const row = (id: number, clientCode: string) => ({
  id, clientCode, docType: 'LABEL', destinationType: 'LOCAL_FS', configSafe: '{}', active: true, notes: null, createdAt: null, updatedAt: null,
})

afterEach(() => cleanup())

describe('OutputDestinationsPage', () => {
  it('BUG-132: a partial, lower-case code finds its clients; nothing matching says so', async () => {
    list.mockResolvedValue({ data: [row(1, 'QA-1017-CL01'), row(2, 'MKL246')] })
    const { default: Page } = await import('./OutputDestinationsPage')
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Outlet context={{ registerRefresh: () => {} }} />}>
            <Route path="*" element={<Page />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    await screen.findByText('MKL246')
    const box = screen.getByPlaceholderText('Filter by client code...')
    await userEvent.type(box, 'qa-1017')
    await waitFor(() => expect(screen.queryByText('MKL246')).toBeNull())
    expect(screen.getByText('QA-1017-CL01')).toBeInTheDocument()

    await userEvent.clear(box)
    await userEvent.type(box, 'zzz')
    await waitFor(() => expect(screen.getByText(/No destinations for a client code containing “zzz”/)).toBeInTheDocument())
    // One fetch — the match is local.
    expect(list).toHaveBeenCalledTimes(1)
  })
})
