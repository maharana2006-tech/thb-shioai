package com.multiship.backend.service.intl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Builds the {@link ExportDeclarationPolicyRegistry} from every
 * {@link ExportDeclarationPolicy} @Component wired into the context.
 * Logs a one-line boot summary so ops can grep for it in the startup
 * log and confirm the corridors they expect to be armed.
 */
@Configuration
@Slf4j
public class ExportDeclarationPolicyConfig {

    @Bean
    public ExportDeclarationPolicyRegistry exportDeclarationPolicyRegistry(
            List<ExportDeclarationPolicy> policies) {
        ExportDeclarationPolicyRegistry registry = new ExportDeclarationPolicyRegistry(policies);
        StringBuilder summary = new StringBuilder();
        for (ExportDeclarationPolicy p : policies) {
            if (summary.length() > 0) summary.append(", ");
            summary.append(p.originIso())
                    .append('@').append(p.thresholdAmount().toPlainString())
                    .append(' ').append(p.thresholdCurrency());
        }
        log.info("ExportDeclarationPolicyRegistry ready — {} corridors armed: {}",
                registry.size(), summary);
        return registry;
    }
}
