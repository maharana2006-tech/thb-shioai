package com.multiship.backend.config;

/**
 * Audit R2 #347 — dedicated exception thrown when {@link CryptoService}
 * is asked to encrypt / decrypt but {@code SECRETS_ENCRYPTION_KEY} is
 * unset. Callers translate this to HTTP 503 with the
 * {@code CRYPTO_UNAVAILABLE} error code so operators see the exact
 * env-var name in the response instead of a generic 500.
 *
 * <p>Kept {@link RuntimeException} so callers don't need to declare
 * checked-exception boilerplate on every save path.
 */
public class CryptoUnavailableException extends RuntimeException {
    public CryptoUnavailableException(String message) { super(message); }
}
