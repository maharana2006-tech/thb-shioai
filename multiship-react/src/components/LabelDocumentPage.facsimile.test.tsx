import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { screen, cleanup, waitFor } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'

/**
 * LabelDocumentPage — JSX facsimile fallback (BUG-001).
 *
 * <p>When the carrier PNG probe and printable PDF both come back
 * unavailable, the page falls back to the JSX "label facsimile" renderer.
 * Pre-BUG-001 that renderer rendered {@code payload.shipper} verbatim,
 * which the backend resolves to the client-warehouse / platform default
 * whenever the order itself has no per-shipment ship-from captured.
 * Result: operators saw the Bengaluru platform default on a shipment
 * clearly tagged with a Zeeland, MI warehouse address, and REF filled
 * with the order number regardless of whether the operator entered one.
 *
 * <p>Fix: when any {@code order.shipFrom*} field is set, the facsimile
 * MUST render those values (not {@code payload.shipper}). REF reflects
 * {@code order.customerRef}, with an em-dash when blank — never the
 * order number silently.
 */

// Mock react-router so useParams returns a stable orderNo without a route.
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useParams: () => ({ orderNo: '934201' }),
    useNavigate: () => vi.fn(),
    useSearchParams: () => [new URLSearchParams(), vi.fn()],
  }
})

const getLabelDocument = vi.fn()
const getLabelPdf = vi.fn()
const headLabelPreviewPng = vi.fn()
const getCommercialInvoicePdf = vi.fn()
const labelPreviewPngUrl = vi.fn()

vi.mock('../api/orderService', () => ({
  orderService: {
    getLabelDocument: (...a: unknown[]) => getLabelDocument(...a),
    getLabelPdf: (...a: unknown[]) => getLabelPdf(...a),
    headLabelPreviewPng: (...a: unknown[]) => headLabelPreviewPng(...a),
    getCommercialInvoicePdf: (...a: unknown[]) => getCommercialInvoicePdf(...a),
    labelPreviewPngUrl: (...a: unknown[]) => labelPreviewPngUrl(...a),
    getLabelZpl: vi.fn(),
    getShipmentDocumentsPdf: vi.fn(),
  },
}))

vi.mock('../utils/notify', () => ({
  notify: { success: vi.fn(), error: vi.fn(), info: vi.fn(), apiError: vi.fn() },
}))

vi.mock('../hooks/useAppSession', () => ({
  useAppSession: () => ({ username: 'tester', role: 'ADMIN', connectedCarriers: [], hasConnectedCarrier: false }),
}))

// pdfjs-dist pulls DOMMatrix/OffscreenCanvas which jsdom lacks; the
// facsimile branch doesn't render PdfPagesPreview anyway (printableState
// is 'unavailable') so a bare stub keeps the module graph loadable.
vi.mock('./PdfPagesPreview', () => ({ default: () => null }))
vi.mock('./modals/PrintPreviewModal', () => ({ default: () => null }))
vi.mock('./tracking/TrackingTimelineModal', () => ({ default: () => null }))

import LabelDocumentPage from './LabelDocumentPage'

const mklOrder = {
  // Shipment captured sender — the real MKL warehouse in Zeeland, MI.
  shipFromName: 'MKL',
  shipFromCompany: null,
  shipFromAddr1: '8191 Logistics Dr',
  shipFromAddr2: null,
  shipFromCity: 'Zeeland',
  shipFromState: 'MI',
  shipFromZip: '49464',
  shipFromCountryCd: 'US',
  shipFromPhone: '6165551212',
  shipFromResolved: false,

  // Recipient (needed so the TO block renders without crashing).
  shipName: 'Jane Doe',
  shipAttn: null,
  shipAddr1: '1 Main St',
  shipAddr2: null,
  shiptoCity: 'Austin',
  shiptoState: 'TX',
  shiptoZip: '78701',
  shiptoCountryCd: 'US',
  phone: '5125550123',

  // Order fields consumed on the facsimile.
  orderNo: 934201,
  displayOrderNo: '934201',
  customerRef: 'MKL246',          // user-entered reference — REF slot source of truth.
  customerReferenceId: 'MKL',     // tenant code / DEPT slot.
  custNo: 'MKL',
  tenantId: 'MKL',
  source: 'MANUAL',
  shipviaCd: 'FEDEX_GROUND',
  weight: 2.5,
  weightUnit: 'LB',
  goodsDesc: null,
  createdDate: '2026-10-17T12:00:00Z',
  orderLines: [],
  orderStatus: 'LABELED',
}

// payload.shipper here mimics the BACKEND behaviour of resolving to the
// platform default when per-order shipFrom isn't threaded. The fix must
// IGNORE this and read from order.shipFrom* instead.
const platformDefaultShipper = {
  name: 'Multiship Warehouse',
  company: null,
  phone: '0000000000',
  addressLine1: '1 Warehouse Way',
  addressLine2: null,
  city: 'Bengaluru',
  state: 'KA',
  postalCode: '560001',
  countryCode: 'IN',
}

