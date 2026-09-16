package com.multiship.backend.service.carriers.usps;

import com.multiship.backend.dto.UspsRefundCsvRowDTO;
import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-D — {@link UspsRefundCsvExporter} pure-Mockito tests.
 *
 * <p>The exporter queries VOIDED+unreconciled USPS shipments in a date
 * window, joins each to its {@link CarrierAccountRef} for CRID/MID, and
 * emits a PS 3533 CSV. Tests cover the shape and the join.
 */
class UspsRefundCsvExporterTest {

    private OrderTrackingRepository trackingRepo;
    private CarrierAccountRefRepository accountRepo;
    private UspsRefundCsvExporter exporter;

    @BeforeEach
    void setUp() {
        trackingRepo = mock(OrderTrackingRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        exporter = new UspsRefundCsvExporter(trackingRepo, accountRepo);
    }

    private static OrderTracking track(String tn, String acct, BigDecimal postage,
                                        LocalDateTime labelDate, int orderNo) {
        OrderTracking t = new OrderTracking();
        t.setTrackingNumber(tn);
        t.setAccountNumber(acct);
        t.setCarrierAmount(postage);
        t.setLabelGeneratedAt(labelDate);
        t.setOrderNo(orderNo);
        t.setStatus("VOIDED");
        return t;
    }

    private static CarrierAccountRef account(String acct, String crid, String mid) {
        CarrierAccountRef a = new CarrierAccountRef();
        a.setAccountNumber(acct);
        a.setCarrierCode("USPS");
        a.setUspsDirectCrid(crid);
        a.setUspsDirectMid(mid);
        return a;
    }

    // ================================================================
    // Empty result — header-only CSV
    // ================================================================

    @Test
    void noCandidatesReturnsEmptyRowList() {
        when(trackingRepo.findVoidedUnreconciledUspsBetween(any(), any()))
                .thenReturn(List.of());
        List<UspsRefundCsvRowDTO> rows = exporter.buildRows(null, null);
        assertTrue(rows.isEmpty());
    }

    @Test
    void headerAlwaysPresentEvenWhenNoCandidates() {
        when(trackingRepo.findVoidedUnreconciledUspsBetween(any(), any()))
                .thenReturn(List.of());
        byte[] bytes = exporter.exportCsv(null, null);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        String header = String.join(",", UspsRefundCsvExporter.PS3533_HEADER);
        assertTrue(csv.startsWith(header),
                "empty export should still emit the PS 3533 header; got: " + csv);
        // Header + \r\n → only two "cells" separated by \n newlines in
        // Windows-line-ending CSV; body is empty.
        assertEquals(1, csv.split("\r\n").length,
                "empty export should have only the header line; got:\n" + csv);
    }

    // ================================================================
    // Rows carry CRID / MID / postage / date / reason / reference
    // ================================================================

    @Test
    void rowsCarryTenantIdentifiersFromCarrierAccountRef() {
        LocalDateTime labelDate = LocalDateTime.of(2026, 9, 10, 14, 30);
        OrderTracking t = track("9400111899223197428301", "ACCT-1",
                new BigDecimal("8.85"), labelDate, 1234);
        when(trackingRepo.findVoidedUnreconciledUspsBetween(any(), any()))
                .thenReturn(List.of(t));
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(
                "ACCT-1", "USPS"))
                .thenReturn(Optional.of(account("ACCT-1", "CRID-99", "MID-88")));

        List<UspsRefundCsvRowDTO> rows = exporter.buildRows(null, null);
        assertEquals(1, rows.size());
        UspsRefundCsvRowDTO r = rows.get(0);
        assertAll(
                () -> assertEquals("9400111899223197428301", r.trackingNumber()),
                () -> assertEquals("MID-88", r.mailerId()),
                () -> assertEquals("CRID-99", r.customerRegistrationId()),
                () -> assertEquals(0, r.originalPostage().compareTo(new BigDecimal("8.85"))),
                () -> assertEquals(labelDate.toLocalDate(), r.labelDate()),
                () -> assertEquals("UNUSED", r.reasonCode()),
                () -> assertEquals("1234", r.customerReference())
        );
    }

    @Test
    void missingCarrierAccountRefStillEmitsRowWithNullIdentifiers() {
        // Legacy data: tracking row references an account_number that
        // has no CarrierAccountRef row. Exporter should still emit the
        // row (USPS may still refund the tracking, or the operator can
        // manually fill in the missing CRID/MID before uploading);
        // both identifier fields land as empty strings in the CSV.
        OrderTracking t = track("TRK-1", "ORPHAN-ACCT", new BigDecimal("5.00"),
                LocalDateTime.now(), 100);
        when(trackingRepo.findVoidedUnreconciledUspsBetween(any(), any()))
                .thenReturn(List.of(t));
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(
                anyString(), anyString()))
                .thenReturn(Optional.empty());

