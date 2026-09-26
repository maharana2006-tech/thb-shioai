import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup, within } from '@testing-library/react'
// `within` used below for scoping button/checkbox queries inside the drawer.
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom'
import type { ConnectionDetail, ConnectionSummary, ConnectorSummary } from '../api/externalSystemsService'

/**
 * V89 — writeback flags coverage for /settings/external-systems.
 *
 * <p>Only one scenario matters for the ponytail scope: an admin clicks
 * each of the six writeback checkboxes and hits Save; the outgoing
 * PUT MUST carry the correct boolean matrix. Everything else on the
 * page is already covered by the existing S3 wire-up (edit-drawer
 * tabs, health, secrets, overrides).
 */

// ── Mocks ─────────────────────────────────────────────────────────

const listMock = vi.fn()
const listConnectorsMock = vi.fn()
const getMock = vi.fn()
const updateMock = vi.fn()

vi.mock('../api/externalSystemsService', () => ({
  externalSystemsService: {
    list: (...a: unknown[]) => listMock(...a),
    listConnectors: (...a: unknown[]) => listConnectorsMock(...a),
    get: (...a: unknown[]) => getMock(...a),
    update: (...a: unknown[]) => updateMock(...a),
    create: vi.fn(),
    delete: vi.fn(),
    putSecret: vi.fn(),
    listClientOverrides: vi.fn().mockResolvedValue([]),
    putClientOverride: vi.fn(),
    deleteClientOverride: vi.fn(),
    health: vi.fn(),
    testConnection: vi.fn(),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: {
    success: vi.fn(),
    error: vi.fn(),
    apiError: vi.fn(),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// Route the page under a MemoryRouter with an Outlet supplying the
// SettingsOutletContext shape the page reads via useOutletContext.
async function renderPage() {
  const { default: ExternalSystemsPage } = await import('./ExternalSystemsPage')
  render(
    <MemoryRouter>
      <Routes>
        <Route
          element={
            <Outlet
              context={{ registerRefresh: () => undefined }}
            />
          }
        >
          <Route path="/" element={<ExternalSystemsPage />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

// ── Fixtures ──────────────────────────────────────────────────────

const CONN_SUMMARY: ConnectionSummary = {
  id: 42,
  name: 'nds-default',
  systemType: 'NDS_ORACLE',
  active: true,
  updatedAt: '2026-09-26T10:00:00Z',
  updatedBy: 'admin',
}

const CONN_DETAIL: ConnectionDetail = {
  id: 42,
  name: 'nds-default',
  systemType: 'NDS_ORACLE',
  active: true,
  configJson: '{}',
  createdAt: '2026-09-26T10:00:00Z',
  updatedAt: '2026-09-26T10:00:00Z',
  updatedBy: 'admin',
  writebackTracking: false,
  writebackShipDate: false,
  writebackStatus: false,
  writebackCarrier: false,
  writebackService: false,
  writebackFreight: false,
  writebackSourceManual: true,
  writebackSourceBulk: true,
  writebackSourceApi: true,
  writebackChannelD2c: true,
  writebackChannelB2b: true,
}

const CONNECTORS: ConnectorSummary[] = [
  { systemType: 'NDS_ORACLE', configType: 'NdsOracleConfig' },
]

beforeEach(() => {
  listMock.mockResolvedValue([CONN_SUMMARY])
  listConnectorsMock.mockResolvedValue(CONNECTORS)
  getMock.mockResolvedValue(CONN_DETAIL)
  updateMock.mockResolvedValue(CONN_DETAIL)
})

afterEach(() => {
  vi.clearAllMocks()
  cleanup()
})

// ── Test ──────────────────────────────────────────────────────────

describe('ExternalSystemsPage — V89 writeback flags', () => {
  it('sends the six writeback booleans on save when every checkbox is toggled on', async () => {
    const user = userEvent.setup()
    await renderPage()

    // Wait for the initial list to render + click Edit on the row.
    await waitFor(() => expect(screen.getByText('nds-default')).toBeInTheDocument())
    await user.click(screen.getByTitle('Edit'))

    // Drawer opens; click Writeback tab.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Writeback' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Writeback' }))

    // Toggle every checkbox — target by DOM id set on the input inside
    // the label. Accessible-name matching would collide with the
    // longer description text next to each checkbox.
    const drawer = screen.getByRole('dialog')
    for (const id of ['wb-tracking', 'wb-shipDate', 'wb-status', 'wb-carrier', 'wb-service', 'wb-freight']) {
      const cb = drawer.querySelector<HTMLInputElement>(`#${id}`)
      if (!cb) throw new Error(`no checkbox with id=${id}`)
      await user.click(cb)
    }

    // Save.
    await user.click(within(drawer).getByRole('button', { name: /^Save$/i }))

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1))
    expect(updateMock).toHaveBeenCalledWith(42, expect.objectContaining({
      writebackTracking: true,
      writebackShipDate: true,
      writebackStatus: true,
      writebackCarrier: true,
      writebackService: true,
      writebackFreight: true,
    }))
  })

  it('sends only the flags the admin turned on', async () => {
    const user = userEvent.setup()
    await renderPage()

    await waitFor(() => expect(screen.getByText('nds-default')).toBeInTheDocument())
    await user.click(screen.getByTitle('Edit'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Writeback' })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Writeback' }))

    const drawer = screen.getByRole('dialog')
    // Only tracking + carrier on — target by input id (see above).
    for (const id of ['wb-tracking', 'wb-carrier']) {
      const cb = drawer.querySelector<HTMLInputElement>(`#${id}`)
      if (!cb) throw new Error(`no checkbox with id=${id}`)
      await user.click(cb)
    }

    await user.click(within(drawer).getByRole('button', { name: /^Save$/i }))

    await waitFor(() => expect(updateMock).toHaveBeenCalledTimes(1))
    expect(updateMock).toHaveBeenCalledWith(42, expect.objectContaining({
      writebackTracking: true,
      writebackShipDate: false,
      writebackStatus: false,
      writebackCarrier: true,
      writebackService: false,
      writebackFreight: false,
    }))
  })
})

