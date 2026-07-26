/**
 * HS (Harmonized System) tariff code helpers. Mirrors the shape check the
 * backend's IntlShipmentValidator does — 6-10 digits after stripping dots,
 * spaces, and hyphens. UI uses this to warn INLINE (not block) so operators
 * see the problem before hitting Save.
 *
 * <p>Not a validity check against the WCO tariff dataset — that's a bigger
 * data problem than a Sprint 5 utility. This just catches shape mistakes
 * (typos, wrong-length codes, alpha characters) that would definitely
 * bounce at customs.
 */

const HS_CODE_PATTERN = /^\d{6,10}$/

export type HsCodeStatus = 'ok' | 'blank' | 'malformed'

export function checkHsCode(value: string | null | undefined): HsCodeStatus {
  if (!value || !value.trim()) return 'blank'
  const stripped = value.trim().replace(/[.\s\-]/g, '')
  return HS_CODE_PATTERN.test(stripped) ? 'ok' : 'malformed'
}

/** Human-readable helper text for the current status. */
export function hsCodeHint(status: HsCodeStatus): string | null {
  switch (status) {
    case 'ok':
      return null
    case 'blank':
      return null
    case 'malformed':
      return 'HS code should be 6–10 digits (e.g. 6104.62.20). Dots/spaces are OK; letters are not.'
  }
}
