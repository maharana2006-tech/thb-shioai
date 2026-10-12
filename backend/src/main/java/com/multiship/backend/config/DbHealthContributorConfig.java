package com.multiship.backend.config;

import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.CompositeHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replaces Spring Boot's auto-configured {@code dbHealthContributor} so the
 * {@code oracleDataSource} child does NOT trigger a stack-trace WARN on every
 * {@code /actuator/health} call when the NDS connection is intentionally
 * inactive (via {@link InactiveOracleDataSource}).
 *
 * <p>Pre-fix: Spring Boot's {@code DataSourceHealthContributorAutoConfiguration}
 * creates one {@link DataSourceHealthIndicator} per DataSource bean. The oracle
 * one probes {@code getConnection()}; our stub throws {@code IllegalStateException}
 * by design; {@link org.springframework.boot.health.contributor.AbstractHealthIndicator}
 * catches + logs the full ~70-line stack trace at WARN every probe. Dashboard
 * FE polls {@code /actuator/health} on load → log noise.
 *
 * <p>Post-fix: the oracle child short-circuits to {@code OUT_OF_SERVICE} with a
 * one-line detail when {@code ExternalSystemRegistry.isActive("nds-default")}
 * returns false — no connection probe, no stack trace. Postgres child retains
 * the standard probe. Live-active oracle still probes normally (the only path
 * that produces a legit WARN if the connection is truly broken).
 *
 * <p>Overrides the auto-config via Spring's standard
 * {@code @ConditionalOnMissingBean(name = "dbHealthContributor")} — the bean
 * below wins because it's eagerly defined with the same name.
 */
@Slf4j
@Configuration
public class DbHealthContributorConfig {

    private static final String ORACLE_CONNECTION_NAME = OracleDataSourceConfig.NDS_CONNECTION_NAME;

    @Bean(name = "dbHealthContributor")
    public HealthContributor dbHealthContributor(
            @Qualifier("postgresDataSource") DataSource postgres,
            @Qualifier("oracleDataSource") @Autowired(required = false) DataSource oracle,
            ExternalSystemRegistry registry) {

        Map<String, HealthContributor> children = new LinkedHashMap<>();
        children.put("postgresDataSource", new DataSourceHealthIndicator(postgres));

        if (oracle != null) {
            children.put("oracleDataSource", new InactiveAwareOracleHealthIndicator(oracle, registry));
        }
        return CompositeHealthContributor.fromMap(children);
    }

    /** Oracle health probe that respects the intentional-inactive state. */
    static final class InactiveAwareOracleHealthIndicator implements HealthIndicator {
        private final DataSource dataSource;
        private final ExternalSystemRegistry registry;

        InactiveAwareOracleHealthIndicator(DataSource dataSource, ExternalSystemRegistry registry) {
            this.dataSource = dataSource;
            this.registry = registry;
        }

        @Override
        public Health health() {
            try {
                if (!registry.isActive(ORACLE_CONNECTION_NAME)) {
                    return Health.outOfService()
                            .withDetail("reason", "nds-default inactive by operator choice")
                            .withDetail("fix", "flip active=true at /settings/external-systems to enable DTC sync")
                            .build();
                }
            } catch (Exception ex) {
                // Registry hiccup — don't block health reporting. Fall through
                // to the standard probe so a real DB failure still surfaces.
                log.debug("db-health: registry.isActive check for {} failed; falling through to live probe: {}",
                        ORACLE_CONNECTION_NAME, ex.getMessage());
            }
            return new DataSourceHealthIndicator(dataSource).health();
        }
    }
}
