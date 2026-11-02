package com.multiship.backend.service;

import com.multiship.backend.dto.OrderImportRowDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * BUG-045 (QA 2026-10-23) — pre-fix, two rows sharing one customer
 * reference across different shipment groups passed Upload &amp;
 * validate and Validate-all, then one row failed at Generate with
 * "This reference is already in use on a GENERATED label" after the
 * first row's label had been purchased. QA repro: Import #24 rows
 * 8-9 (QA Dup One / QA Dup Two) and Import #26 rows 2-3
 * (QA Repro Dup A / QA Repro Dup B).
 *
 * <p>Operator decision (2026-10-24, carried in the fix PR body):
 * reject at validate-all so the operator fixes the file BEFORE Save.
 * This test pins the new {@code flagWithinImportDuplicateReferences}
 * logic hooked into
 * {@link OrderImportServiceImpl#validate(List, boolean)}.
 */
class OrderImportDuplicateReferenceTest {

    private OrderImportServiceImpl service;
    private Method flagMethod;

    @BeforeEach
    void setUp() throws Exception {
        service = new OrderImportServiceImpl(mock(CarrierService.class));
        flagMethod = OrderImportServiceImpl.class.getDeclaredMethod(
                "flagWithinImportDuplicateReferences", List.class);
        flagMethod.setAccessible(true);
    }

    private static OrderImportRowDTO row(int rowNo, String orderRef, String reference) {
        return OrderImportRowDTO.builder()
                .rowNumber(rowNo)
                .orderRef(orderRef)
                .reference(reference)
                .clientCode("ACME")
                .build();
    }

    private void run(List<OrderImportRowDTO> rows) throws Exception {
        flagMethod.invoke(service, rows);
    }

    @Test
    void twoDifferentShipmentsSharingOneReference_eachRowGetsAnError() throws Exception {
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(
                row(1, "GRP-A", "PO-SHARED"),
                row(2, "GRP-B", "PO-SHARED")
        ));
        run(rows);
        for (OrderImportRowDTO r : rows) {
            assertNotNull(r.getErrors(), "row " + r.getRowNumber() + " needs an error");
            assertEquals(1, r.getErrors().size(), r.getErrors().toString());
            assertTrue(r.getErrors().get(0).contains("PO-SHARED"),
                    "error should name the shared reference: " + r.getErrors());
            assertTrue(r.getErrors().get(0).contains("rows 1, 2"),
                    "error should list both row numbers: " + r.getErrors());
        }
    }

    @Test
    void twoRowsSharingOrderRefAndReference_treatedAsOneShipment_noError() throws Exception {
        // Line items of ONE shipment — shared orderRef = one group,
        // reference naturally the same.
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(
                row(1, "GRP-A", "PO-SHIP-1"),
                row(2, "GRP-A", "PO-SHIP-1")
        ));
        run(rows);
        for (OrderImportRowDTO r : rows) {
            assertTrue(r.getErrors() == null || r.getErrors().isEmpty(),
                    "legitimate multi-line shipment must not error: " + r.getErrors());
        }
    }

    @Test
    void blankReference_fallsBackToOrderRef_noErrorsBetweenDistinctGroups() throws Exception {
        // Blank reference: each row's effective ref downstream is its own
        // orderRef, so no collision — only inter-group reference clashes
        // matter here.
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(
                row(1, "GRP-A", null),
                row(2, "GRP-B", null),
                row(3, "GRP-C", "")
        ));
        run(rows);
        for (OrderImportRowDTO r : rows) {
            assertTrue(r.getErrors() == null || r.getErrors().isEmpty(),
                    "blank-reference rows must not error: " + r.getErrors());
        }
    }

    @Test
    void referenceComparedCaseInsensitively() throws Exception {
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(
                row(1, "GRP-A", "po-shared"),
                row(2, "GRP-B", "PO-Shared")
        ));
        run(rows);
        assertNotNull(rows.get(0).getErrors(), "row 1 needs an error (case-insensitive match)");
        assertFalseEmpty(rows.get(0).getErrors());
        assertNotNull(rows.get(1).getErrors(), "row 2 needs an error (case-insensitive match)");
        assertFalseEmpty(rows.get(1).getErrors());
    }

    @Test
    void referenceComparedTrimTolerant() throws Exception {
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(
                row(1, "GRP-A", "  PO-TRIM  "),
                row(2, "GRP-B", "PO-TRIM")
        ));
        run(rows);
        assertNotNull(rows.get(0).getErrors());
        assertFalseEmpty(rows.get(0).getErrors());
        assertNotNull(rows.get(1).getErrors());
    }

    @Test
    void alreadyGeneratedRow_notErroredAgainstItsOwnHistory() throws Exception {
        // When Validate-all re-runs on a saved batch, GENERATED rows must
        // not be flagged as sibling-clashes with the other rows that
        // might still be waiting — the clash concept doesn't apply to a
        // row that already owns a label.
        OrderImportRowDTO generated = row(1, "GRP-A", "PO-SHIP-1");
        generated.setGeneratedStatus("GENERATED");
        OrderImportRowDTO pending = row(2, "GRP-B", "PO-SHIP-1");
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(generated, pending));
        run(rows);
        assertTrue(generated.getErrors() == null || generated.getErrors().isEmpty(),
                "GENERATED row must not re-error: " + generated.getErrors());
        // The still-pending sibling also gets no error because the
        // dedupe skips GENERATED rows AND then sees only one remaining
        // group under that reference. (If a THIRD still-pending group
        // existed, the clash between the two live groups would still
        // fire — covered by the main test.)
        assertTrue(pending.getErrors() == null || pending.getErrors().isEmpty(),
                "pending row with only a GENERATED sibling must not error: " + pending.getErrors());
    }

    @Test
    void singleRow_noError() throws Exception {
        List<OrderImportRowDTO> rows = new ArrayList<>(List.of(row(1, "GRP-A", "PO-SHIP-1")));
        run(rows);
        assertTrue(rows.get(0).getErrors() == null || rows.get(0).getErrors().isEmpty());
    }

    @Test
    void emptyList_noOp() throws Exception {
        run(new ArrayList<>());
        // nothing to assert — the method must not throw on empty input
    }

    private static void assertFalseEmpty(List<String> errors) {
        assertTrue(errors != null && !errors.isEmpty(), "errors must be populated");
    }
}
