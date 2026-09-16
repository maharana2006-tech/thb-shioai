package com.multiship.backend.service.carriers.usps.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Typed-shell reference for the JSON body sent to USPS's
 * {@code POST /international-labels/v3/label} endpoint.
 *
 * <p>The connector builds the request as a {@code LinkedHashMap<String, Object>}
 * for the same reason its domestic sibling does — USPS's v3 shape has
 * corner-case fields (extra services, indicia adjustments, sender roles)
 * that a static POJO would drift on faster than USPS revises the docs.
 * This class exists to document the shape statically for anyone reading
 * the code and to give the payload-shape test a compile-time reminder of
 * which top-level slots must appear.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code imageInfo} — label imaging (imageType, labelType).</li>
 *   <li>{@code toAddress} / {@code fromAddress} — recipient + sender.</li>
 *   <li>{@code senderInfo} — CRID + MID identifying the mailer.</li>
 *   <li>{@code packageDescription} — mail class + weight + dims.</li>
 *   <li>{@code paymentInfo} — EPS accountNumber + accountType.</li>
 *   <li>{@code customsForm} — {@link UspsCustomsForm} block.</li>
 *   <li>{@code destinationCountryCode} — ISO alpha-2 (top-level echo).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UspsIntlLabelRequest {
    private Map<String, Object> imageInfo;
    private Map<String, Object> toAddress;
    private Map<String, Object> fromAddress;
    private Map<String, Object> senderInfo;
    private Map<String, Object> packageDescription;
    private Map<String, Object> paymentInfo;
    private UspsCustomsForm customsForm;
    private String destinationCountryCode;
    private String customerReference;
}
