import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, Outlet } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — SystemSettingsPage · actions slice.
 *
 * Scope:
 *   - Save button disabled when input is blank; enabled once value typed.
 *   - Save happy: update(key, value) called; success toast; input clears;
 *     list re-called (refresh).
 *   - Save with blank value → notify.error('Enter a value to save.');
 *     update NOT called.
 *   - Save with whitespace-only → same blank-value guard.
 *   - Save reject → notify.apiError('Failed to update the setting.');
 *     input value preserved (operator can retry).
 *   - Enter key in the input triggers save.
 *   - Concurrent-save gate: Save button becomes disabled while savingKey
 *     is set for that key.
 *
 * Sibling slice: SystemSettingsPage.shell.test.tsx covers load + list.
 */

// ---------- Service mocks ----------

const listMock = vi.fn()
const updateMock = vi.fn()

vi.mock('../api/systemSettingsService', () => ({
  systemSettingsService: {
    list: (...args: unknown[]) => listMock(...args),
    update: (...args: unknown[]) => updateMock(...args),
  },
}))

const notifySuccessMock = vi.fn()
const notifyErrorMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    error: (...args: unknown[]) => notifyErrorMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({
    username: 'ops', role: 'ADMIN' as const,
    connectedCarriers: [], hasConnectedCarrier: false,
  }),
  clearAuthSession: vi.fn(), storeAuthSession: vi.fn(),
  bootstrapSessionFromCookie: vi.fn(), syncCarrierSession: vi.fn(),
}))

vi.mock('../api/apiClient', () => ({
  ApiError: class ApiError extends Error {},
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// ---------- Fixtures ----------

const setting = (overrides: Partial<{
  key: string, hasValue: boolean, maskedValue: string, description: string,
}> = {}) => ({
  key: overrides.key ?? 'openai.api-key',
  hasValue: overrides.hasValue ?? false,
  maskedValue: overrides.maskedValue ?? '',
  description: overrides.description ?? 'OpenAI API key.',
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  ;[listMock, updateMock, notifySuccessMock, notifyErrorMock, notifyApiErrorMock]
    .forEach((m) => m.mockReset())
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function loadPage(): Promise<ComponentType> {
  const mod = await import('./SystemSettingsPage')
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

// ===================== Save button gating =====================

describe('SystemSettingsPage — Save button gating', () => {
  it('Save button is DISABLED when the input is blank', async () => {
    listMock.mockResolvedValue([setting()])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /^Save$/i })).toBeDisabled()
  })

  it('Save button is ENABLED once the input has a non-blank value', async () => {
    listMock.mockResolvedValue([setting()])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument())
    await userEvent.type(screen.getByPlaceholderText(/Enter new value/i), 'sk-newkey')

    expect(screen.getByRole('button', { name: /^Save$/i })).toBeEnabled()
  })

  it('whitespace-only input keeps Save disabled', async () => {
    listMock.mockResolvedValue([setting()])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument())
    await userEvent.type(screen.getByPlaceholderText(/Enter new value/i), '   ')

    // The button is disabled when input value is only whitespace
    // (the disabled prop uses `!inputValue.trim()`).
    expect(screen.getByRole('button', { name: /^Save$/i })).toBeDisabled()
  })
})

// ===================== Save happy path =====================

describe('SystemSettingsPage — Save happy path', () => {
  it('update(key, value) called + notify.success + input cleared + refresh', async () => {
    listMock.mockResolvedValue([setting({ key: 'openai.api-key' })])
    updateMock.mockResolvedValue(undefined)

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument())
    const baseCalls = listMock.mock.calls.length

    const input = screen.getByPlaceholderText(/Enter new value/i) as HTMLInputElement
    await userEvent.type(input, 'sk-newkey')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))
    })

    // Service called with (key, value).
    await waitFor(() => expect(updateMock).toHaveBeenCalledWith('openai.api-key', 'sk-newkey'))
    // Success toast fired.
    expect(notifySuccessMock).toHaveBeenCalledWith('Setting updated.')
    // Input cleared (so the password field doesn't linger).
    await waitFor(() => expect(input.value).toBe(''))
    // Refresh: list called again.
    await waitFor(() => expect(listMock.mock.calls.length).toBeGreaterThan(baseCalls))
  })
})

// ===================== Save with Enter key =====================

describe('SystemSettingsPage — Save via Enter key', () => {
  it('pressing Enter in the input triggers save', async () => {
    listMock.mockResolvedValue([setting({ key: 'openai.api-key' })])
    updateMock.mockResolvedValue(undefined)

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument())
    const input = screen.getByPlaceholderText(/Enter new value/i)

    await userEvent.type(input, 'sk-key{Enter}')

    await waitFor(() => expect(updateMock).toHaveBeenCalledWith('openai.api-key', 'sk-key'))
  })
})

// ===================== Save reject =====================

describe('SystemSettingsPage — Save reject', () => {
  it('update rejection → notify.apiError; input value preserved for retry', async () => {
    listMock.mockResolvedValue([setting({ key: 'openai.api-key' })])
    updateMock.mockRejectedValue(new Error('boom-save'))

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument())
    const input = screen.getByPlaceholderText(/Enter new value/i) as HTMLInputElement
    await userEvent.type(input, 'sk-key')

    await act(async () => {
      await userEvent.click(screen.getByRole('button', { name: /^Save$/i }))
    })

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(expect.any(Error), 'Failed to update the setting.'),
    )
    // Input is NOT cleared on failure (so the operator can fix and retry).
    expect(input.value).toBe('sk-key')
  })
})

// ===================== Blank-value guard =====================

describe('SystemSettingsPage — blank-value guard', () => {
  it('Enter-key with blank input → notify.error, update NOT called', async () => {
    // Test the runtime guard inside save(): although the button is disabled,
    // the guard also fires on Enter-key or programmatic invocation.
    listMock.mockResolvedValue([setting({ key: 'openai.api-key' })])

    const Page = await loadPage()
    renderPage(Page)

    await waitFor(() => expect(screen.getByPlaceholderText(/Enter new value/i)).toBeInTheDocument())
    const input = screen.getByPlaceholderText(/Enter new value/i)

    // Press Enter with empty input — save() fires but hits the blank-value guard.
    await userEvent.type(input, '{Enter}')

    expect(notifyErrorMock).toHaveBeenCalledWith('Enter a value to save.')
    expect(updateMock).not.toHaveBeenCalled()
  })
})
