/**
 * Sprint 49 Tier 4 Fix 5 — money helpers.
 *
 * <p>Prior code manipulated dollar amounts as JS floats:
 * {@code carrierRate + parsed}, {@code baseCents * (1 + percent / 100)},
 * {@code (qty || 0) * (unitValue || 0)}. That accumulates float error
 * (the classic 0.1 + 0.2 !== 0.3) and rounds inconsistently between
 * accountants and the UI.
 *
 * <p>Store and compute in <b>integer cents</b>; convert to a locale-
 * formatted display string only at the render edge via
 * {@link fromCents}.
 */

/**
 * Parse a decimal string ("12.34", "12", ".5") to integer cents (1234, 1200, 50).
 * Returns 0 for null / empty / non-numeric input — callers can guard against
 * that upstream if they want to reject rather than default.
 */
export function toCents(input: string | number | null | undefined): number {
  if (input == null) return 0
  if (typeof input === 'number') {
    if (!Number.isFinite(input)) return 0
    return Math.round(input * 100)
  }
  const trimmed = String(input).trim()
  if (!trimmed) return 0
  const parsed = Number(trimmed)
  if (!Number.isFinite(parsed)) return 0
  return Math.round(parsed * 100)
}

/**
 * Format integer cents as a locale-aware currency string.
 * fromCents(1234, "USD") === "$12.34"; fromCents(1234, "INR") === "₹12.34" in en-IN.
 */
export function fromCents(cents: number, currency: string = 'USD', locale?: string): string {
  const dollars = (cents ?? 0) / 100
  try {
    return new Intl.NumberFormat(locale ?? navigator.language ?? 'en-US', {
      style: 'currency',
      currency,
    }).format(dollars)
  } catch {
    // Unknown currency code → fall back to a plain number with the code.
    return dollars.toFixed(2) + ' ' + currency
  }
}

/**
 * Add two cent amounts. Trivial today (cents are already integers), but
 * having this as a named helper makes call sites self-documenting and
 * gives us a single place to add rounding semantics later.
 */
export function addMoney(aCents: number, bCents: number): number {
  return (aCents | 0) + (bCents | 0)
}

/**
 * Apply a percent markup to a base amount in cents, using integer math
 * throughout. {@code applyMarkupPercent(1000, 12.5)} === 1125 cents.
 * Rounds half-to-even (banker's) via Math.round.
 */
export function applyMarkupPercent(baseCents: number, percent: number): number {
  if (!Number.isFinite(percent)) return baseCents
  // baseCents * (100 + percent) / 100 kept as one integer expression.
  return Math.round((baseCents * (100 + percent)) / 100)
}
