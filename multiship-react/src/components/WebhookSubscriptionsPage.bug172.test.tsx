import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'

/**
 * BUG-172 — the webhook editor offered revoked API keys (and preselected
 * one), and the table printed a bare "#999999" for a key that no longer
 * exists. The picker now lists active keys only, and a row whose key is
 * revoked or missing carries a badge.
 */

const listKeys = vi.fn()
const listSubs = vi.fn()
vi.mock('../api/apiKeyService', () => ({
  apiKeyService: { list: (...a: unknown[]) => listKeys(...a) },
}))
vi.mock('../api/webhookSubscriptionService', () => ({
  webhookSubscriptionService: {
    list: (...a: unknown[]) => listSubs(...a),
    save: vi.fn(),
    remove: vi.fn(),
  },
}))
vi.mock('../utils/notify', () => ({
  notify: { error: vi.fn(), success: vi.fn(), invalid: vi.fn(), apiError: vi.fn(), confirm: vi.fn().mockResolvedValue(true) },
}))
vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({ role: 'ADMIN', displayName: 'admin', tenantId: null, email: 'a@t' }),
}))

const key = (id: number, active: boolean) => ({
  id, name: `k${id}`, clientCode: 'ZZTEST1', environment: 'test', scopes: 'labels:read', active, masked: 'msk_…',
  token: null, createdAt: '2026-10-01T00:00:00Z', lastUsedAt: null, revokedAt: active ? null : '2026-10-25T00:00:00Z',
})
const sub = (id: number, apiKeyId: number, url: string) => ({
  id, apiKeyId, event: 'LABEL_GENERATED', url, secret: '••••••', active: false,
})

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => { throw new Error('un-mocked fetch forbidden in unit tests') })
  // The newest key is revoked: pre-fix the editor preselected it.
  listKeys.mockResolvedValue({ data: [key(15, false), key(16, true)] })
  listSubs.mockResolvedValue([sub(3, 15, 'https://hooks.example/revoked'), sub(2, 999999, 'https://hooks.example/missing')])
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function renderPage() {
  const { default: Page } = await import('./WebhookSubscriptionsPage')
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

describe('WebhookSubscriptionsPage — BUG-172 revoked API keys', () => {
  it('badges a subscription whose key is revoked or missing', async () => {
    await renderPage()
    const revokedRow = (await screen.findByText('https://hooks.example/revoked')).closest('tr') as HTMLElement
    expect(within(revokedRow).getByText('k15 (ZZTEST1)')).toBeInTheDocument()
    expect(within(revokedRow).getByRole('img', { name: 'key revoked' })).toBeInTheDocument()
    const missingRow = screen.getByText('https://hooks.example/missing').closest('tr') as HTMLElement
    expect(within(missingRow).getByText('#999999')).toBeInTheDocument()
    expect(within(missingRow).getByRole('img', { name: 'key missing' })).toBeInTheDocument()
  })

  it('offers only active keys in a new subscription and preselects the first of them', async () => {
    await renderPage()
    await screen.findByText('https://hooks.example/revoked')
    await userEvent.click(screen.getByRole('button', { name: /New subscription/ }))
    const pick = screen.getByRole('combobox', { name: 'API key' }) as HTMLSelectElement
    expect(pick.value).toBe('16')
    expect(within(pick).getByRole('option', { name: 'k16 (ZZTEST1)' })).toBeInTheDocument()
    expect(within(pick).queryByRole('option', { name: /k15/ })).toBeNull()
  })

  it('keeps a revoked key visible, marked, when editing the subscription that uses it', async () => {
    await renderPage()
    await screen.findByText('https://hooks.example/revoked')
    await userEvent.click(screen.getByRole('button', { name: 'Edit https://hooks.example/revoked' }))
    const pick = screen.getByRole('combobox', { name: 'API key' }) as HTMLSelectElement
    expect(pick.value).toBe('15')
    expect(within(pick).getByRole('option', { name: 'k15 (ZZTEST1) — revoked' })).toBeInTheDocument()
  })
})
