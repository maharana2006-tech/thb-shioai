package com.multiship.backend.service.carriers.usps.queue;

import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.model.Order;
import com.multiship.backend.repository.OrderRepository;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsRequest;
import com.multiship.backend.service.carriers.usps.queue.UspsLabelQueueService.EnqueueMpsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Follow-up to the USPS_DIRECT track — pins the intl-MPS enqueue-time
 * guard added on top of {@link UspsMpsSplitterService}. USPS APIs v3
 * have no batch endpoint for international shipments: each intl label
 * would need its own carrier call, and the per-piece dispatcher
 * ({@link UspsMpsPieceDispatcher}) builds a lean per-piece DTO that
 * skips the customs cascade. Letting an intl MPS order through would
 * reach USPS with a customs-less payload and get rejected with an
 * opaque error, so we fail LOUD at enqueue instead of at runtime.
 *
 * <p>Kept in a dedicated file so the pre-guard
 * {@link UspsMpsSplitterServiceTest} stays untouched and its
 * single-arg-constructor fixture keeps working.
 *
 * <p>Matrix:
 * <table><thead><tr><th>Input</th><th>Expectation</th></tr></thead>
 * <tbody>
 *   <tr><td>Domestic 5-pkg DTO (US)</td><td>enqueues 5 pieces</td></tr>
 *   <tr><td>Intl 5-pkg DTO (GB)</td><td>IAE, no queue interaction</td></tr>
 *   <tr><td>Intl 2-pkg DTO (GB)</td><td>IAE (smallest MPS trips guard)</td></tr>
 *   <tr><td>Blank recipient country + 5 pkgs</td><td>enqueues (domestic)</td></tr>
 *   <tr><td>Null recipient country + 5 pkgs</td><td>enqueues (domestic)</td></tr>
 *   <tr><td>"us" (lowercase) + 5 pkgs</td><td>enqueues (case-insensitive US)</td></tr>
 *   <tr><td>"gb" + 3 pkgs</td><td>IAE (case-insensitive intl)</td></tr>
 *   <tr><td>splitAndEnqueueForOrder(CA, 3 pkgs)</td><td>IAE, verify orderRepo lookup</td></tr>
 * </tbody></table>
 */
class UspsMpsSplitterServiceIntlGuardTest {

    private UspsLabelQueueService queue;
    private OrderRepository orderRepository;
    private UspsMpsSplitterService splitter;

    @BeforeEach
    void setUp() {
        queue = mock(UspsLabelQueueService.class);
        orderRepository = mock(OrderRepository.class);
        splitter = new UspsMpsSplitterService(queue, orderRepository);
    }

    // ================================================================
    // Domestic paths — happy enqueue
    // ================================================================

    @Test
    void splitAndEnqueue_domesticFivePackageDto_enqueuesNormally() {
        ShipmentRequestDTO dto = dtoWithPackageCount(5, "US");
        // Order lookup returns a US order so the order-path guard also
        // passes when splitAndEnqueue delegates through it.
        when(orderRepository.findByOrderNo(12345))
                .thenReturn(Optional.of(orderWithCountry("US")));
        when(queue.enqueueMps(any())).thenReturn(new EnqueueMpsResult(
                12345L, 5,
                LocalDateTime.of(2026, 9, 16, 12, 0),
                LocalDateTime.of(2026, 9, 16, 12, 6)));

        EnqueueMpsResult result = splitter.splitAndEnqueue(dto, "ACME", 12345L);

        assertEquals(5, result.enqueuedCount());
        ArgumentCaptor<EnqueueMpsRequest> captor = ArgumentCaptor.forClass(EnqueueMpsRequest.class);
        verify(queue, times(1)).enqueueMps(captor.capture());
        assertEquals(5, captor.getValue().pieces().size());
    }

    @Test
    void splitAndEnqueue_blankRecipientCountry_treatedAsDomestic() {
        ShipmentRequestDTO dto = dtoWithPackageCount(5, "   ");
        when(orderRepository.findByOrderNo(12345))
                .thenReturn(Optional.of(orderWithCountry(null)));
        when(queue.enqueueMps(any())).thenReturn(new EnqueueMpsResult(
                12345L, 5, LocalDateTime.now(), LocalDateTime.now().plusMinutes(6)));

        EnqueueMpsResult result = splitter.splitAndEnqueue(dto, "ACME", 12345L);

        assertEquals(5, result.enqueuedCount());
        verify(queue, times(1)).enqueueMps(any());
    }

    @Test
    void splitAndEnqueue_nullRecipientCountry_treatedAsDomestic() {
        ShipmentRequestDTO dto = dtoWithPackageCount(5, null);
        when(orderRepository.findByOrderNo(12345))
                .thenReturn(Optional.empty()); // Order not present → domestic
        when(queue.enqueueMps(any())).thenReturn(new EnqueueMpsResult(
                12345L, 5, LocalDateTime.now(), LocalDateTime.now().plusMinutes(6)));

        EnqueueMpsResult result = splitter.splitAndEnqueue(dto, "ACME", 12345L);

        assertEquals(5, result.enqueuedCount());
        verify(queue, times(1)).enqueueMps(any());
    }

