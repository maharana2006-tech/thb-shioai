/**
 * Complete ISO-3166 alpha-2 country list for customs pickers, grouped by
 * shipping region. The ERP decides which countries appear on orders, so this
 * list must be exhaustive — never curate it down or the customs gate can
 * demand a profile the UI cannot create. Unknown codes fall back to 'Other'.
 */
export type Region =
  | 'North America'
  | 'Europe'
  | 'Middle East'
  | 'Asia'
  | 'Oceania'
  | 'South America'
  | 'Africa'
  | 'Other'

/**
 * Customs unions — the ONE unit customs law actually groups countries by.
 * One importer registration (e.g. one EORI) covers a whole territory; outside
 * these unions every country is its own customs territory.
 */
export type CustomsUnion = 'EU' | 'EAEU' | 'GCC' | 'SACU'

export interface Country {
  code: string
  name: string
  region: Region
  /** EU member — drives EORI/IOSS conditional fields (and territory = EU). */
  eu?: boolean
  /** Customs union membership beyond the EU flag (EAEU/GCC/SACU). */
  union?: CustomsUnion
}

/** Region display order (top → bottom in the picker). */
export const REGIONS: Region[] = [
  'North America',
  'Europe',
  'Middle East',
  'Asia',
  'Oceania',
  'South America',
  'Africa',
  'Other',
]

