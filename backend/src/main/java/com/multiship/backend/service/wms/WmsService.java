package com.multiship.backend.service.wms;

import com.multiship.backend.dto.OrderImportRowDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsAddress;
import com.multiship.backend.dto.wms.WmsPendingOrderDTO.WmsContainer;
import com.multiship.backend.dto.wms.WmsPullResultDTO;
import com.multiship.backend.model.ImportBatch;
import com.multiship.backend.service.OrderImportServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pulls pending (shippable) shipments from the external WMS and records each
 * fetch as ONE import batch — the same shape a CSV/XLSX upload produces. The
 * batch surfaces under the "API" section of Order Intake (source = WMS), where
 * the operator can edit rows inline, see per-row validation, and generate
 * carrier labels. Generating stamps the resulting orders as source = API so
 * they stay grouped under that section.
 *
 * <p>Each fetch is its own batch snapshot of the WMS's current pending
 * shipments (no cross-fetch dedup) — matching "one fetch = one batch".
 */
@Service
@RequiredArgsConstructor
public class WmsService {

    private static final Logger log = LoggerFactory.getLogger(WmsService.class);

    private final WmsClient wmsClient;

    /** Persists the fetch as an import batch (optional so the reduced-args
     *  unit-test constructor still compiles). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRepository importBatchRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.fasterxml.jackson.databind.ObjectMapper importObjectMapper;
    /** Resolves the WMS ship-via / ship-method to a catalog service (optional for unit tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.service.ShippingConfigService shippingConfigService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ClientShipviaCodeMapRepository clientShipviaCodeMapRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ShippingServiceRepository shippingServiceRepository;
    /** Settings → Shipping Service Mapping (ship-via code → service, per client). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ShipViaMappingRepository shipViaMappingRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.CarrierAccountRefRepository carrierAccountRefRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ClientWarehouseRepository clientWarehouseRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.WarehouseRepository warehouseRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrderImportServiceImpl orderImportService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.multiship.backend.repository.ImportBatchRowRepository importBatchRowRepository;

    public boolean isConfigured() {
        return wmsClient.isConfigured();
    }

    public WmsPullResultDTO pullShippable(String requestedBy) {
        if (!wmsClient.isConfigured()) {
            return WmsPullResultDTO.builder()
                    .configured(false)
                    .messages(List.of("WMS is not configured. Set WMS_BASE_URL to enable the pull."))
                    .importedOrderNos(List.of())
                    .build();
        }

        List<String> messages = new ArrayList<>();

        // Fetch exactly 1000 records (10 pages × 100 per page) - single click = single batch
        int pageNum = 0;
        List<WmsPendingOrderDTO> shippable = wmsClient.fetchShippableBatch(pageNum);

        if (shippable.isEmpty()) {
            log.info("WMS pull: no orders available from page {}", pageNum);
            return WmsPullResultDTO.builder()
                    .configured(true)
                    .fetched(0)
                    .imported(0)
                    .skipped(0)
                    .failed(0)
                    .batchId(null)
                    .importBatchId(null)
                    .importedOrderNos(List.of())
                    .messages(List.of("No orders available to fetch from WMS"))
                    .build();
        }

        int batchCount = 1;
        int totalFetched = shippable.size();

        // Process the batch in its own transaction
        BatchProcessResult batchResult = processBatch(requestedBy, shippable, batchCount);

        log.info("WMS pull ({}): fetched {} orders in 1 batch, {} row(s) → ({} need fixes, {} skipped)",
                requestedBy, totalFetched, batchResult.totalRows,
                batchResult.totalRows - batchResult.shipments, batchResult.failed);

        return WmsPullResultDTO.builder()
                .configured(true)
                .fetched(totalFetched)
                .imported(batchResult.shipments)
                .skipped(batchResult.skipped)
                .failed(batchResult.failed)
                .batchId(null)               // label batch is assigned when labels are generated
                .importBatchId(batchResult.importBatchId)
                .importedOrderNos(List.of())
                .messages(batchResult.messages)
                .build();
    }

    /** Helper class to return batch processing results */
    private static class BatchProcessResult {
        int shipments;
        int failed;
        int totalRows;
        /** Reuse-branch count — shipments that WEREN'T persisted because the
         *  content hash matched an existing live batch. Old callers set 0. */
        int skipped;
        Long importBatchId;
        List<String> messages;

        BatchProcessResult(int shipments, int failed, int totalRows, Long importBatchId, List<String> messages) {
            this(shipments, failed, totalRows, 0, importBatchId, messages);
        }