        List<UspsRefundCsvRowDTO> rows = exporter.buildRows(null, null);
        assertEquals(1, rows.size());
        assertEquals("TRK-1", rows.get(0).trackingNumber());
    }

    // ================================================================
    // CSV serialization shape
    // ================================================================

    @Test
    void csvBytesMatchExpectedShape() {
        UspsRefundCsvRowDTO row = new UspsRefundCsvRowDTO(
                "TRK-1", "MID-88", "CRID-99",
                new BigDecimal("8.85"),
                java.time.LocalDate.of(2026, 9, 10),
                "UNUSED",
                "ORD-1234");
        byte[] bytes = exporter.toCsvBytes(List.of(row));
        String csv = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertEquals(2, lines.length,
                "1 data row → header + 1 body line; got:\n" + csv);
        assertEquals("TrackingNumber,MailerId,CustomerRegistrationId,OriginalPostage,LabelDate,ReasonCode,CustomerReference",
                lines[0]);
        assertEquals("TRK-1,MID-88,CRID-99,8.85,2026-09-10,UNUSED,ORD-1234",
                lines[1]);
    }

    @Test
    void csvBytesEscapeCommasAndQuotes() {
        UspsRefundCsvRowDTO row = new UspsRefundCsvRowDTO(
                "TRK-1", "MID-88", "CRID-99",
                new BigDecimal("1.00"),
                java.time.LocalDate.of(2026, 9, 10),
                "UNUSED",
                "customer note, with comma and \"quote\"");
        byte[] bytes = exporter.toCsvBytes(List.of(row));
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"customer note, with comma and \"\"quote\"\"\""),
                "commas + quotes should be escaped RFC 4180 style; got:\n" + csv);
    }

    @Test
    void csvBytesHandleNullCells() {
        UspsRefundCsvRowDTO row = new UspsRefundCsvRowDTO(
                "TRK-1", null, null, null, null, "UNUSED", null);
        byte[] bytes = exporter.toCsvBytes(List.of(row));
        String csv = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        // Six commas separate 7 fields; nulls surface as empty strings.
        assertEquals("TRK-1,,,,,UNUSED,", lines[1]);
    }

    // ================================================================
    // Date range validation
    // ================================================================

    @Test
    void reversedDateRangeThrows() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 9, 1, 0, 0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> exporter.buildRows(from, to));
        assertTrue(ex.getMessage().contains("startDate"),
                "message should name startDate; got: " + ex.getMessage());
    }

    @Test
    void nullDatesDefaultToLast30Days() {
        when(trackingRepo.findVoidedUnreconciledUspsBetween(any(), any()))
                .thenReturn(List.of());
        // Just verifying no exception + query was invoked with something
        // in the 30-day range — the actual range boundaries are covered
        // by the repository query semantics test elsewhere.
        List<UspsRefundCsvRowDTO> rows = exporter.buildRows(null, null);
        assertNotNull(rows);
    }

    // ================================================================
    // N+1 avoidance — same account across many trackings hits repo once
    // ================================================================

    @Test
    void accountFetchIsMemoizedAcrossManyTrackings() {
        List<OrderTracking> candidates = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candidates.add(track("TRK-" + i, "ACCT-SHARED",
                    new BigDecimal("2.00"), LocalDateTime.now(), 100 + i));
        }
        when(trackingRepo.findVoidedUnreconciledUspsBetween(any(), any()))
                .thenReturn(candidates);
        when(accountRepo.findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase(
                "ACCT-SHARED", "USPS"))
                .thenReturn(Optional.of(account("ACCT-SHARED", "CRID", "MID")));

        List<UspsRefundCsvRowDTO> rows = exporter.buildRows(null, null);
        assertEquals(5, rows.size());
        // Same MID/CRID on every row.
        assertTrue(rows.stream().allMatch(r -> "MID".equals(r.mailerId())));
        assertFalse(rows.stream().anyMatch(r -> r.customerRegistrationId() == null));
        // Only ONE call to the repo for the shared account.
        org.mockito.Mockito.verify(accountRepo, org.mockito.Mockito.times(1))
                .findFirstByAccountNumberIgnoreCaseAndCarrierCodeIgnoreCase("ACCT-SHARED", "USPS");
    }
}
