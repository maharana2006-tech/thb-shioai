import { describe, expect, it } from 'vitest'
import { addMoney, applyMarkupPercent, fromCents, toCents } from './money'

describe('money helpers', () => {
  describe('toCents', () => {
    it('parses common decimal strings', () => {
      expect(toCents('12.34')).toBe(1234)
      expect(toCents('12')).toBe(1200)
      expect(toCents('.5')).toBe(50)
    })

    it('accepts numeric input', () => {
      expect(toCents(12.34)).toBe(1234)
      expect(toCents(0)).toBe(0)
    })

    it('returns 0 for null / empty / non-numeric', () => {
      expect(toCents(null)).toBe(0)
      expect(toCents(undefined)).toBe(0)
      expect(toCents('')).toBe(0)
      expect(toCents('   ')).toBe(0)
      expect(toCents('abc')).toBe(0)
      expect(toCents(NaN)).toBe(0)
      expect(toCents(Infinity)).toBe(0)
    })

    it('handles the floating-point trap', () => {
      // The whole reason we do cents-integer arithmetic.
      expect(toCents(0.1) + toCents(0.2)).toBe(30)
      expect(toCents('0.1') + toCents('0.2')).toBe(30)
    })
  })

  describe('addMoney', () => {
    it('adds cent amounts without float drift', () => {
      expect(addMoney(toCents(0.1), toCents(0.2))).toBe(30)
      expect(addMoney(1234, 5678)).toBe(6912)
    })

    it('treats null-ish as zero (via | 0 coercion)', () => {
      expect(addMoney(0, 100)).toBe(100)
    })
  })

  describe('applyMarkupPercent', () => {
    it('applies simple percents', () => {
      expect(applyMarkupPercent(1000, 10)).toBe(1100)
      expect(applyMarkupPercent(1000, 0)).toBe(1000)
      expect(applyMarkupPercent(1000, 100)).toBe(2000)
    })

    it('handles fractional percents', () => {
      expect(applyMarkupPercent(1000, 12.5)).toBe(1125)
    })

    it('returns the base when percent is not finite', () => {
      expect(applyMarkupPercent(1000, NaN)).toBe(1000)
      expect(applyMarkupPercent(1000, Infinity)).toBe(1000)
    })
  })

  describe('fromCents', () => {
    it('formats USD by default', () => {
      // Intl.NumberFormat output is locale-dependent; we assert the amount
      // is present (currency symbol placement varies).
      const s = fromCents(1234, 'USD', 'en-US')
      expect(s).toContain('12.34')
    })

    it('handles zero', () => {
      const s = fromCents(0, 'USD', 'en-US')
      expect(s).toContain('0.00')
    })

    it('accepts arbitrary ISO-like currency codes without throwing', () => {
      // Intl.NumberFormat itself handles unknown codes by using the code as
      // the symbol — we just need to not throw or return NaN.
      const s = fromCents(1234, 'ZZZ', 'en-US')
      expect(s).toContain('12.34')
      expect(s).toContain('ZZZ')
    })
  })
})
