import { describe, expect, it } from 'vitest'
import { decorateWithStateWarning, decoratePostalWarning } from './addressWarnings'

/**
 * Sprint 51 polish — client-side guard against FedEx / UPS / DHL Address
 * Validation being lenient about state names ("Delaware" gets Matched=true)
 * while the Rate + Ship APIs demand a 2-letter code.
 *
 * decorateWithStateWarning appends to result.warnings when the user-entered
 * state doesn't match a valid code for the country. Never touches
 * result.valid or result.matchLevel — the address itself really is
 * confirmed; only the state shape is off.
 */

const baseResult = () => ({
  carrierCode: 'FEDEX',
  valid: true,
  matchLevel: 'EXACT' as const,
  classification: 'BUSINESS' as const,
  suggested: null,
  message: 'FedEx confirmed this address.',
  warnings: [],
})

describe('decorateWithStateWarning', () => {
  it('null result is passed through unchanged', () => {
    expect(decorateWithStateWarning(null, 'US', 'DE')).toBeNull()
  })

  it('valid US state code (DE) does NOT add a warning', () => {
    const decorated = decorateWithStateWarning(baseResult(), 'US', 'DE')
    expect(decorated?.warnings).toEqual([])
  })

  it('full US state NAME ("Delaware") appends a warning naming the field', () => {
    const decorated = decorateWithStateWarning(baseResult(), 'US', 'Delaware')
    expect(decorated?.warnings).toHaveLength(1)
    const warning = decorated!.warnings![0]
    expect(warning).toContain('"Delaware"')
    expect(warning).toContain('US')
    expect(warning).toContain('2-letter code')
    // Does NOT touch matchLevel — the address is still EXACT-confirmed,
    // only the state shape is off.
    expect(decorated?.matchLevel).toBe('EXACT')
    expect(decorated?.valid).toBe(true)
  })

  it('lowercase state code is normalised before the membership check', () => {
    const decorated = decorateWithStateWarning(baseResult(), 'US', 'de')
    expect(decorated?.warnings).toEqual([])
  })

  it('non-US/CA/AU country skips the check entirely', () => {
    // India requires a state on the schema but doesn't gate to a fixed set.
    const decorated = decorateWithStateWarning(baseResult(), 'IN', 'Odisha')
    expect(decorated?.warnings).toEqual([])
  })

  it('valid CA province code (ON) does NOT add a warning', () => {
    const decorated = decorateWithStateWarning(baseResult(), 'CA', 'ON')
    expect(decorated?.warnings).toEqual([])
  })

  it('invalid CA province ("Ontario" full name) appends a warning', () => {
    const decorated = decorateWithStateWarning(baseResult(), 'CA', 'Ontario')
    expect(decorated?.warnings).toHaveLength(1)
    expect(decorated!.warnings![0]).toContain('CA')
  })

  it('empty state on a US shipment appends a "required" warning', () => {
    const decorated = decorateWithStateWarning(baseResult(), 'US', '')
    expect(decorated?.warnings).toHaveLength(1)
    expect(decorated!.warnings![0]).toContain('required')
  })

  it('preserves pre-existing warnings and appends the state warning', () => {
    const result = { ...baseResult(), warnings: ['Carrier note: apartment number empty.'] }
    const decorated = decorateWithStateWarning(result, 'US', 'Delaware')
    expect(decorated?.warnings).toHaveLength(2)
    expect(decorated!.warnings![0]).toContain('apartment')
    expect(decorated!.warnings![1]).toContain('"Delaware"')
  })
})

describe('decoratePostalWarning', () => {
  const base = () => ({
    carrierCode: 'FEDEX',
    valid: true,
    matchLevel: 'EXACT' as const,
    classification: 'BUSINESS' as const,
    suggested: null,
    message: 'FedEx confirmed this address.',
    warnings: [] as string[],
  })

  it('null result is passed through unchanged', () => {
    expect(decoratePostalWarning(null, 'US', 'M5H 2N2')).toBeNull()
  })

  it('valid US ZIP does NOT change the result', () => {
    const d = decoratePostalWarning(base(), 'US', '94043')
    expect(d.valid).toBe(true)
    expect(d.matchLevel).toBe('EXACT')
    expect(d.warnings).toHaveLength(0)
  })

  it('Canadian postal under US → downgrades EXACT to AMBIGUOUS, valid=false, warns', () => {
    const d = decoratePostalWarning(base(), 'US', 'M5H 2N2')
    expect(d.valid).toBe(false)
    expect(d.matchLevel).toBe('AMBIGUOUS')
    expect(d.warnings.some((w) => /valid US postal-code format/i.test(w))).toBe(true)
    expect(d.message).toMatch(/valid US postal-code format/i)
  })

  it('valid Canadian postal under CA is accepted', () => {
    const d = decoratePostalWarning(base(), 'CA', 'M5H 2N2')
    expect(d.valid).toBe(true)
    expect(d.warnings).toHaveLength(0)
  })

  it('country with no rule (blank) is passed through', () => {
    const d = decoratePostalWarning(base(), '', '12345')
    expect(d.valid).toBe(true)
  })
})
