package com.multiship.backend.service.dtc;

import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.model.oracle.OracleDtcOrder;
import com.multiship.backend.repository.oracle.OracleDtcOrderRepositoryImpl;
import com.multiship.backend.service.OrderImportServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * ({@code TB_SHIPX_DTC_UVW} / {@code _TEST}) and records each fetch as ONE
 * ImportBatch with {@code source = "DTC"}. Mirrors {@code WmsService} in
 * shape so the copied D2C History page uses the same batch wiring.
 *
 * <p>Feature-gated off by default: when {@code multiship.oracle.enabled=false}
 * (the current default) the Oracle repository bean isn't loaded and
 * {@link #isConfigured()} returns {@code false}; {@link #pullShippable} then
 * short-circuits with a non-configured result — same shape as
 * {@code WmsService} when the WMS URL is blank.
 */
@Service
@RequiredArgsConstructor
public class DtcService {

    private static final Logger log = LoggerFactory.getLogger(DtcService.class);

    /** Null when {@code multiship.oracle.enabled=false} — the bean isn't loaded. */
    @Autowired(required = false)
    private OracleDtcOrderRepositoryImpl oracleRepo;

    @Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRepository importBatchRepository;
    @Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRowRepository importBatchRowRepository;
    @Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper importObjectMapper;
    @Autowired(required = false)
    private OrderImportServiceImpl orderImportService;

    public boolean isConfigured() {
        return oracleRepo != null;
    }

    public WmsPullResultDTO pullShippable(String requestedBy) {
        if (!isConfigured()) {
            return WmsPullResultDTO.builder()
                    .configured(false)
                    .messages(List.of("D2C is not configured. Set multiship.oracle.enabled=true and configure oracle.dtc.* to enable."))
                    .importedOrderNos(List.of())
                    .build();
        }

        List<OracleDtcOrder> rows;
        try {
            rows = oracleRepo.findAllPendingDtcOrders();
        } catch (Exception e) {
            log.warn("D2C pull: Oracle view fetch failed: {}", e.getMessage());
            return WmsPullResultDTO.builder()
                    .configured(true)
                    .messages(List.of("Oracle NDS view isn't reachable right now — check the connection settings, then try the fetch again."))
                    .importedOrderNos(List.of())
                    .build();
        }

        if (rows.isEmpty()) {
            return WmsPullResultDTO.builder()
                    .configured(true).fetched(0).imported(0).skipped(0).failed(0)
                    .messages(List.of("No pending DTC orders in the NDS view."))
                    .importedOrderNos(List.of())
                    .build();
        }

        return processBatch(requestedBy, rows);
    }

    /** Map + persist as one DTC ImportBatch, dedup'd by content hash like WMS. */
    @Transactional
    protected WmsPullResultDTO processBatch(String requestedBy, List<OracleDtcOrder> rows) {
        List<OrderImportRowDTO> importRows = new ArrayList<>();
        int shipments = 0, failed = 0;
        List<String> messages = new ArrayList<>();

        for (OracleDtcOrder src : rows) {
            if (src == null || src.getOrderNo() == null) {
                failed++;
                continue;
            }
            shipments++;
            OrderImportRowDTO row = toImportRow(src, importRows.size() + 1);
            List<String> errors = new ArrayList<>(OrderImportServiceImpl.validateRow(row));
            row.setErrors(errors);
            importRows.add(row);
        }

        int total = importRows.size();
        int invalid = (int) importRows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty()).count();

        Long importBatchId = null;
        String slug = null;
        if (total > 0 && importBatchRepository != null) {
            String hash = OrderImportServiceImpl.contentHash(importRows);
            ImportBatch existing = hash == null ? null
                    : importBatchRepository.findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(hash).orElse(null);
            if (existing != null) {
                importBatchId = existing.getId();
                slug = existing.getSlug();
                messages.add("These DTC rows were already fetched — showing batch #" + existing.getId() + ".");
                return WmsPullResultDTO.builder()
                        .configured(true).fetched(rows.size()).imported(0)
                        .skipped(shipments).failed(failed)
                        .importBatchId(importBatchId).importBatchSlug(slug)
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

        log.info("D2C pull ({}): fetched {}, imported {}, failed {}", requestedBy, rows.size(), shipments, failed);
        return WmsPullResultDTO.builder()
                .configured(true).fetched(rows.size()).imported(shipments)
                .skipped(0).failed(failed)
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

    /** Oracle DTC row → editable import row. Field mapping mirrors OracleDtcSyncService. */
    private OrderImportRowDTO toImportRow(OracleDtcOrder src, int rowNumber) {
        OrderImportRowDTO r = new OrderImportRowDTO();
        r.setRowNumber(rowNumber);
        r.setOrderRef(src.getOrderNo() == null ? null : src.getOrderNo().toPlainString());
        r.setClientCode(trimOrNull(src.getCustNo()));
        r.setRecipientName(firstNonBlank(src.getShipName(), src.getShipAttn()));
        r.setRecipientCompany(trimOrNull(src.getShipAttn()));
        r.setRecipientPhone(digitsOrNull(src.getPhone()));
        r.setRecipientEmail(trimOrNull(src.getEmail()));
        r.setAddressLine1(trimOrNull(src.getShipAddr1()));
        r.setAddressLine2(trimOrNull(src.getShipAddr2()));
        r.setCity(trimOrNull(src.getShipToCity()));
        r.setState(trimOrNull(src.getShipToState()));
        r.setPostalCode(trimOrNull(src.getShipToZip()));
        r.setCountryCode(trimOrNull(src.getShipToCountryCode()));
        r.setShipViaCode(trimOrNull(src.getShipViaCode()));
        r.setReference(trimOrNull(src.getCustPo()));
        BigDecimal w = src.getWeight();
        if (w != null && w.signum() > 0) {
            r.setWeight(w);
            r.setWeightUnit("LB");
        }
        r.setItemDescription(trimOrNull(src.getGoodsDesc()));
        r.setItemUnitValue(src.getUnitValue());
        return r;
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
