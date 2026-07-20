import type { CustomsProfile } from '../api/customsProfileService'
import { territoryLabel } from './countries'

/**
 * Country-specific tax identity for the Importer of Record. Every customs
 * territory names its own identifiers (Brazil CNPJ, Mexico RFC, EU VAT+EORI,
 * Canada BN+RM…) — a generic "Tax ID" field is not how customs paperwork
 * works, and some countries RETURN parcels without the right number.
 *
 * No new DB columns: each field maps onto an existing profile column, and the
 * spec's typeCode is auto-stored in importer_tax_id_type so documents print
 * "CNPJ: …" / "EIN: …" correctly. Column reuse:
 *   importerTaxId      → the territory's primary tax/importer number
 *   importerEori       → customs registration (EU/UK EORI)
 *   importerIoss       → low-value B2C scheme (EU IOSS, Norway VOEC)
 *   importerCompanyReg → secondary registration (Companies House, JCT, NZ
 *                        customs code, SARS code…)
 *   importerIec/Gstin  → India-specific (unchanged)
 */
export interface TaxField {
  column: keyof Pick<
    CustomsProfile,
    'importerTaxId' | 'importerEori' | 'importerIoss' | 'importerCompanyReg' | 'importerIec' | 'importerGstin'
  >
  label: string
  placeholder?: string
}

export interface TaxIdentitySpec {
  /** Stored in importer_tax_id_type — printed on documents ("CNPJ: …"). */
  typeCode: string
  fields: TaxField[]
  note?: string
}

const SPECS: Record<string, TaxIdentitySpec> = {
  EU: {
    typeCode: 'VAT',
    fields: [
      { column: 'importerTaxId', label: 'EU VAT Number', placeholder: 'DE123456789' },
      { column: 'importerEori', label: 'EORI (mandatory)', placeholder: 'DE1234567890123' },
      { column: 'importerIoss', label: 'IOSS (B2C ≤ €150, optional)', placeholder: 'IM1234567890' },
      { column: 'importerCompanyReg', label: 'Company Reg. No (optional)' },
    ],
    note: 'One VAT + EORI registration is valid across all 27 EU member states.',
  },
  GB: {
    typeCode: 'VAT',
    fields: [
      { column: 'importerTaxId', label: 'UK VAT Number (VRN)', placeholder: 'GB123456789' },
      { column: 'importerEori', label: 'UK EORI', placeholder: 'GB123456789000' },
      { column: 'importerCompanyReg', label: 'Companies House No (optional)' },
    ],
    note: 'Post-Brexit the UK needs its own GB EORI — an EU EORI is not valid here.',
  },
  US: {
    typeCode: 'EIN',
    fields: [{ column: 'importerTaxId', label: 'EIN (IRS Employer ID)', placeholder: '12-3456789' }],
    note: 'The customs bond is usually arranged by the carrier/broker against this EIN.',
  },
  CA: {
    typeCode: 'BN',
    fields: [
      { column: 'importerTaxId', label: 'Business Number + RM account', placeholder: '123456789RM0001' },
    ],
    note: 'Commercial importers must be registered in CBSA’s CARM portal.',
  },
  IN: {
    typeCode: 'PAN',
    fields: [
      { column: 'importerGstin', label: 'GSTIN', placeholder: '27AAAPA1234A1Z5' },
      { column: 'importerIec', label: 'IEC (mandatory)', placeholder: '0512345678' },
      { column: 'importerTaxId', label: 'PAN (optional)', placeholder: 'AAAPA1234A' },
    ],
    note: 'India: business imports need an IEC; customs KYC matches the GSTIN/PAN to the importer name.',
  },
  AU: {
    typeCode: 'ABN',
    fields: [{ column: 'importerTaxId', label: 'ABN', placeholder: '51 824 753 556' }],
    note: 'GST-registered importers can defer import GST via the deferral scheme.',
  },
  NZ: {
    typeCode: 'GST',
    fields: [
      { column: 'importerTaxId', label: 'NZ GST Number', placeholder: '123-456-789' },
      { column: 'importerCompanyReg', label: 'NZ Customs client code' },
    ],
    note: 'Shipments over NZ$1,000 need a Customs client code.',
  },
  GCC: {
    typeCode: 'TRN',
    fields: [{ column: 'importerTaxId', label: 'TRN (VAT Registration)', placeholder: '100234567800003' }],
    note: 'One GCC customs territory — cleared once at first point of entry.',
  },
  CH: {
    typeCode: 'UID',
    fields: [{ column: 'importerTaxId', label: 'UID / VAT', placeholder: 'CHE-116.281.710 MWST' }],
  },
  NO: {
    typeCode: 'VAT',
    fields: [
      { column: 'importerTaxId', label: 'Org. Number / VAT', placeholder: '923 456 789 MVA' },
      { column: 'importerIoss', label: 'VOEC (B2C, optional)', placeholder: '2001234' },
    ],
  },
  JP: {
    typeCode: 'CORP-NO',
    fields: [
      { column: 'importerTaxId', label: 'Corporate Number (Hōjin Bangō)', placeholder: '1234567890123' },
      { column: 'importerCompanyReg', label: 'JCT Registration (optional)', placeholder: 'T1234567890123' },
    ],
  },
  CN: {
    typeCode: 'USCC',
    fields: [{ column: 'importerTaxId', label: 'USCC (Unified Social Credit Code)', placeholder: '91110000600037341L' }],
    note: 'Consumer imports may additionally need a CR (customs registration) code.',
  },
  SG: {
    typeCode: 'UEN',
    fields: [
      { column: 'importerTaxId', label: 'UEN', placeholder: '201912345K' },
      { column: 'importerCompanyReg', label: 'GST Registration (optional)' },
    ],
  },
  BR: {
    typeCode: 'CNPJ',
    fields: [{ column: 'importerTaxId', label: 'CNPJ (mandatory)', placeholder: '12.345.678/0001-95' }],
    note: 'Brazilian customs RETURNS or destroys shipments without a valid CNPJ.',
  },
  MX: {
    typeCode: 'RFC',
    fields: [{ column: 'importerTaxId', label: 'RFC (mandatory)', placeholder: 'ABC850101AAA' }],
    note: 'Shipments without the RFC are held by Mexican customs.',
  },
  EAEU: {
    typeCode: 'INN',
    fields: [{ column: 'importerTaxId', label: 'INN (Tax Number)', placeholder: '7707083893' }],
  },
  SACU: {
    typeCode: 'VAT',
    fields: [
      { column: 'importerTaxId', label: 'VAT Number', placeholder: '4123456789' },
      { column: 'importerCompanyReg', label: 'SARS Importer/Customs Code' },
    ],
  },
}

/** The generic fallback for territories without a curated spec. */
const GENERIC: TaxIdentitySpec = {
  typeCode: 'OTHER',
  fields: [
    { column: 'importerTaxId', label: 'Tax ID / VAT' },
    { column: 'importerCompanyReg', label: 'Company Reg. No (optional)' },
  ],
}

/** The tax identity fields the destination territory actually requires. */
export function taxIdentityFor(territory: string): TaxIdentitySpec {
  return SPECS[territory] ?? GENERIC
}

/** Section subtitle, e.g. "Tax identity — the EU customs union". */
export function taxIdentityTitle(territory: string): string {
  return `Tax identity — ${territoryLabel(territory)}`
}
