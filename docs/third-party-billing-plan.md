# Third-party billing on orders — research report

Date: 2026-10-30. Scope: let an order's shipping charges be billed to a third party's carrier account (not the shipper's, not the recipient's). Covers manual New Shipment, bulk import, and the carrier calls.

## 1. What the app has today

| Area | State | Where |
|---|---|---|
| Manual New Shipment | Only a "Account (bill to)" box listing the client's own registered accounts. No bill-to party choice. | `multiship-react/src/components/NewShipmentComponents/sections/ServiceSection.tsx:45` |
| Duties / taxes (international only) | Full third-party support: `clearanceOption=THIRD_PARTY` + `dutiesAccount`. This is customs duties, not freight. | `ServiceSection.tsx:202-218`, `ManualShipmentRequest.java:145-152`, `UpsConnector.buildPaymentInformation` (Type 02 BillThirdParty) |
| Carrier Accounts settings | `third_party_account/name/address1/city/state/postcode/country` columns on `carrier_account_ref`, used only as the duties-clearance default. | `model/CarrierAccountRef.java:232-260`, `components/CarrierConnections.tsx:189-230` |
| Bulk import | `billTo` column (SENDER / RECIPIENT / THIRD_PARTY) + free-text `accountNumber`. Stored in `import_batch_row.bill_to`. Upload validation skips the registered-account check for THIRD_PARTY and only format-checks the number. | `dto/OrderImportRowDTO.java:46-52`, `OrderImportServiceImpl.java:1512-1545, 6252-6290` |
| Bulk → label generation | **Broken for THIRD_PARTY.** The row's account is copied into `ManualShipmentRequest.accountNumber` (`OrderImportServiceImpl.java:5255`), and `CarrierServiceImpl` then requires that number to be a registered platform/client account (`CarrierServiceImpl.java:1896-1902`) → 422 "not a registered X account in the platform". `billTo` is never read outside the import service. | |
| UPS adapter | Freight is always `ShipmentCharge Type 01 / BillShipper` with the shipper account. | `UpsConnector.java:2797-2803` |
| FedEx adapter | `shippingChargesPayment.paymentType` is hard-coded `SENDER`. | `FedExConnector.java:2382-2385` |
| DHL adapter | `accounts[]` always `typeCode=shipper`. | `DhlConnector.java:1027, 1291, 1897` |
| USPS / Stamps | No third-party billing exists at the carrier (postage is prepaid). | |
| Orders table (`label_batch`) | No bill-to columns. `OrderTracking.account_number` stores the account that was billed. | `model/Order.java`, `CarrierServiceImpl.java:3361` |
| D2C orders | `dtc_orders.third_party_account` arrives from Oracle but is not used when labelling. | `model/DtcOrder.java:140` |
| Writeback / NDS | `WritebackPayload.thirdPartyAccount` exists but currently carries the **duties** account. | `CarrierServiceImpl.java:3467` |

Conclusion: the vocabulary (SENDER / RECIPIENT / THIRD_PARTY) and the account-format validation already exist, but freight third-party billing is not wired anywhere between the request and the carrier.

## 2. Design

Keep the shipper's own registered account exactly as it is today (it is the UPS `ShipperNumber` / FedEx account the credentials belong to). Add a **separate** payer block that is only used for the freight payment element. Never substitute the third-party number into `accountNumber`; that is what breaks the bulk path now.

Fields (same names on every layer):