        BatchProcessResult(int shipments, int failed, int totalRows, int skipped,
                           Long importBatchId, List<String> messages) {
            this.shipments = shipments;
            this.failed = failed;
            this.totalRows = totalRows;
            this.skipped = skipped;
            this.importBatchId = importBatchId;
            this.messages = messages;
        }
    }

    /** Process one batch of 1000 orders in its own transaction to avoid timeout */
    @Transactional
    protected BatchProcessResult processBatch(String requestedBy, List<WmsPendingOrderDTO> shippable, int batchCount) {
        List<OrderImportRowDTO> rows = new ArrayList<>();
        int batchShipments = 0;
        int batchFailed = 0;
        List<String> messages = new ArrayList<>();

        for (WmsPendingOrderDTO src : shippable) {
            String externalId = src == null ? null : src.getShipmentNumber();
            if (!StringUtils.hasText(externalId)) {
                batchFailed++;
                messages.add("Skipped a WMS shipment with no shipmentNumber.");
                continue;
            }
            batchShipments++;
            OrderImportRowDTO row = toImportRow(src, rows.size() + 1);
            List<String> errors = new ArrayList<>(OrderImportServiceImpl.validateRow(row));
            errors.addAll(preflight(src, row));
            row.setErrors(errors);
            rows.add(row);
            for (OrderImportRowDTO itemLine : itemLines(src, row, rows.size() + 1)) {
                itemLine.setErrors(new ArrayList<>(OrderImportServiceImpl.validateRow(itemLine)));
                rows.add(itemLine);
            }
        }

        int batchTotal = rows.size();
        int batchInvalid = (int) rows.stream()
                .filter(r -> r.getErrors() != null && !r.getErrors().isEmpty())
                .count();

        Long importBatchId = null;
        if (batchTotal > 0 && importBatchRepository != null) {
            String hash = OrderImportServiceImpl.contentHash(rows);
            ImportBatch existing = hash == null ? null
                    : importBatchRepository.findFirstByContentHashAndDeletedAtIsNullOrderByIdDesc(hash).orElse(null);
            if (existing != null) {
                importBatchId = existing.getId();
                messages.add("Batch " + batchCount + ": These shipments were already fetched — showing batch #" + existing.getId() + ".");
                // Reused batch — nothing new was persisted. Report imported=0
                // (was: batchShipments count, which contradicted the "already
                // fetched" message and made the operator think a duplicate
                // batch was written).
                return new BatchProcessResult(0, 0, batchTotal, batchShipments, importBatchId, messages);
            } else {
                ImportBatch trashed = null;
                try {
                    trashed = hash == null ? null
                            : importBatchRepository.findFirstByContentHashOrderByIdDesc(hash)
                                .filter(b -> b.getDeletedAt() != null).orElse(null);
                } catch (Exception ignore) { }
                if (orderImportService != null) {
                    try { orderImportService.flagOrderRefsAlreadyGenerated(rows); } catch (Exception ignore) { }
                }
                long flagged = rows.stream().filter(r -> r.getWarnings() != null
                        && r.getWarnings().stream().anyMatch(w -> w.contains("already generated")))
                        .map(r -> r.getOrderRef() == null ? "" : r.getOrderRef().trim().toUpperCase())
                        .distinct().count();
                if (trashed != null) {
                    messages.add("Batch " + batchCount + ": These shipments were fetched before as batch #" + trashed.getId()
                            + ", which is in Trash. Restore it from Data History → Trash instead of generating again"
                            + (flagged > 0 ? " — " + flagged + " order(s) already have a live label and are flagged." : "."));
                } else if (flagged > 0) {
                    messages.add("Batch " + batchCount + ": " + flagged + " order(s) were already labelled from an earlier import — generating again creates duplicate shipments.");
                }
                importBatchId = recordBatch(requestedBy, rows, batchInvalid, hash);
                // Save each row to import_batch_row table
                if (importBatchId != null && importBatchRowRepository != null) {
                    ImportBatch batch = importBatchRepository.findById(importBatchId).orElse(null);
                    if (batch != null) {
                        try {
                            for (OrderImportRowDTO rowDto : rows) {
                                com.multiship.backend.model.ImportBatchRow importBatchRow = toImportBatchRow(batch, rowDto);
                                importBatchRowRepository.save(importBatchRow);
                            }
                            log.info("WMS pull batch {} : saved {} rows to import_batch_row table", batchCount, rows.size());
                        } catch (Exception e) {
                            log.error("WMS pull batch {}: error saving rows to import_batch_row: {}", batchCount, e.getMessage(), e);
                        }
                    } else {
                        log.warn("WMS pull batch {}: batch #{} not found after recording", batchCount, importBatchId);
                    }
                } else {
                    if (importBatchId == null) {
                        log.warn("WMS pull batch {}: importBatchId is null, skipping row persistence", batchCount);
                    }
                    if (importBatchRowRepository == null) {
                        log.warn("WMS pull batch {}: importBatchRowRepository is null", batchCount);
                    }
                }
                log.info("WMS pull batch {} : recorded batch #{} with {} row(s)",
                        batchCount, importBatchId, batchTotal);
            }
        }

        return new BatchProcessResult(batchShipments, batchFailed, batchTotal, importBatchId, messages);
    }

