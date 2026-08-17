import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 51 User↔Client linkage re-audit item #2 — smoke tests for
 * VerifyEmailPage.
 *
 *   · happy path — token is present, POST resolves, we render the
 *     "Email verified" success card + link to /login
 *   · missing token — no ?token= in the URL, we render the error
 *     banner without calling the service
 *   · backend rejects — POST 400s (expired token), we surface the
 *     friendly message
 */

const verifyEmail = vi.fn()

vi.mock('../api/inviteService', () => ({
  inviteService: {
    verifyEmail: (...args: unknown[]) => verifyEmail(...args),
    previewInvite: vi.fn(),
    acceptInvite: vi.fn(),
  },
}))

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./VerifyEmailPage')
  return mod.default
}

function renderPage(Page: ComponentType, search: string) {
  return render(
    <MemoryRouter initialEntries={[`/verify-email${search}`]}>
      <Routes>
        <Route path="/verify-email" element={<Page />} />
        <Route path="/login" element={<div>LOGIN_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('VerifyEmailPage', () => {
  beforeEach(() => {
    verifyEmail.mockReset()
  })

  it('renders the verified card when the backend returns ok', async () => {
    verifyEmail.mockResolvedValue({ message: 'Email verified. You can now log in.' })

    const Page = await loadPage()
    renderPage(Page, '?token=tok-happy')

    await waitFor(() => expect(screen.getByRole('status')).toBeTruthy())
    expect(screen.getByRole('status').textContent).toMatch(/Email verified/i)
    // Success card includes a Continue-to-login CTA.
    expect(screen.getByRole('link', { name: /Continue to login/i }).getAttribute('href')).toBe(
      '/login',
    )
    expect(verifyEmail).toHaveBeenCalledWith('tok-happy')
  })

  it('renders the missing-token banner without hitting the service', async () => {
    const Page = await loadPage()
    renderPage(Page, '')

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy())
    expect(screen.getByRole('alert').textContent).toMatch(/missing a token/i)
    expect(verifyEmail).not.toHaveBeenCalled()
  })

  it('renders a friendly error when the backend rejects the token', async () => {
    verifyEmail.mockRejectedValue({
      status: 400,
      errorCode: 'INVITE_EXPIRED',
      message: 'Verification link expired.',
    })

    const Page = await loadPage()
    renderPage(Page, '?token=old')

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy())
    // Friendly map maps INVITE_EXPIRED → "Invite expired" title.
    expect(screen.getByRole('alert').textContent).toMatch(/Invite expired/i)
  })
})
