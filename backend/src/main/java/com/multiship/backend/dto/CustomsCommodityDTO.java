package com.multiship.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One commodity line on the commercial invoice sent to a carrier. Populated
 * from {@link com.multiship.backend.model.OrderCustomsItem} at label time and
 * translated per-carrier into UPS {@code Product}, FedEx {@code Commodity},
 * or SWSIM {@code CustomsLine} shapes. Carrier-neutral so the same struct
 * lands in every connector; each connector is responsible for the field
 * renames and value coercions its API needs.
 *
 * <p>Unit weight is expressed in the parent shipment's {@link
 * IntlShipmentBlockDTO#weightUnit}; unit value is in the parent's {@link
 * IntlShipmentBlockDTO#customsCurrency}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomsCommodityDTO {

    private String description;

    /** Harmonized System / tariff code (6-10 digits). Never enforced numeric so alpha suffixes survive. */
    private String hsCode;

    /** ISO-3166 alpha-2 country where the goods were manufactured. */
    private String countryOfOrigin;

    private Integer quantity;

    /** Unit customs value in the parent's currency. */
    private BigDecimal unitValue;

    /** Per-unit weight in the parent's weightUnit; null when unknown. */
    private BigDecimal unitWeight;

    private String sku;

    /**
     * Line total = quantity × unitValue. Convenience for connectors that need
     * a pre-computed line-value column on the invoice; safe against null
     * inputs so partial rows don't NPE at translation time.
     */
    public BigDecimal lineTotalValue() {
        if (quantity == null || unitValue == null) return null;
        return unitValue.multiply(BigDecimal.valueOf(quantity));
    }
}
