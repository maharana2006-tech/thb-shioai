package com.multiship.backend.service;

import com.multiship.backend.model.CarrierAccountRef;
import com.multiship.backend.model.InvoiceCopies;
import com.multiship.backend.model.OrderTracking;
import com.multiship.backend.repository.CarrierAccountRefRepository;
import com.multiship.backend.repository.InvoiceCopiesRepository;
import com.multiship.backend.repository.OrderTrackingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-Printer-R7a + R7c — resolveCopies fallback chain + upsert bounds +
 * delete idempotency + resolveCopiesForOrder integration.
 */
class InvoiceCopiesServiceTest {

    private InvoiceCopiesRepository repo;
    private OrderTrackingRepository trackingRepo;
    private CarrierAccountRefRepository accountRepo;
    private InvoiceCopiesService service;

    @BeforeEach
    void setUp() {
        repo = mock(InvoiceCopiesRepository.class);
        trackingRepo = mock(OrderTrackingRepository.class);
        accountRepo = mock(CarrierAccountRefRepository.class);
        when(repo.save(any(InvoiceCopies.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new InvoiceCopiesService(repo, trackingRepo, accountRepo);
    }

    // ================================================================
    // resolveCopies — fallback chain
    // ================================================================

    @Test
    void resolveCopies_exactClientMatch_wins() {
        InvoiceCopies rule = row("ACME", "FEDEX", 3);
        when(repo.findRule("ACME", "FEDEX")).thenReturn(Optional.of(rule));

        assertThat(service.resolveCopies("ACME", "FEDEX")).isEqualTo(3);
        // Must not fall through to the wildcard lookup.
        verify(repo, times(0)).findRule(isNull(), eq("FEDEX"));
    }

    @Test
    void resolveCopies_tenantDefault_usedWhenClientRuleMissing() {
        when(repo.findRule("ACME", "FEDEX")).thenReturn(Optional.empty());
        when(repo.findRule(null, "FEDEX")).thenReturn(Optional.of(row(null, "FEDEX", 4)));

        assertThat(service.resolveCopies("ACME", "FEDEX")).isEqualTo(4);
    }

    @Test
    void resolveCopies_hardcoded_1_whenNothingConfigured() {
        when(repo.findRule("ACME", "UPS")).thenReturn(Optional.empty());
        when(repo.findRule(null, "UPS")).thenReturn(Optional.empty());

        assertThat(service.resolveCopies("ACME", "UPS")).isEqualTo(InvoiceCopiesService.DEFAULT_COPIES);
    }

    @Test
    void resolveCopies_blankCarrier_returnsDefault_neverHitsDB() {
        assertThat(service.resolveCopies("ACME", "")).isEqualTo(1);
        assertThat(service.resolveCopies("ACME", null)).isEqualTo(1);
        verify(repo, times(0)).findRule(any(), any());
    }

    @Test
    void resolveCopies_nullClient_looksUpWildcardOnly() {
        // No per-client concept when clientCode is null — go straight
        // to the tenant-default lookup.
        when(repo.findRule(null, "DHL")).thenReturn(Optional.of(row(null, "DHL", 5)));

        assertThat(service.resolveCopies(null, "DHL")).isEqualTo(5);
    }

    // ================================================================
    // upsert — bounds + normalisation
    // ================================================================

    @Test
    void upsert_normalisesClientAndCarrier_toUppercase() {
        when(repo.findRule("ACME", "FEDEX")).thenReturn(Optional.empty());
        InvoiceCopies saved = service.upsert("acme", "fedex", 3);
        assertThat(saved.getClientCode()).isEqualTo("ACME");
        assertThat(saved.getCarrierCode()).isEqualTo("FEDEX");
        assertThat(saved.getCopies()).isEqualTo(3);
    }

    @Test
    void upsert_blankClient_becomesNullTenantDefault() {
        when(repo.findRule(null, "FEDEX")).thenReturn(Optional.empty());
        InvoiceCopies saved = service.upsert("", "fedex", 3);
        assertThat(saved.getClientCode()).isNull();
    }

    @Test
    void upsert_missingCarrier_throws() {
        assertThatThrownBy(() -> service.upsert("ACME", "", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carrierCode");
    }

    @Test
    void upsert_copiesBelow1_throws() {
        assertThatThrownBy(() -> service.upsert("ACME", "FEDEX", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 20");
    }

    @Test
    void upsert_copiesAbove20_throws() {
        assertThatThrownBy(() -> service.upsert("ACME", "FEDEX", 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 and 20");
    }

    // ================================================================
    // delete — idempotency
    // ================================================================

    @Test
    void delete_missingRow_returnsFalse() {
        when(repo.findRule("ACME", "FEDEX")).thenReturn(Optional.empty());
        assertThat(service.delete("ACME", "FEDEX")).isFalse();
    }

    @Test
    void delete_existingRow_removesAndReturnsTrue() {
        InvoiceCopies row = row("ACME", "FEDEX", 3);
        when(repo.findRule("ACME", "FEDEX")).thenReturn(Optional.of(row));
        assertThat(service.delete("ACME", "FEDEX")).isTrue();
        verify(repo).delete(row);
    }

    @Test
    void delete_missingCarrier_isNoop() {
        assertThat(service.delete("ACME", null)).isFalse();
        verify(repo, times(0)).delete(any(InvoiceCopies.class));
    }

    // ================================================================
    // resolveCopiesForOrder — PR-Printer-R7c
    // ================================================================

    @Test
    void resolveCopiesForOrder_happyPath_looksUpTrackingThenAccountThenRule() {
        OrderTracking t = new OrderTracking();
        t.setAccountNumber("ACCT-9999");
        CarrierAccountRef a = new CarrierAccountRef();
        a.setCarrierCode("FEDEX");
        a.setCustomerNo("ACME");

        when(trackingRepo.findByOrderNo(1001)).thenReturn(Optional.of(t));
        when(accountRepo.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc("ACCT-9999"))
                .thenReturn(Optional.of(a));
        when(repo.findRule("ACME", "FEDEX")).thenReturn(Optional.of(row("ACME", "FEDEX", 3)));

        assertThat(service.resolveCopiesForOrder(1001)).isEqualTo(3);
    }

    @Test
    void resolveCopiesForOrder_missingTracking_returns1() {
        when(trackingRepo.findByOrderNo(1001)).thenReturn(Optional.empty());
        assertThat(service.resolveCopiesForOrder(1001)).isEqualTo(1);
    }

    @Test
    void resolveCopiesForOrder_missingAccount_returns1() {
        OrderTracking t = new OrderTracking();
        t.setAccountNumber("ACCT-XYZ");
        when(trackingRepo.findByOrderNo(1001)).thenReturn(Optional.of(t));
        when(accountRepo.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc("ACCT-XYZ"))
                .thenReturn(Optional.empty());
        assertThat(service.resolveCopiesForOrder(1001)).isEqualTo(1);
    }

    @Test
    void resolveCopiesForOrder_missingAccountNumberOnTracking_returns1() {
        OrderTracking t = new OrderTracking();
        t.setAccountNumber(null);
        when(trackingRepo.findByOrderNo(1001)).thenReturn(Optional.of(t));
        assertThat(service.resolveCopiesForOrder(1001)).isEqualTo(1);
    }

    @Test
    void resolveCopiesForOrder_nullOrderNo_returns1() {
        assertThat(service.resolveCopiesForOrder(null)).isEqualTo(1);
    }

    @Test
    void resolveCopiesForOrder_trackingRepoThrows_returns1_neverBubbles() {
        when(trackingRepo.findByOrderNo(1001)).thenThrow(new RuntimeException("db down"));
        // Never let a copies-config lookup fail a real invoice print.
        assertThat(service.resolveCopiesForOrder(1001)).isEqualTo(1);
    }

    @Test
    void resolveCopiesForOrder_noConfiguredRule_returns1() {
        OrderTracking t = new OrderTracking();
        t.setAccountNumber("ACCT-1");
        CarrierAccountRef a = new CarrierAccountRef();
        a.setCarrierCode("UPS");
        a.setCustomerNo("BETA");

        when(trackingRepo.findByOrderNo(1001)).thenReturn(Optional.of(t));
        when(accountRepo.findFirstByAccountNumberIgnoreCaseOrderByUpdatedAtDesc("ACCT-1"))
                .thenReturn(Optional.of(a));
        when(repo.findRule(anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.findRule(isNull(), anyString())).thenReturn(Optional.empty());

        assertThat(service.resolveCopiesForOrder(1001)).isEqualTo(1);
    }

    private static InvoiceCopies row(String client, String carrier, int copies) {
        InvoiceCopies r = new InvoiceCopies();
        r.setClientCode(client);
        r.setCarrierCode(carrier);
        r.setCopies(copies);
        return r;
    }
}