    /** Persist the fetched shipments as one editable/generatable import batch. */
    private Long recordBatch(String requestedBy, List<OrderImportRowDTO> rows, int invalid, String contentHash) {
        if (importBatchRepository == null) return null;
        try {
            ImportBatch batch = new ImportBatch();
            batch.setCreatedBy(requestedBy);
            batch.setFileName("WMS fetch — " + rows.size() + " shipment" + (rows.size() == 1 ? "" : "s"));
            batch.setSource("WMS");
            batch.setStatus(invalid > 0 ? "DRAFT" : "INITIATE");
            batch.setCreatedAt(LocalDateTime.now());
            batch.setTotalRows(rows.size());
            batch.setSavedRows(rows.size());
            batch.setInvalidRows(invalid);
            batch.setBillingMode("AUTO");
            batch.setContentHash(contentHash);
            // Whose orders these are — lets Bulk Mailer scope the list in the database.
            rows.stream().map(OrderImportRowDTO::getClientCode)
                    .filter(c -> c != null && !c.isBlank()).findFirst()
                    .ifPresent(c -> batch.setClientCode(c.trim().toUpperCase(java.util.Locale.ROOT)));
            return importBatchRepository.save(batch).getId();
        } catch (Exception e) {
            log.warn("WMS pull: could not record the fetch batch: {}", e.getMessage());
            return null;
        }
    }

    /** Convert OrderImportRowDTO to ImportBatchRow for database persistence */
    private com.multiship.backend.model.ImportBatchRow toImportBatchRow(ImportBatch batch, OrderImportRowDTO dto) {
        com.multiship.backend.model.ImportBatchRow row = new com.multiship.backend.model.ImportBatchRow();
        row.setImportBatch(batch);
        row.setRowNumber(dto.getRowNumber());
        row.setOrderRef(dto.getOrderRef());
        row.setReference(dto.getReference());
        row.setClientCode(dto.getClientCode());
        row.setWarehouseCode(dto.getWarehouseCode());
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
        row.setCarrierCode(dto.getCarrierCode());
        row.setServiceType(dto.getServiceType());
        row.setShipViaCode(dto.getShipViaCode());
        row.setShipViaNote(dto.getShipViaNote());
        row.setAccountNumber(dto.getAccountNumber());
        row.setPackageType(dto.getPackageType());
        row.setWeight(dto.getWeight());
        row.setWeightUnit(dto.getWeightUnit());
        row.setWeightInherited(dto.getWeightInherited());
        row.setLength(dto.getLength());
        row.setWidth(dto.getWidth());
        row.setHeight(dto.getHeight());
        row.setDimUnit(dto.getDimUnit());
        row.setCurrency(dto.getCurrency());
        row.setIncoterms(dto.getIncoterms());
        row.setHsCode(dto.getHsCode());
        row.setCountryOfOrigin(dto.getCountryOfOrigin());
        row.setItemSku(dto.getItemSku());
        row.setItemDescription(dto.getItemDescription());
        row.setItemQuantity(dto.getItemQuantity());
        row.setItemUnitValue(dto.getItemUnitValue());

        // Convert errors and warnings lists to JSON strings
        if (dto.getErrors() != null && !dto.getErrors().isEmpty()) {
            try {
                row.setErrors(importObjectMapper != null ? importObjectMapper.writeValueAsString(dto.getErrors()) : null);
            } catch (Exception e) {
                row.setErrors(String.join(", ", dto.getErrors()));
            }
        }
        if (dto.getWarnings() != null && !dto.getWarnings().isEmpty()) {
            try {
                row.setWarnings(importObjectMapper != null ? importObjectMapper.writeValueAsString(dto.getWarnings()) : null);
            } catch (Exception e) {
                row.setWarnings(String.join(", ", dto.getWarnings()));
            }
        }

        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        return row;
    }

