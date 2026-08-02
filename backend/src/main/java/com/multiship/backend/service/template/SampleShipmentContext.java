package com.multiship.backend.service.template;

import java.util.List;
import java.util.Map;

/**
 * Static sample data for the template preview endpoint. Every field in the
 * frontend's BINDING_GROUPS catalog has a plausible value here so operators
 * can see a template render end-to-end before any real shipment exists.
 *
 * <p>When Phase 2b/c wire a real Shipment-to-context adapter, this file
 * becomes the "sample" branch; the same shape is what the adapter emits from
 * a live row. Keep the keys aligned with
 * {@code multiship-react/src/utils/templateLayout.ts:BINDING_GROUPS}.
 */
public final class SampleShipmentContext {

    private SampleShipmentContext() { /* static utility */ }

    public static Map<String, Object> sample() {
        return Map.ofEntries(
                Map.entry("order", Map.of(
                        "number", "ORD-000123",
                        "poNumber", "PO-2026-45",
                        "reference", "REF/A/00042",
                        "notes", "Fragile — inner carton branded",
                        "createdAt", "2026-01-15",
                        "currency", "USD"
                )),
                Map.entry("client", Map.of(
                        "code", "MA1885",
                        "name", "Modern Art Fabrics",
                        "email", "billing@modernart.example",
                        "phone", "+1 555-000-1234"
                )),
                Map.entry("shipTo", Map.of(
                        "name", "Ava Chen",
                        "line1", "42 Sample Way",
                        "line2", "Apt 7B",
                        "city", "Portland",
                        "state", "OR",
                        "zip", "97201",
                        "country", "US",
                        "phone", "+1 503-555-0142",
                        "email", "ava.chen@example.com"
                )),
                Map.entry("shipFrom", Map.of(
                        "name", "MA Fabrics Warehouse East",
                        "line1", "1 Industrial Way",
                        "line2", "",
                        "city", "Chicago",
                        "state", "IL",
                        "zip", "60601",
                        "country", "US",
                        "phone", "+1 312-555-0100"
                )),
                Map.entry("return", Map.of(
                        "name", "MA Fabrics Returns",
                        "line1", "1 Industrial Way, Dock 4",
                        "city", "Chicago",
                        "state", "IL",
                        "zip", "60601",
                        "country", "US"
                )),
                Map.entry("shipment", Map.of(
                        "trackingNumber", "1Z9999W99999999999",
                        "serviceCode", "03",
                        "serviceName", "UPS Ground",
                        "carrier", "UPS",
                        "weight", "4.5",
                        "weightUnit", "LB",
                        "dimensions", "10×8×6 in",
                        "declaredValue", "250.00",
                        "dispatchDate", "2026-01-16"
                )),
                Map.entry("customs", Map.of(
                        "purpose", "SALE",
                        "clearance", "DDP",
                        "incoterms", "DAP",
                        "reasonForExport", "Commercial sale",
                        "importer", Map.of("name", "Ava Chen", "taxId", ""),
                        "broker", Map.of("name", "", "company", "Carrier default")
                )),
                Map.entry("account", Map.of(
                        "number", "AC123456",
                        "name", "MA Fabrics UPS",
                        "environment", "PRODUCTION"
                )),
                // Items list — items[0].sku etc. bindings resolve against this.
                // The HTML/PDF/ZPL renderers also read the whole list when the
                // template has an ITEMS block, so single access + iteration
                // share the same source of truth.
                Map.entry("items", List.of(
                        Map.of("sku", "SKU-100", "description", "Silk lining, natural",  "qty", 2, "unitPrice", "45.00", "lineTotal", "90.00",  "weight", "0.5", "hsCode", "5007.20", "originCountry", "IT"),
                        Map.of("sku", "SKU-200", "description", "Cotton canvas, cream",  "qty", 1, "unitPrice", "60.00", "lineTotal", "60.00",  "weight", "1.2", "hsCode", "5209.11", "originCountry", "IN"),
                        Map.of("sku", "SKU-300", "description", "Wool tweed, charcoal",  "qty", 4, "unitPrice", "25.00", "lineTotal", "100.00", "weight", "2.8", "hsCode", "5111.19", "originCountry", "GB")
                )),
                Map.entry("totals", Map.of(
                        "subtotal", "250.00",
                        "freight", "18.50",
                        "duties", "12.00",
                        "insurance", "0.00",
                        "grandTotal", "280.50"
                ))
        );
    }
}
