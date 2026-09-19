/**
 * F5-C — country curated list + display-name helper. Split from
 * CountrySelect.tsx so the component file only exports React
 * components (react-refresh/only-export-components).
 */

export const COUNTRIES: [string, string][] = [
  ['US', 'United States'], ['CA', 'Canada'], ['MX', 'Mexico'], ['GB', 'United Kingdom'],
  ['IE', 'Ireland'], ['FR', 'France'], ['DE', 'Germany'], ['NL', 'Netherlands'], ['BE', 'Belgium'],
  ['LU', 'Luxembourg'], ['IT', 'Italy'], ['ES', 'Spain'], ['PT', 'Portugal'], ['CH', 'Switzerland'],
  ['AT', 'Austria'], ['DK', 'Denmark'], ['SE', 'Sweden'], ['NO', 'Norway'], ['FI', 'Finland'],
  ['PL', 'Poland'], ['CZ', 'Czechia'], ['HU', 'Hungary'], ['RO', 'Romania'], ['GR', 'Greece'],
  ['IN', 'India'], ['CN', 'China'], ['HK', 'Hong Kong'], ['JP', 'Japan'], ['KR', 'South Korea'],
  ['SG', 'Singapore'], ['MY', 'Malaysia'], ['TH', 'Thailand'], ['VN', 'Vietnam'], ['ID', 'Indonesia'],
  ['PH', 'Philippines'], ['AE', 'United Arab Emirates'], ['SA', 'Saudi Arabia'], ['IL', 'Israel'],
  ['TR', 'Turkey'], ['ZA', 'South Africa'], ['NG', 'Nigeria'], ['EG', 'Egypt'], ['KE', 'Kenya'],
  ['AU', 'Australia'], ['NZ', 'New Zealand'], ['BR', 'Brazil'], ['AR', 'Argentina'], ['CL', 'Chile'],
  ['CO', 'Colombia'], ['PE', 'Peru'],
]
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
