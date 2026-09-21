package com.multiship.backend.service.oracle;

import com.multiship.backend.model.DtcOrder;
import com.multiship.backend.model.oracle.OracleDtcOrder;
import com.multiship.backend.repository.oracle.OracleDtcOrderRepository;
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
 *   1. Fetch pending orders from Oracle TB_SHIPX_DTC_UVW
 *   2. Transform Oracle model to PostgreSQL DtcOrder model
 *   3. Bulk insert/update into PostgreSQL (handles duplicates)
 *   4. Return summary (fetched, imported, skipped)
 */
@Service
@RequiredArgsConstructor
public class OracleDtcSyncService {

    private static final Logger log = LoggerFactory.getLogger(OracleDtcSyncService.class);

    private final OracleDtcOrderRepository oracleDtcOrderRepository;
    private final DtcOrderRepository dtcOrderRepository;

    /**
     * Fetch pending DTC orders from Oracle and sync to PostgreSQL.
     *
     * @param tenantId filter by tenant (empty = all tenants)
     * @return sync result with counts
     */
    @Transactional(value = "oracleTransactionManager", readOnly = true)
    public OracleSyncResult syncPendingDtcOrders(String tenantId) {
        log.info("Starting DTC order sync from Oracle (tenant={})",
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
     * Handles duplicate prevention via batch_id uniqueness.
     */
    @Transactional(value = "postgresTransactionManager")
    private OracleSyncResult syncOrdersToPostgres(List<OracleDtcOrder> oracleOrders) {
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        for (OracleDtcOrder oracleOrder : oracleOrders) {
            try {
                // Check if order already exists (idempotent)
                if (dtcOrderRepository.existsByBatchId(oracleOrder.getBatchId())) {
                    log.debug("DTC Order batch={} already exists in PostgreSQL, skipping",
                            oracleOrder.getBatchId());
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
                log.error("Failed to import Oracle DTC order batch={}: {}",
                        oracleOrder.getBatchId(), e.getMessage());
                failed++;
            }
        }

        String message = String.format(
                "DTC Sync: imported=%d, skipped=%d, failed=%d",
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
     */
    private DtcOrder transformOracleToPostgres(OracleDtcOrder oracleOrder) {
        DtcOrder dtcOrder = new DtcOrder();

        // Identifiers
        dtcOrder.setBatchId(oracleOrder.getBatchId());
        dtcOrder.setToteNumber(oracleOrder.getToteNumber());
        dtcOrder.setOrderNo(oracleOrder.getOrderNo());
        dtcOrder.setOrderSuffix(oracleOrder.getOrderSuffix());

        // Tenant/Customer
        dtcOrder.setTenantId(oracleOrder.getTenantId());
        dtcOrder.setCustNo(oracleOrder.getCustNo());

        // Shipping method
        dtcOrder.setShipViaCode(oracleOrder.getShipViaCode());

        // Ship-to address
        dtcOrder.setShipName(oracleOrder.getShipName());
        dtcOrder.setShipAttn(oracleOrder.getShipAttn());
        dtcOrder.setShipAddr1(oracleOrder.getShipAddr1());
        dtcOrder.setShipAddr2(oracleOrder.getShipAddr2());
        dtcOrder.setShipAddr3(oracleOrder.getShipAddr3());
        dtcOrder.setShipToCity(oracleOrder.getShipToCity());
        dtcOrder.setShipToState(oracleOrder.getShipToState());
        dtcOrder.setShipToZip(oracleOrder.getShipToZip());
        dtcOrder.setShipToCountryCode(oracleOrder.getShipToCountryCode());
        dtcOrder.setPhone(oracleOrder.getPhone());
        dtcOrder.setEmail(oracleOrder.getEmail());

        // Package details
        dtcOrder.setWeight(oracleOrder.getWeight());
        dtcOrder.setUnitValue(oracleOrder.getUnitValue());
        dtcOrder.setPrice(oracleOrder.getPrice());
        dtcOrder.setGoodsDesc(oracleOrder.getGoodsDesc());

        // Shipping details
        dtcOrder.setThirdPartyAccount(oracleOrder.getThirdPartyAccount());
        dtcOrder.setIntlYn(oracleOrder.getIntlYn());
        dtcOrder.setLocation(oracleOrder.getLocation());

        // Additional fields
        dtcOrder.setCustPo(oracleOrder.getCustPo());

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