export const COUNTRIES: Country[] = [
  // ===== North America (incl. Central America + Caribbean) =====
  { code: 'US', name: 'United States', region: 'North America' },
  { code: 'CA', name: 'Canada', region: 'North America' },
  { code: 'MX', name: 'Mexico', region: 'North America' },
  { code: 'AG', name: 'Antigua and Barbuda', region: 'North America' },
  { code: 'AI', name: 'Anguilla', region: 'North America' },
  { code: 'AW', name: 'Aruba', region: 'North America' },
  { code: 'BB', name: 'Barbados', region: 'North America' },
  { code: 'BL', name: 'Saint Barthélemy', region: 'North America' },
  { code: 'BM', name: 'Bermuda', region: 'North America' },
  { code: 'BQ', name: 'Bonaire, Sint Eustatius and Saba', region: 'North America' },
  { code: 'BS', name: 'Bahamas', region: 'North America' },
  { code: 'BZ', name: 'Belize', region: 'North America' },
  { code: 'CR', name: 'Costa Rica', region: 'North America' },
  { code: 'CU', name: 'Cuba', region: 'North America' },
  { code: 'CW', name: 'Curaçao', region: 'North America' },
  { code: 'DM', name: 'Dominica', region: 'North America' },
  { code: 'DO', name: 'Dominican Republic', region: 'North America' },
  { code: 'GD', name: 'Grenada', region: 'North America' },
  { code: 'GL', name: 'Greenland', region: 'North America' },
  { code: 'GP', name: 'Guadeloupe', region: 'North America' },
  { code: 'GT', name: 'Guatemala', region: 'North America' },
  { code: 'HN', name: 'Honduras', region: 'North America' },
  { code: 'HT', name: 'Haiti', region: 'North America' },
  { code: 'JM', name: 'Jamaica', region: 'North America' },
  { code: 'KN', name: 'Saint Kitts and Nevis', region: 'North America' },
  { code: 'KY', name: 'Cayman Islands', region: 'North America' },
  { code: 'LC', name: 'Saint Lucia', region: 'North America' },
  { code: 'MF', name: 'Saint Martin', region: 'North America' },
  { code: 'MQ', name: 'Martinique', region: 'North America' },
  { code: 'MS', name: 'Montserrat', region: 'North America' },
  { code: 'NI', name: 'Nicaragua', region: 'North America' },
  { code: 'PA', name: 'Panama', region: 'North America' },
  { code: 'PM', name: 'Saint Pierre and Miquelon', region: 'North America' },
  { code: 'PR', name: 'Puerto Rico', region: 'North America' },
  { code: 'SV', name: 'El Salvador', region: 'North America' },
  { code: 'SX', name: 'Sint Maarten', region: 'North America' },
  { code: 'TC', name: 'Turks and Caicos Islands', region: 'North America' },
  { code: 'TT', name: 'Trinidad and Tobago', region: 'North America' },
  { code: 'VC', name: 'Saint Vincent and the Grenadines', region: 'North America' },
  { code: 'VG', name: 'British Virgin Islands', region: 'North America' },
  { code: 'VI', name: 'U.S. Virgin Islands', region: 'North America' },

  // ===== Europe =====
  { code: 'GB', name: 'United Kingdom', region: 'Europe' },
  { code: 'IE', name: 'Ireland', region: 'Europe', eu: true },
  { code: 'DE', name: 'Germany', region: 'Europe', eu: true },
  { code: 'FR', name: 'France', region: 'Europe', eu: true },
  { code: 'ES', name: 'Spain', region: 'Europe', eu: true },
  { code: 'IT', name: 'Italy', region: 'Europe', eu: true },
  { code: 'NL', name: 'Netherlands', region: 'Europe', eu: true },
  { code: 'BE', name: 'Belgium', region: 'Europe', eu: true },
  { code: 'AD', name: 'Andorra', region: 'Europe' },
  { code: 'AL', name: 'Albania', region: 'Europe' },
  { code: 'AT', name: 'Austria', region: 'Europe', eu: true },
  { code: 'AX', name: 'Åland Islands', region: 'Europe' },
  { code: 'BA', name: 'Bosnia and Herzegovina', region: 'Europe' },
  { code: 'BG', name: 'Bulgaria', region: 'Europe', eu: true },
  { code: 'BY', name: 'Belarus', region: 'Europe', union: 'EAEU' },
  { code: 'CH', name: 'Switzerland', region: 'Europe' },
  { code: 'CY', name: 'Cyprus', region: 'Europe', eu: true },
  { code: 'CZ', name: 'Czechia', region: 'Europe', eu: true },
  { code: 'DK', name: 'Denmark', region: 'Europe', eu: true },
  { code: 'EE', name: 'Estonia', region: 'Europe', eu: true },
  { code: 'FI', name: 'Finland', region: 'Europe', eu: true },
  { code: 'FO', name: 'Faroe Islands', region: 'Europe' },
  { code: 'GG', name: 'Guernsey', region: 'Europe' },
  { code: 'GI', name: 'Gibraltar', region: 'Europe' },
  { code: 'GR', name: 'Greece', region: 'Europe', eu: true },
  { code: 'HR', name: 'Croatia', region: 'Europe', eu: true },
  { code: 'HU', name: 'Hungary', region: 'Europe', eu: true },
  { code: 'IM', name: 'Isle of Man', region: 'Europe' },
  { code: 'IS', name: 'Iceland', region: 'Europe' },
  { code: 'JE', name: 'Jersey', region: 'Europe' },
  { code: 'LI', name: 'Liechtenstein', region: 'Europe' },
  { code: 'LT', name: 'Lithuania', region: 'Europe', eu: true },
  { code: 'LU', name: 'Luxembourg', region: 'Europe', eu: true },
  { code: 'LV', name: 'Latvia', region: 'Europe', eu: true },
  { code: 'MC', name: 'Monaco', region: 'Europe' },
  { code: 'MD', name: 'Moldova', region: 'Europe' },
  { code: 'ME', name: 'Montenegro', region: 'Europe' },
  { code: 'MK', name: 'North Macedonia', region: 'Europe' },
  { code: 'MT', name: 'Malta', region: 'Europe', eu: true },
  { code: 'NO', name: 'Norway', region: 'Europe' },
  { code: 'PL', name: 'Poland', region: 'Europe', eu: true },
  { code: 'PT', name: 'Portugal', region: 'Europe', eu: true },
  { code: 'RO', name: 'Romania', region: 'Europe', eu: true },
  { code: 'RS', name: 'Serbia', region: 'Europe' },
  { code: 'RU', name: 'Russia', region: 'Europe', union: 'EAEU' },
  { code: 'SE', name: 'Sweden', region: 'Europe', eu: true },
  { code: 'SI', name: 'Slovenia', region: 'Europe', eu: true },
  { code: 'SJ', name: 'Svalbard and Jan Mayen', region: 'Europe' },
  { code: 'SK', name: 'Slovakia', region: 'Europe', eu: true },
  { code: 'SM', name: 'San Marino', region: 'Europe' },
  { code: 'UA', name: 'Ukraine', region: 'Europe' },
  { code: 'VA', name: 'Vatican City', region: 'Europe' },
  { code: 'XK', name: 'Kosovo', region: 'Europe' },

  // ===== Middle East =====
  { code: 'AE', name: 'United Arab Emirates', region: 'Middle East', union: 'GCC' },
  { code: 'BH', name: 'Bahrain', region: 'Middle East', union: 'GCC' },
  { code: 'IL', name: 'Israel', region: 'Middle East' },
  { code: 'IQ', name: 'Iraq', region: 'Middle East' },
  { code: 'IR', name: 'Iran', region: 'Middle East' },
  { code: 'JO', name: 'Jordan', region: 'Middle East' },
  { code: 'KW', name: 'Kuwait', region: 'Middle East', union: 'GCC' },
  { code: 'LB', name: 'Lebanon', region: 'Middle East' },
  { code: 'OM', name: 'Oman', region: 'Middle East', union: 'GCC' },
  { code: 'PS', name: 'Palestine', region: 'Middle East' },
  { code: 'QA', name: 'Qatar', region: 'Middle East', union: 'GCC' },
  { code: 'SA', name: 'Saudi Arabia', region: 'Middle East', union: 'GCC' },
  { code: 'SY', name: 'Syria', region: 'Middle East' },
  { code: 'TR', name: 'Türkiye', region: 'Middle East' },
  { code: 'YE', name: 'Yemen', region: 'Middle East' },

  // ===== Asia =====
  { code: 'JP', name: 'Japan', region: 'Asia' },
  { code: 'CN', name: 'China', region: 'Asia' },
  { code: 'HK', name: 'Hong Kong', region: 'Asia' },
  { code: 'SG', name: 'Singapore', region: 'Asia' },
  { code: 'KR', name: 'South Korea', region: 'Asia' },
  { code: 'IN', name: 'India', region: 'Asia' },
  { code: 'AF', name: 'Afghanistan', region: 'Asia' },
  { code: 'AM', name: 'Armenia', region: 'Asia', union: 'EAEU' },
  { code: 'AZ', name: 'Azerbaijan', region: 'Asia' },
  { code: 'BD', name: 'Bangladesh', region: 'Asia' },
  { code: 'BN', name: 'Brunei', region: 'Asia' },
  { code: 'BT', name: 'Bhutan', region: 'Asia' },
  { code: 'GE', name: 'Georgia', region: 'Asia' },
  { code: 'ID', name: 'Indonesia', region: 'Asia' },
  { code: 'KG', name: 'Kyrgyzstan', region: 'Asia', union: 'EAEU' },
  { code: 'KH', name: 'Cambodia', region: 'Asia' },
  { code: 'KP', name: 'North Korea', region: 'Asia' },
  { code: 'KZ', name: 'Kazakhstan', region: 'Asia', union: 'EAEU' },
  { code: 'LA', name: 'Laos', region: 'Asia' },
  { code: 'LK', name: 'Sri Lanka', region: 'Asia' },
  { code: 'MM', name: 'Myanmar', region: 'Asia' },
  { code: 'MN', name: 'Mongolia', region: 'Asia' },
  { code: 'MO', name: 'Macao', region: 'Asia' },
  { code: 'MV', name: 'Maldives', region: 'Asia' },
  { code: 'MY', name: 'Malaysia', region: 'Asia' },
  { code: 'NP', name: 'Nepal', region: 'Asia' },
  { code: 'PH', name: 'Philippines', region: 'Asia' },
  { code: 'PK', name: 'Pakistan', region: 'Asia' },
  { code: 'TH', name: 'Thailand', region: 'Asia' },
  { code: 'TJ', name: 'Tajikistan', region: 'Asia' },
  { code: 'TL', name: 'Timor-Leste', region: 'Asia' },
  { code: 'TM', name: 'Turkmenistan', region: 'Asia' },
  { code: 'TW', name: 'Taiwan', region: 'Asia' },
  { code: 'UZ', name: 'Uzbekistan', region: 'Asia' },
  { code: 'VN', name: 'Vietnam', region: 'Asia' },

  // ===== Oceania =====
  { code: 'AU', name: 'Australia', region: 'Oceania' },
  { code: 'NZ', name: 'New Zealand', region: 'Oceania' },
  { code: 'AS', name: 'American Samoa', region: 'Oceania' },
  { code: 'CK', name: 'Cook Islands', region: 'Oceania' },
  { code: 'FJ', name: 'Fiji', region: 'Oceania' },
  { code: 'FM', name: 'Micronesia', region: 'Oceania' },
  { code: 'GU', name: 'Guam', region: 'Oceania' },
  { code: 'KI', name: 'Kiribati', region: 'Oceania' },
  { code: 'MH', name: 'Marshall Islands', region: 'Oceania' },
  { code: 'MP', name: 'Northern Mariana Islands', region: 'Oceania' },
  { code: 'NC', name: 'New Caledonia', region: 'Oceania' },
  { code: 'NR', name: 'Nauru', region: 'Oceania' },
  { code: 'NU', name: 'Niue', region: 'Oceania' },
  { code: 'NF', name: 'Norfolk Island', region: 'Oceania' },
  { code: 'PF', name: 'French Polynesia', region: 'Oceania' },
  { code: 'PG', name: 'Papua New Guinea', region: 'Oceania' },
  { code: 'PW', name: 'Palau', region: 'Oceania' },
  { code: 'SB', name: 'Solomon Islands', region: 'Oceania' },
  { code: 'TK', name: 'Tokelau', region: 'Oceania' },
  { code: 'TO', name: 'Tonga', region: 'Oceania' },
  { code: 'TV', name: 'Tuvalu', region: 'Oceania' },
  { code: 'VU', name: 'Vanuatu', region: 'Oceania' },
  { code: 'WF', name: 'Wallis and Futuna', region: 'Oceania' },
  { code: 'WS', name: 'Samoa', region: 'Oceania' },

  // ===== South America =====
  { code: 'AR', name: 'Argentina', region: 'South America' },
  { code: 'BO', name: 'Bolivia', region: 'South America' },
  { code: 'BR', name: 'Brazil', region: 'South America' },
  { code: 'CL', name: 'Chile', region: 'South America' },
  { code: 'CO', name: 'Colombia', region: 'South America' },
  { code: 'EC', name: 'Ecuador', region: 'South America' },
  { code: 'FK', name: 'Falkland Islands', region: 'South America' },
  { code: 'GF', name: 'French Guiana', region: 'South America' },
  { code: 'GY', name: 'Guyana', region: 'South America' },
  { code: 'PE', name: 'Peru', region: 'South America' },
  { code: 'PY', name: 'Paraguay', region: 'South America' },
  { code: 'SR', name: 'Suriname', region: 'South America' },
  { code: 'UY', name: 'Uruguay', region: 'South America' },
  { code: 'VE', name: 'Venezuela', region: 'South America' },

  // ===== Africa =====
  { code: 'ZA', name: 'South Africa', region: 'Africa', union: 'SACU' },
  { code: 'AO', name: 'Angola', region: 'Africa' },
  { code: 'BF', name: 'Burkina Faso', region: 'Africa' },
  { code: 'BI', name: 'Burundi', region: 'Africa' },
  { code: 'BJ', name: 'Benin', region: 'Africa' },
  { code: 'BW', name: 'Botswana', region: 'Africa', union: 'SACU' },
  { code: 'CD', name: 'DR Congo', region: 'Africa' },
  { code: 'CF', name: 'Central African Republic', region: 'Africa' },
  { code: 'CG', name: 'Congo', region: 'Africa' },
  { code: 'CI', name: "Côte d'Ivoire", region: 'Africa' },
  { code: 'CM', name: 'Cameroon', region: 'Africa' },
  { code: 'CV', name: 'Cabo Verde', region: 'Africa' },
  { code: 'DJ', name: 'Djibouti', region: 'Africa' },
  { code: 'DZ', name: 'Algeria', region: 'Africa' },
  { code: 'EG', name: 'Egypt', region: 'Africa' },
  { code: 'EH', name: 'Western Sahara', region: 'Africa' },
  { code: 'ER', name: 'Eritrea', region: 'Africa' },
  { code: 'ET', name: 'Ethiopia', region: 'Africa' },
  { code: 'GA', name: 'Gabon', region: 'Africa' },
  { code: 'GH', name: 'Ghana', region: 'Africa' },
  { code: 'GM', name: 'Gambia', region: 'Africa' },
  { code: 'GN', name: 'Guinea', region: 'Africa' },
  { code: 'GQ', name: 'Equatorial Guinea', region: 'Africa' },
  { code: 'GW', name: 'Guinea-Bissau', region: 'Africa' },
  { code: 'KE', name: 'Kenya', region: 'Africa' },
  { code: 'KM', name: 'Comoros', region: 'Africa' },
  { code: 'LR', name: 'Liberia', region: 'Africa' },
  { code: 'LS', name: 'Lesotho', region: 'Africa', union: 'SACU' },
  { code: 'LY', name: 'Libya', region: 'Africa' },
  { code: 'MA', name: 'Morocco', region: 'Africa' },
  { code: 'MG', name: 'Madagascar', region: 'Africa' },
  { code: 'ML', name: 'Mali', region: 'Africa' },
  { code: 'MR', name: 'Mauritania', region: 'Africa' },
  { code: 'MU', name: 'Mauritius', region: 'Africa' },
  { code: 'MW', name: 'Malawi', region: 'Africa' },
  { code: 'MZ', name: 'Mozambique', region: 'Africa' },
  { code: 'NA', name: 'Namibia', region: 'Africa', union: 'SACU' },
  { code: 'NE', name: 'Niger', region: 'Africa' },
  { code: 'NG', name: 'Nigeria', region: 'Africa' },
  { code: 'RE', name: 'Réunion', region: 'Africa' },
  { code: 'RW', name: 'Rwanda', region: 'Africa' },
  { code: 'SC', name: 'Seychelles', region: 'Africa' },
  { code: 'SD', name: 'Sudan', region: 'Africa' },
  { code: 'SH', name: 'Saint Helena', region: 'Africa' },
  { code: 'SL', name: 'Sierra Leone', region: 'Africa' },
  { code: 'SN', name: 'Senegal', region: 'Africa' },
  { code: 'SO', name: 'Somalia', region: 'Africa' },
  { code: 'SS', name: 'South Sudan', region: 'Africa' },
  { code: 'ST', name: 'São Tomé and Príncipe', region: 'Africa' },
  { code: 'SZ', name: 'Eswatini', region: 'Africa', union: 'SACU' },
  { code: 'TD', name: 'Chad', region: 'Africa' },
  { code: 'TG', name: 'Togo', region: 'Africa' },
  { code: 'TN', name: 'Tunisia', region: 'Africa' },
  { code: 'TZ', name: 'Tanzania', region: 'Africa' },
  { code: 'UG', name: 'Uganda', region: 'Africa' },
  { code: 'YT', name: 'Mayotte', region: 'Africa' },
  { code: 'ZM', name: 'Zambia', region: 'Africa' },
  { code: 'ZW', name: 'Zimbabwe', region: 'Africa' },

  // ===== Other (remote territories) =====
  { code: 'AQ', name: 'Antarctica', region: 'Other' },
  { code: 'BV', name: 'Bouvet Island', region: 'Other' },
  { code: 'CC', name: 'Cocos Islands', region: 'Other' },
  { code: 'CX', name: 'Christmas Island', region: 'Other' },
  { code: 'GS', name: 'South Georgia', region: 'Other' },
  { code: 'HM', name: 'Heard and McDonald Islands', region: 'Other' },
  { code: 'IO', name: 'British Indian Ocean Territory', region: 'Other' },
  { code: 'PN', name: 'Pitcairn Islands', region: 'Other' },
  { code: 'TF', name: 'French Southern Territories', region: 'Other' },
  { code: 'UM', name: 'U.S. Minor Outlying Islands', region: 'Other' },
]

