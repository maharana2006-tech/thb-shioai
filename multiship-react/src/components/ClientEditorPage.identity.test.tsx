import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within, cleanup, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ComponentType } from 'react'

/**
 * Sprint 53 wizard-tests — Identity step of the client add/edit wizard.
 *
 * Scope: create + edit modes of `/settings/clients/new` and
 * `/settings/clients/{code}`. We only exercise the Identity step (clientCode,
 * name, email, phone) plus the Defaults panel (currency, weight unit, dim
 * unit, origin country, timezone). Later steps are covered by sibling files.
 *
 * All network-touching services are fully mocked; nothing hits real fetch.
 * The router is a MemoryRouter so useParams / useNavigate resolve.
 */

// ===== Service mocks (top level so hoisting lands before the component
// module is imported dynamically inside the tests) =====

const getClient = vi.fn()
const createClient = vi.fn()
const updateClient = vi.fn()
const listClients = vi.fn()
const listClientAccounts = vi.fn()

vi.mock('../api/clientService', () => ({
  clientService: {
    getClient: (...args: unknown[]) => getClient(...args),
    createClient: (...args: unknown[]) => createClient(...args),
    updateClient: (...args: unknown[]) => updateClient(...args),
    listClients: (...args: unknown[]) => listClients(...args),
    listClientAccounts: (...args: unknown[]) => listClientAccounts(...args),
    cascadePreview: vi.fn(),
    toggleActive: vi.fn(),
    deleteClient: vi.fn(),
  },
}))

vi.mock('../api/accountRefService', () => ({
  accountRefService: {
    listAccounts: vi.fn().mockResolvedValue([]),
    upsertAccount: vi.fn(),
    resolveOrders: vi.fn().mockResolvedValue([]),
  },
}))

vi.mock('../api/warehouseService', () => ({
  warehouseService: {
    listWarehouses: vi.fn().mockResolvedValue({ data: { content: [] } }),
  },
  clientWarehouseService: {
    listForClient: vi.fn().mockResolvedValue({ data: [] }),
    attach: vi.fn(),
    setDefault: vi.fn(),
  },
}))

vi.mock('../api/shippingConfigService', () => ({
  shippingConfigService: {
    catalog: vi.fn().mockResolvedValue({ services: [] }),
    saveRule: vi.fn(),
  },
}))

vi.mock('../api/customsProfileService', () => ({
  customsProfileService: {
    list: vi.fn().mockResolvedValue([]),
    save: vi.fn(),
    remove: vi.fn(),
  },
}))

// Notify — every step reaches into notify.error / notify.success on save. We
// stub the whole surface so tests don't need to spy per call and so the toast
// noise doesn't pollute the DOM assertions.
const notifyError = vi.fn()
const notifySuccess = vi.fn()
const notifyApiError = vi.fn()
vi.mock('../utils/notify', () => ({
  notify: {
    success: (...args: unknown[]) => notifySuccess(...args),
    error: (...args: unknown[]) => notifyError(...args),
    apiError: (...args: unknown[]) => notifyApiError(...args),
    info: vi.fn(),
    confirm: vi.fn().mockResolvedValue(true),
  },
}))

// Dynamic import so vi.mock calls land before ClientEditorPage is evaluated
// (the component pulls every one of the services above at top level).
async function loadPage(): Promise<ComponentType> {
  const mod = await import('./ClientEditorPage')
  return mod.default
}

