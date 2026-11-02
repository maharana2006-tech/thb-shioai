package com.multiship.backend.service;

import com.multiship.backend.dto.ReportFilters;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-166 / BUG-168 regression. The report SQL named a table that does not
 * exist ({@code order_tracking}; the entity maps {@code order_label_tracking})
 * and bound parameters its SQL never mentioned. Both are checked here without
 * a database: every FROM/JOIN table must be a mapped JPA table, and the set of
 * binds must equal the set of placeholders for every filter combination.
 */
class ReportSqlTest {

    private static final List<Function<ReportFilters, ReportSql>> DATASETS =
            List.of(ReportSql::orders, ReportSql::tracking, ReportSql::billing);

    private static final ReportFilters NONE = ReportFilters.builder().build();
    private static final ReportFilters ALL = ReportFilters.builder()
            .from(LocalDateTime.of(2026, 10, 1, 0, 0)).to(LocalDateTime.of(2026, 10, 26, 0, 0))
            .customerNo("MKL246").carrier("UPS").status("GENERATED").destCountry("US")
            .minWeightLb(new BigDecimal("1")).maxWeightLb(new BigDecimal("50"))
            .build();

    @Test
    void everyTableInTheReportSqlIsAMappedEntityTable() {
        Set<String> mapped = mappedTables();
        for (Function<ReportFilters, ReportSql> ds : DATASETS) {
            for (String table : ds.apply(ALL).tables()) {
                assertTrue(mapped.contains(table), "report SQL names a table that no entity maps: " + table);
            }
        }
    }

    @Test
    void bindsMatchPlaceholdersForEveryFilterCombination() {
        for (Function<ReportFilters, ReportSql> ds : DATASETS) {
            for (ReportFilters f : List.of(NONE, ALL, ReportFilters.builder().carrier("UPS").build(),
                    ReportFilters.builder().minWeightLb(BigDecimal.ONE).build())) {
                ReportSql s = ds.apply(f);
                assertEquals(s.placeholders(), s.params().keySet(), "binds vs placeholders for\n" + s.sql());
            }
        }
    }

    @Test
    void carrierFilterIsAContainsMatch() {
        assertEquals("%UPS%", ReportSql.orders(ReportFilters.builder().carrier("UPS").build()).params().get("carrier"));
    }

    private static Set<String> mappedTables() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<String> out = new HashSet<>();
        scanner.findCandidateComponents("com.multiship.backend.model").forEach(bd -> {
            try {
                Class<?> c = Class.forName(bd.getBeanClassName());
                Table t = c.getAnnotation(Table.class);
                out.add(t != null && !t.name().isBlank() ? t.name() : snake(c.getSimpleName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        });
        return out;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
