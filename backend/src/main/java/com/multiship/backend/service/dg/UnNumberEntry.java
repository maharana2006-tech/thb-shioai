package com.multiship.backend.service.dg;

/**
 * One curated UN number entry. Matches the shape of {@code un-numbers/common.json}
 * on the classpath.
 *
 * @param unNumber            UN\d{4} form, e.g. "UN3480".
 * @param properShippingName  Table A shipping name.
 * @param hazardClass         Class 1-9 with optional subclass ("4.1", "5.2").
 * @param defaultPackingGroup Suggested packing group I / II / III. Null for
 *                            classes that don't have one.
 * @param notes               Free-form operator notes: quantity limits,
 *                            common product examples, carrier gotchas.
 */
public record UnNumberEntry(
        String unNumber,
        String properShippingName,
        String hazardClass,
        String defaultPackingGroup,
        String notes
) {
}
