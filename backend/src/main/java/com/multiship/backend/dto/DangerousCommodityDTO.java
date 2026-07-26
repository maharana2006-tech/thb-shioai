package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One dangerous commodity line item within a
 * {@link DangerousGoodsBlockDTO}. Fields map to IATA DGR / UN Model
 * Regulations column names so wire emission is straightforward.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DangerousCommodityDTO {

    /**
     * UN number, e.g. "UN3480" (lithium ion batteries), "UN1993"
     * (flammable liquid n.o.s.). Format is {@code UN\d{4}} — the
     * validator enforces the shape.
     */
    private String unNumber;

    /**
     * Proper shipping name, e.g. "Lithium ion batteries" for UN3480.
     * Comes from the UN Model Regulations Table A. When the caller
     * doesn't supply it, the {@code UnNumberDirectory} looks it up.
     */
    private String properShippingName;

    /**
     * Hazard class, "1" through "9" (some classes have subclasses like
     * "4.1", "5.2"). See UN Recommendations on the Transport of
     * Dangerous Goods.
     */
    private String hazardClass;

    /**
     * Packing group: "I" (high danger), "II" (medium), "III" (low), or
     * null for commodities that don't have a packing group (Class 1
     * explosives, Class 7 radioactives).
     */
    private String packingGroup;

    /**
     * Quantity per package — mass for solids, volume for liquids.
     * IATA rate the shipment on quantity, not weight.
     */
    private BigDecimal quantity;

    /**
     * Unit for {@link #quantity}: "KG" | "L" | "PCS" (individual items
     * like batteries). Case-insensitive.
     */
    private String quantityUnit;

    /**
     * True when this commodity is subject to LIMITED QUANTITY rules
     * (small enough to ship under simplified paperwork per IATA
     * Section II). Carriers price limited-quantity shipments
     * differently.
     */
    private Boolean limitedQuantity;

    /**
     * Number of physical packages containing this commodity. Not the
     * total shipment package count — the DG package count within it.
     * Defaults to 1 when null.
     */
    private Integer packageCount;
}