| Field | Type | Rule |
|---|---|---|
| `billTo` | `SENDER` (default) / `THIRD_PARTY` | Reuse the existing bulk vocabulary. `RECIPIENT` can be added later; it needs the recipient's account and is not requested now. |
| `thirdPartyAccount` | string ≤ 100 | Required when `billTo=THIRD_PARTY`. Format-check with the per-carrier regex already used by the import (`OrderImportServiceImpl.java:6072`) and `validation/carrierAccountValidation.ts`. |
| `thirdPartyPostalCode` | string | Required for UPS (`BillThirdParty.Address.PostalCode` is mandatory and must match the payer's UPS billing postcode). Optional for FedEx. |
| `thirdPartyCountry` | ISO-2 | Required for UPS, recommended for FedEx. |

Carrier mapping:

- **UPS**: `PaymentInformation.ShipmentCharge[{Type:"01", BillThirdParty:{AccountNumber, Address:{PostalCode, CountryCode}}}]`. Shipper party keeps `ShipperNumber` = shipper account.
- **FedEx**: `shippingChargesPayment:{paymentType:"THIRD_PARTY", payor:{responsibleParty:{accountNumber:{value}, address:{postalCode, countryCode}}}}`.
- **DHL Express** (optional, later): add `{typeCode:"payer", number}` to `accounts[]`.
- **USPS / Stamps**: not supported. Reject with a 422 on the field and hide the option in the UI.

Rates: pass the same block on the rate-shop request (UPS `buildRateShopShipment` ~`UpsConnector.java:2076`, FedEx rate request) so quoted rates reflect the payer's tariff. Nice-to-have; the label call is the must.

## 3. Backend changes

| # | File | Change |
|---|---|---|
| 1 | `dto/ManualShipmentRequest.java` | Add `billTo`, `thirdPartyAccount`, `thirdPartyPostalCode`, `thirdPartyCountry` (next to `accountNumber`, line 58). |
| 2 | `dto/ShipmentRequestDTO.java` | Same four fields (carrier-facing DTO). |
| 3 | `service/CarrierServiceImpl.java` | In `resolveManualShipment` (~line 1663-1950): normalise `billTo` (reuse the `case "THIRD_PARTY", "THIRDPARTY"` mapper at line 4717); when THIRD_PARTY require account (+ postcode/country for UPS), reject USPS with `FieldValidationException("thirdPartyAccount", …)`. Copy the block onto `ShipmentRequestDTO` in the builder at ~line 4866-4900. Persist on the `Order` row; add it to the audit note at ~line 3392 with the number masked (`****1234`). |
| 4 | `service/carriers/UpsConnector.java` | `buildPaymentInformation` (line 2797): branch Type 01 to `BillThirdParty` when `billTo=THIRD_PARTY`. Same in the rate-shop payment block. |
| 5 | `service/carriers/FedExConnector.java` | Line 2382: choose `paymentType` + payor from the request instead of the `SENDER` literal. |
| 6 | `model/Order.java` + `db/migration/V183__label_batch_bill_to.sql` | `ALTER TABLE label_batch ADD COLUMN bill_to VARCHAR(16) NOT NULL DEFAULT 'SENDER', ADD COLUMN third_party_account VARCHAR(100), ADD COLUMN third_party_postal_code VARCHAR(20), ADD COLUMN third_party_country VARCHAR(2);` (table is LIST-partitioned; an ALTER on the parent propagates to the partitions). |
| 7 | `service/OrderImportServiceImpl.java` | Row → request conversion (line 5255): when the row's `billTo=THIRD_PARTY`, set `req.billTo` and `req.thirdPartyAccount = row.accountNumber`, and leave `req.accountNumber` blank so the existing `resolveBulkAccount` picks the client's shipper account. Add optional `thirdPartyPostcode` / `thirdPartyCountry` columns to `OrderImportRowDTO`, `ImportBatchRowStore`, `ImportColumnAliases`, `OrderImportTemplateBuilder`, and the validation at line 6252 (UPS rows need the postcode). Default both from the matched `CarrierAccountRef` third-party fields when blank. |
| 8 | Order-details payload (`carrierAccount` block in the order-with-lines DTO) | Add `billTo` and a masked `thirdPartyAccount` so the details modal and exports can show who was billed. |
| 9 | `service/externalsystems/writeback/WritebackPayload.java` / `NdsShipmentOracleWriter.java:267` | Decide whether `thirdParty` (VARCHAR 20 in NDS) should carry the freight payer instead of the duties account. Today it carries duties. |
| 10 | External API (`/api/v1/external/create`) | Optional: expose the same four fields on the external create DTO. |
| 11 | Tests | `CarrierServiceImpl` resolve tests (required fields, USPS rejection, masking), `UpsConnector` / `FedExConnector` payment-block tests, import validation test for THIRD_PARTY rows, a migration smoke test if the suite has one. |

## 4. Frontend changes

| # | File | Change |
|---|---|---|
| 1 | `components/NewShipmentComponents/sections/ServiceSection.tsx` | In the "Account & service" card: rename "Account (bill to)" to "Shipper account". Below it add a radio/segmented control **"Shipping charges billed to"**: *Sender* (default) / *Third party*. When Third party: "Third-party account number" (required, format error from `validation/carrierAccountValidation.ts`), "Payer postal code" (required for UPS), "Payer country" (reuse the country select from `AddressesSection.tsx`). For USPS disable the option with the hint "USPS postage is prepaid; third-party billing is not available". Prefill the three fields from the matched account's `thirdPartyAccount/Postcode/Country` when the operator switches to Third party. |
| 2 | `components/NewShipmentComponents/useNewShipmentModel.ts` | State for the four fields; include in the request payload (~line 823) and the rate payload; add `errAt('thirdPartyAccount')` validation; add to the "ready to generate" gate (~line 884); reset to SENDER when the carrier changes (~line 989) or becomes USPS; include in the review summary (~line 1866) and the unsaved-work dirty set. |
| 3 | `api/orderService.ts` | Add the fields to the manual-shipment request type and to the order-details payload type. |
| 4 | `components/modals/OrderDetailsModal.tsx` | In the `Shipment` block (~line 352-366) add a line "Billed to: Third party · ****1234" (Sender shows nothing new). |
| 5 | `components/modals/OrderImportModal.tsx` + `components/bulk/FixRowPanel.tsx` | `billTo` is already a column. Add `thirdPartyPostcode` / `thirdPartyCountry` to the column list (line 912/1056) and to the "Shipping" group in the fix panel (line 13). Show a row-level error when THIRD_PARTY on UPS has no postcode. |
| 6 | Settings → Carrier Accounts (`CarrierConnections.tsx`) | No new fields; optionally relabel the third-party block to say it is the default payer for both duties and freight. |
| 7 | Tests | `ServiceSection` render test (option hidden for USPS, required errors), model test for payload/reset, details-modal test for the masked line. |

Not needed now (YAGNI): an orders-list column, label-template field for "billed to", AI-assist prompt changes, RECIPIENT billing.

## 5. Order of work and effort

1. Backend DTOs + migration + `CarrierServiceImpl` validation/persist (0.5 day).
2. UPS and FedEx payment blocks + unit tests (0.5 day).
3. New Shipment UI + model + details modal (1 day).
4. Bulk import wiring + template columns (0.5 day).
5. Writeback decision, external API, rate requests (0.5 day, optional).

Testing limits on this machine: FedEx sandbox works end to end; UPS CIE auth is broken here, so UPS is covered by payload unit tests only; USPS is excluded by design.
