package com.multiship.backend.service.externalsystems.writeback;

import java.math.BigDecimal;
import java.util.List;

/**
 * V89 — per-package payload for {@link WritebackPayload}. Retains
 * external-system row keys (NDS container_ids, order_nos, order suffix)
 * so a writer can locate every row that shipped as part of this label
 * without a second lookup. Non-NDS connectors ignore the keys.
 *
 * @param sequence        package index within the shipment (1-based)
 * @param containerNo     NDS container_no (or generic external ref)
 * @param containerIds    CLIPPER row ids for this container
 * @param orderNos        every external order_no bundled into this container
 * @param orderSuffix     NDS order suffix
 * @param weight          package weight (post-shipper-edits)
 * @param weightUnit      LB / KG
 * @param packageTracking per-piece tracking (null when only master applies)
 */
public record WritebackPackagePayload(
        int sequence,
        String containerNo,
        List<Long> containerIds,
        List<Integer> orderNos,
        Integer orderSuffix,
        BigDecimal weight,
        String weightUnit,
        String packageTracking
) {}
