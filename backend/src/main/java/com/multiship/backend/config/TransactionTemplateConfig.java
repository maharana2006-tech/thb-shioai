package com.multiship.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Programmatic-transaction helper for the handful of call sites that need
 * to commit a fallback write independently of an enclosing @Transactional
 * method (see CarrierServiceImpl's failed-shipment ERROR-order persistence).
 */
@Configuration
public class TransactionTemplateConfig {

    @Bean
    public TransactionTemplate requiresNewTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
