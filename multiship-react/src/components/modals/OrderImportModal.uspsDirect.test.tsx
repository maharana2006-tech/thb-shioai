import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, cleanup } from '@testing-library/react'

/**
 * PR-G4 — OrderImportModal USPS_DIRECT UX coverage (audit U2 + U4).
 *
 * <ol>
 *   <li>{@code BulkLabelQueueBadge} is mounted in the results section
 *       so operators see queue depth without hopping to admin.</li>
 *   <li>Pre-commit warning banner shows ONLY when the platform runs
 *       USPS_DIRECT AND the parsed CSV would take > 1h at the 55/hr
 *       platform cap. Copy contains the USPS row count + estimated
 *       hours so ops can decide whether to proceed.</li>
 *   <li>Banner is absent for STAMPS_COM (existing sync path).</li>
 *   <li>Banner is absent when the USPS row count is below the
 *       ~55-row threshold (would take < 1h).</li>
 * </ol>
 *
 * <p>Every module the modal imports at load-time is mocked at the
 * boundary; the badge's uspsLabelQueueService is stubbed so the badge
 * self-hides in the base case (depth=0) and self-shows when we return
 * a non-zero depth.
 */

// ---------- Mocks ----------

const listStaging = vi.fn()
const getStaging = vi.fn()
const stageUpload = vi.fn()
const saveStaging = vi.fn()
const updateStagingRow = vi.fn()
const downloadStagingErrors = vi.fn()
const downloadXlsxTemplate = vi.fn()
const discardStaging = vi.fn()

vi.mock('../../api/orderImportService', () => ({
  orderImportService: {
    listStaging: (...a: unknown[]) => listStaging(...a),
    getStaging: (...a: unknown[]) => getStaging(...a),
    stageUpload: (...a: unknown[]) => stageUpload(...a),
    saveStaging: (...a: unknown[]) => saveStaging(...a),
    updateStagingRow: (...a: unknown[]) => updateStagingRow(...a),
    downloadStagingErrors: (...a: unknown[]) => downloadStagingErrors(...a),
    downloadXlsxTemplate: (...a: unknown[]) => downloadXlsxTemplate(...a),
    discardStaging: (...a: unknown[]) => discardStaging(...a),
    templateUrl: () => '/orders/import/template',
  },
}))

const notifyInfo = vi.fn()
const notifySuccess = vi.fn()
const notifyError = vi.fn()
const notifyApiError = vi.fn()
const notifyConfirm = vi.fn().mockResolvedValue(false)
vi.mock('../../utils/notify', () => ({
  notify: {
    info: (...a: unknown[]) => notifyInfo(...a),
    success: (...a: unknown[]) => notifySuccess(...a),
    error: (...a: unknown[]) => notifyError(...a),
    apiError: (...a: unknown[]) => notifyApiError(...a),
    confirm: (...a: unknown[]) => notifyConfirm(...a),
  },
  notifyStore: {
    subscribe: vi.fn(() => () => {}),
    snapshot: vi.fn(() => []),
    dismiss: vi.fn(),
  },
}))

vi.mock('../../hooks/useFocusTrap', () => ({
  useFocusTrap: vi.fn(),
}))

// BulkLabelQueueBadge's service — return depth=0 by default so the
// badge is silent; individual tests override to show the pill.
const getMetricsMock = vi.fn().mockResolvedValue({
  data: {
    depth: 0,
    processing: 0,
    estimatedWaitSeconds: 0,
    totalPerHourCap: 55,
    tenantCode: null,
  },
})
vi.mock('../../api/uspsLabelQueueService', () => ({
  uspsLabelQueueService: {
    getMetrics: (...a: unknown[]) => getMetricsMock(...a),
    getMpsProgress: vi.fn(),
  },
}))

vi.mock('../../api/apiClient', () => {
  class ApiError extends Error {
    status: number
    errorCode: string | null
    payload: unknown
    constructor(message: string, status = 500, errorCode: string | null = null, payload: unknown = null) {
      super(message)
      this.name = 'ApiError'
      this.status = status
      this.errorCode = errorCode
      this.payload = payload
    }
  }
  return {
    ApiError,
    isAbortError: (err: unknown) =>
      err != null && typeof err === 'object' && (err as { name?: string }).name === 'AbortError',
    apiClient: {
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    },
  }
})

