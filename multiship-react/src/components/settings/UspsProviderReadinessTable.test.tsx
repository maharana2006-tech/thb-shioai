import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UspsProviderReadiness } from '../../api/systemSettingsService'

/**
 * PR-A · Agent D · USPS Direct integration.
 *
 * Coverage for the readiness table component that renders inline on
 * /settings/system while the USPS_PROVIDER is in the
 * PROVISIONING_USPS_DIRECT state.
 *
 *   - Fully-ready payload → empty-state row + green "Ready to switch" badge.
 *   - Partial payload → per-tenant rows + amber "missing field" pills.
 *   - Platform creds unset → red CLIENT_ID / CLIENT_SECRET chips.
 *   - Refresh button re-invokes getUspsProviderReadiness.
 *   - onLoaded fires with the fresh DTO after every successful fetch.
 *
 * Every network path is mocked; no fetch escapes to the wire.
 */

// ---------- Service mocks ----------

const getReadinessMock = vi.fn()

vi.mock('../../api/systemSettingsService', () => ({
  systemSettingsService: {
    getUspsProviderReadiness: (...a: unknown[]) => getReadinessMock(...a),
  },
}))

const notifyApiErrorMock = vi.fn()
vi.mock('../../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
    apiError: (...a: unknown[]) => notifyApiErrorMock(...a),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// ---------- Fixtures ----------

function readyFixture(overrides: Partial<UspsProviderReadiness> = {}): UspsProviderReadiness {
  return {
    currentProvider: 'PROVISIONING_USPS_DIRECT',
    targetProvider: 'USPS_DIRECT',
    platformCreds: { clientIdSet: true, clientSecretSet: true },
    totalUspsAccounts: 3,
    readyAccounts: 3,
    pendingAccounts: [],
    overallReady: true,
    ...overrides,
  }
}

function pendingFixture(overrides: Partial<UspsProviderReadiness> = {}): UspsProviderReadiness {
  return {
    currentProvider: 'PROVISIONING_USPS_DIRECT',
    targetProvider: 'USPS_DIRECT',
    platformCreds: { clientIdSet: true, clientSecretSet: true },
    totalUspsAccounts: 3,
    readyAccounts: 1,
    pendingAccounts: [
      {
        tenantCode: 'ACME',
        accountNumber: 'stamps-user-1',
        missing: ['usps_direct_account_number', 'usps_direct_crid'],
      },
      {
        tenantCode: 'GLOBE',
        accountNumber: 'stamps-user-2',
        missing: ['usps_direct_mid'],
      },
    ],
    overallReady: false,
    ...overrides,
  }
}

// ---------- Suite setup ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  getReadinessMock.mockReset()
  notifyApiErrorMock.mockReset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function loadComponent() {
  const mod = await import('./UspsProviderReadinessTable')
  return mod.default
}

// ============================================================================
// Ready state
// ============================================================================

describe('UspsProviderReadinessTable — everything ready', () => {
  it('renders the empty-state row and green "Ready to switch" overall badge', async () => {
    getReadinessMock.mockResolvedValue({ data: readyFixture() })

    const Comp = await loadComponent()
    render(<Comp />)

    await waitFor(() =>
      expect(screen.getByTestId('usps-readiness-overall')).toHaveTextContent(
        /ready to switch to USPS_DIRECT/i,
      ),
    )
    // Empty-state marker (no pending accounts).
    expect(screen.getByTestId('usps-readiness-empty')).toBeTruthy()
    // Ready X of Y text uses the DTO's numbers.
    expect(screen.getByText(/Ready 3 of 3 USPS accounts/i)).toBeTruthy()
    // Platform chips both green (aria-label carries the state).
    expect(screen.getByLabelText(/CLIENT_ID set/i)).toBeTruthy()
    expect(screen.getByLabelText(/CLIENT_SECRET set/i)).toBeTruthy()
  })
})

// ============================================================================
// Pending state — some accounts missing fields
// ============================================================================

describe('UspsProviderReadinessTable — some accounts pending', () => {
  it('renders one row per pending account with missing-field pills', async () => {
    getReadinessMock.mockResolvedValue({ data: pendingFixture() })

    const Comp = await loadComponent()
    render(<Comp />)

    await waitFor(() =>
      expect(screen.getByTestId('usps-readiness-overall')).toHaveTextContent(/not ready/i),
    )

    // Both tenant codes rendered.
    expect(screen.getByText('ACME')).toBeTruthy()
    expect(screen.getByText('GLOBE')).toBeTruthy()

    // Missing-field pills use human labels, not the raw enum keys.
    // ACME row is missing both EPS # and CRID.
    expect(screen.getByText('EPS account #')).toBeTruthy()
    expect(screen.getByText('CRID')).toBeTruthy()
    // GLOBE row is missing MID.
    expect(screen.getByText('MID')).toBeTruthy()

    // Ready X of Y reads from the DTO.
    expect(screen.getByText(/Ready 1 of 3 USPS accounts/i)).toBeTruthy()
    // Empty-state marker absent.
    expect(screen.queryByTestId('usps-readiness-empty')).toBeNull()
  })
})

// ============================================================================
// Platform-creds missing
// ============================================================================

describe('UspsProviderReadinessTable — platform creds missing', () => {
  it('shows red CLIENT_ID / CLIENT_SECRET chips + Not-ready badge when platform creds unset', async () => {
    getReadinessMock.mockResolvedValue({
      data: readyFixture({
        platformCreds: { clientIdSet: false, clientSecretSet: false },
        overallReady: false,
      }),
    })

    const Comp = await loadComponent()
    render(<Comp />)

    await waitFor(() =>
      expect(screen.getByLabelText(/CLIENT_ID not set/i)).toBeTruthy(),
    )
    expect(screen.getByLabelText(/CLIENT_SECRET not set/i)).toBeTruthy()

    // Overall badge is red even if pendingAccounts is empty, because
    // platform creds gate the switch too.
    expect(screen.getByTestId('usps-readiness-overall')).toHaveTextContent(/not ready/i)
  })
})

// ============================================================================
// Refresh interaction
// ============================================================================

describe('UspsProviderReadinessTable — refresh + callbacks', () => {
  it('Refresh button re-invokes the readiness service', async () => {
    getReadinessMock.mockResolvedValue({ data: readyFixture() })

    const Comp = await loadComponent()
    const user = userEvent.setup()
    render(<Comp />)

    await waitFor(() => expect(getReadinessMock).toHaveBeenCalledTimes(1))
    await user.click(screen.getByRole('button', { name: /refresh usps provider readiness/i }))
    await waitFor(() => expect(getReadinessMock).toHaveBeenCalledTimes(2))
  })

  it('onLoaded is invoked with the fresh DTO after each successful fetch', async () => {
    const fixture = pendingFixture()
    getReadinessMock.mockResolvedValue({ data: fixture })
    const onLoaded = vi.fn()

    const Comp = await loadComponent()
    render(<Comp onLoaded={onLoaded} />)

    await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(fixture))
  })
})

// ============================================================================
// Error path
// ============================================================================

describe('UspsProviderReadinessTable — error handling', () => {
  it('surfaces notify.apiError when getReadiness rejects', async () => {
    getReadinessMock.mockRejectedValue(new Error('boom-readiness'))

    const Comp = await loadComponent()
    render(<Comp />)

    await waitFor(() =>
      expect(notifyApiErrorMock).toHaveBeenCalledWith(
        expect.any(Error),
        'Failed to load USPS provider readiness.',
      ),
    )
    // Alert region rendered so screen readers pick up the failure.
    expect(screen.getByRole('alert')).toBeTruthy()
  })
})
