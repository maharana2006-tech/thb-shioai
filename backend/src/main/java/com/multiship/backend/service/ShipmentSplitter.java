package com.multiship.backend.service;

import com.multiship.backend.dto.CustomsCommodityDTO;
import com.multiship.backend.dto.IntlShipmentBlockDTO;
import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
import com.multiship.backend.dto.SplitStrategy;
import com.multiship.backend.exception.CommoditiesLimitExceededException;
import com.multiship.backend.model.CarrierShippingLimit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Chunks a {@link ShipmentRequestDTO} whose package count (or total
 * weight) exceeds a carrier's cap into ≤cap sub-requests. Sprint 48 B2.
 *
 * <p>Preserved across sub-requests: recipient, shipper, service, account
 * number, incoterms, customs block, signature/insured options — every
 * shared field is copied via {@link ShipmentRequestDTO}'s builder.
 *
 * <p>Redistributed:
 * <ul>
 *   <li>{@code packages[]} — chunked into batches of ≤ {@code cap} while
 *       preserving each entry's original {@code sequenceNumber} so
 *       downstream persistence keeps a global 1..N piece index.</li>
 *   <li>{@code declaredValue} (shipment-level) — split proportionally by
 *       batch weight so each sub-shipment declares only its share.</li>
 * </ul>
 *
 * <p>Not redistributed:
 * <ul>
 *   <li>{@code insuredValue} — copied to every batch (worst-case each
 *       batch is insured for the full amount); operator can override.</li>
 *   <li>{@code dangerousGoods} — copied to every batch that contains any
 *       package (safer default; carriers require the declaration on any
 *       hazmat shipment).</li>
 * </ul>
 */
@Slf4j
@Service
public class ShipmentSplitter {

    /**
     * Sprint 52 — pre-flight check that a shipment's commodity count fits
     * the carrier cap. When {@code allowAutoSplit=true}, over-cap requests
     * DO NOT throw — the caller intends to invoke
     * {@link #splitByCommodityStrategy} downstream. When false (the
     * pre-2026-09-06 behaviour), throws {@link
     * CommoditiesLimitExceededException} for legacy callers.
     *
     * <p>No-op when {@code request.intl} is null (domestic) or commodities
     * is empty / below cap.
     */
    public void assertCommoditiesFit(ShipmentRequestDTO request, CarrierShippingLimit limit,
            boolean allowAutoSplit) {
        if (request == null || request.getIntl() == null) return;
        List<?> commodities = request.getIntl().getCommodities();
        if (commodities == null || commodities.isEmpty()) return;
        int cap = commodityCap(limit);
        if (commodities.size() > cap) {
            if (allowAutoSplit) return; // caller will split downstream
            String carrier = limit != null && limit.getCarrierCode() != null
                    ? limit.getCarrierCode() : "carrier";
            throw new CommoditiesLimitExceededException(carrier, commodities.size(), cap);
        }
    }

    /**
     * Back-compat overload for pre-2026-09-06 callers that don't pass
     * an auto-split flag. Behaves the old way (throws on over-cap).
     */
    public void assertCommoditiesFit(ShipmentRequestDTO request, CarrierShippingLimit limit) {
        assertCommoditiesFit(request, limit, false);
    }

    /**
     * Report whether {@code request} exceeds the carrier's commodity cap.
     * Domestic (no intl) shipments always return false. Callers use this
     * to decide whether to invoke {@link #splitByCommodityStrategy} or
     * hit the carrier directly.
     */
    public boolean isOverCommodityCap(ShipmentRequestDTO request, CarrierShippingLimit limit) {
        if (request == null || request.getIntl() == null
                || request.getIntl().getCommodities() == null
                || request.getIntl().getCommodities().isEmpty()) return false;
        return request.getIntl().getCommodities().size() > commodityCap(limit);
    }

    /**
     * Compute how many sub-shipments the split will produce given the
     * current commodity count + carrier cap. Used to preview the split
     * on the FE modal ({@code SplitRequiredResponse.requiredSplitCount}).
     */
    public int requiredSplitCount(ShipmentRequestDTO request, CarrierShippingLimit limit) {
        if (!isOverCommodityCap(request, limit)) return 1;
        int cap = commodityCap(limit);
        int count = request.getIntl().getCommodities().size();
        return (count + cap - 1) / cap;
    }

