package com.multiship.backend.service.resolution;

import com.multiship.backend.dto.ErrorCode;
import lombok.Getter;

/**
 * Thrown by {@link ShipmentResolutionService} when a resolution invariant
 * is violated. Carries a stable {@link ErrorCode} so callers (internal
 * label controller, external API) can map to their own response shape
 * without matching on message text.
 *
 * <p>The exception is unchecked so resolver call sites don't have to
 * pollute method signatures; typical usage wraps a resolver call in a
 * try/catch at the request-boundary layer.
 */
@Getter
public class ShipmentResolutionException extends RuntimeException {

    private final ErrorCode errorCode;

    public ShipmentResolutionException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
