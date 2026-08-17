import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, cleanup, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentType } from 'react'

/**
 * Sprint 53 page-tests — CustomFieldsSection (order-values slice).
 *
 * This shared component surfaces custom-field inputs on the order form
 * (New Shipment, Order Import). It reads customFieldService.applicable
 * (active-only defs for a tenant) and calls `onChange(values)` upward
 * as the operator types. It does NOT call upsertValues itself — the
 * host form submits the value bag on save.
 *
 * Scope:
 *   - Loads applicable(tenantId) on mount; loading placeholder visible.
 *   - Empty definitions → section renders NOTHING (returns null).
 *   - Per-def input rendering (TEXT / NUMBER / DATE / SELECT).
 *   - Required badge (*) when def.required is true.
 *   - Typing fires onChange with the new values bag.
 *   - Blank/whitespace value deletes the key from the bag.
 *   - Re-fetch when tenantId prop changes.
 *   - applicable rejection → empty defs (silent; no notify).
 */

// ---------- Service mocks ----------

const applicableMock = vi.fn()

vi.mock('../../api/customFieldService', () => ({
  customFieldService: {
    list: vi.fn(),
    applicable: (...args: unknown[]) => applicableMock(...args),
    save: vi.fn(),
    remove: vi.fn(),
    values: vi.fn(),
    upsertValues: vi.fn(),
  },
}))

vi.mock('../../api/apiClient', () => ({
  ApiError: class ApiError extends Error {},
  isAbortError: () => false,
  apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
}))

// ---------- Fixtures ----------

const def = (overrides: Partial<{
  fieldKey: string, label: string, fieldType: 'TEXT' | 'NUMBER' | 'DATE' | 'SELECT',
  required: boolean, selectOptions: string | null,
}> = {}) => ({
  id: Math.floor(Math.random() * 1000),
  tenantId: null,
  fieldKey: overrides.fieldKey ?? 'notes',
  label: overrides.label ?? 'Notes',
  fieldType: overrides.fieldType ?? 'TEXT' as const,
  required: overrides.required ?? false,
  selectOptions: overrides.selectOptions ?? null,
  active: true,
  position: 100,
})

// ---------- Fail-loud fetch spy ----------

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => {
    throw new Error('un-mocked fetch forbidden in unit tests')
  })
  applicableMock.mockReset()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

async function loadSection(): Promise<ComponentType<{
  tenantId: string | null | undefined
  values: Record<string, string>
  onChange: (v: Record<string, string>) => void
  compact?: boolean
}>> {
  const mod = await import('./CustomFieldsSection')
  return mod.default
}

// ===================== Mount + load =====================

describe('CustomFieldsSection — mount + load', () => {
  it('calls applicable(tenantId) on mount', async () => {
    applicableMock.mockResolvedValue([def()])

    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(applicableMock).toHaveBeenCalledWith('ACME'))
  })

  it('null tenantId → applicable(undefined)', async () => {
    applicableMock.mockResolvedValue([def()])

    const Section = await loadSection()
    render(<Section tenantId={null} values={{}} onChange={() => {}} />)

    await waitFor(() => expect(applicableMock).toHaveBeenCalledWith(undefined))
  })

  it('loading placeholder ("Loading fields…") shown while applicable in-flight', async () => {
    applicableMock.mockReturnValue(new Promise(() => {}))

    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    expect(await screen.findByText(/Loading fields…/i)).toBeInTheDocument()
  })

  it('empty defs → section renders NOTHING (returns null)', async () => {
    applicableMock.mockResolvedValue([])

    const Section = await loadSection()
    const { container } = render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    // Wait for load to finish (loading placeholder gone).
    await waitFor(() =>
      expect(screen.queryByText(/Loading fields…/i)).not.toBeInTheDocument(),
    )
    // Container is empty — the component returned null.
    expect(container.textContent).toBe('')
  })
})

// ===================== Field rendering =====================