    /** Map one WMS pending shipment to an editable import row. */
    private OrderImportRowDTO toImportRow(WmsPendingOrderDTO src, int rowNumber) {
        WmsAddress to = src.getShipToAddress();
        OrderImportRowDTO r = new OrderImportRowDTO();
        r.setRowNumber(rowNumber);
        r.setOrderRef(firstNonBlank(src.getOrderNo(), src.getShipmentNumber()));
        // WMS gives no customer number in the pending feed — the operator picks
        // the billing client in the grid (validateRow flags it as required).
        r.setClientCode(trimOrNull(src.getCustomerReferenceId()));
        if (to != null) {
            r.setRecipientName(firstNonBlank(to.getName(), to.getAttn()));
            r.setRecipientCompany(to.getAttn());
            r.setRecipientPhone(digitsOrNull(to.getPhone()));
            r.setRecipientEmail(trimOrNull(to.getEmail()));
            r.setAddressLine1(street(to.getAddr1()));
            r.setAddressLine2(trimOrNull(to.getAddr2()));
            r.setCity(trimOrNull(to.getCity()));
            r.setState(trimOrNull(to.getState()));
            r.setPostalCode(trimOrNull(to.getZip()));
            r.setCountryCode(firstNonBlank(to.getIso2(), to.getCountry()));
        }
        // Best-effort carrier from the WMS ship-via code; the operator can
        // override it inline. Unknown codes pass through so generation flags them.
        r.setCarrierCode(mapCarrier(src.getShipVia()));
        r.setServiceType(resolveService(r.getClientCode(), r.getCarrierCode(), src.getShipVia(),
                src.getShipMethod(), r.getCountryCode(), r));
        r.setAccountNumber(defaultAccount(r.getClientCode(), r.getCarrierCode()));
        r.setWarehouseCode(defaultWarehouse(r.getClientCode()));
        BigDecimal w = totalWeight(src.getContainers());
        if (w != null) {
            r.setWeight(w);
            r.setWeightUnit("LB");
        }
        r.setReference(trimOrNull(src.getPoNumber()));
        applyFirstItem(src, r);
        return r;
    }

    /**
     * WMS ship-via → catalog service, in order of trust:
     * <ol>
     *   <li>the WMS's own shipMethod when it sends one;</li>
     *   <li>the client's Shipping Service Mapping row for that ERP/ship-via code;</li>
     *   <li>the platform resolver on the raw code (handles "03", "UPS Ground",
     *       "GROUND", "U03"-style codes);</li>
     *   <li>the carrier's ground service, with a warning telling the operator to
     *       map the code — a label on the wrong service beats no label only when
     *       the operator can see it, hence the warning.</li>
     * </ol>
     * Returns null (and leaves the row to preflight) when nothing resolves.
     */
    private String resolveService(String clientCode, String carrier, String shipVia, String shipMethod,
                                  String destCountry, OrderImportRowDTO row) {
        if (shippingConfigService == null) {
            return trimOrNull(shipMethod);   // reduced-args unit-test wiring
        }
        String canon = carrier == null ? null : carrier.trim().toUpperCase();
        if (canon == null || canon.isBlank()) return trimOrNull(shipMethod);
        try {
            // Settings → Shipping Service Mapping FIRST, through the same rule
            // engine a CSV upload uses: client, destination and warehouse
            // specificity all count, and a disabled service doesn't match. The
            // rule also decides the carrier — the WMS ship-via's first letter is
            // only a guess, and a client may route "U11" to anyone they like.
            if (StringUtils.hasText(shipVia)) {
                var ruled = shippingConfigService.resolveRule(clientCode, shipVia.trim(), destCountry, null);
                if (ruled.isPresent() && StringUtils.hasText(ruled.get().getServiceCode())) {
                    var svc = ruled.get();
                    if (row != null && StringUtils.hasText(svc.getCarrier())) {
                        row.setCarrierCode(svc.getCarrier().trim().toUpperCase());
                        row.setShipViaCode(shipVia.trim().toUpperCase());
                        row.setShipViaNote(shipVia.trim().toUpperCase() + " maps to " + svc.getName()
                                + " (" + svc.getCarrier() + " " + svc.getServiceCode() + ")");
                    }
                    return svc.getServiceCode();
                }
            }
            if (StringUtils.hasText(shipMethod)) {
                var hit = shippingConfigService.resolveServiceCode(canon, shipMethod, null);
                if (hit.isPresent()) return hit.get().getServiceCode();
            }
            if (StringUtils.hasText(shipVia) && StringUtils.hasText(clientCode)
                    && clientShipviaCodeMapRepository != null && shippingServiceRepository != null) {
                var map = clientShipviaCodeMapRepository
                        .findByClientCodeIgnoreCaseAndErpCodeIgnoreCase(clientCode.trim(), shipVia.trim());
                if (map.isPresent() && map.get().getServiceId() != null) {
                    var svc = shippingServiceRepository.findById(map.get().getServiceId());
                    if (svc.isPresent() && StringUtils.hasText(svc.get().getServiceCode())) {
                        return svc.get().getServiceCode();
                    }
                }
            }
            if (StringUtils.hasText(shipVia)) {
                var hit = shippingConfigService.resolveServiceCode(canon, shipVia, null);
                if (hit.isPresent()) return hit.get().getServiceCode();
                // "U03" / "F03" — the WMS prefixes the carrier letter to the carrier's own code.
                String stripped = shipVia.trim().replaceFirst("^(?i)(UPS|FEDEX|USPS|DHL|U|F)[-_ ]?", "");
                if (!stripped.equals(shipVia.trim()) && !stripped.isBlank()) {
                    hit = shippingConfigService.resolveServiceCode(canon, stripped, null);
                    if (hit.isPresent()) return hit.get().getServiceCode();
                }
            }
            // No silent Ground default any more. An unmapped ship-via used to
            // ship the client's parcel on the carrier's cheapest ground service
            // with only a warning to show for it; now the row stops here and
            // preflight() turns the blank service into an error the operator
            // fixes — the same rule a CSV upload follows.
        } catch (Exception e) {
            log.warn("WMS pull: service resolution for ship-via '{}' failed: {}", shipVia, e.getMessage());
        }
        return trimOrNull(shipMethod);
    }

