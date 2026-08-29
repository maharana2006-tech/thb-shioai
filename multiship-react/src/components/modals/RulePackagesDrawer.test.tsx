import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { PackagePreset, ShipMethodRule, ShippingServiceItem } from '../../api/shippingConfigService'

/**
 * Sprint 52 — RulePackagesDrawer's Service Catalog scope filter.
 * The drawer's 4th eligibility rule (added in this PR): CARRIER presets
 * must be linked to the rule's service in service_package. CUSTOM
 * presets always bypass (mirrors the backend PackagingCompatibilityGuard
 * kind=CUSTOM short-circuit). Empty pool + branded_packaging_allowed=
 * false collapse to "no CARRIER preset passes."
 */

const listPresetsMock = vi.fn()
vi.mock('../../api/shippingConfigService', () => ({
  shippingConfigService: {
    listPresets: () => listPresetsMock(),
    saveRule: vi.fn().mockResolvedValue({ data: null }),
  },
  // Not real values — only referenced as types.
}))

vi.mock('../../utils/notify', () => ({
  notify: { success: vi.fn(), error: vi.fn(), apiError: vi.fn(), info: vi.fn() },
}))

vi.mock('../../hooks/useModalDismiss', () => ({
  useModalDismiss: () => {},
}))

vi.mock('../../utils/carrierUtils', async () => {
  const actual = await vi.importActual<typeof import('../../utils/carrierUtils')>('../../utils/carrierUtils')
  return actual
})

async function loadDrawer() {
  const mod = await import('./RulePackagesDrawer')
  return mod.default
}

const svc = (id: number, carrier: string, code: string): ShippingServiceItem => ({
  id, carrier, serviceCode: code, name: `${carrier} ${code}`,
  scope: 'DOMESTIC', enabled: true, sortOrder: 0, originCountry: 'US',
})

const rule = (id: number, serviceId: number): ShipMethodRule => ({
  id, shipviaCd: 'P80', serviceId,
})

const preset = (id: number, kind: 'CARRIER' | 'CUSTOM', name: string,
                 carrier?: string, code?: string): PackagePreset => ({
  id, name, kind,
  carrier: carrier ?? null,
  carrierPackageCode: code ?? null,
  ownerType: 'PLATFORM',
  dimUnit: 'IN', weightUnit: 'LB', enabled: true,
  originCountry: carrier ? 'US' : null,
})

beforeEach(() => {
  listPresetsMock.mockReset()
})

describe('RulePackagesDrawer — Service Catalog scope filter (Sprint 52)', () => {
  it('CARRIER presets are HIDDEN when serviceLinks has no rows for this service', async () => {
    // FEDEX_GROUND scenario: V30 seeded branded_packaging_allowed=false
    // → zero service_package rows. Drawer should show only CUSTOM boxes.
    listPresetsMock.mockResolvedValue([
      preset(1, 'CARRIER', 'FedEx Envelope', 'FEDEX', 'FEDEX_ENVELOPE'),
      preset(2, 'CARRIER', 'FedEx Pak', 'FEDEX', 'FEDEX_PAK'),
      preset(3, 'CUSTOM', 'Small Custom Box'),
    ])

    const Drawer = await loadDrawer()
    render(
      <Drawer
        rule={rule(10, 42)}
        service={svc(42, 'FEDEX', 'FEDEX_GROUND')}
        originCountries={['US']}
        initialPresetIds={[]}
        serviceLinks={[]}  // empty pool
        onClose={() => {}}
        onSaved={() => {}}
      />,
    )

    // CUSTOM ("Small Custom Box") visible always.
    await waitFor(() => expect(screen.getByText('Small Custom Box')).toBeInTheDocument())
    // CARRIER presets hidden because service has no Service Catalog links.
    expect(screen.queryByText('FedEx Envelope')).toBeNull()
    expect(screen.queryByText('FedEx Pak')).toBeNull()
  })

  it('CARRIER presets are FILTERED to only those linked in service_package', async () => {
    // FEDEX_2_DAY scenario: V29 links envelope + pak + tube. Drawer
    // should show the linked ones and hide the unlinked (e.g. 10kg box).
    listPresetsMock.mockResolvedValue([
      preset(1, 'CARRIER', 'FedEx Envelope', 'FEDEX', 'FEDEX_ENVELOPE'),
      preset(2, 'CARRIER', 'FedEx Pak', 'FEDEX', 'FEDEX_PAK'),
      preset(4, 'CARRIER', 'FedEx 10kg Box', 'FEDEX', 'FEDEX_10KG_BOX'),
      preset(3, 'CUSTOM', 'Small Custom Box'),
    ])

    const Drawer = await loadDrawer()
    render(
      <Drawer
        rule={rule(10, 42)}
        service={svc(42, 'FEDEX', 'FEDEX_2_DAY')}
        originCountries={['US']}
        initialPresetIds={[]}
        serviceLinks={[
          // Only envelope + pak linked to service 42.
          { serviceId: 42, presetId: 1 },
          { serviceId: 42, presetId: 2 },
          // 10kg (id=4) linked to a DIFFERENT service — must not leak here.
          { serviceId: 99, presetId: 4 },
        ]}
        onClose={() => {}}
        onSaved={() => {}}
      />,
    )

    // Envelope + Pak show because they're linked to service 42.
    await waitFor(() => expect(screen.getByText('FedEx Envelope')).toBeInTheDocument())
    expect(screen.getByText('FedEx Pak')).toBeInTheDocument()
    // 10kg Box is linked to another service, must be hidden here.
    expect(screen.queryByText('FedEx 10kg Box')).toBeNull()
    // CUSTOM always visible.
    expect(screen.getByText('Small Custom Box')).toBeInTheDocument()
  })

  it('serviceLinks omitted (legacy caller) preserves pre-Sprint-52 behavior — no Service Catalog filter', async () => {
    // Backwards-compat pin: if the parent doesn't pass serviceLinks
    // (older caller before this PR), the drawer must skip the 4th
    // filter step entirely and show every CARRIER preset that matches
    // carrier + origin.
    listPresetsMock.mockResolvedValue([
      preset(1, 'CARRIER', 'FedEx Envelope', 'FEDEX', 'FEDEX_ENVELOPE'),
      preset(3, 'CUSTOM', 'Small Custom Box'),
    ])

    const Drawer = await loadDrawer()
    render(
      <Drawer
        rule={rule(10, 42)}
        service={svc(42, 'FEDEX', 'FEDEX_GROUND')}
        originCountries={['US']}
        initialPresetIds={[]}
        // serviceLinks intentionally omitted
        onClose={() => {}}
        onSaved={() => {}}
      />,
    )

    // Both visible because the filter step is skipped.
    await waitFor(() => expect(screen.getByText('FedEx Envelope')).toBeInTheDocument())
    expect(screen.getByText('Small Custom Box')).toBeInTheDocument()
  })
})
