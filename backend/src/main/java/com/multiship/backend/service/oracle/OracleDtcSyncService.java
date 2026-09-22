package com.multiship.backend.service.oracle;

import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.model.oracle.OracleDtcOrder;
import com.multiship.backend.repository.oracle.OracleDtcOrderRepositoryImpl;
import com.multiship.backend.repository.DtcOrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Oracle DTC Order Synchronization Service.
 * Fetches pending DTC orders from Oracle NDS and syncs them to PostgreSQL.
 *
 * Flow:
 *   1. Fetch pending orders from Oracle view (TB_SHIPX_DTC_UVW_TEST or TB_SHIPX_DTC_UVW)
 *      View name is configured via oracle.dtc.view-name property
 *      Development: application.properties (TB_SHIPX_DTC_UVW_TEST)
 *      Production: application-prod.properties (TB_SHIPX_DTC_UVW)
 *   2. Transform Oracle model to PostgreSQL DtcOrder model
 *   3. Bulk insert/update into PostgreSQL (handles duplicates)
 *   4. Return summary (fetched, imported, skipped)
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "multiship.oracle.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OracleDtcSyncService {

    private static final Logger log = LoggerFactory.getLogger(OracleDtcSyncService.class);

    private final OracleDtcOrderRepositoryImpl oracleDtcOrderRepository;
    private final DtcOrderRepository dtcOrderRepository;

    /**
     * Fetch pending DTC orders from Oracle and sync to PostgreSQL.
     * Uses the view configured in application properties (dev or prod).
     *
     * @param tenantId filter by tenant (empty = all tenants)
     * @return sync result with counts
     */
    @Transactional(value = "oracleTransactionManager", readOnly = true)
    public OracleSyncResult syncPendingDtcOrders(String tenantId) {
        log.info("Starting DTC order sync from Oracle (tenant={}, view configured in properties)",
                tenantId == null || tenantId.isEmpty() ? "ALL" : tenantId);

        List<OracleDtcOrder> oracleOrders;

        try {
            // Fetch from Oracle with 300s timeout
            if (tenantId == null || tenantId.isEmpty()) {
                oracleOrders = oracleDtcOrderRepository.findAllPendingDtcOrders();
            } else {
                oracleOrders = oracleDtcOrderRepository.findPendingOrdersByTenant(tenantId);
            }

            log.info("Fetched {} pending DTC orders from Oracle", oracleOrders.size());

            if (oracleOrders.isEmpty()) {
                return new OracleSyncResult(0, 0, 0, "No pending orders found in Oracle");
            }

            // Transform and sync to PostgreSQL
            return syncOrdersToPostgres(oracleOrders);

        } catch (Exception e) {
            log.error("Oracle DTC sync failed", e);
            return new OracleSyncResult(0, 0, 0,
                    "Sync failed: " + e.getMessage());
        }
    }

    /**
     * Sync fetched Oracle orders to PostgreSQL.
     * Handles duplicate prevention via composite key (batchId + toteNumber + tenantId).
     */
    @Transactional(value = "postgresTransactionManager")
    private OracleSyncResult syncOrdersToPostgres(List<OracleDtcOrder> oracleOrders) {
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        for (OracleDtcOrder oracleOrder : oracleOrders) {
            try {
                // Check if order already exists using composite key (idempotent)
                // Composite key: batchId + toteNumber + tenantId
                // This prevents duplicates when the same batchId exists for different totes or tenants
                if (dtcOrderRepository.existsByBatchIdAndToteNumberAndTenantId(
                        oracleOrder.getBatchId(),
                        oracleOrder.getToteNumber(),
                        oracleOrder.getTenantId())) {
                    log.debug("DTC Order (batch={}, tote={}, tenant={}) already exists in PostgreSQL, skipping",
                            oracleOrder.getBatchId(),
                            oracleOrder.getToteNumber(),
                            oracleOrder.getTenantId());
                    skipped++;
                    continue;
                }

                // Transform Oracle model to PostgreSQL DtcOrder model
                DtcOrder dtcOrder = transformOracleToPostgres(oracleOrder);

                // Save to PostgreSQL
                dtcOrderRepository.save(dtcOrder);
                imported++;

                if (imported % 100 == 0) {
                    log.info("Imported {} DTC orders so far...", imported);
                }

            } catch (Exception e) {
                log.error("Failed to import Oracle DTC order (batch={}, tote={}, tenant={}): {}",
                        oracleOrder.getBatchId(),
                        oracleOrder.getToteNumber(),
                        oracleOrder.getTenantId(),
                        e.getMessage());
                failed++;
            }
        }

        String message = String.format(
                "DTC Sync: imported=%d, skipped=%d, failed=%d (composite key: batchId+toteNumber+tenantId)",
                imported, skipped, failed);
        log.info(message);

        return new OracleSyncResult(
                oracleOrders.size(),  // fetched
                imported,
                skipped,
                message
        );
    }

    /**
     * Transform Oracle DTC order to PostgreSQL DtcOrder model.
     * Maps all columns from Oracle view to PostgreSQL entity.
     */
    private DtcOrder transformOracleToPostgres(OracleDtcOrder oracleOrder) {
        DtcOrder dtcOrder = new DtcOrder();

        // ═══════════════════════════ Primary Keys ═══════════════════════════
        dtcOrder.setBatchId(oracleOrder.getBatchId());

        // ═══════════════════════════ Order Information ═══════════════════════════
        dtcOrder.setOrderNo(oracleOrder.getOrderNo());
        dtcOrder.setOrderSuffix(oracleOrder.getOrderSuffix());
        dtcOrder.setOrderStatus(oracleOrder.getOrderStatus());

        // ═══════════════════════════ Customer Information ═══════════════════════════
        dtcOrder.setCustNo(oracleOrder.getCustNo());
        dtcOrder.setCustPo(oracleOrder.getCustPo());
        dtcOrder.setTenantId(oracleOrder.getTenantId());

        // ═══════════════════════════ Shipping Method & Terms ═══════════════════════════
        dtcOrder.setShipViaCode(oracleOrder.getShipViaCode());
        dtcOrder.setShipVia(oracleOrder.getShipVia());
        dtcOrder.setTermsCode(oracleOrder.getTermsCode());

        // ═══════════════════════════ Ship-to Address ═══════════════════════════
        dtcOrder.setShipName(oracleOrder.getShipName());
        dtcOrder.setShipAttn(oracleOrder.getShipAttn());
        dtcOrder.setShipAddr1(oracleOrder.getShipAddr1());
        dtcOrder.setShipAddr2(oracleOrder.getShipAddr2());
        dtcOrder.setShipAddr3(oracleOrder.getShipAddr3());
        dtcOrder.setShipToCity(oracleOrder.getShipToCity());
        dtcOrder.setShipToState(oracleOrder.getShipToState());
        dtcOrder.setShipToZip(oracleOrder.getShipToZip());
        dtcOrder.setShipToCountryCode(oracleOrder.getShipToCountryCode());
        dtcOrder.setCountryName(oracleOrder.getCountryName());

        // ═══════════════════════════ Contact Information ═══════════════════════════
        dtcOrder.setPhone(oracleOrder.getPhone());
        dtcOrder.setEmail(oracleOrder.getEmail());

        // ═══════════════════════════ Package & Shipment Details ═══════════════════════════
        dtcOrder.setWeight(oracleOrder.getWeight());
        dtcOrder.setUnitValue(oracleOrder.getUnitValue());
        dtcOrder.setPrice(oracleOrder.getPrice());
        dtcOrder.setFreightCost(oracleOrder.getFreightCost());
        dtcOrder.setGoodsDesc(oracleOrder.getGoodsDesc());
        dtcOrder.setIntlYn(oracleOrder.getIntlYn());

        // ═══════════════════════════ Warehouse & Logistics ═══════════════════════════
        dtcOrder.setToteNumber(oracleOrder.getToteNumber());
        dtcOrder.setLocation(oracleOrder.getLocation());
        dtcOrder.setTrack(oracleOrder.getTrack());
        dtcOrder.setThirdPartyAccount(oracleOrder.getThirdPartyAccount());

        // ═══════════════════════════ Fulfillment & Shipping Dates ═══════════════════════════
        dtcOrder.setShipDate(oracleOrder.getShipDate());
        dtcOrder.setFfSchemaSubstr(oracleOrder.getFfSchemaSubstr());

        return dtcOrder;
    }

    /**
     * Get count of pending orders in Oracle for a tenant.
     */
    @Transactional(value = "oracleTransactionManager", readOnly = true)
    public long getPendingOrderCount(String tenantId) {
        return oracleDtcOrderRepository.countPendingByTenant(tenantId);
    }

    /**
     * DTC Sync result DTO.
     */
    public static class OracleSyncResult {
        public final int fetched;
        public final int imported;
        public final int skipped;
        public final String message;

        public OracleSyncResult(int fetched, int imported, int skipped, String message) {
            this.fetched = fetched;
            this.imported = imported;
            this.skipped = skipped;
            this.message = message;
        }

        @Override
        public String toString() {
            return String.format(
                    "OracleSyncResult{fetched=%d, imported=%d, skipped=%d, message='%s'}",
                    fetched, imported, skipped, message);
        }
    }
}