    private static int commodityCap(CarrierShippingLimit limit) {
        return (limit != null && limit.getMaxCommodities() != null)
                ? limit.getMaxCommodities()
                : Integer.MAX_VALUE;
    }

    /**
     * Split a shipment whose commodity count exceeds the carrier's cap
     * into N sub-requests. Sprint XX auto-split (see docs/plans/
     * commodity_autosplit.md).
     *
     * <p>Strategy semantics:
     * <ul>
     *   <li>{@link SplitStrategy#SAME_PACKAGES}: each sub-request carries
     *       ALL physical packages. Wasteful (carrier bills 4× shipping
     *       for 3 boxes if we split 4×) but customs-simple. Recommended
     *       default.</li>
     *   <li>{@link SplitStrategy#PROPORTIONAL_PACKAGES}: packages
     *       distributed round-robin across sub-requests. Some sub-requests
     *       may have 0 packages (skip those — commodities alone don't
     *       ship).</li>
     *   <li>{@link SplitStrategy#ONE_PACKAGE_PER_SPLIT}: N packages → N
     *       sub-requests, each with 1 package. Commodities distributed
     *       across sub-requests either by {@code boxSeq} metadata (when
     *       populated) or evenly (fallback).</li>
     * </ul>
     *
     * <p>Per-split declaredValue = sum(qty × unitValue) for the split's
     * commodity slice. Currency copied from parent.
     *
     * <p>No-op when {@code request.intl} is null or already ≤ cap —
     * returns the input as a single-element list.
     */
    public List<ShipmentRequestDTO> splitByCommodityStrategy(
            ShipmentRequestDTO request, CarrierShippingLimit limit, SplitStrategy strategy) {
        if (!isOverCommodityCap(request, limit)) return List.of(request);
        int cap = commodityCap(limit);
        List<CustomsCommodityDTO> commodities = request.getIntl().getCommodities();

        // Slice commodities into ceil(N/cap) chunks.
        List<List<CustomsCommodityDTO>> commoditySlices = new ArrayList<>();
        for (int start = 0; start < commodities.size(); start += cap) {
            int end = Math.min(start + cap, commodities.size());
            commoditySlices.add(new ArrayList<>(commodities.subList(start, end)));
        }
        int splitCount = commoditySlices.size();

        // Determine per-split packages according to strategy.
        List<PackageDetailDTO> allPkgs = request.effectivePackages();
        List<List<PackageDetailDTO>> packageSlices = new ArrayList<>();
        SplitStrategy effective = strategy != null ? strategy : SplitStrategy.SAME_PACKAGES;
        switch (effective) {
            case PROPORTIONAL_PACKAGES:
                for (int i = 0; i < splitCount; i++) packageSlices.add(new ArrayList<>());
                for (int i = 0; i < allPkgs.size(); i++) {
                    packageSlices.get(i % splitCount).add(allPkgs.get(i));
                }
                break;
            case ONE_PACKAGE_PER_SPLIT:
                // If splitCount > pkgs.size(), duplicate the last package
                // into the extra slots (no meaningful boxSeq fallback here).
                for (int i = 0; i < splitCount; i++) {
                    PackageDetailDTO pkg = i < allPkgs.size()
                            ? allPkgs.get(i) : allPkgs.get(allPkgs.size() - 1);
                    packageSlices.add(new ArrayList<>(List.of(pkg)));
                }
                break;
            case SAME_PACKAGES:
            default:
                for (int i = 0; i < splitCount; i++) {
                    packageSlices.add(new ArrayList<>(allPkgs));
                }
                break;
        }

        // Build sub-requests, one per commodity slice.
        List<ShipmentRequestDTO> out = new ArrayList<>(splitCount);
        for (int i = 0; i < splitCount; i++) {
            List<CustomsCommodityDTO> slice = commoditySlices.get(i);
            List<PackageDetailDTO> pkgSlice = packageSlices.get(i);
            BigDecimal sliceDeclared = slice.stream()
                    .filter(c -> c.getUnitValue() != null)
                    .map(c -> {
                        BigDecimal qty = c.getQuantity() != null
                                ? BigDecimal.valueOf(c.getQuantity()) : BigDecimal.ONE;
                        return c.getUnitValue().multiply(qty);
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            IntlShipmentBlockDTO parentIntl = request.getIntl();
            IntlShipmentBlockDTO sliceIntl = parentIntl.toBuilder()
                    .commodities(slice)
                    .build();
            // Reuse cloneShipmentWith but override declaredValue AND intl.
            ShipmentRequestDTO sub = cloneShipmentWith(request, pkgSlice, sliceDeclared);
            sub.setIntl(sliceIntl);
            out.add(sub);
        }
        log.info("Commodity auto-split: {} commodities → {} sub-shipments (cap={}, strategy={})",
                commodities.size(), splitCount, cap, effective);
        return out;
    }

    /**
     * @return N sub-requests where each has ≤ {@code cap} packages. If the
     * input already fits, returns a single-element list wrapping the input.
     */
    public List<ShipmentRequestDTO> split(ShipmentRequestDTO request, CarrierShippingLimit limit) {
        List<PackageDetailDTO> pkgs = request.effectivePackages();
        int cap = (limit != null && limit.getMaxPackages() != null)
                ? Math.max(1, limit.getMaxPackages())
                : Integer.MAX_VALUE;
        if (pkgs.size() <= cap) {
            return List.of(request);
        }

        // Chunk while preserving each package's original sequenceNumber.
        // Ensure every package has a sequence number (fall back to index+1).
        List<PackageDetailDTO> normalised = new ArrayList<>(pkgs.size());
        for (int i = 0; i < pkgs.size(); i++) {
            PackageDetailDTO p = pkgs.get(i);
            if (p.getSequenceNumber() == null) {
                PackageDetailDTO cloned = cloneWithSeq(p, i + 1);
                normalised.add(cloned);
            } else {
                normalised.add(p);
            }
        }

        BigDecimal shipmentDeclaredValue = request.getDeclaredValue();
        BigDecimal totalWeight = normalised.stream()
                .map(PackageDetailDTO::getWeight)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ShipmentRequestDTO> out = new ArrayList<>();
        for (int start = 0; start < normalised.size(); start += cap) {
            int end = Math.min(start + cap, normalised.size());
            List<PackageDetailDTO> chunk = normalised.subList(start, end);
            BigDecimal chunkWeight = chunk.stream()
                    .map(PackageDetailDTO::getWeight)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal chunkDeclared = null;
            if (shipmentDeclaredValue != null && totalWeight.signum() > 0) {
                // Proportional share by weight; final chunk gets the rounding remainder
                // so the sum equals the shipment declared value.
                if (end == normalised.size()) {
                    // Compute sum of prior chunks and subtract.
                    BigDecimal usedSoFar = out.stream()
                            .map(ShipmentRequestDTO::getDeclaredValue)
                            .filter(java.util.Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    chunkDeclared = shipmentDeclaredValue.subtract(usedSoFar);
                } else {
                    chunkDeclared = shipmentDeclaredValue
                            .multiply(chunkWeight)
                            .divide(totalWeight, 2, RoundingMode.HALF_UP);
                }
            } else if (shipmentDeclaredValue != null) {
                // No weight signal — split equally.
                chunkDeclared = shipmentDeclaredValue
                        .divide(BigDecimal.valueOf((normalised.size() + cap - 1) / cap),
                                2, RoundingMode.HALF_UP);
            }

            out.add(cloneShipmentWith(request, new ArrayList<>(chunk), chunkDeclared));
        }
        log.debug("Split shipment into {} batches (cap={}, pkgs={})", out.size(), cap, normalised.size());
        return out;
    }

    private PackageDetailDTO cloneWithSeq(PackageDetailDTO src, int seq) {
        return PackageDetailDTO.builder()
                .sequenceNumber(seq)
                .packageType(src.getPackageType())
                .weight(src.getWeight())
                .weightUnit(src.getWeightUnit())
                .length(src.getLength())
                .width(src.getWidth())
                .height(src.getHeight())
                .dimUnit(src.getDimUnit())
                .declaredValue(src.getDeclaredValue())
                .description(src.getDescription())
                .reference(src.getReference())
                .build();
    }

    /** Copy every field from {@code src} into a new request, overriding
     *  {@code packages} and {@code declaredValue}. */
    private ShipmentRequestDTO cloneShipmentWith(ShipmentRequestDTO src,
                                                  List<PackageDetailDTO> packages,
                                                  BigDecimal declaredValue) {
        // Compute weight for the batch (sum of packages' weights when available;
        // else fall back to the original request's weight).
        BigDecimal batchWeight = packages.stream()
                .map(PackageDetailDTO::getWeight)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (batchWeight.signum() == 0 && src.getWeight() != null) batchWeight = src.getWeight();

        return ShipmentRequestDTO.builder()
                .carrierCode(src.getCarrierCode())
                .accountNumber(src.getAccountNumber())
                .serviceType(src.getServiceType())
                .packageType(src.getPackageType())
                .length(src.getLength()).width(src.getWidth()).height(src.getHeight())
                .weight(batchWeight)
                .weightUnit(src.getWeightUnit())
                .dimUnit(src.getDimUnit())
                .shipperName(src.getShipperName())
                .shipperPhone(src.getShipperPhone())
                // Sprint 51 — carry company + email through MPS split so each
                // sub-request emits them on the wire. Dropped fields would
                // silently disappear on any shipment over the carrier's MPS
                // cap (11+ boxes on UPS/FedEx).
                .shipperCompany(src.getShipperCompany())
                .shipperEmail(src.getShipperEmail())
                .shipperAddressLine1(src.getShipperAddressLine1())
                .shipperAddressLine2(src.getShipperAddressLine2())
                .shipperCity(src.getShipperCity())
                .shipperState(src.getShipperState())
                .shipperPostalCode(src.getShipperPostalCode())
                .shipperCountryCode(src.getShipperCountryCode())
                .recipientName(src.getRecipientName())
                .recipientPhone(src.getRecipientPhone())
                // Sprint 51 — same rationale as shipperCompany above.
                .recipientCompany(src.getRecipientCompany())
                .recipientEmail(src.getRecipientEmail())
                .recipientAddressLine1(src.getRecipientAddressLine1())
                .recipientAddressLine2(src.getRecipientAddressLine2())
                .recipientAddressLine3(src.getRecipientAddressLine3())
                .recipientCity(src.getRecipientCity())
                .recipientState(src.getRecipientState())
                .recipientPostalCode(src.getRecipientPostalCode())
                .recipientCountryCode(src.getRecipientCountryCode())
                .recipientResidential(src.getRecipientResidential())
                .recipientPhoneCountryCode(src.getRecipientPhoneCountryCode())
                .referenceNumber(src.getReferenceNumber())
                .specialInstructions(src.getSpecialInstructions())
                .declaredValue(declaredValue)
                .isReturn(src.getIsReturn())
                .dangerousGoods(src.getDangerousGoods())
                .signatureOption(src.getSignatureOption())
                .insuredValue(src.getInsuredValue())
                .insuredValueCurrency(src.getInsuredValueCurrency())
                // Sprint 48 B11 — pass the whole intl block through unchanged.
                // Commodities may reference boxes from OTHER batches; the
                // DeclaredValueContextBuilder filters them by seqToIdx built
                // from the batch's packages list, so unmatched items are
                // silently ignored for this batch.
                .intl(src.getIntl())
                .packages(packages)
                .build();
    }

    /** Utility for the caller — sums the request's package weights in LB
     *  (assumes packages carry their unit; else assumes LB). */
    public BigDecimal totalWeightLb(ShipmentRequestDTO request) {
        List<PackageDetailDTO> pkgs = request.effectivePackages();
        if (CollectionUtils.isEmpty(pkgs)) {
            return request.getWeight() == null ? BigDecimal.ZERO
                    : com.multiship.backend.util.UnitConverter.toPounds(request.getWeight(), request.getWeightUnit());
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (PackageDetailDTO p : pkgs) {
            if (p.getWeight() == null) continue;
            BigDecimal lb = com.multiship.backend.util.UnitConverter.toPounds(
                    p.getWeight(),
                    p.getWeightUnit() != null ? p.getWeightUnit() : request.getWeightUnit());
            if (lb != null) sum = sum.add(lb);
        }
        return sum;
    }
}
