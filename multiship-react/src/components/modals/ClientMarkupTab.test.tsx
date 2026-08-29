import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

/**
 * Sprint 52 — ClientMarkupTab "no billing markup saved" warning.
 * The tab previously showed FE-default state (kind=PERCENT, value=0,
 * currency=USD) indistinguishably whether the admin had saved a 0% row
 * or simply never visited. Now: unsaved → amber banner naming the fix;
 * saved → banner absent. This test isolates the load() branch since
 * the visual is the primary Sprint 52 UX change on this tab.
 */

const getMock = vi.fn()
vi.mock('../../api/clientPolicyService', () => ({
  clientBillingMarkupService: {
    get: (code: string) => getMock(code),
    update: vi.fn().mockResolvedValue({ data: null }),
  },
}))

vi.mock('../../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    apiError: vi.fn(),
    info: vi.fn(),
  },
}))

// Pull in the real money helpers — they don't touch the network + are
// used by the preview subtree; mocking them would just bloat the test.
vi.mock('../../utils/money', async () => {
  const actual = await vi.importActual<typeof import('../../utils/money')>('../../utils/money')
  return actual
})

async function loadTab() {
  const mod = await import('./ClientMarkupTab')
  return mod.default
}

beforeEach(() => {
  getMock.mockReset()
})

describe('ClientMarkupTab — no-saved-markup warning (Sprint 52)', () => {
  it('shows amber banner when the server returns no saved markup row', async () => {
    // Backend returns 200 with data:null when the client has no
    // client_billing_markup row — matches clientBillingMarkupService.get
    // wire shape.
    getMock.mockResolvedValue({ data: null })

    const Tab = await loadTab()
    render(<Tab clientCode="THB000" />)

    const banner = await waitFor(() => screen.getByTestId('markup-not-saved-banner'))
    // Terminology per Sprint 52 design pick — "No billing markup saved",
    // NOT "not configured" or "will be refused" (chosen so admins with
    // pass-through 0% clients know saving is still the fix, not skipping).
    expect(banner.textContent).toContain('No billing markup saved')
    // Value-of-0 clarification is critical — the whole point of the amber
    // wording is to nudge admins to Save even if their value is 0.
    expect(banner.textContent).toContain('value of')
    expect(banner.textContent).toContain('0')
  })

  it('hides the banner when the server returns a saved row (even value=0)', async () => {
    // Saved row with value=0 is a legitimate explicit choice — must NOT
    // trigger the warning. Backend distinguishes "row saved with 0"
    // from "no row" in the exists-check; the FE mirrors that here.
    getMock.mockResolvedValue({
      data: { clientCode: 'ACME', kind: 'PERCENT', value: 0, currency: 'USD' },
    })

    const Tab = await loadTab()
    render(<Tab clientCode="ACME" />)

    // Wait for load() to settle so the banner has had a chance to render.
    await waitFor(() => expect(getMock).toHaveBeenCalledWith('ACME'))
    // Give React one more tick.
    await new Promise((r) => setTimeout(r, 0))

    expect(screen.queryByTestId('markup-not-saved-banner')).toBeNull()
  })

  it('hides the banner when the server returns a saved row with a non-zero value', async () => {
    getMock.mockResolvedValue({
      data: { clientCode: 'ACME', kind: 'PERCENT', value: 12.5, currency: 'USD' },
    })

    const Tab = await loadTab()
    render(<Tab clientCode="ACME" />)

    await waitFor(() => expect(getMock).toHaveBeenCalledWith('ACME'))
    await new Promise((r) => setTimeout(r, 0))

    expect(screen.queryByTestId('markup-not-saved-banner')).toBeNull()
  })
})
