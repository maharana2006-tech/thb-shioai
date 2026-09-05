package com.multiship.backend.service.intl;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Origin-indexed lookup for {@link ExportDeclarationPolicy}. Spring
 * autowires every impl registered as a bean; the registry indexes them
 * by {@link ExportDeclarationPolicy#originIso()}.
 *
 * <p>Registry ownership pattern: policies register themselves as
 * @Component; the validator consumes {@link #lookup(String)}. Adding a
 * new corridor never touches the validator — just add a policy impl in
 * this package and it appears in the map at boot time.
 */
public class ExportDeclarationPolicyRegistry {

    private final Map<String, ExportDeclarationPolicy> byOrigin;

    public ExportDeclarationPolicyRegistry(List<ExportDeclarationPolicy> policies) {
        this.byOrigin = policies == null ? Collections.emptyMap() : policies.stream()
                .collect(Collectors.toUnmodifiableMap(
                        p -> p.originIso().toUpperCase(Locale.ROOT),
                        p -> p,
                        (a, b) -> {
                            // Two policies claiming the same origin is a
                            // programming error — fail loud at startup.
                            throw new IllegalStateException(
                                    "Duplicate ExportDeclarationPolicy for origin " + a.originIso()
                                            + ": " + a.getClass().getName() + " vs "
                                            + b.getClass().getName());
                        }));
    }

    /**
     * Look up the policy for a given ISO 3166-1 alpha-2 origin. Returns
     * empty when no policy is registered — corridor is uncovered, no
     * rule fires (the generic advisory in ShipmentValidationService is
     * the fallback catch-all).
     */
    public Optional<ExportDeclarationPolicy> lookup(String originIso) {
        if (originIso == null) return Optional.empty();
        return Optional.ofNullable(byOrigin.get(originIso.trim().toUpperCase(Locale.ROOT)));
    }

    /** How many corridors are wired. Handy for boot-time logging + tests. */
    public int size() {
        return byOrigin.size();
    }
}
