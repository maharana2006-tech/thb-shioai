package com.multiship.backend.service.externalsystems.writeback;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V89 — payload the framework hands to a connector's
 * {@code writeShipment} method on label-generate success. Immutable.
 *
 * <p>Field-level nulls carry semantic meaning: a null field was NOT
 * flagged for writeback on this connection and the connector MUST
 * treat it as "leave the external row alone". The dispatcher
 * {@link ExternalSystemWritebackDispatcher} redacts unflagged fields
 * to null before calling the connector — connectors never need to
 * consult the flags themselves.
 *
 * <p>Promoted out of {@code NdsShipmentWriteback} in V89 so all
 * external-system connectors share one shape. NDS-specific keys
 * (containerIds, clientCode, scannedValue) stay on the payload
 * because they're just row-lookup keys — connectors that don't
 * recognise them ignore them harmlessly.
 *
 * @param connectionName connection name for logging / audit
 * @param scannedValue   original {@code .X} / {@code .Y} value the shipper scanned
 *                       (null for orders that didn't originate from a WMS scan)
 * @param clientCode     tenant / client identifier (NDS FF_SCHEMA, REST tenant)
 * @param batchId        external batch id (WMS batch), null for direct
 * @param orderNo        multiship order number (always present)
 * @param trackingNumber carrier tracking, null when writeback_tracking flag is off
 * @param shipDate       label-generated timestamp, null when writeback_ship_date is off
 * @param status         "SHIPPED" on generate, null when writeback_status is off
 * @param carrierCode    UPS / FEDEX / …, null when writeback_carrier is off
 * @param serviceCode    carrier-side service code (FEDEX_GROUND), null when off
 * @param freightAmount  final freight, null when writeback_freight is off
 * @param currency       ISO-4217 for freightAmount, null when off
 * @param packages       one entry per FE package row; empty for single-piece
 */
public record WritebackPayload(
        String connectionName,
        String scannedValue,
        String clientCode,
        String batchId,
        Integer orderNo,
        String trackingNumber,
        LocalDateTime shipDate,
        String status,
        String carrierCode,
        String serviceCode,
        BigDecimal freightAmount,
        String currency,
        List<WritebackPackagePayload> packages
) {
    public static Builder builder() { return new Builder(); }

    /** Copy-with helper for the dispatcher's field-redaction step. */
    public WritebackPayload withRedacted(
            boolean keepTracking, boolean keepShipDate, boolean keepStatus,
            boolean keepCarrier, boolean keepService, boolean keepFreight) {
        return new WritebackPayload(
                connectionName, scannedValue, clientCode, batchId, orderNo,
                keepTracking ? trackingNumber : null,
                keepShipDate ? shipDate : null,
                keepStatus ? status : null,
                keepCarrier ? carrierCode : null,
                keepService ? serviceCode : null,
                keepFreight ? freightAmount : null,
                keepFreight ? currency : null,
                packages);
    }

    public static final class Builder {
        private String connectionName, scannedValue, clientCode, batchId;
        private Integer orderNo;
        private String trackingNumber, status, carrierCode, serviceCode, currency;
        private LocalDateTime shipDate;
        private BigDecimal freightAmount;
        private List<WritebackPackagePayload> packages = List.of();

        public Builder connectionName(String v) { this.connectionName = v; return this; }
        public Builder scannedValue(String v) { this.scannedValue = v; return this; }
        public Builder clientCode(String v) { this.clientCode = v; return this; }
        public Builder batchId(String v) { this.batchId = v; return this; }
        public Builder orderNo(Integer v) { this.orderNo = v; return this; }
        public Builder trackingNumber(String v) { this.trackingNumber = v; return this; }
        public Builder shipDate(LocalDateTime v) { this.shipDate = v; return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder carrierCode(String v) { this.carrierCode = v; return this; }
        public Builder serviceCode(String v) { this.serviceCode = v; return this; }
        public Builder freightAmount(BigDecimal v) { this.freightAmount = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder packages(List<WritebackPackagePayload> v) {
            this.packages = v == null ? List.of() : v; return this;
        }

        public WritebackPayload build() {
            return new WritebackPayload(connectionName, scannedValue, clientCode, batchId,
                    orderNo, trackingNumber, shipDate, status, carrierCode, serviceCode,
                    freightAmount, currency, packages);
        }
    }
}