    @Test
    void splitAndEnqueue_lowerCaseUs_treatedAsDomestic() {
        ShipmentRequestDTO dto = dtoWithPackageCount(5, "us");
        when(orderRepository.findByOrderNo(12345))
                .thenReturn(Optional.of(orderWithCountry("us")));
        when(queue.enqueueMps(any())).thenReturn(new EnqueueMpsResult(
                12345L, 5, LocalDateTime.now(), LocalDateTime.now().plusMinutes(6)));

        EnqueueMpsResult result = splitter.splitAndEnqueue(dto, "ACME", 12345L);

        assertEquals(5, result.enqueuedCount());
        verify(queue, times(1)).enqueueMps(any());
    }

    // ================================================================
    // Intl paths — guard fires with remediation-rich message
    // ================================================================

    @Test
    void splitAndEnqueue_intlFivePackageDto_throwsIaeBeforeQueue() {
        ShipmentRequestDTO dto = dtoWithPackageCount(5, "GB");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueue(dto, "ACME", 12345L));

        // Message must name: order number, package count, recipient country,
        // BOTH remediation options (split single-label OR switch to
        // STAMPS_COM provider).
        String msg = ex.getMessage();
        assertTrue(msg.contains("12345"),   "message must name order: " + msg);
        assertTrue(msg.contains("5"),       "message must name package count: " + msg);
        assertTrue(msg.contains("GB"),      "message must name recipient country: " + msg);
        assertTrue(msg.contains("split"),   "message must mention split-single remediation: " + msg);
        assertTrue(msg.contains("STAMPS_COM"),
                "message must mention STAMPS_COM remediation: " + msg);
        assertTrue(msg.contains("multi-piece international"),
                "message must name the failure mode: " + msg);

        // Guard MUST fire before the queue is touched — no partial enqueue.
        verifyNoInteractions(queue);
    }

    @Test
    void splitAndEnqueue_intlTwoPackageDto_throwsIae() {
        // Smallest MPS (N=2) still trips the guard. N=1 is single-label
        // and never reaches the splitter (rejected by the non-MPS
        // packages<2 branch above).
        ShipmentRequestDTO dto = dtoWithPackageCount(2, "GB");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueue(dto, "ACME", 99L));
        assertTrue(ex.getMessage().contains("2"), "package count in message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("GB"),
                "recipient country in message: " + ex.getMessage());
        verifyNoInteractions(queue);
    }

    @Test
    void splitAndEnqueue_intlLowerCaseGb_throwsIae() {
        // Case-insensitive check on the non-US side too: "gb" is intl.
        ShipmentRequestDTO dto = dtoWithPackageCount(3, "gb");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueue(dto, "ACME", 77L));
        // Normalised uppercase in the surfaced message so operators
        // don't get confused by "gb" vs "GB" mismatches.
        assertTrue(ex.getMessage().contains("GB"),
                "case-insensitive rejection must uppercase in message: " + ex.getMessage());
        verifyNoInteractions(queue);
    }

    // ================================================================
    // Order-based path (splitAndEnqueueForOrder) — guard uses OrderRepository
    // ================================================================

    @Test
    void splitAndEnqueueForOrder_intlOrder_throwsIaeAndReadsOrder() {
        // CA recipient — no DTO on this path, so the guard must load
        // the Order and read shiptoCountryCd.
        Order canadaOrder = orderWithCountry("CA");
        when(orderRepository.findByOrderNo(555)).thenReturn(Optional.of(canadaOrder));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> splitter.splitAndEnqueueForOrder(555L, 3, "ACME"));

        assertTrue(ex.getMessage().contains("555"), "order in message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("3"), "package count in message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("CA"),
                "recipient country in message: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("STAMPS_COM"),
                "STAMPS_COM remediation in message: " + ex.getMessage());

        // Verify the guard actually looked up the loaded order — not
        // just inferred domestic from the null DTO.
        verify(orderRepository, times(1)).findByOrderNo(555);
        verifyNoInteractions(queue);
    }

    // ================================================================
    // helpers
    // ================================================================

    /** Build a ShipmentRequestDTO carrying N packages + a recipient country. */
    private static ShipmentRequestDTO dtoWithPackageCount(int n, String recipientCountry) {
        List<PackageDetailDTO> pkgs = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            pkgs.add(PackageDetailDTO.builder()
                    .sequenceNumber(i)
                    .weight(BigDecimal.valueOf(1))
                    .build());
        }
        return ShipmentRequestDTO.builder()
                .carrierCode("USPS")
                .accountNumber("acct-1")
                .recipientCountryCode(recipientCountry)
                .packages(pkgs)
                .build();
    }

    /** Build an Order with the given shiptoCountryCd; nothing else matters. */
    private static Order orderWithCountry(String country) {
        Order o = new Order();
        o.setShiptoCountryCd(country);
        return o;
    }
}