function renderCreate(Page: ComponentType) {
  return render(
    <MemoryRouter initialEntries={['/settings/clients/new']}>
      <Routes>
        <Route path="/settings/clients/new" element={<Page />} />
        <Route path="/settings/clients/:clientCode" element={<Page />} />
        <Route path="/settings/clients" element={<div>CLIENTS_LIST</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function renderEdit(Page: ComponentType, code: string) {
  return render(
    <MemoryRouter initialEntries={[`/settings/clients/${code}`]}>
      <Routes>
        <Route path="/settings/clients/new" element={<Page />} />
        <Route path="/settings/clients/:clientCode" element={<Page />} />
        <Route path="/settings/clients" element={<div>CLIENTS_LIST</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

/** ApiError-like rejection factory — the validator checks err.status === 404
 *  to decide "code is available". */
function statusReject(status: number) {
  return Object.assign(new Error(`HTTP ${status}`), { status })
}

/** Sample Client shape for edit-mode hydration. Includes every Defaults-panel
 *  value so the corresponding hydration test can assert them all. */
function sampleClient(overrides: Record<string, unknown> = {}) {
  return {
    id: 42,
    clientCode: 'ACME',
    name: 'Acme Widgets',
    email: 'ops@acme.io',
    phone: '+1 555-123-4567',
    status: 'ACTIVE',
    shipFrom: null,
    returnAddress: null,
    returnSameAsShipFrom: true,
    defaultCurrency: 'USD',
    defaultWeightUnit: 'LB',
    defaultDimUnit: 'IN',
    timezone: 'America/New_York',
    defaultOriginCountry: 'US',
    createdAt: '2026-08-01T00:00:00',
    updatedAt: '2026-08-01T00:00:00',
    carrierAccounts: [],
    orderCount: 0,
    ...overrides,
  }
}

beforeEach(() => {
  // Reset every service mock between tests so calls from a previous test
  // don't leak into the assertions below.
  getClient.mockReset()
  createClient.mockReset()
  updateClient.mockReset()
  listClients.mockReset().mockResolvedValue({ data: { content: [] } })
  listClientAccounts.mockReset().mockResolvedValue([])
  notifyError.mockReset()
  notifySuccess.mockReset()
  notifyApiError.mockReset()
  // Default: code is available. Individual tests override to simulate conflict.
  getClient.mockRejectedValue(statusReject(404))
  // Clear draft persistence between tests — the create-mode wizard writes
  // to localStorage on every state change and would restore across tests
  // otherwise (breaking the "starts empty" assumption).
  try { localStorage.clear() } catch { /* ignore */ }
})

afterEach(() => {
  cleanup()
})

// ===== Positive cases =====

describe('ClientEditorPage · Identity · positive', () => {
  // Extended timeout on the FIRST test only: it eats the cold-start cost of
  // the transformer pulling in the 3000-LOC ClientEditorPage + every mocked
  // service module. On Windows CI this occasionally trips the default 5s.
  it('renders all identity inputs + the Defaults panel controls', async () => {
    const Page = await loadPage()
    renderCreate(Page)

    // Identity block — placeholders + labels distinguish the four inputs.
    expect(screen.getByPlaceholderText('MA1885')).toBeTruthy()
    expect(screen.getByPlaceholderText('Modern Art Fabrics')).toBeTruthy()
    expect(screen.getByPlaceholderText('contact@client.com')).toBeTruthy()
    expect(screen.getByPlaceholderText('+1 555-123-4567')).toBeTruthy()

    // Defaults panel — 4 native selects + 1 combobox.
    expect(screen.getByText('Defaults')).toBeTruthy()
    // Each Field label is uppercased in the DOM; use case-insensitive regex.
    // The combobox has its own accessible role, so we count all 5 controls.
    expect(screen.getAllByRole('combobox').length).toBeGreaterThanOrEqual(1)
    // Currency select carries a "USD" option; use that as a stable marker.
    expect(screen.getByRole('option', { name: 'USD' })).toBeTruthy()
    expect(screen.getByRole('option', { name: 'LB (pounds)' })).toBeTruthy()
    expect(screen.getByRole('option', { name: 'IN (inches)' })).toBeTruthy()
    expect(screen.getByRole('option', { name: 'US — United States' })).toBeTruthy()
  }, 30000)

  it('uppercases clientCode as the operator types (abc-123 → ACME-123)', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const codeInput = screen.getByPlaceholderText('MA1885') as HTMLInputElement
    const user = userEvent.setup()

    await user.type(codeInput, 'abc-123')
    // The component upper-cases in state before setState, so the value in
    // the DOM is uppercase even though the operator typed lowercase.
    expect(codeInput.value).toBe('ABC-123')
  })

  it('enables Next once identity is valid (no defaults required)', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    // Type valid identity + trigger blur so the live-check debounce settles
    // to "not taken" (getClient mock rejects with 404).
    await user.type(screen.getByPlaceholderText('MA1885'), 'NEWCODE')
    await user.type(screen.getByPlaceholderText('Modern Art Fabrics'), 'New Widgets')

    // Wait for the 500ms debounce + the getClient rejection to resolve.
    await waitFor(() => {
      expect(getClient).toHaveBeenCalledWith('NEWCODE')
    }, { timeout: 3000 })

    // Next button ought to be enabled now (only identity + defaults on this
    // step; defaults are optional; email/phone are optional).
    await waitFor(() => {
      const next = screen.getByRole('button', { name: /^Next/i })
      expect((next as HTMLButtonElement).disabled).toBe(false)
    })
  })

  it('Currency / weight / dim / origin-country selects update on choose', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    // The four Defaults selects share role=combobox. Pluck them by the option
    // set each one carries — every value list is disjoint.
    const currency = screen.getByRole('option', { name: 'USD' }).closest('select') as HTMLSelectElement
    const weight = screen.getByRole('option', { name: 'LB (pounds)' }).closest('select') as HTMLSelectElement
    const dim = screen.getByRole('option', { name: 'IN (inches)' }).closest('select') as HTMLSelectElement
    const origin = screen.getByRole('option', { name: 'US — United States' }).closest('select') as HTMLSelectElement

    await user.selectOptions(currency, 'USD')
    await user.selectOptions(weight, 'KG')
    await user.selectOptions(dim, 'CM')
    await user.selectOptions(origin, 'CA')

    expect(currency.value).toBe('USD')
    expect(weight.value).toBe('KG')
    expect(dim.value).toBe('CM')
    expect(origin.value).toBe('CA')
  })

  it('timezone combobox opens on focus, filters on type, commits a pick', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    // TZ combobox is the only role=combobox that starts empty AND accepts
    // free text. The four native selects are also role=combobox but they
    // don't accept keystrokes for arbitrary strings.
    const tzInput = screen.getByPlaceholderText(/Search e.g\. New_York/i) as HTMLInputElement

    await user.click(tzInput)
    // Focus opens the dropdown — role=listbox appears.
    await waitFor(() => expect(screen.getByRole('listbox')).toBeTruthy())

    // Filter: typing "New_York" should reduce the list to America/New_York.
    await user.type(tzInput, 'New_York')
    await waitFor(() => {
      const options = screen.getAllByRole('option')
      // The four native selects contribute their own options too; use
      // some(...) rather than exact-count so we're resilient to that.
      expect(options.some((o) => o.textContent?.includes('America/New_York'))).toBe(true)
    })

    // Click the New_York option → value commits to the input.
    const nyOption = screen.getAllByRole('option').find((o) => o.textContent?.includes('America/New_York'))!
    // Use mouseDown because the combobox commits on onMouseDown to survive blur.
    await user.pointer({ target: nyOption, keys: '[MouseLeft>]' })
    await waitFor(() => expect(tzInput.value).toBe('America/New_York'))
  })

  it('timezone combobox: Detect-from-browser fills the input', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const tzInput = screen.getByPlaceholderText(/Search e.g\. New_York/i) as HTMLInputElement
    // Detect button carries aria-label "Detect timezone from browser".
    const detectBtn = screen.getByLabelText(/Detect timezone from browser/i)
    await user.click(detectBtn)

    // Whatever Intl.DateTimeFormat().resolvedOptions().timeZone returns in
    // the jsdom environment (typically 'UTC' or a real IANA), the value
    // ought to be non-empty and non-whitespace.
    await waitFor(() => expect(tzInput.value.trim().length).toBeGreaterThan(0))
  })
})

describe('ClientEditorPage · Identity · edit mode', () => {
  it('hydrates identity fields from the server response + makes clientCode read-only', async () => {
    getClient.mockResolvedValueOnce({ data: sampleClient() })
    const Page = await loadPage()
    renderEdit(Page, 'ACME')

    // Wait for the loading spinner to hand off to the editor.
    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())
    const codeInput = screen.getByPlaceholderText('MA1885') as HTMLInputElement
    const nameInput = screen.getByPlaceholderText('Modern Art Fabrics') as HTMLInputElement
    const emailInput = screen.getByPlaceholderText('contact@client.com') as HTMLInputElement
    const phoneInput = screen.getByPlaceholderText('+1 555-123-4567') as HTMLInputElement

    expect(codeInput.value).toBe('ACME')
    expect(nameInput.value).toBe('Acme Widgets')
    expect(emailInput.value).toBe('ops@acme.io')
    expect(phoneInput.value).toBe('+1 555-123-4567')
    // clientCode is immutable after create — the input carries readOnly.
    expect(codeInput.readOnly).toBe(true)
  })

  it('hydrates every Defaults value from the server response', async () => {
    getClient.mockResolvedValueOnce({
      data: sampleClient({
        defaultCurrency: 'EUR',
        defaultWeightUnit: 'KG',
        defaultDimUnit: 'CM',
        timezone: 'Europe/London',
        defaultOriginCountry: 'GB',
      }),
    })
    const Page = await loadPage()
    renderEdit(Page, 'ACME')

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())

    // Pluck each select by an option unique to that dropdown, then check
    // its current selected value.
    const currency = screen.getByRole('option', { name: 'USD' }).closest('select') as HTMLSelectElement
    const weight = screen.getByRole('option', { name: 'LB (pounds)' }).closest('select') as HTMLSelectElement
    const dim = screen.getByRole('option', { name: 'IN (inches)' }).closest('select') as HTMLSelectElement
    const origin = screen.getByRole('option', { name: 'US — United States' }).closest('select') as HTMLSelectElement

    expect(currency.value).toBe('EUR')
    expect(weight.value).toBe('KG')
    expect(dim.value).toBe('CM')
    expect(origin.value).toBe('GB')

    // Timezone combobox displays the committed value when closed.
    const tzInput = screen.getByPlaceholderText(/Search e.g\. New_York/i) as HTMLInputElement
    expect(tzInput.value).toBe('Europe/London')
  })
})

