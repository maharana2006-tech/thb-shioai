package com.multiship.backend.exception;

import com.multiship.backend.dto.ErrorCode;

/**
 * Sprint 52 — thrown when a shipment carries more commodity lines than
 * the resolved carrier cap. Splitting commodities across sub-shipments is
 * intentionally NOT supported (would break shipper invoice intent), so
 * this always surfaces as a 422 error to the caller with the actionable
 * message telling them the actual vs allowed line count.
 *
 * <p>Carries the {@link ErrorCode} so the caller can branch on it
 * without matching message text.
 */
public class CommoditiesLimitExceededException extends RuntimeException {

    private final int actualCount;
    private final int maxAllowed;
    private final String carrierCode;

    public CommoditiesLimitExceededException(String carrierCode, int actualCount, int maxAllowed) {
        super(String.format(
                "%s accepts at most %d commodity lines per shipment; this shipment has %d. "
                        + "Split the order into smaller shipments before generating the label.",
                carrierCode, maxAllowed, actualCount));
        this.carrierCode = carrierCode;
        this.actualCount = actualCount;
        this.maxAllowed = maxAllowed;
    }

    public int getActualCount() { return actualCount; }
    public int getMaxAllowed() { return maxAllowed; }
    public String getCarrierCode() { return carrierCode; }
    public ErrorCode getErrorCode() { return ErrorCode.COMMODITIES_LIMIT_EXCEEDED; }
}