    /** The client's default billing account for the carrier, when it has one. */
    private String defaultAccount(String clientCode, String carrier) {
        if (carrierAccountRefRepository == null || !StringUtils.hasText(clientCode) || !StringUtils.hasText(carrier)) return null;
        try {
            return carrierAccountRefRepository
                    .findByCustomerNoIgnoreCaseOrderByClientDefaultDescUpdatedAtDesc(clientCode.trim()).stream()
                    .filter(a -> !Boolean.FALSE.equals(a.getActive()))
                    .filter(a -> a.getCarrierCode() != null && a.getCarrierCode().equalsIgnoreCase(carrier.trim()))
                    .map(a -> a.getAccountNumber())
                    .filter(StringUtils::hasText)
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The client's default warehouse code (what the New Shipment form pre-selects). */
    private String defaultWarehouse(String clientCode) {
        if (clientWarehouseRepository == null || warehouseRepository == null || !StringUtils.hasText(clientCode)) return null;
        try {
            return clientWarehouseRepository.findByClientCodeIgnoreCaseAndIsDefaultTrue(clientCode.trim())
                    .flatMap(link -> warehouseRepository.findById(link.getWarehouseId()))
                    .filter(w -> !Boolean.FALSE.equals(w.getActive()) && StringUtils.hasText(w.getCode()))
                    .map(w -> w.getCode())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Items the WMS actually shipped (shipQty > 0; back-ordered lines stay off the label). */
    private static List<WmsPendingOrderDTO.WmsItem> shippedItems(WmsPendingOrderDTO src) {
        List<WmsPendingOrderDTO.WmsItem> out = new ArrayList<>();
        if (src.getItems() == null) return out;
        for (WmsPendingOrderDTO.WmsItem it : src.getItems()) {
            if (it == null) continue;
            int qty = it.getShipQty() != null ? it.getShipQty() : (it.getOrderQty() != null ? it.getOrderQty() : 0);
            if (qty > 0) out.add(it);
        }
        return out;
    }

    private static void applyFirstItem(WmsPendingOrderDTO src, OrderImportRowDTO r) {
        List<WmsPendingOrderDTO.WmsItem> items = shippedItems(src);
        if (items.isEmpty()) return;
        applyItem(items.get(0), r);
    }

    private static void applyItem(WmsPendingOrderDTO.WmsItem it, OrderImportRowDTO r) {
        r.setItemSku(trimOrNull(it.getItemNo()));
        r.setItemDescription(trimOrNull(it.getItemDesc()));
        r.setItemQuantity(it.getShipQty() != null ? it.getShipQty() : it.getOrderQty());
    }

    /** Continuation rows for the 2nd..nth shipped item: same shipment, same parcel, own SKU line. */
    private List<OrderImportRowDTO> itemLines(WmsPendingOrderDTO src, OrderImportRowDTO leader, int firstRowNumber) {
        List<OrderImportRowDTO> out = new ArrayList<>();
        List<WmsPendingOrderDTO.WmsItem> items = shippedItems(src);
        for (int i = 1; i < items.size(); i++) {
            OrderImportRowDTO line = new OrderImportRowDTO();
            line.setRowNumber(firstRowNumber + out.size());
            line.setOrderRef(leader.getOrderRef());
            line.setClientCode(leader.getClientCode());
            line.setWarehouseCode(leader.getWarehouseCode());
            line.setRecipientName(leader.getRecipientName());
            line.setRecipientCompany(leader.getRecipientCompany());
            line.setRecipientPhone(leader.getRecipientPhone());
            line.setRecipientEmail(leader.getRecipientEmail());
            line.setAddressLine1(leader.getAddressLine1());
            line.setAddressLine2(leader.getAddressLine2());
            line.setCity(leader.getCity());
            line.setState(leader.getState());
            line.setPostalCode(leader.getPostalCode());
            line.setCountryCode(leader.getCountryCode());
            line.setCarrierCode(leader.getCarrierCode());
            line.setAccountNumber(leader.getAccountNumber());
            line.setServiceType(leader.getServiceType());
            line.setWeight(leader.getWeight());
            line.setWeightUnit(leader.getWeightUnit());
            line.setWeightInherited(Boolean.TRUE);
            line.setReference(leader.getReference());
            applyItem(items.get(i), line);
            out.add(line);
        }
        return out;
    }

    /** Errors the carrier would certainly raise — surfaced now so the row isn't shown as Ready. */
    private List<String> preflight(WmsPendingOrderDTO src, OrderImportRowDTO row) {
        List<String> errors = new ArrayList<>();
        if (shippingConfigService != null && !StringUtils.hasText(row.getServiceType())) {
            String code = StringUtils.hasText(src.getShipVia()) ? src.getShipVia().trim().toUpperCase() : null;
            String who = StringUtils.hasText(row.getClientCode()) ? row.getClientCode().trim() : "this client";
            errors.add(code == null
                    ? "serviceType is required — this WMS order carries no ship via code"
                    : "serviceType '" + code + "' is not mapped for " + who
                        + " — add the ship via code in Settings → Shipping Service Mapping, "
                        + "or pick a service on this row");
        }
        return errors;
    }

    private static void addWarning(OrderImportRowDTO row, String warning) {
        List<String> w = new ArrayList<>(row.getWarnings() == null ? List.of() : row.getWarnings());
        w.add(warning);
        row.setWarnings(w);
    }

    /** Heuristic WMS ship-via → carrier code (UPS / FEDEX / USPS). Operator overrides. */
    static String mapCarrier(String shipVia) {
        if (!StringUtils.hasText(shipVia)) return null;
        String s = shipVia.trim().toUpperCase();
        if (s.startsWith("USP") || s.startsWith("US")) return "USPS";
        if (s.startsWith("U")) return "UPS";
        if (s.startsWith("F")) return "FEDEX";
        return shipVia.trim();
    }

    /** WMS crams "street  city, ST zip" into addr1 with long whitespace runs — keep the street. */
    private static String street(String addr1) {
        if (!StringUtils.hasText(addr1)) return addr1;
        return addr1.trim().split("\\s{2,}")[0].trim();
    }

    private static BigDecimal totalWeight(List<WmsContainer> containers) {
        if (containers == null) return null;
        double sum = containers.stream()
                .filter(Objects::nonNull)
                .map(WmsContainer::getWeight)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .sum();
        return sum > 0 ? BigDecimal.valueOf(sum) : null;
    }

    private static String trimOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    /** Keep a phone as its dialable digits (WMS sends "14697017960" and "310.259.0546"-style strings). */
    private static String digitsOrNull(String v) {
        if (!StringUtils.hasText(v)) return null;
        String digits = v.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? v.trim() : digits;
    }

    private static String firstNonBlank(String a, String b) {
        if (StringUtils.hasText(a)) return a.trim();
        return StringUtils.hasText(b) ? b.trim() : null;
    }
}
