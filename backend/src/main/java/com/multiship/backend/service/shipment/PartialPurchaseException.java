package com.multiship.backend.service.shipment;

import java.util.List;

/**
 * A multi-piece purchase failed after some sub-requests were already bought.
 * Carries what the compensating void managed and what it could not, so the
 * recorded failure (and the operator) know exactly which labels still exist.
 */
public class PartialPurchaseException extends RuntimeException {

    private final List<String> voided;
    private final List<String> unvoided;

    public PartialPurchaseException(Throwable cause, List<String> voided, List<String> unvoided) {
        super(cause.getMessage()
                + (voided.isEmpty() ? "" : " Already-bought labels voided: " + String.join(", ", voided) + ".")
                + (unvoided.isEmpty() ? "" : " Labels bought but NOT voided — void them manually: " + String.join(", ", unvoided) + "."),
                cause);
        this.voided = List.copyOf(voided);
        this.unvoided = List.copyOf(unvoided);
    }

    public List<String> voided() { return voided; }
    public List<String> unvoided() { return unvoided; }
}
