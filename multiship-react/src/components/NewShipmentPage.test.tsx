import { describe, expect, it, vi } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * Sprint 49 Tier 5 Fix 6 — NewShipmentPage smoke test.
 *
 * <p>The largest component in the app (2500+ LOC, ~15 API service
 * dependencies) had zero test coverage. Any TypeScript refactor, import
 * rename, or provider requirement shift would break it silently.
 *
 * <p>This smoke test just proves the module imports cleanly, renders
 * within the standard providers (Redux, Router), and surfaces a
 * recognizable section header. Deeper interaction coverage (form fills,
 * carrier picker, submit gating) is a follow-up — the highest immediate
 * value is a compile-then-render guard.
 *
 * <p>Every API service used by the page is mocked at module level so
 * mount-time effects don't hit a real network.
 */

// Mock every external network-touching module the page imports on mount.
// Empty successful responses so effects settle without error toasts.
vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue({ data: [] }),
    getPlatformCredentials: vi.fn().mockResolvedValue({ data: { found: false } }),
  },
}))
vi.mock('../api/clientService', () => ({
  clientService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    listShippingServices: vi.fn().mockResolvedValue({ data: [] }),
    listPackages: vi.fn().mockResolvedValue({ data: [] }),
    listCarriers: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/addressService', () => ({
  addressService: { validate: vi.fn().mockResolvedValue({ data: null }) },
}))
vi.mock('../api/addressValidationService', () => ({
  addressValidationService: { validate: vi.fn().mockResolvedValue({ data: null }) },
}))
vi.mock('../api/recipientBookService', () => ({
  recipientBookService: {
    search: vi.fn().mockResolvedValue({ data: [] }),
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/warehouseService', () => ({
  clientWarehouseService: {
    list: vi.fn().mockResolvedValue({ data: [] }),
  },
}))
vi.mock('../api/aiService', () => ({
  aiService: {
    reviewShipment: vi.fn().mockResolvedValue({ data: null }),
  },
}))

describe('NewShipmentPage', () => {
  it('renders without crashing within Redux + Router providers', async () => {
    // Dynamic import so the vi.mock calls above land before the component
    // module is evaluated.
    const { default: NewShipmentPage } = await import('./NewShipmentPage')
    renderWithProviders(<NewShipmentPage />)

    // With all account/carrier services stubbed empty, the page shows its
    // empty-state ("No shippable carrier yet"). That's fine — the smoke
    // test just proves the module boots and renders the outer shell.
    const emptyState = await screen.findByText(/No shippable carrier yet/i)
    expect(emptyState).toBeInTheDocument()
  })
})