beforeEach(() => {
  getLabelDocument.mockResolvedValue({
    data: {
      order: mklOrder,
      label: { trackingNumber: '794600000001', generatedAt: '2026-10-17T12:00:00Z' },
      shipper: platformDefaultShipper,
      resolution: { scenario: 'CLIENT_OWN', carrierCode: 'FEDEX', accountNumber: '123', accountName: 'MKL', environment: 'PRODUCTION' },
      isReturn: false,
      international: false,
      service: { carrier: 'FEDEX', code: 'FEDEX_GROUND', name: 'Ground' },
    },
  })
  // Force BOTH the printable-PDF route and the carrier-PNG probe to miss,
  // so the facsimile branch renders.
  getLabelPdf.mockRejectedValue(new Error('no label pdf'))
  headLabelPreviewPng.mockResolvedValue(false)
  labelPreviewPngUrl.mockReturnValue('about:blank')
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('LabelDocumentPage facsimile (BUG-001)', () => {
  it('renders the per-order Ship-From, not the platform default (no Bengaluru)', async () => {
    renderWithProviders(<LabelDocumentPage />)

    // Wait for the facsimile to appear — SIGN: {shipper.name} is unique to
    // the fallback header block.
    await waitFor(() => expect(screen.getByText(/SIGN:\s*MKL/i)).toBeInTheDocument())

    // Per-order sender rendered.
    expect(screen.getByText(/8191 Logistics Dr/)).toBeInTheDocument()
    expect(screen.getByText(/Zeeland, MI 49464 US/)).toBeInTheDocument()

    // Platform default suppressed.
    expect(screen.queryByText(/Bengaluru/)).toBeNull()
    expect(screen.queryByText(/Warehouse Way/)).toBeNull()
    expect(screen.queryByText(/Multiship Warehouse/)).toBeNull()
  })

  it('REF slot uses the user-entered customerRef, not the order number', async () => {
    renderWithProviders(<LabelDocumentPage />)
    await waitFor(() => expect(screen.getByText(/REF:\s*MKL246/)).toBeInTheDocument())
    // Order number must not show up in the REF slot (it still appears in
    // PO/CAD, which is correct per PR #543).
    const refEl = screen.getByText(/REF:/)
    expect(refEl.textContent).toContain('MKL246')
    expect(refEl.textContent).not.toContain('934201')
  })

  it('REF falls back to em-dash (never the order number silently) when no reference is entered', async () => {
    getLabelDocument.mockResolvedValueOnce({
      data: {
        order: { ...mklOrder, customerRef: null },
        label: { trackingNumber: '794600000001', generatedAt: '2026-10-17T12:00:00Z' },
        shipper: platformDefaultShipper,
        resolution: { scenario: 'CLIENT_OWN', carrierCode: 'FEDEX', accountNumber: '123', accountName: 'MKL', environment: 'PRODUCTION' },
        isReturn: false,
        international: false,
        service: { carrier: 'FEDEX', code: 'FEDEX_GROUND', name: 'Ground' },
      },
    })
    renderWithProviders(<LabelDocumentPage />)
    await waitFor(() => expect(screen.getByText(/REF:\s*—/)).toBeInTheDocument())
    const refEl = screen.getByText(/REF:/)
    expect(refEl.textContent).not.toContain('934201')
  })

  it('falls back to the resolved payload.shipper when no per-order ship-from is captured', async () => {
    // Bulk/ERP path: order has no shipFrom* at all. The facsimile should
    // then render payload.shipper (even if that's the platform default) —
    // the fix is "prefer per-order", not "always ignore shipper".
    getLabelDocument.mockResolvedValueOnce({
      data: {
        order: {
          ...mklOrder,
          shipFromName: null, shipFromCompany: null, shipFromPhone: null,
          shipFromAddr1: null, shipFromAddr2: null, shipFromCity: null,
          shipFromState: null, shipFromZip: null, shipFromCountryCd: null,
        },
        label: { trackingNumber: '794600000001', generatedAt: '2026-10-17T12:00:00Z' },
        shipper: platformDefaultShipper,
        resolution: { scenario: 'CLIENT_OWN', carrierCode: 'FEDEX', accountNumber: '123', accountName: 'MKL', environment: 'PRODUCTION' },
        isReturn: false,
        international: false,
        service: { carrier: 'FEDEX', code: 'FEDEX_GROUND', name: 'Ground' },
      },
    })
    renderWithProviders(<LabelDocumentPage />)
    // "Multiship Warehouse" appears both in the ORIGIN block and the SIGN: line;
    // use getAllByText to allow both matches.
    await waitFor(() => expect(screen.getAllByText(/Multiship Warehouse/).length).toBeGreaterThanOrEqual(1))
    expect(screen.getByText(/Bengaluru, KA 560001 IN/)).toBeInTheDocument()
  })
})