const NAME_BY_CODE = new Map(COUNTRIES.map((c) => [c.code, c.name]))
const REGION_BY_CODE = new Map(COUNTRIES.map((c) => [c.code, c.region]))
const EU_CODES = new Set(COUNTRIES.filter((c) => c.eu).map((c) => c.code))

export const countryName = (code?: string | null): string =>
  code ? NAME_BY_CODE.get(code.toUpperCase()) ?? code.toUpperCase() : ''

/** Never undefined — unknown/legacy codes land in 'Other' so nothing is unpickable. */
export const regionOf = (code?: string | null): Region =>
  (code ? REGION_BY_CODE.get(code.toUpperCase()) : undefined) ?? 'Other'

export const isEuCountry = (code?: string | null): boolean =>
  !!code && EU_CODES.has(code.toUpperCase())

const UNION_BY_CODE = new Map(
  COUNTRIES.filter((c) => c.union || c.eu).map((c) => [c.code, (c.union ?? 'EU') as CustomsUnion])
)

/**
 * The customs territory a country belongs to: its union (EU/EAEU/GCC/SACU) or
 * itself. One BUSINESS importer registration is valid across exactly one
 * territory — an EU EORI covers all 27 members, but the UK, Switzerland, and
 * every non-union country are territories of their own.
 */
