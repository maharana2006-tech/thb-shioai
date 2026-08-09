package com.multiship.backend.service;

import com.multiship.backend.dto.PackageDetailDTO;
import com.multiship.backend.dto.ShipmentRequestDTO;
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
                .shipperAddressLine1(src.getShipperAddressLine1())
                .shipperAddressLine2(src.getShipperAddressLine2())
                .shipperCity(src.getShipperCity())
                .shipperState(src.getShipperState())
                .shipperPostalCode(src.getShipperPostalCode())
                .shipperCountryCode(src.getShipperCountryCode())
                .recipientName(src.getRecipientName())
                .recipientPhone(src.getRecipientPhone())
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
