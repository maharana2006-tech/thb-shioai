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
 * <p>V90 adds {@code source} + {@code channel} — routing keys used
 * by the dispatcher to decide whether the connection is even eligible
 * for this payload. They're stripped before {@code writeShipment} is
 * called (connectors don't need them).
 *
 * <p>2026-09-28 adds the NDS TB_MANUAL_SHIPMENT INSERT payload:
 * {@code shipmentMode} (SHIPMENT / RETURN), {@code note} (internal
 * operator note; 255-char PRODUCTION.TB_MANUAL_SHIPMENT.NOTE column),
 * {@code carrierDisplay} + {@code serviceDescription} (human-readable
 * values NDS's UI shows), and a {@link ShipTo} sub-record with the
 * recipient block. All optional — connectors that don't INSERT into
 * TB_MANUAL_SHIPMENT ignore them.
 */
public record WritebackPayload(
        String connectionName,
        String scannedValue,
        String clientCode,
        String batchId,
        Integer orderNo,
        String source,
        String channel,
        String trackingNumber,
        LocalDateTime shipDate,
        String status,
        String carrierCode,
        String serviceCode,
        BigDecimal freightAmount,
        String currency,
        List<WritebackPackagePayload> packages,
        /** NDS TB_MANUAL_SHIPMENT extras — populated by CarrierServiceImpl.generateManualLabel. */
        String shipmentMode,       // "SHIPMENT" | "RETURN"
        String note,               // Internal note; 255-char cap enforced downstream
        String carrierDisplay,     // "FedEx" | "UPS" | "USPS" | "DHL"
        String serviceDescription, // "UPS Ground" — SHIP_SERVICE column value
        String thirdPartyAccount,
        ShipTo shipTo,
        /** G6 — non-null when the incoming order was shipvia=STD and we
         *  resolved it to a concrete service. NdsShipmentOracleWriter uses
         *  this to UPDATE OEHEAD.SHIPVIA_CD to the ERP code the client's
         *  mapping produced on the way in. Null = no OEHEAD update. */
        String stdReplacementErpCode
) {
    public static Builder builder() { return new Builder(); }

    /** Recipient block for the NDS TB_MANUAL_SHIPMENT INSERT. */
    public record ShipTo(
            String attn, String company, String email,
            String addr1, String addr2, String city, String state, String country, String postal
    ) {
        public static final ShipTo EMPTY = new ShipTo(null, null, null, null, null, null, null, null, null);
    }

    /** Copy-with helper for the dispatcher's field-redaction step. */
    public WritebackPayload withRedacted(
            boolean keepTracking, boolean keepShipDate, boolean keepStatus,
            boolean keepCarrier, boolean keepService, boolean keepFreight) {
        return new WritebackPayload(
                connectionName, scannedValue, clientCode, batchId, orderNo,
                source, channel,
                keepTracking ? trackingNumber : null,
                keepShipDate ? shipDate : null,
                keepStatus ? status : null,
                keepCarrier ? carrierCode : null,
                keepService ? serviceCode : null,
                keepFreight ? freightAmount : null,
                keepFreight ? currency : null,
                packages,
                shipmentMode, note,
                keepCarrier ? carrierDisplay : null,
                keepService ? serviceDescription : null,
                thirdPartyAccount, shipTo,
                // OEHEAD update is a service-side write; gate on writeback_service.
                keepService ? stdReplacementErpCode : null);
    }

    public static final class Builder {
        private String connectionName, scannedValue, clientCode, batchId;
        private Integer orderNo;
        private String source, channel;
        private String trackingNumber, status, carrierCode, serviceCode, currency;
        private LocalDateTime shipDate;
        private BigDecimal freightAmount;
        private List<WritebackPackagePayload> packages = List.of();
        private String shipmentMode, note, carrierDisplay, serviceDescription, thirdPartyAccount;
        private ShipTo shipTo = ShipTo.EMPTY;
        private String stdReplacementErpCode;

        public Builder connectionName(String v) { this.connectionName = v; return this; }
        public Builder scannedValue(String v) { this.scannedValue = v; return this; }
        public Builder clientCode(String v) { this.clientCode = v; return this; }
        public Builder batchId(String v) { this.batchId = v; return this; }
        public Builder orderNo(Integer v) { this.orderNo = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder channel(String v) { this.channel = v; return this; }
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
        public Builder shipmentMode(String v) { this.shipmentMode = v; return this; }
        public Builder note(String v) { this.note = v; return this; }
        public Builder carrierDisplay(String v) { this.carrierDisplay = v; return this; }
        public Builder serviceDescription(String v) { this.serviceDescription = v; return this; }
        public Builder thirdPartyAccount(String v) { this.thirdPartyAccount = v; return this; }
        public Builder shipTo(ShipTo v) { this.shipTo = v == null ? ShipTo.EMPTY : v; return this; }
        public Builder stdReplacementErpCode(String v) { this.stdReplacementErpCode = v; return this; }

        public WritebackPayload build() {
            return new WritebackPayload(connectionName, scannedValue, clientCode, batchId,
                    orderNo, source, channel, trackingNumber, shipDate, status, carrierCode, serviceCode,
                    freightAmount, currency, packages,
                    shipmentMode, note, carrierDisplay, serviceDescription, thirdPartyAccount, shipTo,
                    stdReplacementErpCode);
        }
    }
}
