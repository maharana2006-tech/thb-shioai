# Customs → Carrier API field mapping

**Status: NOT YET WIRED.** The dev connectors (UpsConnector/FedexConnector/StampsConnector)
fake successful shipments and ignore customs data. Today the customs profile only feeds
`GET /orders/{n}/label` (our own label document). When real carrier accounts land,
`CarrierServiceImpl.buildShipmentRequest` must carry the fields below into the carrier
request — this doc is the map so nobody re-reverse-engineers the model.

## Source model

`client_customs_profile` (+ `customs_profile_country`) resolved at ship time by
`findByClientAndCountry(clientCode, order.shiptoCountryCd)`. Goods come from
`order_lines` (hs_code, country_of_origin, customs_decl_value, qty_shipped).

## Importer of Record

| Our field | Meaning | UPS Shipping API | FedEx Ship API |
|---|---|---|---|
| `importer_type = RECEIVER` (DAP) | Consignee is IOR | Omit SoldTo/importer block — ShipTo is the importer; carrier collects KYC | `customsClearanceDetail.importerOfRecord` omitted; recipient acts as IOR |
| `importer_type = BUSINESS` (DDP) | Fixed registered entity | `InternationalForms.Contacts.SoldTo` (name/address/phone) | `customsClearanceDetail.importerOfRecord` (contact + address) |
| `importer_tax_id` + `importer_tax_id_type` | VAT/EIN/GST… | `SoldTo.TaxIdentificationNumber` | `importerOfRecord.tins[]` (`tinType`) |
| `importer_eori` | EU/UK EORI | Shipper/SoldTo tax ID with EORI type | `tins[]` type `BUSINESS_NATIONAL` w/ EORI |
| `importer_ioss` | EU low-value B2C | `TaxInformationIndicator` + IOSS registration | `customsClearanceDetail.recipientCustomsId` / IOSS field |
| `importer_iec`, `importer_gstin` | India IEC / GSTIN | SoldTo tax IDs (India routing) | `tins[]`; India clearance guide fields |

## Broker

| Our state | Meaning | UPS | FedEx |
|---|---|---|---|
| `broker_name` empty → `brokerage=CARRIER_DEFAULT` | Carrier's included brokerage clears | Nothing to send (default) | Nothing to send (broker-inclusive default) |
| `broker_name` set → `brokerage=BROKER_SELECT` | Hand off to named broker at border | `ShipmentServiceOptions.Broker` (name/address/phone) + Broker Select accessorial | `customsClearanceDetail.brokers[]` with `type=IMPORT`, `broker` contact + `BROKER_SELECT_OPTION` special service |

## Shipment defaults

| Our field | UPS | FedEx |
|---|---|---|
| `incoterms` | `InternationalForms.TermsOfShipment` (DDP/DAP/…) | `customsClearanceDetail.commercialInvoice.termsOfSale` |
| `duties_bill_to` (+`duties_account`) | `ItemizedPaymentInformation.ShipmentCharge[Type=02 Duties]` BillShipper/BillReceiver/BillThirdParty(+account) | `customsClearanceDetail.dutiesPayment.paymentType` (SENDER/RECIPIENT/THIRD_PARTY + payor account) |
| `currency` | `InternationalForms.CurrencyCode` | `commercialInvoice` `customsValue.currency` |
| `reason_for_export` | `InternationalForms.ReasonForExport` | `commercialInvoice.shipmentPurpose` |

## Commercial invoice items (from order_lines)

| Our field | UPS `InternationalForms.Product[]` | FedEx `commodities[]` |
|---|---|---|
| `item_description`/`description` | `Description` | `description` |
| `hs_code` | `CommodityCode` | `harmonizedCode` |
| `country_of_origin` | `OriginCountryCode` | `countryOfManufacture` |
| `qty_shipped` | `Unit.Number` | `quantity` (+`quantityUnits`) |
| `customs_decl_value` / `unit_price` | `Unit.Value` | `unitPrice` / `customsValue` |

Missing `hs_code` per item is surfaced as a WARNING in the order's customs view
(clearance may be delayed) — deliberately not a generation blocker.

## Same-territory shipments = domestic (gate + payload)

`util/CustomsTerritories.sameTerritory(origin, dest)` — customs only applies
across a CUSTOMS border. Intra-union shipments (DE→FR in the EU, SA→AE in the
GCC…) skip the customs gate entirely and the label payload carries no
importer/broker blocks, exactly like a domestic parcel. This class is the
backend copy of the frontend taxonomy in `countries.ts` — update both together.

## Customs territories (frontend-enforced)

A BUSINESS (DDP) importer registration is valid for exactly ONE customs
territory: the EU, EAEU (RU/BY/KZ/AM/KG), GCC (SA/AE/KW/QA/BH/OM), SACU
(ZA/BW/NA/LS/SZ) — or a single country everywhere else. The UI enforces this
(picker locks to the first country's territory; importer country must be inside
it). NOTE: enforcement is client-side only — the API accepts any country set;
add a server-side territory map if API consumers beyond our UI appear.
RECEIVER (DAP) profiles are exempt (no fixed importer identity).

## US territories nuance

USPS treats Puerto Rico (and largely USVI/Guam) as DOMESTIC — no customs forms.
UPS/FedEx require customs declarations for PR/Guam. Our gate treats any
ship-to ≠ origin country as international regardless of carrier; if PR/GU/VI
orders appear, gate/document generation may need carrier-aware exceptions
(PR is inside the US customs territory; USVI/Guam are outside it).

## Paperless

Both carriers support electronic transmission (UPS Paperless / FedEx ETD) —
enable via `SpecialServicesRequested.ElectronicTradeDocuments` (FedEx) /
`InternationalForms` inclusion (UPS) so no printed invoice is required.
