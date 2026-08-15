import { describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * Sprint 52 — smoke test for the CarrierShippingLimitsPage.
 * Mocks the API service with a couple of seeded rows and asserts the
 * table renders + shows the carrier / service codes. Interaction
 * (create / edit / delete) is covered by the backend tests + manual QA.
 */

vi.mock('../api/carrierShippingLimitService', () => ({
  carrierShippingLimitService: {
    list: vi.fn().mockResolvedValue({
      data: [
        {
          id: 1,
          carrierCode: 'UPS',
          serviceCode: 'UPS_GROUND',
          scope: 'DOMESTIC',
          direction: 'FORWARD',
          maxPackages: 20,
          maxCommodities: 50,
          maxTotalWeightLb: 150,
          freeDeclaredValue: 100,
          effectiveFrom: '2026-08-01T00:00:00',
          effectiveUntil: null,
          active: true,
          notes: 'seeded',
        },
        {
          id: 2,
          carrierCode: 'FEDEX',
          serviceCode: null,
          scope: 'INTERNATIONAL',
          direction: null,
          maxPackages: 40,
          maxCommodities: null,
          maxTotalWeightLb: null,
          freeDeclaredValue: null,
          effectiveFrom: '2026-08-01T00:00:00',
          effectiveUntil: null,
          active: false,
          notes: null,
        },
      ],
    }),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    remove: vi.fn(),
  },
}))

describe('CarrierShippingLimitsPage', () => {
  it('renders the table with the seeded rows', async () => {
    const { default: CarrierShippingLimitsPage } = await import('./CarrierShippingLimitsPage')
    renderWithProviders(<CarrierShippingLimitsPage />)

    // Wait for the async fetch to resolve + table to render.
    await waitFor(() => {
      expect(screen.getByText('UPS')).toBeTruthy()
    })
    expect(screen.getByText('FEDEX')).toBeTruthy()
    expect(screen.getByText('UPS_GROUND')).toBeTruthy()
    // "New row" create button surfaces the create flow.
    expect(screen.getByRole('button', { name: /new row/i })).toBeTruthy()
  })
})
