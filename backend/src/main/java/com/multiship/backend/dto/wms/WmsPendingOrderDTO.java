package com.multiship.backend.dto.wms;

import lombok.Data;

import java.util.List;

/**
 * One shippable shipment as returned by the WMS
 * {@code GET /api/v1/shipping-label/pending-orders} endpoint.
 *
 * <p>NOTE: a single WMS orderNo can appear more than once (one row per
 * shipment transaction / backorder split), so {@code shipmentNumber} — not
 * {@code orderNo} — is the stable, per-shipment key used for idempotent pulls.
 * Unknown JSON fields are ignored by the client's lenient ObjectMapper.
 */
@Data
public class WmsPendingOrderDTO {

    private Long orderId;
    private String orderNo;
    private String poNumber;
    private String custNo;
    private Long shipmentTransactionId;
    private String shipmentNumber;
    private Integer backorderNumber;
    private String transactionDate;   // yyyy-MM-dd
    private String shipVia;           // WMS ship-via code, e.g. "U11"
    private String shipMethod;
    private Integer totalShipQty;
    private Integer totalBackorderQty;

    private WmsAddress shipToAddress;
    private WmsAddress billFromAddress;
    private List<WmsItem> items;
    private List<WmsContainer> containers;

    @Data
    public static class WmsAddress {
        private String name;
        private String attn;
        private String email;
        private String emailCc;
        private String phone;
        private String addr1;
        private String addr2;
        private String city;
        private String state;
        private String country;
        private String iso2;
        private String zip;
    }

    @Data
    public static class WmsItem {
        private String itemNo;
        private String itemDesc;
        private Integer orderQty;
        private Integer shipQty;
        private Integer backorderQty;
    }

    @Data
    public static class WmsContainer {
        private String containerId;
        private Double weight;
    }
}
