/**
 * F5-B — types + validators shared across ClientEditorPage step
 * components. Split out from _shared.tsx so the primitives file
 * only exports React components (react-refresh rule).
 */
import type { CarrierEnvironment } from '../../utils/carrierUtils'

/** Wizard step keys — trimmed to the mandatory onboarding path:
 *    identity → shipFrom → return → carriers → mapping
 *  markup + importerBroker are optional; summary is the final review. */
export type StepKey =
  | 'identity'
  | 'shipFrom'
  | 'return'
  | 'carriers'
  | 'mapping'
  | 'markup'
  | 'importerBroker'
  | 'summary'

/** One carrier-account staged in create mode. Field shape mirrors the
 *  accountRefService.upsertAccount payload so `handleCreate` can post each
 *  draft directly. `id` is client-side only — a monotonic counter used as
 *  the React key. */
export type CarrierAccountDraft = {
  id: number
  carrierCode: string
  accountNumber: string
  accountName: string
  clientId: string
  clientSecret: string
  environment: CarrierEnvironment
  clientDefault: boolean
  /** International-shipment defaults for this account. Optional; carriers
   *  fall back to their own defaults when unset. */
  shippingPurpose: string
  clearanceOption: string
  /** Third-party billing default — only meaningful when clearanceOption is
   *  THIRD_PARTY. Persisted with the account; per-shipment overrides live
   *  on the Shipment row (follow-up). */
  thirdPartyAccount: string
  thirdPartyName: string
  thirdPartyAddress1: string
  thirdPartyCity: string
  thirdPartyState: string
  thirdPartyPostcode: string
  thirdPartyCountry: string
}

/** One shipping-service mapping rule staged in create mode. Minimal fields
 *  only — full scoping (destination zone, warehouse restriction, package
 *  allowlist) lives in the post-create Mapping step editor. */
export type MappingRuleDraft = {
  id: number
  shipviaCd: string
  serviceId: number
}

/**
 * Create-mode-only draft for the Importer / Broker step. Persisted to the
 * per-user localStorage draft alongside carrierDrafts + mappingDrafts, and
 * committed via `customsProfileService.save` immediately after the client
 * is created (mirrors the carrier + mapping post-create fan-out).
 *
 * `filled=false` means the operator explicitly skipped this step — the
 * persist path is a no-op. `filled=true` requires the BUSINESS-importer
 * identity fields; RECEIVER (DAP consignee-is-IOR) has no identity of
 * its own, so those fields stay optional.
 */
export type ImporterBrokerDraft = {
  filled: boolean
  countries: string[]  // ISO-3166 alpha-2; empty = catch-all
  importerType: 'BUSINESS' | 'RECEIVER'
  importerName: string
  importerCountry: string
  importerAddress1: string
  importerAddress2: string
  importerCity: string
  importerState: string
  importerPostcode: string
  importerPhone: string
  importerTaxId: string
  importerTaxIdType: string
  brokerName: string
  brokerPhone: string
  incoterms: string
  reasonForExport: string
}

export const emptyImporterBrokerDraft = (): ImporterBrokerDraft => ({
  filled: false,
  countries: [],
  importerType: 'BUSINESS',
  importerName: '',
  importerCountry: '',
  importerAddress1: '',
  importerAddress2: '',
  importerCity: '',
  importerState: '',
  importerPostcode: '',
  importerPhone: '',
  importerTaxId: '',
  importerTaxIdType: '',
  brokerName: '',
  brokerPhone: '',
  incoterms: '',
  reasonForExport: '',
})

/** BUSINESS-importer requires the identity + address block. RECEIVER
 *  profiles legitimately have no importer identity (DAP terms — the
 *  receiver is the IOR), so a filled=true RECEIVER always validates. */
export function importerBrokerDraftValid(d: ImporterBrokerDraft): boolean {
  if (!d.filled) return true
  if (d.importerType === 'RECEIVER') return true
  return !!d.importerName.trim()
    && !!d.importerCountry.trim()
    && !!d.importerAddress1.trim()
    && !!d.importerCity.trim()
    && !!d.importerPostcode.trim()
}