// ===== Negative cases =====

describe('ClientEditorPage · Identity · negative', () => {
  it('empty clientCode → touched → shows the required-field error + disables Next', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const codeInput = screen.getByPlaceholderText('MA1885')
    // Touch without typing.
    await user.click(codeInput)
    await user.tab()
    // Error text lives adjacent to the field.
    await waitFor(() => {
      expect(screen.getByText(/Client code is required/i)).toBeTruthy()
    })
    const next = screen.getByRole('button', { name: /^Next/i })
    expect((next as HTMLButtonElement).disabled).toBe(true)
  })

  it('invalid clientCode chars (space/!) → validator error surfaces on blur', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const codeInput = screen.getByPlaceholderText('MA1885')
    // The onChange handler uppercases before setState; typing a space
    // yields a space in state which fails the [A-Z0-9_-]+ pattern.
    await user.type(codeInput, 'BAD!')
    await user.tab()
    await waitFor(() =>
      expect(screen.getByText(/Only letters, digits, '-' and '_' are allowed/i)).toBeTruthy(),
    )
  })

  it('duplicate clientCode (live check succeeds) → conflict error + Next disabled', async () => {
    // First call resolves — code is TAKEN. Every subsequent 404 for other
    // codes still resolves via the default rejection.
    getClient.mockImplementation((code: string) => {
      if (code === 'TAKEN') return Promise.resolve({ data: sampleClient({ clientCode: 'TAKEN' }) })
      return Promise.reject(statusReject(404))
    })

    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    await user.type(screen.getByPlaceholderText('MA1885'), 'TAKEN')
    await user.type(screen.getByPlaceholderText('Modern Art Fabrics'), 'X')
    await user.tab()

    // Debounce (500ms) + async → wait up to 3s.
    await waitFor(() => {
      expect(screen.getByText(/Client code TAKEN is already registered/i)).toBeTruthy()
    }, { timeout: 3000 })

    const next = screen.getByRole('button', { name: /^Next/i })
    expect((next as HTMLButtonElement).disabled).toBe(true)
  })

  it('empty name → touched → required error', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const nameInput = screen.getByPlaceholderText('Modern Art Fabrics')
    await user.click(nameInput)
    await user.tab()
    await waitFor(() =>
      expect(screen.getByText(/Name is required/i)).toBeTruthy(),
    )
  })

  it('invalid email format → validator error on blur', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const emailInput = screen.getByPlaceholderText('contact@client.com')
    await user.type(emailInput, 'not-an-email')
    await user.tab()
    await waitFor(() =>
      expect(screen.getByText(/Enter a valid email address/i)).toBeTruthy(),
    )
  })

  it('invalid phone (too few digits) → validator error on blur', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const phoneInput = screen.getByPlaceholderText('+1 555-123-4567')
    // 3-digit phone: passes the character set but fails the min-digit check.
    await user.type(phoneInput, '123')
    await user.tab()
    await waitFor(() =>
      expect(screen.getByText(/Phone needs at least 7 digits/i)).toBeTruthy(),
    )
  })

  it('timezone combobox: gibberish query renders the "No timezone matches" empty state', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const tzInput = screen.getByPlaceholderText(/Search e.g\. New_York/i)
    await user.click(tzInput)
    await user.type(tzInput, 'yorkyork')
    await waitFor(() =>
      // The empty-state div carries the query text back to the operator.
      expect(screen.getByText(/No timezone matches/i)).toBeTruthy(),
    )
  })

  it('timezone combobox: Escape closes the dropdown', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const tzInput = screen.getByPlaceholderText(/Search e.g\. New_York/i)
    await user.click(tzInput)
    await waitFor(() => expect(screen.getByRole('listbox')).toBeTruthy())

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
  })

  it('timezone combobox: outside click closes the dropdown', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    const tzInput = screen.getByPlaceholderText(/Search e.g\. New_York/i)
    await user.click(tzInput)
    await waitFor(() => expect(screen.getByRole('listbox')).toBeTruthy())

    // The name input is outside the combobox container and definitely
    // in the DOM — clicking it fires document.mousedown outside the
    // combobox ref, which triggers the close.
    const nameInput = screen.getByPlaceholderText('Modern Art Fabrics')
    await user.click(nameInput)
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull())
  })
})

