package com.multiship.backend.service;

import com.multiship.backend.model.InvoiceCopies;
import com.multiship.backend.repository.InvoiceCopiesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-Printer-R7a — resolveCopies fallback chain + upsert bounds +
 * delete idempotency.
 */
class InvoiceCopiesServiceTest {

    private InvoiceCopiesRepository repo;
    private InvoiceCopiesService service;

    @BeforeEach
    void setUp() {
        repo = mock(InvoiceCopiesRepository.class);
        when(repo.save(any(InvoiceCopies.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new InvoiceCopiesService(repo);
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

    private static InvoiceCopies row(String client, String carrier, int copies) {
        InvoiceCopies r = new InvoiceCopies();
        r.setClientCode(client);
        r.setCarrierCode(carrier);
        r.setCopies(copies);
        return r;
    }
}
