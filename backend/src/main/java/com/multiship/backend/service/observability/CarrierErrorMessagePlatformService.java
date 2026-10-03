package com.multiship.backend.service.observability;

import com.multiship.backend.model.CarrierErrorMessageEntity;
import com.multiship.backend.repository.CarrierErrorMessageRepository;
import com.multiship.backend.util.CarrierErrorMessages;
import com.multiship.backend.util.CarrierErrorMessages.PatternRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * V118 — loads the carrier_error_message rows at ApplicationReadyEvent
 * and swaps {@link CarrierErrorMessages}' live pattern list. Zero caller
 * churn — every existing consumer of {@code humanize} picks up DB-tunable
 * rules for free.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarrierErrorMessagePlatformService {

    private final CarrierErrorMessageRepository repo;

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnReady() {
        try {
            List<PatternRule> rules = repo.findAll().stream()
                    .sorted((a, b) -> Integer.compare(
                            a.getSortOrder() == null ? 0 : a.getSortOrder(),
                            b.getSortOrder() == null ? 0 : b.getSortOrder()))
                    .map(r -> new PatternRule(r.getMatchAnyOf(), r.getHumanized()))
                    .toList();
            if (rules.isEmpty()) {
                log.info("carrier-error-message: no rows seeded; CarrierErrorMessages keeps bootstrap");
                return;
            }
            CarrierErrorMessages.setPatternRules(rules);
            log.info("carrier-error-message: swapped CarrierErrorMessages to {} DB-driven rule(s)", rules.size());
        } catch (Exception ex) {
            log.warn("carrier-error-message: load failed; CarrierErrorMessages keeps prior rules: {}",
                    ex.getMessage());
        }
    }
}
