import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 51 User↔Client linkage re-audit item #1 — smoke tests for
 * AcceptInvitePage.
 *
 *   · happy path — preview succeeds, form submits, we redirect to /login
 *   · invalid token — preview 404s, we render the "invite not found"
 *     banner + back-to-login CTA
 *   · already used — preview 409s, we render the "already used" banner
 *
 * The service module is fully mocked; nothing touches real fetch.
 * The router is a MemoryRouter with the /invite/:token path so
 * useParams resolves.
 */

const previewInvite = vi.fn()
const acceptInvite = vi.fn()
const notifyApiError = vi.fn().mockResolvedValue(undefined)
const notifySuccess = vi.fn().mockResolvedValue(undefined)

vi.mock('../api/inviteService', () => ({
  inviteService: {
    previewInvite: (...args: unknown[]) => previewInvite(...args),
    acceptInvite: (...args: unknown[]) => acceptInvite(...args),
    verifyEmail: vi.fn(),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccess(...args),
    apiError: (...args: unknown[]) => notifyApiError(...args),
    error: vi.fn(),
    info: vi.fn(),
  },
}))

// Dynamic import so vi.mock calls above land before the component
// module (which pulls inviteService + notify at top level) is evaluated.
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./AcceptInvitePage')
  return mod.default
}

function renderPage(Page: ComponentType, token: string) {
  return render(
    <MemoryRouter initialEntries={[`/invite/${token}`]}>
      <Routes>
        <Route path="/invite/:token" element={<Page />} />
        <Route path="/login" element={<div>LOGIN_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('AcceptInvitePage', () => {
  beforeEach(() => {
    previewInvite.mockReset()
    acceptInvite.mockReset()
    notifyApiError.mockClear()
    notifySuccess.mockClear()
  })

  // Extended timeout because userEvent.type walks each character through
  // formik's validation cycle. Vitest's default 5s is comfortable when
  // this file runs alone but tight when it follows the OrdersWorkspace
  // smoke test (which leaves lingering act(...) warnings that slow the
  // next test's macrotask queue).
  it('shows the invite preview + submits the accept form on happy path', async () => {
    previewInvite.mockResolvedValue({
      email: 'invitee@acme.io',
      clientCode: 'ACME',
      role: 'USER',
      expiresAt: '2026-08-25T00:00:00',
    })
    acceptInvite.mockResolvedValue({ message: 'ok' })

    const Page = await loadPage()
    renderPage(Page, 'tok-happy')

    // Preview banner surfaces the client + role.
    await waitFor(() =>
      expect(screen.getByText(/You've been invited to/i)).toBeTruthy(),
    )
    expect(screen.getByText('ACME')).toBeTruthy()
    expect(screen.getByText('USER')).toBeTruthy()

    // Fill the form. Use exact `Password` (not regex) so the "Show
    // password" toggle button's aria-label doesn't also match.
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Full Name'), 'Test Invitee')
    await user.type(screen.getByLabelText('Username'), 'newuser')
    await user.type(screen.getByLabelText('Password'), 'p4ssw0rd')

    await user.click(screen.getByRole('button', { name: /Accept Invite/i }))

    await waitFor(() => {
      expect(acceptInvite).toHaveBeenCalledWith({
        token: 'tok-happy',
        username: 'newuser',
        password: 'p4ssw0rd',
        fullName: 'Test Invitee',
      })
    })
    expect(notifySuccess).toHaveBeenCalled()
    // Redirect renders the sentinel from the /login route.
    await waitFor(() => expect(screen.getByText('LOGIN_PAGE')).toBeTruthy())
  }, 15000)

  it('renders the invalid-token banner when preview 404s', async () => {
    previewInvite.mockRejectedValue({
      status: 404,
      errorCode: 'INVITE_NOT_FOUND',
      message: 'Invite not found.',
    })

    const Page = await loadPage()
    renderPage(Page, 'bogus')

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy())
    // Audit I7 — INVITE_NOT_FOUND now has a dedicated friendly-error entry
    // (was falling through to the generic default which masked typo vs
    // stale). The banner title should be the mapped copy.
    expect(screen.getByRole('alert').textContent).toMatch(/Invite link not recognised/i)
    // Back-to-login CTA is present and points at /login.
    expect(screen.getByRole('link', { name: /Back to login/i }).getAttribute('href')).toBe('/login')
    // Form is not rendered.
    expect(screen.queryByRole('button', { name: /Accept Invite/i })).toBeNull()
  })

  it('renders the already-used banner from the friendly map', async () => {
    previewInvite.mockRejectedValue({
      status: 409,
      errorCode: 'INVITE_ALREADY_USED',
      message: 'Invite already used.',
    })

    const Page = await loadPage()
    renderPage(Page, 'used')

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy())
    // Friendly map: "Invite already used" title.
    expect(screen.getByRole('alert').textContent).toMatch(/Invite already used/i)
  })
})
