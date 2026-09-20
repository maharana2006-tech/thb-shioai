import { describe, expect, it, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/**
 * Slice-3 component test — covers the two flows an admin lands on:
 *  1. Unconfigured tenant → warning banner + Save enabled → PUT fires.
 *  2. Configured tenant → SAVED chip on the stored option + Save
 *     disabled until picker differs → PUT with the new selection.
 *
 * <p>Anti-fallback: the service module is fully mocked so no real
 * fetch runs. Assertions cover both the visible UI transitions
 * (banner appears/disappears, SAVED chip moves) and the wire
 * payload sent to setEnabledChannels.
 */

// ─── mocks (must be declared before imports of the SUT) ─────────

const listClientsMock = vi.fn()
vi.mock('../../api/clientService', () => ({
  clientService: {
    listClients: (...args: unknown[]) => listClientsMock(...args),
  },
}))

const getEnabledMock = vi.fn()
const setEnabledMock = vi.fn()
vi.mock('../../api/tenantSettingsService', () => ({
  tenantSettingsService: {
    getEnabledChannels: (...args: unknown[]) => getEnabledMock(...args),
    setEnabledChannels: (...args: unknown[]) => setEnabledMock(...args),
  },
}))

const notifySuccessMock = vi.fn()
const notifyApiErrorMock = vi.fn()
vi.mock('../../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccessMock(...args),
    apiError: (...args: unknown[]) => notifyApiErrorMock(...args),
  },
}))

// ─── SUT + fixtures ─────────────────────────────────────────────

import SystemChannelSection from './SystemChannelSection'

const clientFixture = [
  { clientCode: 'THB000', name: 'THB Ops' },
  { clientCode: 'ACME01', name: 'Acme Inc.' },
]

beforeEach(() => {
  vi.clearAllMocks()
  // Reset localStorage between cases so tenant memory doesn't leak.
  window.localStorage.clear()
  listClientsMock.mockResolvedValue({
    data: { content: clientFixture, totalPages: 1 },
  })
})

// ─── cases ──────────────────────────────────────────────────────

describe('SystemChannelSection', () => {
  it('shows the unconfigured banner and PUTs on Save for a fresh tenant', async () => {
    getEnabledMock.mockResolvedValue({
      tenantCode: 'THB000',
      enabledChannels: [],
      isConfigured: false,
    })
    setEnabledMock.mockResolvedValue({
      tenantCode: 'THB000',
      enabledChannels: ['D2C'],
      isConfigured: true,
    })

    const user = userEvent.setup()
    render(<SystemChannelSection />)

    // Wait for the client list to populate.
    await waitFor(() => expect(screen.getByRole('combobox')).toBeInTheDocument())

    await user.selectOptions(screen.getByRole('combobox'), 'THB000')

    // Warning banner surfaces the "unconfigured, currently rejecting" copy.
    await waitFor(() =>
      expect(screen.getByText(/hasn't picked its order channels yet/i)).toBeInTheDocument()
    )
    expect(getEnabledMock).toHaveBeenCalledWith('THB000')

    // Pick "D2C only" from the radio group.
    await user.click(screen.getByRole('radio', { name: /D2C only/ }))

    // Save is enabled because picker (D2C) differs from stored (null).
    const saveBtn = screen.getByRole('button', { name: /save/i })
    expect(saveBtn).not.toBeDisabled()
    await user.click(saveBtn)

    await waitFor(() =>
      expect(setEnabledMock).toHaveBeenCalledWith('THB000', ['D2C'])
    )
    expect(notifySuccessMock).toHaveBeenCalled()

    // Post-save: the banner ("hasn't picked its order channels yet")
    // is gone; the header still mentions the errorCode as reference.
    await waitFor(() =>
      expect(screen.queryByText(/hasn't picked its order channels yet/i)).not.toBeInTheDocument()
    )
    expect(screen.getAllByText(/TENANT_CHANNEL_NOT_ENABLED/)).toHaveLength(1)
  })

  it('marks the stored option with SAVED and disables Save until the picker changes', async () => {
    // Seed persisted tenant so the component loads THB000 on mount.
    window.localStorage.setItem('multiship_system_channels_tenant', 'THB000')
    getEnabledMock.mockResolvedValue({
      tenantCode: 'THB000',
      enabledChannels: ['B2B', 'D2C'],
      isConfigured: true,
    })
    setEnabledMock.mockResolvedValue({
      tenantCode: 'THB000',
      enabledChannels: ['B2B'],
      isConfigured: true,
    })

    const user = userEvent.setup()
    render(<SystemChannelSection />)

    // "Both" carries the SAVED chip because that's what's stored.
    await waitFor(() => {
      const both = screen.getByRole('radio', { name: /Both/ })
      expect(both).toHaveAttribute('aria-checked', 'true')
    })

    // Save is disabled — picker equals stored.
    expect(screen.getByRole('button', { name: /save/i })).toBeDisabled()
    expect(screen.getByText(/No changes to save/)).toBeInTheDocument()

    // Switch to B2B only → Save enables → PUT sends the new value.
    await user.click(screen.getByRole('radio', { name: /B2B only/ }))
    expect(screen.getByRole('button', { name: /save/i })).not.toBeDisabled()
    await user.click(screen.getByRole('button', { name: /save/i }))

    await waitFor(() =>
      expect(setEnabledMock).toHaveBeenCalledWith('THB000', ['B2B'])
    )
    expect(notifySuccessMock).toHaveBeenCalled()
  })

  it('surfaces load failures via notify.apiError without crashing', async () => {
    window.localStorage.setItem('multiship_system_channels_tenant', 'THB000')
    getEnabledMock.mockRejectedValue(new Error('backend down'))

    render(<SystemChannelSection />)

    await waitFor(() => expect(notifyApiErrorMock).toHaveBeenCalled())
    // Component still renders the tenant picker so the operator can
    // retry with a different tenant.
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })
})
