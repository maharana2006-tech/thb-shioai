package com.multiship.backend.service;

import com.multiship.backend.dto.OrderImportRowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * BUG-044 (QA 2026-10-23) — pre-fix, a 36-character recipient name
 * passed Upload &amp; validate and Validate-all, then failed at
 * Generate with "Name exceeds the 35-character carrier limit"
 * (Import #26 row 4, Import #24 row 10). The 35-char ceiling is
 * the lower bound across UPS / FedEx / DHL and is enforced on the
 * per-shipment Validate path for manual shipments by
 * {@link ShipmentValidationService#checkCarrierLengthLimits}. The
 * fix mirrors the same checks on the bulk-import row so the
 * operator sees "fix this cell" at Validate-all time.
 */
class OrderImportFieldLengthTest {

    private OrderImportServiceImpl service;
    private Method flagMethod;

    @BeforeEach
    void setUp() throws Exception {
        service = new OrderImportServiceImpl(mock(CarrierService.class));
        flagMethod = OrderImportServiceImpl.class.getDeclaredMethod(
                "flagRowFieldLengths", List.class);
        flagMethod.setAccessible(true);
    }

    private void run(List<OrderImportRowDTO> rows) throws Exception {
        flagMethod.invoke(service, rows);
    }

    private static OrderImportRowDTO row(int rowNo) {
        return OrderImportRowDTO.builder()
                .rowNumber(rowNo)
                .clientCode("ACME")
                .build();
    }

    private static String nchars(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('x');
        return sb.toString();
    }

    @Test
    void recipientNameLongerThan35_errored() throws Exception {
        OrderImportRowDTO r = row(1);
        r.setRecipientName(nchars(36));
        run(new ArrayList<>(List.of(r)));
        assertNotNull(r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Name exceeds the 35")),
                r.getErrors().toString());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("currently 36")),
                r.getErrors().toString());
    }

    @Test
    void recipientNameAt35_notErrored() throws Exception {
        OrderImportRowDTO r = row(1);
        r.setRecipientName(nchars(35));
        run(new ArrayList<>(List.of(r)));
        assertTrue(r.getErrors() == null || r.getErrors().isEmpty());
    }

    @Test
    void recipientCompanyLongerThan35_errored() throws Exception {
        OrderImportRowDTO r = row(1);
        r.setRecipientCompany(nchars(40));
        run(new ArrayList<>(List.of(r)));
        assertNotNull(r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Company exceeds the 35")),
                r.getErrors().toString());
    }

    @Test
    void addressLine1LongerThan35_errored() throws Exception {
        OrderImportRowDTO r = row(1);
        r.setAddressLine1(nchars(36));
        run(new ArrayList<>(List.of(r)));
        assertNotNull(r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Address line 1 exceeds the 35")),
                r.getErrors().toString());
    }

    @Test
    void addressLine2LongerThan35_errored() throws Exception {
        OrderImportRowDTO r = row(1);
        r.setAddressLine2(nchars(50));
        run(new ArrayList<>(List.of(r)));
        assertNotNull(r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Address line 2 exceeds the 35")),
                r.getErrors().toString());
    }

    @Test
    void referenceLongerThan35_errored() throws Exception {
        OrderImportRowDTO r = row(1);
        r.setReference(nchars(36));
        run(new ArrayList<>(List.of(r)));
        assertNotNull(r.getErrors());
        assertTrue(r.getErrors().stream().anyMatch(e -> e.contains("Reference exceeds the 35")),
                r.getErrors().toString());
    }

    @Test
    void multipleOverlongFields_eachErroredSeparately() throws Exception {
        OrderImportRowDTO r = row(1);
        r.setRecipientName(nchars(36));
        r.setAddressLine1(nchars(40));
        r.setReference(nchars(80));
        run(new ArrayList<>(List.of(r)));
        assertNotNull(r.getErrors());
        // Three distinct error messages — the operator sees the full
        // punchlist in one Validate-all pass instead of fixing one and
        // re-uploading three times.
        assertTrue(r.getErrors().size() >= 3, "expected 3+ errors, got " + r.getErrors());
    }

    @Test
    void nullValues_ignored() throws Exception {
        OrderImportRowDTO r = row(1);
        run(new ArrayList<>(List.of(r)));
        assertTrue(r.getErrors() == null || r.getErrors().isEmpty());
    }

    @Test
    void generatedRow_notReErroredOnReValidate() throws Exception {
        // Validate-all re-runs on a saved batch may include GENERATED
        // rows whose stored values predate this validator landing. Not
        // our problem to re-surface — the label already exists; the
        // check is forward-looking.
        OrderImportRowDTO r = row(1);
        r.setRecipientName(nchars(40));
        r.setGeneratedStatus("GENERATED");
        run(new ArrayList<>(List.of(r)));
        assertTrue(r.getErrors() == null || r.getErrors().isEmpty(),
                "GENERATED row must not re-error: " + r.getErrors());
    }
}
