package com.multiship.backend.service;

import com.multiship.backend.model.PrinterTag;
import com.multiship.backend.repository.PrinterTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-Printer-R8a — normalisation + replaceAll diff + edge cases.
 */
class PrinterTagServiceTest {

    private PrinterTagRepository repo;
    private PrinterTagService service;

    @BeforeEach
    void setUp() {
        repo = mock(PrinterTagRepository.class);
        service = new PrinterTagService(repo);
    }

    // ================================================================
    // normaliseTag
    // ================================================================

    @Test
    void normaliseTag_lowercasesAndTrims() {
        assertThat(PrinterTagService.normaliseTag("  Warehouse-North  ")).isEqualTo("warehouse-north");
    }

    @Test
    void normaliseTag_blank_returnsNull() {
        assertThat(PrinterTagService.normaliseTag("")).isNull();
        assertThat(PrinterTagService.normaliseTag("   ")).isNull();
        assertThat(PrinterTagService.normaliseTag(null)).isNull();
    }

    @Test
    void normaliseTag_tooLong_throws() {
        String tooLong = "x".repeat(61);
        assertThatThrownBy(() -> PrinterTagService.normaliseTag(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longer than 60");
    }

    // ================================================================
    // replaceAllForPrinter — diff
    // ================================================================

    @Test
    void replaceAll_noExisting_insertsAllNormalised() {
        when(repo.findByPrinterIdOrderByTagAsc(7L))
                .thenReturn(List.of())
                .thenReturn(List.of(row(7L, "backup-only"), row(7L, "warehouse-north")));

        List<PrinterTag> result = service.replaceAllForPrinter(7L, List.of("Warehouse-North", "BACKUP-ONLY", "warehouse-north"));

        // saveAll called once with the 2 deduped normalised tags.
        verify(repo).saveAll(anyCollection());
        assertThat(result).hasSize(2);
    }

    @Test
    void replaceAll_partialOverlap_deletesOnlyRemovedInsertsOnlyAdded() {
        // Existing: A, B. Desired: B, C. Expect: delete A, insert C.
        PrinterTag a = row(7L, "a");
        PrinterTag b = row(7L, "b");
        when(repo.findByPrinterIdOrderByTagAsc(7L))
                .thenReturn(List.of(a, b))
                .thenReturn(List.of(b, row(7L, "c")));

        service.replaceAllForPrinter(7L, List.of("b", "c"));

        verify(repo).deleteAll(List.of(a));
        verify(repo).saveAll(anyCollection());
    }

    @Test
    void replaceAll_emptyInput_deletesAll() {
        PrinterTag a = row(7L, "a");
        when(repo.findByPrinterIdOrderByTagAsc(7L))
                .thenReturn(List.of(a))
                .thenReturn(List.of());

        List<PrinterTag> result = service.replaceAllForPrinter(7L, List.of());

        verify(repo).deleteAll(List.of(a));
        verify(repo, times(0)).saveAll(any());
        assertThat(result).isEmpty();
    }

    @Test
    void replaceAll_nullPrinterId_throws() {
        assertThatThrownBy(() -> service.replaceAllForPrinter(null, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replaceAll_noOp_whenDesiredMatchesCurrent() {
        PrinterTag a = row(7L, "a");
        when(repo.findByPrinterIdOrderByTagAsc(7L))
                .thenReturn(List.of(a))
                .thenReturn(List.of(a));

        service.replaceAllForPrinter(7L, List.of("A"));

        // Nothing to delete, nothing to insert.
        verify(repo, times(0)).deleteAll(anyCollection());
        verify(repo, times(0)).saveAll(anyCollection());
    }

    // ================================================================
    // tagsByPrinterId — feeds the FE table chip column
    // ================================================================

    @Test
    void tagsByPrinterId_groupsByPrinterId() {
        when(repo.findByPrinterIdInOrderByPrinterIdAscTagAsc(List.of(1L, 2L)))
                .thenReturn(List.of(row(1L, "a"), row(1L, "b"), row(2L, "c")));

        var byId = service.tagsByPrinterId(List.of(1L, 2L));

        assertThat(byId).hasSize(2);
        assertThat(byId.get(1L)).containsExactly("a", "b");
        assertThat(byId.get(2L)).containsExactly("c");
    }

    @Test
    void tagsByPrinterId_emptyInput_returnsEmpty() {
        assertThat(service.tagsByPrinterId(List.of())).isEmpty();
    }

    private static PrinterTag row(Long printerId, String tag) {
        PrinterTag t = new PrinterTag();
        t.setPrinterId(printerId);
        t.setTag(tag);
        return t;
    }
}