describe('CustomFieldsSection — field rendering', () => {
  it('renders each def as a labeled input', async () => {
    applicableMock.mockResolvedValue([
      def({ fieldKey: 'notes', label: 'Notes', fieldType: 'TEXT' }),
      def({ fieldKey: 'weight', label: 'Weight', fieldType: 'NUMBER' }),
    ])

    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(screen.getByText('Notes')).toBeInTheDocument())
    expect(screen.getByText('Weight')).toBeInTheDocument()
  })

  it('NUMBER type renders input[type=number]', async () => {
    applicableMock.mockResolvedValue([
      def({ fieldKey: 'weight', label: 'Weight', fieldType: 'NUMBER' }),
    ])

    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(screen.getByText('Weight')).toBeInTheDocument())
    const input = document.querySelector('input[type="number"]') as HTMLInputElement
    expect(input).toBeTruthy()
  })

  it('DATE type renders input[type=date]', async () => {
    applicableMock.mockResolvedValue([
      def({ fieldKey: 'ship_date', label: 'Ship date', fieldType: 'DATE' }),
    ])

    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(screen.getByText('Ship date')).toBeInTheDocument())
    const input = document.querySelector('input[type="date"]') as HTMLInputElement
    expect(input).toBeTruthy()
  })

  it('SELECT type renders a <select> with the split options', async () => {
    applicableMock.mockResolvedValue([
      def({ fieldKey: 'size', label: 'Size', fieldType: 'SELECT', selectOptions: 'S, M, L' }),
    ])

    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(screen.getByText('Size')).toBeInTheDocument())
    expect(screen.getByRole('option', { name: 'S' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'M' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'L' })).toBeInTheDocument()
  })

  it('required=true renders a red asterisk after the label', async () => {
    applicableMock.mockResolvedValue([
      def({ fieldKey: 'notes', label: 'Notes', required: true }),
    ])

    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(screen.getByText('Notes')).toBeInTheDocument())
    // Asterisk lives in a <span class="text-rose-500"> next to the label.
    expect(screen.getByText('*')).toBeInTheDocument()
  })
})

// ===================== onChange bag semantics =====================

describe('CustomFieldsSection — onChange bag semantics', () => {
  it('typing into a TEXT input fires onChange with the new values bag', async () => {
    applicableMock.mockResolvedValue([
      def({ fieldKey: 'notes', label: 'Notes', fieldType: 'TEXT' }),
    ])

    const onChange = vi.fn()
    const Section = await loadSection()
    render(<Section tenantId="ACME" values={{}} onChange={onChange} />)

    await waitFor(() => expect(screen.getByText('Notes')).toBeInTheDocument())
    const input = document.querySelector('input[type="text"]') as HTMLInputElement

    await act(async () => { await userEvent.type(input, 'x') })

    // Fires per-keystroke; last call has the accumulated value.
    expect(onChange).toHaveBeenCalled()
    // Called at least once with the new value present.
    const seen = onChange.mock.calls.map((c) => c[0])
    expect(seen.some((bag) => bag.notes === 'x')).toBe(true)
  })

  it('blank/whitespace value DELETES the key from the values bag', async () => {
    applicableMock.mockResolvedValue([
      def({ fieldKey: 'notes', label: 'Notes', fieldType: 'TEXT' }),
    ])

    const onChange = vi.fn()
    const Section = await loadSection()
    // Values already has notes=hello — typing whitespace should delete it.
    render(<Section tenantId="ACME" values={{ notes: 'hello' }} onChange={onChange} />)

    await waitFor(() => expect(screen.getByText('Notes')).toBeInTheDocument())
    const input = document.querySelector('input[type="text"]') as HTMLInputElement

    // Clear the input.
    await act(async () => { await userEvent.clear(input) })

    // Last onChange call is a bag WITHOUT the notes key.
    const lastBag = onChange.mock.calls[onChange.mock.calls.length - 1][0]
    expect(lastBag).not.toHaveProperty('notes')
  })
})

// ===================== Re-fetch on tenant change =====================

describe('CustomFieldsSection — re-fetch on tenant change', () => {
  it('changing tenantId prop → new applicable call with the new tenantId', async () => {
    applicableMock.mockResolvedValue([def()])

    const Section = await loadSection()
    const { rerender } = render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(applicableMock).toHaveBeenCalledWith('ACME'))
    applicableMock.mockClear()

    rerender(<Section tenantId="ZORP" values={{}} onChange={() => {}} />)

    await waitFor(() => expect(applicableMock).toHaveBeenCalledWith('ZORP'))
  })
})

// ===================== Error path (silent) =====================

describe('CustomFieldsSection — applicable rejection', () => {
  it('rejection → empty defs (silent; component renders nothing)', async () => {
    applicableMock.mockRejectedValue(new Error('boom'))

    const Section = await loadSection()
    const { container } = render(<Section tenantId="ACME" values={{}} onChange={() => {}} />)

    await waitFor(() =>
      expect(screen.queryByText(/Loading fields…/i)).not.toBeInTheDocument(),
    )
    // No notify.error, no visible copy — component collapses to null.
    expect(container.textContent).toBe('')
  })
})
