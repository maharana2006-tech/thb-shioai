import type { ManualShipmentAddress } from '../api/orderService'
import type { RateShopShipment } from '../api/rateShopService'

/**
 * Sprint 51 refactor — map a whole {@link ManualShipmentAddress} to the flat
 * {@code shipper*} keys the backend {@code ShipmentRequestDTO} accepts, or
 * to {@code recipient*} keys via the sibling helper. Prior to this refactor
 * the {@code rateShopRequest} builder in {@code NewShipmentPage} cherry-
 * picked ~7 fields per party by hand, which is how {@code shipperPhone} +
 * {@code recipientPhone} got silently omitted for weeks (fixed in #508) —
 * the operator filled the form, the JSON body left the field off, and the
 * backend {@code @NotBlank} pointed at a field the operator clearly filled.
 *
 * <p>Spreading the whole address prevents that class of bug: adding a new
 * field to {@link ManualShipmentAddress} + {@link RateShopShipment} makes
 * it flow through automatically. Fields that don't map to the shipment
 * DTO (currently {@code company}, {@code email}) are intentionally dropped
 * because the backend doesn't accept them yet — add them here if / when
 * the DTO catches up.
 *
 * <p>Empty strings coerce to {@code undefined} so the JSON body doesn't
 * carry {@code "phone": ""} (which would satisfy any FE-side "is defined"
 * check while failing the backend {@code @NotBlank}).
 *
 * <p><b>Country code is NEVER hardcoded.</b> Prior code fell back to
 * {@code "US"} inside the builder — which silently substituted a
 * different country than the operator picked whenever the field was
 * somehow blank. Now we pass through exactly what the address object
 * carries (empty string when unset); backend {@code @NotBlank} surfaces
 * the genuine miss loudly. Same rule for postal code — no builder-side
 * default, honest empty string when unset.
 */

const emptyToStr = (v?: string | null): string =>
  v != null && v.trim() !== '' ? v : ''

const emptyToUndef = (v?: string | null): string | undefined =>
  v != null && v.trim() !== '' ? v : undefined

/** Sender/shipper fields of a rate-shop or shipment request body. */
export type ShipperFields = Pick<RateShopShipment,
  'shipperName' | 'shipperPhone' | 'shipperAddressLine1' | 'shipperAddressLine2'
  | 'shipperCity' | 'shipperState' | 'shipperPostalCode' | 'shipperCountryCode'>

/** Recipient fields of a rate-shop or shipment request body. */
export type RecipientFields = Pick<RateShopShipment,
  'recipientName' | 'recipientPhone' | 'recipientPhoneCountryCode'
  | 'recipientAddressLine1' | 'recipientAddressLine2' | 'recipientAddressLine3'
  | 'recipientCity' | 'recipientState' | 'recipientPostalCode' | 'recipientCountryCode'
  | 'recipientResidential'>

/**
 * Flatten a sender {@link ManualShipmentAddress} into the {@code shipper*}
 * keys the shipment DTO uses. Postal code + country code pass through as
 * strings (empty when unset) so backend {@code @NotBlank} catches genuine
 * misses — the builder never substitutes a hardcoded country.
 */
export function shipperFieldsFrom(addr: ManualShipmentAddress): ShipperFields {
  return {
    shipperName: emptyToUndef(addr.name),
    shipperPhone: emptyToUndef(addr.phone),
    shipperAddressLine1: emptyToUndef(addr.addressLine1),
    shipperAddressLine2: emptyToUndef(addr.addressLine2),
    shipperCity: emptyToUndef(addr.city),
    shipperState: emptyToUndef(addr.state),
    shipperPostalCode: emptyToStr(addr.postalCode),
    shipperCountryCode: emptyToStr(addr.countryCode),
  }
}

/**
 * Flatten a recipient {@link ManualShipmentAddress} into the {@code recipient*}
 * keys the shipment DTO uses. {@code residential} passes through untouched
 * so the "unknown" (undefined) case falls to carrier default; only true /
 * false ever crosses the wire.
 */
export function recipientFieldsFrom(addr: ManualShipmentAddress): RecipientFields {
  return {
    recipientName: emptyToUndef(addr.name),
    recipientPhone: emptyToUndef(addr.phone),
    recipientPhoneCountryCode: emptyToUndef(addr.phoneCountryCode),
    recipientAddressLine1: emptyToUndef(addr.addressLine1),
    recipientAddressLine2: emptyToUndef(addr.addressLine2),
    recipientAddressLine3: emptyToUndef(addr.addressLine3),
    recipientCity: emptyToUndef(addr.city),
    recipientState: emptyToUndef(addr.state),
    recipientPostalCode: emptyToStr(addr.postalCode),
    recipientCountryCode: emptyToStr(addr.countryCode),
    recipientResidential: addr.residential,
  }
}
