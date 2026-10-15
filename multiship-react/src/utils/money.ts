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
// BUG-035 — formatting USD / EUR / GBP with the operator's browser locale
// produced non-Western digit grouping (e.g. en-IN turns $250,000,000,024.75
// into $2,50,00,00,00,024.75). Pin common western-settled currencies to a
// locale that matches operator expectations; let INR legitimately render
// Indian grouping; fall back to the browser locale only for currencies
// without an obvious canonical display locale.
const CURRENCY_LOCALE: Readonly<Record<string, string>> = {
  USD: 'en-US',
  EUR: 'de-DE',
  GBP: 'en-GB',
  AUD: 'en-AU',
  CAD: 'en-CA',
  INR: 'en-IN',
  JPY: 'ja-JP',
  CNY: 'zh-CN',
}

export function fromCents(cents: number, currency: string = 'USD', locale?: string): string {
  const dollars = (cents ?? 0) / 100
  const normalised = (currency || 'USD').toUpperCase()
  const effectiveLocale = locale ?? CURRENCY_LOCALE[normalised] ?? navigator.language ?? 'en-US'
  try {
    return new Intl.NumberFormat(effectiveLocale, {
      style: 'currency',
      currency: normalised,
    }).format(dollars)
  } catch {
    // Unknown currency code → fall back to a plain number with the code.
    return dollars.toFixed(2) + ' ' + normalised
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

/**
 * BUG-125 — shared ISO 4217 allowlist for every currency picker (Orders
 * form, Importer/Broker, Client wizard). Union of the previous hardcoded
 * lists on each page; keeps the Currencies catalog (178 entries) out of
 * dropdowns while covering every currency any of the three screens
 * previously offered — so MXN as a client default is also valid on the
 * order form, AED on the order form is also valid on an Importer profile.
 */
export const CURRENCY_ALLOWLIST = [
  'USD', 'EUR', 'GBP', 'CAD', 'AUD', 'NZD',
  'INR', 'SGD', 'HKD', 'JPY', 'CNY', 'AED',
  'MXN', 'BRL', 'CHF',
] as const
