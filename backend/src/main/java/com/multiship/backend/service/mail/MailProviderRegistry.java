package com.multiship.backend.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A4.1 — indexes every {@link MailProvider} @Component bean by its
 * {@link MailProvider#kind()} so {@link ConfiguredMailSender} and
 * {@link MailConfigService} can look one up by name. Spring injects the
 * list at construction; adding a new provider is a data-only change from
 * the framework's perspective.
 */
@Slf4j
@Component
public class MailProviderRegistry {

    private final Map<String, MailProvider> byKind;

    public MailProviderRegistry(List<MailProvider> providers) {
        this.byKind = providers.stream()
                .collect(Collectors.toUnmodifiableMap(
                        p -> p.kind().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Two MailProvider beans claim kind '" + a.kind()
                                            + "': " + a.getClass() + " vs " + b.getClass());
                        }));
        log.info("MailProviderRegistry: {} provider(s) registered — {}",
                byKind.size(), byKind.keySet());
    }

    /** All known kinds (SMTP, SENDGRID, ...). Used by the admin FE dropdown. */
    public List<String> kinds() {
        return byKind.keySet().stream().sorted().toList();
    }

    public Optional<MailProvider> find(String kind) {
        if (kind == null || kind.isBlank()) return Optional.empty();
        return Optional.ofNullable(byKind.get(kind.toUpperCase(Locale.ROOT)));
    }

    public MailProvider require(String kind) {
        return find(kind).orElseThrow(() -> new IllegalArgumentException(
                "Unknown mail provider kind: " + kind + ". Known: " + byKind.keySet()));
    }
}
