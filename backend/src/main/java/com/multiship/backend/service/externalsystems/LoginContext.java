package com.multiship.backend.service.externalsystems;

import java.util.Objects;
import java.util.Optional;

/**
 * Framework-neutral request context handed to a connector's
 * {@code connect()} call. Carries the two facts every connector cares
 * about — the caller's tenant/client code (may be absent for platform
 * operators) + which login "profile" the caller wants from a
 * multi-login connector (e.g. NDS's {@code PRODUCTION} vs
 * {@code CLIENT}).
 *
 * <p>Connectors that don't distinguish login profiles ignore
 * {@link #loginProfile}. Connectors that don't care about tenant scope
 * (e.g. a REST integration with static creds) ignore
 * {@link #clientCode}.
 */
public final class LoginContext {

    private final String clientCode;
    private final String loginProfile;

    private LoginContext(String clientCode, String loginProfile) {
        this.clientCode = clientCode;
        this.loginProfile = loginProfile;
    }

    /** Platform-operator context (no tenant scope, no login profile). */
    public static LoginContext platform() {
        return new LoginContext(null, null);
    }

    /** Tenant-scoped context with no explicit login-profile pick — connector
     *  applies its own default (e.g. NDS picks PRODUCTION unless asked). */
    public static LoginContext forClient(String clientCode) {
        return new LoginContext(clientCode, null);
    }

    /** Tenant-scoped context with an explicit login-profile pick (e.g.
     *  {@code "PRODUCTION"} or {@code "CLIENT"} on the NDS connector). */
    public static LoginContext forClientWithProfile(String clientCode, String loginProfile) {
        return new LoginContext(clientCode, loginProfile);
    }

    /** Platform context with an explicit login profile. */
    public static LoginContext withProfile(String loginProfile) {
        return new LoginContext(null, loginProfile);
    }

    public Optional<String> clientCode() {
        return Optional.ofNullable(clientCode);
    }

    public Optional<String> loginProfile() {
        return Optional.ofNullable(loginProfile);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LoginContext other)) return false;
        return Objects.equals(clientCode, other.clientCode)
                && Objects.equals(loginProfile, other.loginProfile);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientCode, loginProfile);
    }

    @Override
    public String toString() {
        return "LoginContext[client=" + clientCode + ", profile=" + loginProfile + "]";
    }
}
