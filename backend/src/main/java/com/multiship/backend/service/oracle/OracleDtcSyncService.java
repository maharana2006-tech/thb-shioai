package com.multiship.backend.service.oracle;

import com.multiship.backend.model.Order;
import com.multiship.backend.model.oracle.OracleDtcOrder;
import com.multiship.backend.repository.oracle.OracleDtcOrderRepository;
import com.multiship.backend.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Oracle DTC Order Synchronization Service.
 * Fetches pending DTC orders from Oracle NDS and syncs them to PostgreSQL.
 *
 * Flow:
 *   1. Fetch pending orders from Oracle TB_SHIPX_DTC_UVW
 *   2. Transform Oracle model to PostgreSQL Order model
 *   3. Bulk insert/update into PostgreSQL (handles ON CONFLICT)
 *   4. Return summary (fetched, imported, skipped)
 */
@Service
@RequiredArgsConstructor
public class OracleDtcSyncService {

    private static final Logger log = LoggerFactory.getLogger(OracleDtcSyncService.class);

    private final OracleDtcOrderRepository oracleDtcOrderRepository;
    private final OrderRepository postgresOrderRepository;

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
     * Handles duplicate prevention via ON CONFLICT DO NOTHING.
     */
    @Transactional(value = "postgresTransactionManager")
    private OracleSyncResult syncOrdersToPostgres(List<OracleDtcOrder> oracleOrders) {
        int imported = 0;
        int skipped = 0;
        int failed = 0;

        for (OracleDtcOrder oracleOrder : oracleOrders) {
            try {
                // Transform Oracle model to PostgreSQL Order model
                Order postgresOrder = transformOracleToPostgres(oracleOrder);

                // Check if order already exists (idempotent)
                if (postgresOrderRepository.existsByWmsExternalId(
                        String.valueOf(oracleOrder.getBatchId()))) {
                    log.debug("Order {} already exists in PostgreSQL, skipping",
                            oracleOrder.getOrderNo());
                    skipped++;
                    continue;
                }

                // Save to PostgreSQL
                postgresOrderRepository.save(postgresOrder);
                imported++;

                if (imported % 100 == 0) {
                    log.info("Imported {} orders so far...", imported);
                }

            } catch (Exception e) {
                log.error("Failed to import Oracle order {}: {}",
                        oracleOrder.getOrderNo(), e.getMessage());
                failed++;
            }
        }

        String message = String.format(
                "Synced: imported=%d, skipped=%d, failed=%d",
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
     * Transform Oracle DTC order to PostgreSQL Order model.
     */
    private Order transformOracleToPostgres(OracleDtcOrder oracleOrder) {
        Order order = new Order();

//        // Identifiers
//        order.setOrderNo(oracleOrder.getOrderNo());
//        order.setOrderSuffix("0");
//        order.setWmsExternalId(String.valueOf(oracleOrder.getBatchId()));
//        order.setBatchId(oracleOrder.getBatchId().intValue());
//
//        // Tenant/Customer
//        order.setCustNo(oracleOrder.getCustNo());
//        order.setTenantId(oracleOrder.getTenantId());
//
//        // Shipping method
//        order.setShipvia(oracleOrder.getShipViaCode());
//        order.setOrderChannel("D2C");  // DTC = Direct-to-Consumer
//        order.setOrderSource("DTC");
//
//        // Ship-to address
//        order.setShipName(oracleOrder.getShipName());
//        order.setShipAttn(oracleOrder.getShipAttn());
//        order.setShipAddr1(oracleOrder.getShipAddr1());
//        order.setShipAddr2(oracleOrder.getShipAddr2());
//        order.setShipAddr3(oracleOrder.getShipAddr3());
//        order.setShiptoCity(oracleOrder.getShipToCity());
//        order.setShiptoState(oracleOrder.getShipToState());
//        order.setShiptoZip(oracleOrder.getShipToZip());
//        order.setShiptoCoun(oracleOrder.getShipToCountryCode());
//        order.setPhone(oracleOrder.getPhone());
//        order.setEmail(oracleOrder.getEmail());
//
//        // Package details
//        order.setWeight(oracleOrder.getWeight());
//        order.setGoodsDesc(oracleOrder.getGoodsDesc());
//        order.setCustomerRef(oracleOrder.getCustPo());
//        order.setIntlYn(oracleOrder.getIntlYn());
//
//        // International flag
//        order.setIntlYn("Y".equalsIgnoreCase(oracleOrder.getIntlYn()) ? "Y" : "N");
//
//        // Billing
//        if (oracleOrder.getThirdPartyAccount() != null) {
//            order.setThirdPartyAcc(oracleOrder.getThirdPartyAccount());
//        }
//
//        // Audit
//        order.setCreatedDate(oracleOrder.getCreatedDate());
//        order.setIsManual("B");  // "B" = Background (automated pull)

        return order;
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
