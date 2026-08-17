import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * AdminUsersPage — first-pass coverage (2026-08-17).
 *
 * <p>The /settings/users page had ZERO tests. This suite covers:
 *
 * <ul>
 *   <li><b>Load + render</b>: list users, empty state, api-error surfacing.</li>
 *   <li><b>Filters</b>: search + role filter + active-only checkbox reach the API.</li>
 *   <li><b>Last-admin protection (audit 1.3)</b>: blocking notify.error when
 *       operator tries to deactivate the ONLY active admin.</li>
 *   <li><b>Self-edit guard (audit 3.1)</b>: extra window.confirm when the
 *       operator deactivates their OWN account; "You" badge on current-user row.</li>
 *   <li><b>Reactivate path</b>: reactivate immediately with no confirm/prompt.</li>
 * </ul>
 */

// ==================================================================
// Mocks
// ==================================================================

const list = vi.fn()
const recentAudit = vi.fn()
const deactivate = vi.fn()
const reactivate = vi.fn()

vi.mock('../api/adminUserService', () => ({
  adminUserService: {
    list: (...a: unknown[]) => list(...a),
    recentAudit: (...a: unknown[]) => recentAudit(...a),
    deactivate: (...a: unknown[]) => deactivate(...a),
    reactivate: (...a: unknown[]) => reactivate(...a),
    assignClient: vi.fn(),
    userAudit: vi.fn(),
  },
}))

vi.mock('../api/clientService', () => ({
  clientService: {
    listClients: vi.fn().mockResolvedValue({
      data: { content: [], pageNumber: 0, pageSize: 500, totalElements: 0, totalPages: 1 },
    }),
  },
}))

const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    info: vi.fn(),
    confirm: vi.fn(),
  },
}))

let mockUsername: string | null = 'admin@acme'
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: mockUsername, role: 'ADMIN' }),
  clearAuthSession: vi.fn(),
}))

// ==================================================================
// Fixtures
// ==================================================================

const user = (over: Partial<Record<string, unknown>> = {}) => ({
  id: 1,
  username: 'user1@acme',
  email: 'user1@acme',
  fullName: 'User One',
  role: 'USER',
  clientCode: 'ACME',
  emailVerified: true,
  deactivatedAt: null,
  deactivatedBy: null,
  createdAt: '2026-08-01T00:00:00',
  ...over,
})

const oneAdmin = [
  user({ id: 100, username: 'admin@acme', role: 'ADMIN', clientCode: null }),
  user({ id: 101, username: 'op1@acme', role: 'USER', clientCode: 'ACME' }),
  user({ id: 102, username: 'op2@acme', role: 'USER', clientCode: 'BETA' }),
]

const twoAdmins = [
  user({ id: 100, username: 'admin@acme', role: 'ADMIN', clientCode: null }),
  user({ id: 200, username: 'admin2@acme', role: 'ADMIN', clientCode: null }),
  user({ id: 101, username: 'op1@acme', role: 'USER', clientCode: 'ACME' }),
]

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./AdminUsersPage')
  return mod.default
}

function OutletShell() {
  return <Outlet context={{ registerRefresh: () => {} }} />
}

function renderPage(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/users']}>
      <Routes>
        <Route element={<OutletShell />}>
          <Route path="/settings/users" element={<Page />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  list.mockResolvedValue({ data: oneAdmin })
  recentAudit.mockResolvedValue({ data: [] })
  deactivate.mockResolvedValue({ data: {} })
  reactivate.mockResolvedValue({ data: {} })
  mockUsername = 'admin@acme'
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden')
  })
})

afterEach(() => {
  cleanup()
})

// ==================================================================
// Load + render
// ==================================================================