// systemSettingsService — one call per mount, returns USPS_PROVIDER
// via the stock SystemSetting shape.
const listSettings = vi.fn()
vi.mock('../../api/systemSettingsService', () => ({
  systemSettingsService: {
    list: (...a: unknown[]) => listSettings(...a),
  },
}))

// VirtualTable — stub with a plain div so we don't have to seed
// react-virtual for a simple presence test.
vi.mock('../VirtualTable', () => ({
  default: ({ empty }: { empty?: React.ReactNode }) => <div data-testid="virtual-table-stub">{empty}</div>,
}))

// react-router-dom — OrderImportModal.tsx gained a useNavigate() call in
// PR 95e035be (feat(import): ship via codes beside the upload). The test
// renders the modal without a <Router>, so useNavigate throws. Stub the
// hook to a no-op — navigation isn't exercised in any of these cases.
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => () => undefined }
})

// ---------- Fixtures ----------

/**
 * A staged upload with N USPS rows.
 *
 * PR-G4's banner uses {@code countUspsRows} which normalises the
 * carrierCode via {@link normalizeCarrierCode}; both 'USPS' and legacy
 * aliases like 'L01' / 'STAMPS' / 'ENDICIA' should count.
 */
function stagingWith(uspsRowCount: number, otherRowCount = 0, opts?: { carrierCode?: string }) {
  const carrier = opts?.carrierCode ?? 'USPS'
  const rows: Array<{ rowNumber: number; carrierCode: string; errors: string[] }> = []
  for (let i = 0; i < uspsRowCount; i++) {
    rows.push({ rowNumber: i + 1, carrierCode: carrier, errors: [] })
  }
  for (let i = 0; i < otherRowCount; i++) {
    rows.push({ rowNumber: uspsRowCount + i + 1, carrierCode: 'FEDEX', errors: [] })
  }
  return {
    id: 42,
    fileName: 'test.csv',
    status: 'OPEN' as const,
    totalRows: rows.length,
    validRows: rows.length,
    invalidRows: 0,
    totalOrders: rows.length,
    validOrders: rows.length,
    invalidOrders: 0,
    savedOrders: 0,
    readyOrders: rows.length,
    lastSavedBatchId: null,
    savedRowNumbers: [],
    createdAt: '2026-09-16T12:00:00Z',
    expiresAt: '2026-09-30T12:00:00Z',
    rows,
  }
}

const settingResp = (value: string) => [
  {
    key: 'USPS_PROVIDER',
    hasValue: true,
    maskedValue: value,
    currentValue: value,
    description: 'USPS provider mode',
    kind: 'CHOICE',
    options: ['STAMPS_COM', 'PROVISIONING_USPS_DIRECT', 'USPS_DIRECT'],
  },
]

// ---------- Test harness ----------

async function loadModal() {
  const mod = await import('./OrderImportModal')
  return mod.default
}

beforeEach(() => {
  vi.clearAllMocks()
  listStaging.mockResolvedValue({ data: [] })
  getMetricsMock.mockResolvedValue({
    data: {
      depth: 0,
      processing: 0,
      estimatedWaitSeconds: 0,
      totalPerHourCap: 55,
      tenantCode: null,
    },
  })
  listSettings.mockResolvedValue([])
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

// ==================================================================
// 1. BulkLabelQueueBadge presence
// ==================================================================

describe('OrderImportModal — BulkLabelQueueBadge mount (audit U2)', () => {
  it('mounts the queue-badge slot in the step-2 results section', async () => {
    // Step 2 is reached by getStaging returning a staged upload OR by
    // seeding via listStaging. Simpler: mock listStaging to return the
    // waiting-in-staging list; we then simulate the resume click by
    // constructing the initial state with a preseeded staging.
    // Approach: mock getStaging so `resume(id)` returns a full staging,
    // and reach step 2 by rendering the modal + calling resume through
    // the "Continue" button on the Waiting-in-staging list.
    const staging = stagingWith(1)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })

    const Modal = await loadModal()
    render(<Modal inline />)

    // Waiting-in-staging list appears; click Continue to reach step 2.
    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    // Fire the mounted click. React Testing Library's fireEvent path is
    // cleaner than userEvent for a controlled synchronous handler.
    continueBtn.click()

    // Wait for step 2 to mount + the badge slot to appear.
    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge-slot')).toBeInTheDocument()
    })
  })

  it('badge itself renders visible pill when metrics report a non-zero depth', async () => {
    const staging = stagingWith(1)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    // Non-zero depth so the pill isn't hidden.
    getMetricsMock.mockResolvedValue({
      data: {
        depth: 5,
        processing: 1,
        estimatedWaitSeconds: 60,
        totalPerHourCap: 55,
        tenantCode: null,
      },
    })

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge')).toBeInTheDocument()
    })
    // Pill copy contains the depth.
    expect(screen.getByTestId('usps-queue-badge').textContent).toContain('5')
  })
})

