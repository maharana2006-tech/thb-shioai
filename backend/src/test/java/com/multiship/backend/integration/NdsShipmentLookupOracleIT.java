package com.multiship.backend.integration;

import com.multiship.backend.model.ClientShipviaCodeMap;
import com.multiship.backend.repository.ClientShipviaCodeMapRepository;
import com.multiship.backend.service.TenantScopeEnforcer;
import com.multiship.backend.service.ndsshipment.NdsShipmentLookupRepository;
import com.multiship.backend.service.ndsshipment.NdsShipmentLookupService;
import com.multiship.backend.service.ndsshipment.NdsShipmentPrefill;
import com.multiship.backend.service.ndsshipment.NdsTemplates;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.OracleContainer;

import javax.sql.DataSource;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR3 — full-round-trip integration test for the NDS Shipment prefill
 * feature (PR #735 backend + PR #736 FE). Uses a real Oracle container
 * (gvenzl/oracle-free:23-slim-faststart) instead of mocked JdbcTemplate
 * so the SQL text + bind params + row mappers get end-to-end coverage.
 *
 * <p><b>What this proves:</b>
 * <ul>
 *   <li>SQL text parses under Oracle (no PostgreSQL-only syntax slipped in).</li>
 *   <li>Bind-parameter names match column projections.</li>
 *   <li>{@link NdsShipmentLookupRepository} row mappers hydrate typed
 *       records correctly.</li>
 *   <li>{@link NdsShipmentLookupService} orchestrator threads the whole
 *       response together (recipient sanitize, phone normalize, notify
 *       fallback, international detection, BLOCKED/WARNING escalation).</li>
 * </ul>
 *
 * <p><b>What this does NOT prove:</b> that the invented DDL in
 * {@code nds-oracle-init.sql} matches the real NDS schema — the schema
 * is inferred from the task spec. When ops confirms real column names /
 * types, adjust both the DDL and the repository.
 *
 * <p><b>Guard:</b> extends {@link AbstractIntegrationTest} → skipped
 * unless {@code INTEGRATION_TESTS=1}. Also tagged {@code oracle-it} for
 * a future {@code -Dgroups=oracle-it} selective run.
 *
 * <p><b>Container lifecycle:</b> JVM-singleton (per repo convention) —
 * one Oracle boot per JVM, ~30-60s warm. First-ever run pulls the
 * ~1.5GB image, ~2-3 min. Cached after.
 */
@Tag("oracle-it")
@Import(NdsShipmentLookupOracleIT.OracleTemplatesConfig.class)
class NdsShipmentLookupOracleIT extends AbstractIntegrationTest {

    // JVM-singleton — see AbstractIntegrationTest for the rationale.
    // Container image explicitly free-tier + "faststart" for CI-friendliness.
    private static final OracleContainer oracle;
    static {
        oracle = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withDatabaseName("FREEPDB1")
                .withUsername("test")
                .withPassword("test")
                .withInitScript("nds-oracle-init.sql");
        oracle.start();
    }

    /**
     * Test-only bean replacement: {@link NdsTemplates} normally goes
     * through the S1 external-systems registry (needs a seeded
     * connection row + secrets). Here we point both PRODUCTION and
     * CLIENT logins at the same container's DataSource — the invented
     * DDL puts everything in one schema, so the distinction is moot.
     */
    @TestConfiguration
    static class OracleTemplatesConfig {
        @Bean
        @Primary
        NdsTemplates ndsTemplates() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(oracle.getJdbcUrl());
            ds.setUsername(oracle.getUsername());
            ds.setPassword(oracle.getPassword());
            ds.setMaximumPoolSize(3);
            return new NdsTemplates(null) {
                @Override public NamedParameterJdbcTemplate production() {
                    return new NamedParameterJdbcTemplate(ds);
                }
                @Override public NamedParameterJdbcTemplate forClient(String clientCode) {
                    return new NamedParameterJdbcTemplate(ds);
                }
            };
        }
    }

    @Autowired NdsShipmentLookupService service;
    @Autowired NdsTemplates ndsTemplates;
    @Autowired ClientShipviaCodeMapRepository clientShipviaRepo;
    @Autowired TenantScopeEnforcer tenantScopeEnforcer;

    private JdbcTemplate jdbc;

    @BeforeAll
    static void logContainer() {
        System.out.println("nds-oracle-it: container jdbcUrl=" + oracle.getJdbcUrl());
    }

    @BeforeEach
    void seedFreshData() {
        DataSource ds = extractDataSource(ndsTemplates);
        jdbc = new JdbcTemplate(ds);
        wipeAllTables();
        seedClient("ACME", "123456", "1", "77777");
        clientShipviaRepo.save(newClientMap("ACME", "P80", 42L));
    }

    @AfterEach
    void cleanupClientMap() {
        clientShipviaRepo.deleteAll();
    }

    // ═════════════════ .X — DIRECT flow ════════════════════════════

    @Test
    void directLookupHappyPath() {
        Optional<NdsShipmentPrefill> maybe = service.lookup(".X77777");
        assertTrue(maybe.isPresent(), "container 77777 should be found");
        NdsShipmentPrefill p = maybe.get();
        assertEquals(NdsShipmentPrefill.Status.OK, p.status(), "no blocking flags → OK");
        assertEquals(NdsShipmentPrefill.Scope.DIRECT, p.scope());
        assertEquals("ACME", p.clientCode());
        assertNotNull(p.recipient());
        assertEquals("Wile E Coyote", p.recipient().name());
        assertEquals("Tucson", p.recipient().city());
        assertEquals(1, p.packages().size());
        assertTrue(p.packages().get(0).isScanned(), "scanned container flagged");
        assertEquals(42L, p.shipMethod().mappedServiceId(), "P80 resolves via ClientShipviaCodeMap");
    }

    @Test
    void directLookupNotFoundReturnsEmpty() {
        assertTrue(service.lookup(".X00000").isEmpty());
    }

    @Test
    void directLookupHoldFlagIsBlocked() {
        jdbc.update("UPDATE OEHEAD SET HOLD_FLAG = 'Y' WHERE ORDER_NO = ?", "123456");
        NdsShipmentPrefill p = service.lookup(".X77777").orElseThrow();
        assertEquals(NdsShipmentPrefill.Status.BLOCKED, p.status());
        assertTrue(p.messages().stream().anyMatch(m -> m.text().contains("HOLD_FLAG")));
    }

    @Test
    void directLookupHldShipviaIsBlocked() {
        jdbc.update("UPDATE OEHEAD SET SHIPVIA_CD = 'HLD' WHERE ORDER_NO = ?", "123456");
        NdsShipmentPrefill p = service.lookup(".X77777").orElseThrow();
        assertEquals(NdsShipmentPrefill.Status.BLOCKED, p.status());
    }

    @Test
    void directLookupUnmappedShipviaIsWarning() {
        clientShipviaRepo.deleteAll();
        jdbc.update("UPDATE OEHEAD SET SHIPVIA_CD = 'XYZ' WHERE ORDER_NO = ?", "123456");
        NdsShipmentPrefill p = service.lookup(".X77777").orElseThrow();
        assertEquals(NdsShipmentPrefill.Status.WARNING, p.status());
        assertNull(p.shipMethod().mappedServiceId());
    }

    @Test
    void directLookupInternationalPopulatesItems() {
        jdbc.update("UPDATE OEHEAD SET SHIP_TO_COUNTRY_CD = 'GB' WHERE ORDER_NO = ?", "123456");
        jdbc.update(
                "INSERT INTO OE_INTL_ITEMS (ORDER_NO, ORDER_SUFFIX, LINE_NO, DESCRIPTION, "
                        + "QUANTITY, UNIT_VALUE, CURRENCY_CD, HS_CODE, COUNTRY_OF_ORIGIN, UNIT_WEIGHT_LB) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "123456", "1", 1, "Widget", 3, 12.50, "USD", "8471.30", "US", 0.5);
        NdsShipmentPrefill p = service.lookup(".X77777").orElseThrow();
        assertNotNull(p.international());
        assertTrue(p.international().international());
        assertEquals(1, p.international().items().size());
        assertEquals("Widget", p.international().items().get(0).description());
    }

    // ═════════════════ .Y — BATCH flow ═════════════════════════════

    @Test
    void batchLookupHappyPath() {
        // Add a second container to the same order + a batch that owns both
        seedContainer("ACME", "123456", "1", "77778", 1.5);
        jdbc.update(
                "INSERT INTO TB_BILLABLE_CONTAINERS (BATCH_ID, CONTAINER_ID, ORDER_NO, "
                        + "ORDER_SUFFIX, FF_SCHEMA, PRIMARY_CLIENT_CODE) VALUES (?, ?, ?, ?, ?, ?)",
                "B42", "77777", "123456", "1", "FF", "ACME");
        jdbc.update(
                "INSERT INTO TB_BILLABLE_CONTAINERS (BATCH_ID, CONTAINER_ID, ORDER_NO, "
                        + "ORDER_SUFFIX, FF_SCHEMA, PRIMARY_CLIENT_CODE) VALUES (?, ?, ?, ?, ?, ?)",
                "B42", "77778", "123456", "1", "FF", "ACME");
        NdsShipmentPrefill p = service.lookup(".YB42").orElseThrow();
        assertEquals(NdsShipmentPrefill.Scope.BATCH, p.scope());
        assertEquals("B42", p.batchId());
        assertEquals(2, p.packages().size(), "both batch containers become package rows");
    }

    @Test
    void batchLookupNotFoundReturnsEmpty() {
        assertTrue(service.lookup(".YNONESUCH").isEmpty());
    }

    // ═════════════════ notify email defaulting ══════════════════════

    @Test
    void notifyEmailDefaultsWhenNoRows() {
        NdsShipmentPrefill p = service.lookup(".X77777").orElseThrow();
        assertTrue(p.notifyBlock().emailDefaulted());
        assertEquals(NdsShipmentLookupService.DEFAULT_NOTIFY_EMAIL, p.notifyBlock().sendTo());
        assertTrue(p.defaultedFields().contains("notify.sendTo"));
    }

    @Test
    void notifyEmailFromNdsWhenPresent() {
        jdbc.update("INSERT INTO OE_SEND_TO (ORDER_NO, ORDER_SUFFIX, SEND_TO, SEND_TYPE) "
                + "VALUES (?, ?, ?, ?)", "123456", "1", "ops@acme.example", "E");
        NdsShipmentPrefill p = service.lookup(".X77777").orElseThrow();
        assertFalse(p.notifyBlock().emailDefaulted());
        assertEquals("ops@acme.example", p.notifyBlock().sendTo());
    }

    // ═════════════════ helpers ═════════════════════════════════════

    private void wipeAllTables() {
        for (String t : new String[]{"OE_SEND_TO", "OE_INTL_ITEMS", "OE_SHIP_CONTAINER",
                "OEHEAD", "SHIPVIA", "TB_SHIP_CONTAINER", "TB_BILLABLE_CONTAINERS"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    private void seedClient(String client, String orderNo, String suffix, String containerId) {
        jdbc.update(
                "INSERT INTO TB_SHIP_CONTAINER (CONTAINER_ID, TENANT_ID, ORDER_NO, ORDER_SUFFIX) "
                        + "VALUES (?, ?, ?, ?)",
                containerId, client, orderNo, suffix);
        jdbc.update(
                "INSERT INTO OEHEAD (ORDER_NO, ORDER_SUFFIX, SHIP_TO_NAME, SHIP_TO_ATTN, "
                        + "SHIP_TO_ADDR1, SHIP_TO_CITY, SHIP_TO_STATE, SHIP_TO_POSTAL, "
                        + "SHIP_TO_COUNTRY_CD, SHIP_TO_PHONE, SHIP_TO_EMAIL, SHIPVIA_CD, "
                        + "SHIPPED_FLAG, HOLD_FLAG, CUST_PO, DEPARTMENT, INCOTERMS, CURRENCY_CD) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                orderNo, suffix, "Wile E Coyote", "ACME Corp",
                "1 Anvil Way", "Tucson", "AZ", "85701",
                "US", "6165551212", "wile@acme.example", "P80",
                "N", "N", "PO-100", "SHIP", "DAP", "USD");
        jdbc.update("INSERT INTO SHIPVIA (SHIPVIA_CD, SHIPVIA_DESC) VALUES (?, ?)",
                "P80", "UPS Ground");
        seedContainer(client, orderNo, suffix, containerId, 2.5);
    }

    private void seedContainer(String client, String orderNo, String suffix,
                               String containerId, double weight) {
        jdbc.update(
                "INSERT INTO OE_SHIP_CONTAINER (CONTAINER_ID, ORDER_NO, ORDER_SUFFIX, "
                        + "GROSS_WT, LENGTH, WIDTH, HEIGHT, CONTAINER_TYPE) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                containerId, orderNo, suffix, weight, 12, 6, 4, "BOX");
    }

    private static ClientShipviaCodeMap newClientMap(String client, String erpCode, long serviceId) {
        ClientShipviaCodeMap m = new ClientShipviaCodeMap();
        m.setClientCode(client);
        m.setErpCode(erpCode);
        m.setServiceId(serviceId);
        return m;
    }

    /** Reflection-cheat to extract the injected DataSource for direct SQL seeding. */
    private DataSource extractDataSource(NdsTemplates templates) {
        // Both production() and forClient() return the same DataSource per our
        // test override; grab either. NamedParameterJdbcTemplate → JdbcTemplate → DataSource.
        return templates.production().getJdbcTemplate().getDataSource();
    }
}
