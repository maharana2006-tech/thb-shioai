package com.multiship.backend.service.dtc;

import com.multiship.backend.config.OracleDtcConfig;
import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.service.OrderImportServiceImpl;
import com.multiship.backend.service.externalsystems.ExternalSystemConfigService;
import com.multiship.backend.service.ndsshipment.NdsTemplates;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Fetch from NDS" — pulls pending rows from the Oracle NDS view
 * ({@code oracle.dtc.view-name}, e.g. {@code TB_SHIPX_DTC_UVW}) and records
 * each fetch as ONE ImportBatch with {@code source = "DTC"}. Mirrors
 * {@code WmsService} in shape so the copied D2C History page uses the
 * same batch wiring.
 *
 * <p>Wired to the S1 external-systems framework via {@link NdsTemplates}
 * (PRODUCTION login on {@code nds-default}) so the connection details
 * live in the {@code external_system_connection} table + secrets — no
 * separate {@code multiship.oracle.enabled} flag or {@code oracleEntityManagerFactory}
 * needed. If the {@code nds-default} connection row is missing or
 * inactive, {@link #isConfigured()} returns {@code false} and the pull
 * short-circuits with a non-configured result (same shape
 * {@code WmsService} uses when {@code WMS_BASE_URL} is blank).
 */
@Service
@RequiredArgsConstructor
public class DtcService {

    private static final Logger log = LoggerFactory.getLogger(DtcService.class);

    /** NDS SPI helper — dispenses a PRODUCTION-login JDBC template
     *  from the active {@code nds-default} connection. */
    private final NdsTemplates ndsTemplates;

    /** Read-only helper — checks that the {@code nds-default} row exists + is active. */
    private final ExternalSystemConfigService externalSystemConfig;

    /** The view name lives in {@code oracle.dtc.view-name} (dev default
     *  {@code TB_SHIPX_DTC_NEW_DEV}, prod {@code TB_SHIPX_DTC_UVW}). */
    private final OracleDtcConfig oracleDtcConfig;

    @Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRepository importBatchRepository;
    @Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRowRepository importBatchRowRepository;
    @Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper importObjectMapper;
    @Autowired(required = false)
    private OrderImportServiceImpl orderImportService;

    /** True when the {@code nds-default} connection row exists and is active. */
    public boolean isConfigured() {
        return externalSystemConfig.findByName(NdsTemplates.NDS_CONNECTION)
                .filter(com.multiship.backend.model.ExternalSystemConnection::isActive)
                .isPresent();
    }

    public WmsPullResultDTO pullShippable(String requestedBy) {
        if (!isConfigured()) {
            return WmsPullResultDTO.builder()
                    .configured(false)
                    .messages(List.of("D2C is not configured. Set up the '" + NdsTemplates.NDS_CONNECTION
                            + "' connection under /settings/external-systems (must be active)."))
                    .importedOrderNos(List.of())
                    .build();
        }

        String viewName = oracleDtcConfig.getDtcViewName();
        if (!StringUtils.hasText(viewName)) {
            return WmsPullResultDTO.builder()
                    .configured(false)
                    .messages(List.of("D2C view name is blank — set oracle.dtc.view-name in application properties."))
                    .importedOrderNos(List.of())
                    .build();
        }

        List<OrderImportRowDTO> rows;
        try {
            rows = fetchViewRows(viewName);
        } catch (Exception e) {
            log.warn("D2C pull: NDS view fetch failed ({}): {}", viewName, e.getMessage());
            return WmsPullResultDTO.builder()
                    .configured(true)
                    .messages(List.of("Oracle NDS view isn't reachable right now — check " + NdsTemplates.NDS_CONNECTION
                            + " under /settings/external-systems, then try the fetch again."))
                    .importedOrderNos(List.of())
                    .build();
        }

        if (rows.isEmpty()) {
            return WmsPullResultDTO.builder()
                    .configured(true).fetched(0).imported(0).skipped(0).failed(0)
                    .messages(List.of("No pending DTC orders in " + viewName + "."))
                    .importedOrderNos(List.of())
                    .build();
        }

        return processBatch(requestedBy, rows);
    }

    /** SELECT * FROM {view} through NDS production-login JDBC template, mapped to import rows. */
    private List<OrderImportRowDTO> fetchViewRows(String viewName) {
        // Validate view name against a strict character set to keep this
        // safe even though it comes from server-side properties (defense
        // in depth — properties files sometimes take env-var overrides).
        if (!viewName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid oracle.dtc.view-name: " + viewName);
        }
        String sql = "SELECT ORDER_NO, ORDER_SUFFIX, ORDER_STATUS, CUST_NO, CUST_PO, TENANT_ID, "
                + "SHIPVIA_CD, SHIP_VIA, TERMS_CD, SHIP_NAME, SHIP_ATTN, SHIP_ADDR1, SHIP_ADDR2, "
                + "SHIPTO_CITY, SHIPTO_STATE, SHIPTO_ZIP, SHIPTO_COUNTRY_CD, PHONE, EMAIL, "
                + "WEIGHT, UNIT_VALUE, GOODS_DESC, INTL_YN, TOTE_NUMBER "
                + "FROM " + viewName + " o "
                + "WHERE o.BATCH_ID != 0 "
                + "  AND o.TOTE_NUMBER IS NOT NULL "
                + "  AND TRIM(o.TOTE_NUMBER) != '' "
                + "  AND COALESCE(o.ORDER_SUFFIX, 0) = 0 "
                + "ORDER BY o.BATCH_ID DESC";
        NamedParameterJdbcTemplate jdbc = ndsTemplates.production();
        List<OrderImportRowDTO> out = new ArrayList<>();
        int[] rowNumber = { 0 };
        jdbc.query(sql, new MapSqlParameterSource(), rs -> {
            OrderImportRowDTO r = new OrderImportRowDTO();
            r.setRowNumber(++rowNumber[0]);
            BigDecimal orderNo = rs.getBigDecimal("ORDER_NO");
            r.setOrderRef(orderNo == null ? null : orderNo.toPlainString());
            r.setClientCode(trimOrNull(rs.getString("CUST_NO")));
            r.setRecipientName(firstNonBlank(rs.getString("SHIP_NAME"), rs.getString("SHIP_ATTN")));
            r.setRecipientCompany(trimOrNull(rs.getString("SHIP_ATTN")));
            r.setRecipientPhone(digitsOrNull(rs.getString("PHONE")));
            r.setRecipientEmail(trimOrNull(rs.getString("EMAIL")));
            r.setAddressLine1(trimOrNull(rs.getString("SHIP_ADDR1")));
            r.setAddressLine2(trimOrNull(rs.getString("SHIP_ADDR2")));
            r.setCity(trimOrNull(rs.getString("SHIPTO_CITY")));
            r.setState(trimOrNull(rs.getString("SHIPTO_STATE")));
            r.setPostalCode(trimOrNull(rs.getString("SHIPTO_ZIP")));
            r.setCountryCode(trimOrNull(rs.getString("SHIPTO_COUNTRY_CD")));
            r.setShipViaCode(trimOrNull(rs.getString("SHIPVIA_CD")));
            r.setReference(trimOrNull(rs.getString("CUST_PO")));
            BigDecimal w = rs.getBigDecimal("WEIGHT");
            if (w != null && w.signum() > 0) {
                r.setWeight(w);
                r.setWeightUnit("LB");
            }
            r.setItemDescription(trimOrNull(rs.getString("GOODS_DESC")));
            r.setItemUnitValue(rs.getBigDecimal("UNIT_VALUE"));
            out.add(r);
        });
        return out;
    }

    /** Persist the fetch as one DTC ImportBatch, dedup'd by content hash like WMS. */
    @Transactional
    protected WmsPullResultDTO processBatch(String requestedBy, List<OrderImportRowDTO> importRows) {
        // Validate each row so the batch surfaces "needs fixes" correctly.
        for (OrderImportRowDTO r : importRows) {
            r.setErrors(new ArrayList<>(OrderImportServiceImpl.validateRow(r)));
        }
        int total = importRows.size();
        int invalid = (int) importRows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty()).count();
        List<String> messages = new ArrayList<>();

        Long importBatchId = null;
        String slug = null;
        if (total > 0 && importBatchRepository != null) {
            String hash = OrderImportServiceImpl.contentHash(importRows);
            ImportBatch existing = hash == null ? null
                    : importBatchRepository.findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(hash).orElse(null);
            if (existing != null) {
                messages.add("These DTC rows were already fetched — showing batch #" + existing.getId() + ".");
                return WmsPullResultDTO.builder()
                        .configured(true).fetched(total).imported(0).skipped(total).failed(0)
                        .importBatchId(existing.getId()).importBatchSlug(existing.getSlug())
                        .importedOrderNos(List.of()).messages(messages).build();
            }
            if (orderImportService != null) {
                try { orderImportService.flagOrderRefsAlreadyGenerated(importRows); } catch (Exception ignore) { }
            }
            importBatchId = recordBatch(requestedBy, importRows, invalid, hash);
            if (importBatchId != null && importBatchRowRepository != null) {
                ImportBatch batch = importBatchRepository.findById(importBatchId).orElse(null);
                if (batch != null) {
                    slug = batch.getSlug();
                    try {
                        for (OrderImportRowDTO r : importRows) {
                            importBatchRowRepository.save(toImportBatchRow(batch, r));
                        }
                    } catch (Exception e) {
                        log.warn("D2C pull: row persistence error: {}", e.getMessage());
                    }
                }
            }
        }

        log.info("D2C pull ({}): fetched {}, imported {}", requestedBy, total, total);
        return WmsPullResultDTO.builder()
                .configured(true).fetched(total).imported(total).skipped(0).failed(0)
                .importBatchId(importBatchId).importBatchSlug(slug)
                .importedOrderNos(List.of()).messages(messages).build();
    }

    private Long recordBatch(String requestedBy, List<OrderImportRowDTO> rows, int invalid, String contentHash) {
        try {
            ImportBatch batch = new ImportBatch();
            batch.setCreatedBy(requestedBy);
            batch.setFileName("NDS fetch — " + rows.size() + " shipment" + (rows.size() == 1 ? "" : "s"));
            batch.setSource("DTC");
            batch.setStatus(invalid > 0 ? "DRAFT" : "INITIATE");
            batch.setCreatedAt(LocalDateTime.now());
            batch.setTotalRows(rows.size());
            batch.setSavedRows(rows.size());
            batch.setInvalidRows(invalid);
            batch.setBillingMode("AUTO");
            batch.setContentHash(contentHash);
            rows.stream().map(OrderImportRowDTO::getClientCode)
                    .filter(c -> c != null && !c.isBlank()).findFirst()
                    .ifPresent(c -> batch.setClientCode(c.trim().toUpperCase(Locale.ROOT)));
            return importBatchRepository.save(batch).getId();
        } catch (Exception e) {
            log.warn("D2C pull: could not record the fetch batch: {}", e.getMessage());
            return null;
        }
    }

    private com.multiship.backend.model.ImportBatchRow toImportBatchRow(ImportBatch batch, OrderImportRowDTO dto) {
        com.multiship.backend.model.ImportBatchRow row = new com.multiship.backend.model.ImportBatchRow();
        row.setImportBatch(batch);
        row.setRowNumber(dto.getRowNumber());
        row.setOrderRef(dto.getOrderRef());
        row.setReference(dto.getReference());
        row.setClientCode(dto.getClientCode());
        row.setRecipientName(dto.getRecipientName());
        row.setRecipientCompany(dto.getRecipientCompany());
        row.setRecipientPhone(dto.getRecipientPhone());
        row.setRecipientEmail(dto.getRecipientEmail());
        row.setAddressLine1(dto.getAddressLine1());
        row.setAddressLine2(dto.getAddressLine2());
        row.setCity(dto.getCity());
        row.setState(dto.getState());
        row.setPostalCode(dto.getPostalCode());
        row.setCountryCode(dto.getCountryCode());
        row.setShipViaCode(dto.getShipViaCode());
        row.setWeight(dto.getWeight());
        row.setWeightUnit(dto.getWeightUnit());
        row.setItemDescription(dto.getItemDescription());
        row.setItemUnitValue(dto.getItemUnitValue());
        if (dto.getErrors() != null && !dto.getErrors().isEmpty()) {
            try {
                row.setErrors(importObjectMapper != null ? importObjectMapper.writeValueAsString(dto.getErrors()) : null);
            } catch (Exception e) {
                row.setErrors(String.join(", ", dto.getErrors()));
            }
        }
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    private static String trimOrNull(String v) { return StringUtils.hasText(v) ? v.trim() : null; }
    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) return a.trim();
        return StringUtils.hasText(b) ? b.trim() : null;
    }
    private static String digitsOrNull(String v) {
        if (!StringUtils.hasText(v)) return null;
        String digits = v.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? v.trim() : digits;
    }
}
