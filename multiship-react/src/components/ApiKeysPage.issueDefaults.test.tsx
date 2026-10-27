import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * R2-BUG-019 — Issue API key dialog opens with least-privilege defaults.
 *
 * Pre-fix the dialog pre-selected: client scope = All clients,
 * environment = LIVE, every scope checked. Every one of those is a
 * maximum-blast-radius default on a mint-key flow.
 *
 * Guards (asserted on fresh dialog open):
 *   - Issue button is DISABLED (no client scope + no API scopes picked).
 *   - Environment radio shows TEST selected (not LIVE).
 *   - Zero scope checkboxes are checked.
 *   - Neither "All clients" nor "One client" is pre-selected.
 */

// ---------- Service mocks ----------

const listMock = vi.fn()
const issueMock = vi.fn()
const revokeMock = vi.fn()
const rotateMock = vi.fn()
const listClientsMock = vi.fn()

vi.mock('../api/apiKeyService', () => ({
  apiKeyService: {
    list: (...args: unknown[]) => listMock(...args),
    issue: (...args: unknown[]) => issueMock(...args),
    revoke: (...args: unknown[]) => revokeMock(...args),
    rotate: (...args: unknown[]) => rotateMock(...args),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    apiError: vi.fn(), success: vi.fn(), error: vi.fn(), info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  listMock.mockReset(); issueMock.mockReset(); revokeMock.mockReset(); rotateMock.mockReset(); listClientsMock.mockReset()
  listMock.mockResolvedValue({ data: [] })
  listClientsMock.mockResolvedValue({ data: { content: [], totalPages: 1 } })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ApiKeysPage')
  return mod.default
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter>
      <Routes>
        <Route element={<Outlet context={{ registerRefresh: vi.fn() }} />}>
          <Route path="*" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ===================== Fresh-open defaults =====================

describe('ApiKeysPage — BUG-146 revoked expiry', () => {
  it('a revoked key shows "revoked", not days left', async () => {
    const key = (id: number, active: boolean) => ({
      id, name: `k${id}`, clientCode: 'ACME', environment: 'test', scopes: 'rates', active, masked: 'msk_…',
      token: null, createdAt: '2026-10-01T00:00:00Z', lastUsedAt: null, revokedAt: active ? null : '2026-10-20T00:00:00Z',
      expiresAt: new Date(Date.now() + 85 * 86_400_000).toISOString(),
    })
    listMock.mockResolvedValue({ data: [key(1, true), key(2, false)] })
    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('k2')).toBeInTheDocument())
    expect(screen.getByText('85d left')).toBeInTheDocument()
    expect(screen.getByText('revoked')).toBeInTheDocument()
  })
})

describe('ApiKeysPage — Issue dialog least-privilege defaults (R2-BUG-019)', () => {
  it('on fresh open: Issue button disabled, env=TEST, zero scopes checked, no client scope pre-picked', async () => {
    const user = userEvent.setup()
    const Page = await loadPage()
    renderPage(Page)

    // Wait for the list fetch to settle (empty) before opening the modal.
    await waitFor(() => expect(listMock).toHaveBeenCalledTimes(1))

    // Open the modal via the actions-bar button (there are two
    // "Issue"-shaped buttons — "Issue API key" + "Issue the first key"
    // when empty — pick the one in the actions bar by exact name).
    await user.click(screen.getByRole('button', { name: /^Issue API key$/ }))

    // ---- 1. "Issue key" primary button is disabled.
    const issueBtn = await screen.findByRole('button', { name: /^Issue key$/ })
    expect(issueBtn).toBeDisabled()

    // ---- 2. Environment radio: TEST is selected, LIVE is NOT.
    // The env buttons are rendered as text 'live' / 'test' (uppercase via CSS).
    const liveBtn = screen.getByRole('button', { name: /^live$/i })
    const testBtn = screen.getByRole('button', { name: /^test$/i })
    // Selected state is encoded in the dark-background utility class.
    expect(testBtn.className).toMatch(/bg-\[#1f150c\]/)
    expect(liveBtn.className).not.toMatch(/bg-\[#1f150c\]/)

    // ---- 3. Zero scope checkboxes are checked on open.
    const checkboxes = screen.getAllByRole('checkbox')
    // 7 scopes defined (AVAILABLE_SCOPES in the page); none pre-checked.
    expect(checkboxes.length).toBeGreaterThanOrEqual(7)
    for (const cb of checkboxes) {
      expect(cb).not.toBeChecked()
    }

    // ---- 4. Neither "All clients" nor "One client" is pre-selected.
    // Same selected-state encoding as the env buttons.
    const allClientsBtn = screen.getByRole('button', { name: /^All clients$/ })
    const oneClientBtn = screen.getByRole('button', { name: /^One client$/ })
    expect(allClientsBtn.className).not.toMatch(/bg-\[#1f150c\]/)
    expect(oneClientBtn.className).not.toMatch(/bg-\[#1f150c\]/)
  })
})