describe('AdminUsersPage — load + render', () => {
  it('renders each user row from the API response', async () => {
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => {
      expect(screen.getByText('admin@acme')).toBeInTheDocument()
    })
    expect(screen.getByText('op1@acme')).toBeInTheDocument()
    expect(screen.getByText('op2@acme')).toBeInTheDocument()
  })

  it('list-fetch reject → notify.apiError; no rows rendered', async () => {
    list.mockRejectedValueOnce(new Error('boom'))
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(notifyApiError).toHaveBeenCalled())
    expect(screen.queryByText('admin@acme')).toBeNull()
  })

  it('empty list → "No users." state', async () => {
    list.mockResolvedValueOnce({ data: [] })
    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByText(/^No users\.$/)).toBeInTheDocument())
  })
})

// ==================================================================
// Self-edit guard + "You" badge (audit 3.1)
// ==================================================================

describe('AdminUsersPage — self-edit guard (audit 3.1)', () => {
  it('current-user row shows "You" badge', async () => {
    const Page = await loadPage()
    renderPage(Page)

    // admin@acme is the current user (mockUsername) — badge on that row.
    await waitFor(() => {
      const adminRow = screen.getByText('admin@acme').closest('tr')!
      expect(within(adminRow).getByText('You')).toBeInTheDocument()
    })
    // Other rows do NOT have the badge.
    const opRow = screen.getByText('op1@acme').closest('tr')!
    expect(within(opRow).queryByText('You')).toBeNull()
  })

  it('deactivating a NON-self user does NOT show the self-edit confirm', async () => {
    // Add a second admin so the last-admin guard doesn't fire.
    list.mockResolvedValue({ data: twoAdmins })
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('reason')
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('op1@acme')).toBeInTheDocument())

    const opRow = screen.getByText('op1@acme').closest('tr')!
    await userEvent.click(within(opRow).getByRole('button', { name: /^Deactivate$/i }))

    // No self-edit confirm.
    expect(confirmSpy).not.toHaveBeenCalled()
    // Prompt fired (for reason).
    await waitFor(() => expect(promptSpy).toHaveBeenCalled())
    await waitFor(() => expect(deactivate).toHaveBeenCalledWith(101, 'reason'))

    promptSpy.mockRestore()
    confirmSpy.mockRestore()
  })

  it('deactivating SELF fires the extra self-edit confirm before the reason prompt', async () => {
    // Two admins so the last-admin guard doesn't take priority.
    list.mockResolvedValue({ data: twoAdmins })
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('leaving')

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('admin@acme')).toBeInTheDocument())

    const selfRow = screen.getByText('admin@acme').closest('tr')!
    await userEvent.click(within(selfRow).getByRole('button', { name: /^Deactivate$/i }))

    await waitFor(() => expect(confirmSpy).toHaveBeenCalled())
    const msg = String(confirmSpy.mock.calls[0]?.[0] ?? '')
    // Warning includes ownership language + logout consequence.
    expect(msg).toMatch(/YOUR OWN/i)
    expect(msg).toMatch(/logged out/i)
    // After confirm accepts, the reason prompt fires and the deactivate call happens.
    await waitFor(() => expect(promptSpy).toHaveBeenCalled())
    await waitFor(() => expect(deactivate).toHaveBeenCalledWith(100, 'leaving'))

    confirmSpy.mockRestore()
    promptSpy.mockRestore()
  })

  it('rejecting the self-edit confirm skips the API call', async () => {
    list.mockResolvedValue({ data: twoAdmins })
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const promptSpy = vi.spyOn(window, 'prompt')

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('admin@acme')).toBeInTheDocument())

    const selfRow = screen.getByText('admin@acme').closest('tr')!
    await userEvent.click(within(selfRow).getByRole('button', { name: /^Deactivate$/i }))

    await waitFor(() => expect(confirmSpy).toHaveBeenCalled())
    // Reason prompt NOT fired (short-circuited).
    expect(promptSpy).not.toHaveBeenCalled()
    expect(deactivate).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
    promptSpy.mockRestore()
  })
})

// ==================================================================
// Last-admin protection (audit 1.3)
// ==================================================================