export const territoryOf = (code?: string | null): string =>
  code ? UNION_BY_CODE.get(code.toUpperCase()) ?? code.toUpperCase() : ''

const UNION_LABELS: Record<CustomsUnion, string> = {
  EU: 'the EU customs union',
  EAEU: 'the EAEU customs union',
  GCC: 'the GCC customs union',
  SACU: 'the SACU customs union',
}

/** Human label for a territory ("the EU customs union" / "Japan"). */
export const territoryLabel = (territory: string): string =>
  (UNION_LABELS as Record<string, string>)[territory] ?? countryName(territory)

/** Countries in one region (display order preserved). */
export const countriesInRegion = (region: Region): Country[] =>
  COUNTRIES.filter((c) => c.region === region)

/** Group a set of country codes by region for chip display. */
export const groupByRegion = (codes: string[]): Array<{ region: Region; codes: string[] }> => {
  const up = codes.map((c) => c.toUpperCase())
  const known = new Set(COUNTRIES.map((c) => c.code))
  const groups = REGIONS.map((region) => ({
    region,
    codes: countriesInRegion(region).map((c) => c.code).filter((c) => up.includes(c)),
  }))
  // Legacy/unknown codes still display under Other rather than vanishing.
  const unknown = up.filter((c) => !known.has(c))
  if (unknown.length) groups[groups.length - 1].codes.push(...unknown)
  return groups.filter((g) => g.codes.length > 0)
}