// ==================================================================
// 2. Pre-commit warning banner — SHOWN branch
// ==================================================================

describe('OrderImportModal — USPS_DIRECT rate-limit banner SHOWN (audit U4)', () => {
  it('renders the banner when USPS_PROVIDER=USPS_DIRECT AND USPS row count > 1h threshold', async () => {
    // 60 USPS rows × 65 s/label = 3900 s > 3600 s threshold; > 1 hour.
    const staging = stagingWith(60)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    listSettings.mockResolvedValue(settingResp('USPS_DIRECT'))

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    const banner = await waitFor(() =>
      screen.getByTestId('usps-direct-import-warning'),
    )
    // Copy contains row count + hours estimate.
    const text = banner.textContent ?? ''
    expect(text).toContain('60 USPS')
    expect(text).toMatch(/hour/i)
  })

  it('also fires for USPS-family aliases (STAMPS / L01) — normalizeCarrierCode routes them', async () => {
    // 60 rows carrying carrierCode='L01' (legacy USPS alias) should
    // count as USPS for the banner threshold.
    const staging = stagingWith(60, 0, { carrierCode: 'L01' })
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    listSettings.mockResolvedValue(settingResp('USPS_DIRECT'))

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    const banner = await waitFor(() =>
      screen.getByTestId('usps-direct-import-warning'),
    )
    expect(banner.textContent).toContain('60 USPS')
  })
})

// ==================================================================
// 3. Banner — HIDDEN branches
// ==================================================================

describe('OrderImportModal — USPS_DIRECT banner HIDDEN (correct behaviour)', () => {
  it('does NOT render banner when USPS_PROVIDER=STAMPS_COM (sync path is fine)', async () => {
    const staging = stagingWith(60)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    listSettings.mockResolvedValue(settingResp('STAMPS_COM'))

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    // Wait for step 2 badge slot before asserting banner absence — else
    // we'd assert against the pre-step-2 tree and get a false negative.
    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge-slot')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('usps-direct-import-warning')).toBeNull()
  })

  it('does NOT render banner when USPS_PROVIDER=USPS_DIRECT AND row count < threshold', async () => {
    // 10 USPS rows × 65 s = 650 s < 3600 s threshold.
    const staging = stagingWith(10)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    listSettings.mockResolvedValue(settingResp('USPS_DIRECT'))

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge-slot')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('usps-direct-import-warning')).toBeNull()
  })

  it('does NOT render banner when USPS_PROVIDER is unavailable (non-admin: 403 silent)', async () => {
    const staging = stagingWith(1000)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    // systemSettings 403s (non-admin) — banner stays hidden.
    listSettings.mockRejectedValue(
      Object.assign(new Error('403'), { status: 403 }),
    )

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge-slot')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('usps-direct-import-warning')).toBeNull()
  })

  it('does NOT render banner when USPS row count is 0 (non-USPS carriers only)', async () => {
    // 60 FEDEX rows, zero USPS.
    const staging = stagingWith(0, 60)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    listSettings.mockResolvedValue(settingResp('USPS_DIRECT'))

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    await waitFor(() => {
      expect(screen.getByTestId('usps-queue-badge-slot')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('usps-direct-import-warning')).toBeNull()
  })
})

// ==================================================================
// 4. Banner copy sanity
// ==================================================================

describe('OrderImportModal — banner copy sanity', () => {
  it('banner mentions the 55/hr platform cap so operator sees the "why"', async () => {
    const staging = stagingWith(60)
    listStaging.mockResolvedValue({ data: [staging] })
    getStaging.mockResolvedValue({ data: staging })
    listSettings.mockResolvedValue(settingResp('USPS_DIRECT'))

    const Modal = await loadModal()
    render(<Modal inline />)

    const continueBtn = await screen.findByRole('button', { name: /Continue/i })
    continueBtn.click()

    const banner = await waitFor(() =>
      screen.getByTestId('usps-direct-import-warning'),
    )
    expect(banner.textContent).toMatch(/55 labels\/hour/i)
    expect(banner.textContent).toMatch(/Data History/i)
  })
})