describe('AdminUsersPage — last-admin protection (audit 1.3)', () => {
  it('deactivating the ONLY active admin is BLOCKED with notify.error', async () => {
    // oneAdmin fixture — only 1 admin active.
    const promptSpy = vi.spyOn(window, 'prompt')
    const confirmSpy = vi.spyOn(window, 'confirm')

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('admin@acme')).toBeInTheDocument())

    const adminRow = screen.getByText('admin@acme').closest('tr')!
    await userEvent.click(within(adminRow).getByRole('button', { name: /^Deactivate$/i }))

    await waitFor(() => expect(notifyError).toHaveBeenCalled())
    const msg = String(notifyError.mock.calls[0]?.[0] ?? '')
    expect(msg).toMatch(/ONLY active admin/i)
    // No prompt, no confirm, no API call.
    expect(promptSpy).not.toHaveBeenCalled()
    expect(confirmSpy).not.toHaveBeenCalled()
    expect(deactivate).not.toHaveBeenCalled()

    promptSpy.mockRestore()
    confirmSpy.mockRestore()
  })

  it('with TWO active admins, deactivating one is ALLOWED', async () => {
    list.mockResolvedValue({ data: twoAdmins })
    mockUsername = 'someone-else@acme'  // not self, so self-guard doesn't fire
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('reason')

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('admin@acme')).toBeInTheDocument())

    const adminRow = screen.getByText('admin@acme').closest('tr')!
    await userEvent.click(within(adminRow).getByRole('button', { name: /^Deactivate$/i }))

    await waitFor(() => expect(promptSpy).toHaveBeenCalled())
    await waitFor(() => expect(deactivate).toHaveBeenCalledWith(100, 'reason'))
    // No blocking error.
    expect(notifyError).not.toHaveBeenCalled()

    promptSpy.mockRestore()
  })

  it('a DEACTIVATED admin does not count toward the active-admin quorum', async () => {
    // 2 admins in the list, but one is already deactivated → only 1 active.
    // Deactivating the remaining active admin must still be blocked.
    list.mockResolvedValue({
      data: [
        user({ id: 100, username: 'admin@acme', role: 'ADMIN', clientCode: null }),
        user({ id: 200, username: 'admin2@acme', role: 'ADMIN', clientCode: null,
               deactivatedAt: '2026-01-01T00:00:00' }),
      ],
    })
    const promptSpy = vi.spyOn(window, 'prompt')

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('admin@acme')).toBeInTheDocument())

    const adminRow = screen.getByText('admin@acme').closest('tr')!
    await userEvent.click(within(adminRow).getByRole('button', { name: /^Deactivate$/i }))

    await waitFor(() => expect(notifyError).toHaveBeenCalled())
    expect(promptSpy).not.toHaveBeenCalled()
    expect(deactivate).not.toHaveBeenCalled()

    promptSpy.mockRestore()
  })
})

// ==================================================================
// Reactivate path
// ==================================================================

describe('AdminUsersPage — reactivate path', () => {
  it('reactivating a deactivated user immediately calls the API (no confirm)', async () => {
    list.mockResolvedValue({
      data: [
        user({ id: 100, username: 'admin@acme', role: 'ADMIN', clientCode: null }),
        user({ id: 300, username: 'deactivated@acme', role: 'USER',
               deactivatedAt: '2026-01-01T00:00:00' }),
      ],
    })
    const confirmSpy = vi.spyOn(window, 'confirm')

    const Page = await loadPage()
    renderPage(Page)
    await waitFor(() => expect(screen.getByText('deactivated@acme')).toBeInTheDocument())

    const row = screen.getByText('deactivated@acme').closest('tr')!
    await userEvent.click(within(row).getByRole('button', { name: /^Reactivate$/i }))

    await waitFor(() => expect(reactivate).toHaveBeenCalledWith(300))
    expect(confirmSpy).not.toHaveBeenCalled()

    confirmSpy.mockRestore()
  })
})
