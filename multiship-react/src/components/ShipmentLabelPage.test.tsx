import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { screen, cleanup, waitFor, fireEvent } from '@testing-library/react'
import { renderWithProviders } from '../test/renderWithProviders'
import type { LabelArtifact } from '../api/labelService'

/**
 * Phase 2 gate — the new Shipment & Label page: ready / empty /
 * raster-badge / multi-package tab switching, and the Copy ZPL fallback
 * when the browser refuses clipboard access.
 */

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useParams: () => ({ orderNo: '900058' }),
    useNavigate: () => vi.fn(),
  }
})

const describeMock = vi.fn()
const packageZpl = vi.fn()
const allZpl = vi.fn()
const getPdf = vi.fn()

vi.mock('../api/labelService', () => ({
  labelService: {
    describe: (...args: unknown[]) => describeMock(...args),
    packageZpl: (...args: unknown[]) => packageZpl(...args),
    allZpl: (...args: unknown[]) => allZpl(...args),
    getPdf: (...args: unknown[]) => getPdf(...args),
    previewPngUrl: (orderNo: number, seq: number) => `/api/v1/labels/${orderNo}/packages/${seq}/preview.png`,
    pdfUrl: (orderNo: number) => `/api/v1/labels/${orderNo}/pdf`,
    headPreview: vi.fn().mockResolvedValue(true),
  },
}))

const notifySuccess = vi.fn()
const notifyInfo = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...a: unknown[]) => notifySuccess(...a),
    info: (...a: unknown[]) => notifyInfo(...a),
    error: vi.fn(),
    apiError: vi.fn(),
  },
}))

import ShipmentLabelPage from './ShipmentLabelPage'

function artifact(overrides: Partial<LabelArtifact> = {}): LabelArtifact {
  return {
    orderNo: 900058,
    state: 'GENERATED',
    carrier: 'UPS',
    environment: 'SANDBOX',
    generatedAt: '2026-10-22T11:19:39',
    packages: [{ seq: 1, format: 'ZPL', source: 'CARRIER_NATIVE', tracking: '1Z999AA10123456784', trackingUrl: null, generatedAt: null }],
    hasCommercialInvoice: false,
    ...overrides,
  }
}

function respond(a: LabelArtifact) {
  describeMock.mockResolvedValue({ status: 'SUCCESS', code: 200, message: '', timestamp: '', data: a })
}

describe('ShipmentLabelPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  afterEach(() => {
    cleanup()
  })

  it('ready: shows the order, a Native ZPL badge, the sandbox pill, and one preview image of the active package', async () => {
    respond(artifact())
    renderWithProviders(<ShipmentLabelPage />)

    expect(await screen.findByText('Order #900058')).toBeInTheDocument()
    expect(screen.getByText('Native ZPL')).toBeInTheDocument()
    expect(screen.getByText('SANDBOX')).toBeInTheDocument()
    const img = screen.getByRole('img', { name: /Shipping label for order 900058, package 1 of 1/ })
    expect(img).toHaveAttribute('src', '/api/v1/labels/900058/packages/1/preview.png')
    // The old page's facsimile is gone: exactly one preview image.
    expect(screen.getAllByRole('img')).toHaveLength(1)
  })

  it('empty: an ungenerated order gets an honest "No label yet" state, not a synthetic label', async () => {
    respond(artifact({ state: 'PENDING', packages: [] }))
    renderWithProviders(<ShipmentLabelPage />)

    expect(await screen.findByText('No label yet')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Generate label' })).toBeInTheDocument()
  })

  it('raster: a carrier image label is badged as an image label, from the source column', async () => {
    respond(artifact({ packages: [{ seq: 1, format: 'GIF', source: 'CARRIER_RASTER', tracking: null, trackingUrl: null, generatedAt: null }] }))
    renderWithProviders(<ShipmentLabelPage />)

    expect(await screen.findByText('Image label (GIF)')).toBeInTheDocument()
    expect(screen.queryByText('Native ZPL')).not.toBeInTheDocument()
  })

  it('multi-package: tabs switch the preview to that package and overlay PKG n OF m', async () => {
    respond(
      artifact({
        packages: [
          { seq: 1, format: 'ZPL', source: 'CARRIER_NATIVE', tracking: 'T1', trackingUrl: null, generatedAt: null },
          { seq: 2, format: 'ZPL', source: 'CARRIER_NATIVE', tracking: 'T2', trackingUrl: null, generatedAt: null },
        ],
      }),
    )
    renderWithProviders(<ShipmentLabelPage />)

    await screen.findByText('Order #900058')
    expect(screen.getByRole('img', { name: /package 1 of 2/ })).toHaveAttribute(
      'src',
      '/api/v1/labels/900058/packages/1/preview.png',
    )
    expect(screen.getByText('PKG 1 OF 2')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'PKG 2 of 2' }))

    await waitFor(() =>
      expect(screen.getByRole('img', { name: /package 2 of 2/ })).toHaveAttribute(
        'src',
        '/api/v1/labels/900058/packages/2/preview.png',
      ),
    )
    expect(screen.getByText('PKG 2 OF 2')).toBeInTheDocument()
    expect(screen.getByText('T2')).toBeInTheDocument()
  })

  it('Copy ZPL: copies the active package ZPL, and when the clipboard is refused exposes it selected instead of only erroring', async () => {
    respond(artifact())
    packageZpl.mockResolvedValue('^XA^FDhi^FS^XZ')
    const writeText = vi.fn().mockRejectedValue(new DOMException('denied', 'NotAllowedError'))
    Object.assign(navigator, { clipboard: { writeText } })
    renderWithProviders(<ShipmentLabelPage />)

    fireEvent.click(await screen.findByRole('button', { name: 'Copy ZPL' }))

    await waitFor(() => expect(packageZpl).toHaveBeenCalledWith(900058, 1))
    const box = (await screen.findByLabelText(/ZPL \(selected/)) as HTMLTextAreaElement
    expect(box.value).toBe('^XA^FDhi^FS^XZ')
    expect(notifyInfo).toHaveBeenCalled()
    expect(notifySuccess).not.toHaveBeenCalled()

    // And when the clipboard works, it is a plain success with no textarea.
    writeText.mockResolvedValue(undefined)
    fireEvent.click(screen.getByRole('button', { name: 'Copy ZPL' }))
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled())
    expect(screen.queryByLabelText(/ZPL \(selected/)).not.toBeInTheDocument()
  })
})
