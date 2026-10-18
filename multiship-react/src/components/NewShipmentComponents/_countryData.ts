/**
 * F5-C — country curated list + display-name helper. Split from
 * CountrySelect.tsx so the component file only exports React
 * components (react-refresh/only-export-components).
 *
 * BUG-046 R6: the previous ~50-country curated list hid countries the
 * Address book's native 250-country select accepted (Iceland, Peru,
 * etc.). Source from the shared `utils/countries.ts` catalog so every
 * Settings picker — Warehouse > Country, Client wizard > Origin, Address
 * book, Importer / Broker — offers the same superset.
 */
import { COUNTRIES as ALL_COUNTRIES } from '../../utils/countries'

export const COUNTRIES: [string, string][] = ALL_COUNTRIES.map(
  (c) => [c.code, c.name] as [string, string]
)
const COUNTRY_NAME: Record<string, string> = Object.fromEntries(COUNTRIES)

/** Name for ANY ISO code — curated list first, Intl.DisplayNames fallback so
 *  codes outside the list (CU, NR, …) render "Cuba (CU)" instead of "CU (CU)". */
const REGION_NAMES = (() => {
  try { return new Intl.DisplayNames(['en'], { type: 'region' }) } catch { return null }
})()
export const countryNameFor = (code: string): string => {
  const c = (code || '').trim().toUpperCase()
  if (!c) return ''
  if (COUNTRY_NAME[c]) return COUNTRY_NAME[c]
  try { return (c.length === 2 && REGION_NAMES?.of(c)) || c } catch { return c }
}