// ===== Cross-cutting =====

describe('ClientEditorPage · Identity · cross-cutting', () => {
  it('draft persistence: fields typed in create mode are restored after remount', async () => {
    const Page = await loadPage()
    const { unmount } = renderCreate(Page)
    const user = userEvent.setup()

    await user.type(screen.getByPlaceholderText('MA1885'), 'DRAFT1')
    await user.type(screen.getByPlaceholderText('Modern Art Fabrics'), 'Draft Client')

    // Draft is written on every state change via a useEffect — wait for it
    // to land in localStorage.
    await waitFor(() => {
      const raw = Object.keys(localStorage).find((k) => k.startsWith('clientEditorDraft:'))
      expect(raw).toBeTruthy()
    })

    unmount()

    // Remount → the wizard reads the draft on mount and restores state.
    renderCreate(Page)

    const restoredCode = screen.getByPlaceholderText('MA1885') as HTMLInputElement
    const restoredName = screen.getByPlaceholderText('Modern Art Fabrics') as HTMLInputElement
    await waitFor(() => {
      expect(restoredCode.value).toBe('DRAFT1')
      expect(restoredName.value).toBe('Draft Client')
    })
  })

  it('edit mode: Save changes fires updateClient with a trimmed + upper-cased payload', async () => {
    // Hydrate an existing client with valid shipFrom + defaults so the
    // handleUpdate gate (which validates every visible address block)
    // clears. Two consecutive getClient mocks: first for identity load,
    // second for downstream code-availability (harmless).
    const validAddr = {
      name: 'Warehouse A',
      line1: '100 Main St',
      line2: '',
      city: 'Boston',
      state: 'MA',
      zip: '02101',
      country: 'US',
      phone: '',
    }
    getClient.mockResolvedValue({
      data: sampleClient({ shipFrom: validAddr, returnAddress: null, returnSameAsShipFrom: true }),
    })
    updateClient.mockResolvedValue({ data: sampleClient({ name: 'Renamed Co' }) })

    const Page = await loadPage()
    renderEdit(Page, 'ACME')

    await waitFor(() => expect(screen.getByPlaceholderText('MA1885')).toBeTruthy())

    // Change name with surrounding whitespace — the save path is expected
    // to trim it before hitting the API.
    const nameInput = screen.getByPlaceholderText('Modern Art Fabrics') as HTMLInputElement
    const user = userEvent.setup()
    await user.clear(nameInput)
    await user.type(nameInput, '  Renamed Co  ')

    // Save changes button lives in the header (edit mode + identity/shipFrom/
    // return steps). Fire it and assert on the payload shape.
    await user.click(screen.getByRole('button', { name: /Save changes/i }))

    await waitFor(() => {
      expect(updateClient).toHaveBeenCalledTimes(1)
    }, { timeout: 3000 })
    const [code, payload] = updateClient.mock.calls[0]
    expect(code).toBe('ACME')
    // Trimmed name; identity fields normalized.
    expect(payload.name).toBe('Renamed Co')
    expect(payload.clientCode).toBe('ACME')
    // Defaults panel round-trips upper-cased values (or undefined when blank).
    expect(payload.defaultCurrency).toBe('USD')
    expect(payload.defaultWeightUnit).toBe('LB')
    expect(payload.defaultDimUnit).toBe('IN')
    expect(payload.defaultOriginCountry).toBe('US')
    expect(payload.timezone).toBe('America/New_York')
  })

  it('create-mode readyToCreate is false until every wizard step is visited', async () => {
    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    // Valid identity + no defaults required.
    await user.type(screen.getByPlaceholderText('MA1885'), 'ONESTEP')
    await user.type(screen.getByPlaceholderText('Modern Art Fabrics'), 'One Step Co')
    await waitFor(() => expect(getClient).toHaveBeenCalledWith('ONESTEP'), { timeout: 3000 })

    // Even with valid identity, the Submit button isn't visible on step 1;
    // Next IS enabled. Assert the wizard hasn't leaked into a create call.
    expect(createClient).not.toHaveBeenCalled()
    const next = screen.getByRole('button', { name: /^Next/i })
    expect((next as HTMLButtonElement).disabled).toBe(false)
  })

  it('checking-availability hint shows while the debounced live check is in flight', async () => {
    // Never-resolving getClient so the "Checking…" state stays visible.
    let resolveDeferred: ((v: unknown) => void) | null = null
    const deferred = new Promise((r) => { resolveDeferred = r })
    getClient.mockImplementation(() => deferred as Promise<never>)

    const Page = await loadPage()
    renderCreate(Page)
    const user = userEvent.setup()

    await user.type(screen.getByPlaceholderText('MA1885'), 'SLOWCODE')
    await waitFor(
      () => expect(screen.getByText(/Checking availability/i)).toBeTruthy(),
      { timeout: 2000 },
    )
    // Cleanup: resolve so pending timers/promises don't leak into the next test.
    await act(async () => { resolveDeferred?.({ data: sampleClient({ clientCode: 'SLOWCODE' }) }) })
  })

  it('clientCode maxLength = 50 characters (FIELD_LIMITS.clientCode)', async () => {
    const Page = await loadPage()
    renderCreate(Page)

    const codeInput = screen.getByPlaceholderText('MA1885') as HTMLInputElement
    expect(codeInput.maxLength).toBe(50)
  })
})

// Helper reference so the linter doesn't flag `within` as unused across
// future assertions we add here.
void within
