/**
 * Sprint 51 — shared source of truth for US / CA / AU state and province
 * codes with their human-readable names. Consumed by:
 *
 *   - {@link ../validation/yup/shipmentSchema} for membership validation
 *   - The manual New Shipment page's state dropdown (see AddressBlock)
 *   - Any future importer/broker or client-address form that also needs
 *     to gate state codes to a real value
 *
 * Codes are the wire values carriers accept (UPS `StateProvinceCode`,
 * FedEx `stateOrProvinceCode`, USPS `State`). Labels are English display
 * names for the operator's UI, matching the US Postal Service /
 * Canada Post / Australia Post conventions.
 *
 * Adding a new country: add its list here + entry in {@link STATE_CODE_OPTIONS}
 * and the schema's {@link STATE_REQUIRED} set (in shipmentSchema.ts).
 */

export interface StateOption {
  code: string
  label: string
}

/** US 50 states + DC + territories (PR / VI / GU / AS / MP). Alphabetical by label. */
export const US_STATE_OPTIONS: StateOption[] = [
  { code: 'AL', label: 'Alabama' },
  { code: 'AK', label: 'Alaska' },
  { code: 'AS', label: 'American Samoa' },
  { code: 'AZ', label: 'Arizona' },
  { code: 'AR', label: 'Arkansas' },
  { code: 'CA', label: 'California' },
  { code: 'CO', label: 'Colorado' },
  { code: 'CT', label: 'Connecticut' },
  { code: 'DE', label: 'Delaware' },
  { code: 'DC', label: 'District of Columbia' },
  { code: 'FL', label: 'Florida' },
  { code: 'GA', label: 'Georgia' },
  { code: 'GU', label: 'Guam' },
  { code: 'HI', label: 'Hawaii' },
  { code: 'ID', label: 'Idaho' },
  { code: 'IL', label: 'Illinois' },
  { code: 'IN', label: 'Indiana' },
  { code: 'IA', label: 'Iowa' },
  { code: 'KS', label: 'Kansas' },
  { code: 'KY', label: 'Kentucky' },
  { code: 'LA', label: 'Louisiana' },
  { code: 'ME', label: 'Maine' },
  { code: 'MD', label: 'Maryland' },
  { code: 'MA', label: 'Massachusetts' },
  { code: 'MI', label: 'Michigan' },
  { code: 'MN', label: 'Minnesota' },
  { code: 'MS', label: 'Mississippi' },
  { code: 'MO', label: 'Missouri' },
  { code: 'MT', label: 'Montana' },
  { code: 'NE', label: 'Nebraska' },
  { code: 'NV', label: 'Nevada' },
  { code: 'NH', label: 'New Hampshire' },
  { code: 'NJ', label: 'New Jersey' },
  { code: 'NM', label: 'New Mexico' },
  { code: 'NY', label: 'New York' },
  { code: 'NC', label: 'North Carolina' },
  { code: 'ND', label: 'North Dakota' },
  { code: 'MP', label: 'Northern Mariana Islands' },
  { code: 'OH', label: 'Ohio' },
  { code: 'OK', label: 'Oklahoma' },
  { code: 'OR', label: 'Oregon' },
  { code: 'PA', label: 'Pennsylvania' },
  { code: 'PR', label: 'Puerto Rico' },
  { code: 'RI', label: 'Rhode Island' },
  { code: 'SC', label: 'South Carolina' },
  { code: 'SD', label: 'South Dakota' },
  { code: 'TN', label: 'Tennessee' },
  { code: 'TX', label: 'Texas' },
  { code: 'UT', label: 'Utah' },
  { code: 'VT', label: 'Vermont' },
  { code: 'VI', label: 'Virgin Islands' },
  { code: 'VA', label: 'Virginia' },
  { code: 'WA', label: 'Washington' },
  { code: 'WV', label: 'West Virginia' },
  { code: 'WI', label: 'Wisconsin' },
  { code: 'WY', label: 'Wyoming' },
]

/** Canadian provinces + territories. Alphabetical by label. */
export const CA_PROVINCE_OPTIONS: StateOption[] = [
  { code: 'AB', label: 'Alberta' },
  { code: 'BC', label: 'British Columbia' },
  { code: 'MB', label: 'Manitoba' },
  { code: 'NB', label: 'New Brunswick' },
  { code: 'NL', label: 'Newfoundland and Labrador' },
  { code: 'NT', label: 'Northwest Territories' },
  { code: 'NS', label: 'Nova Scotia' },
  { code: 'NU', label: 'Nunavut' },
  { code: 'ON', label: 'Ontario' },
  { code: 'PE', label: 'Prince Edward Island' },
  { code: 'QC', label: 'Quebec' },
  { code: 'SK', label: 'Saskatchewan' },
  { code: 'YT', label: 'Yukon' },
]

/** Australian states + territories. Alphabetical by label. */
export const AU_STATE_OPTIONS: StateOption[] = [
  { code: 'ACT', label: 'Australian Capital Territory' },
  { code: 'NSW', label: 'New South Wales' },
  { code: 'NT', label: 'Northern Territory' },
  { code: 'QLD', label: 'Queensland' },
  { code: 'SA', label: 'South Australia' },
  { code: 'TAS', label: 'Tasmania' },
  { code: 'VIC', label: 'Victoria' },
  { code: 'WA', label: 'Western Australia' },
]

/**
 * Country code (uppercase) → list of valid states/provinces. Consumers
 * typically check `STATE_CODE_OPTIONS[country.toUpperCase()]`; falsy
 * means "free text, no dropdown".
 */
export const STATE_CODE_OPTIONS: Record<string, StateOption[]> = {
  US: US_STATE_OPTIONS,
  CA: CA_PROVINCE_OPTIONS,
  AU: AU_STATE_OPTIONS,
}

/** Convenience: valid-code Set per country, for schema membership tests. */
export const STATE_CODE_SETS: Record<string, Set<string>> = {
  US: new Set(US_STATE_OPTIONS.map((s) => s.code)),
  CA: new Set(CA_PROVINCE_OPTIONS.map((s) => s.code)),
  AU: new Set(AU_STATE_OPTIONS.map((s) => s.code)),
}
