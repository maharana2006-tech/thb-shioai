package com.multiship.backend.config;

import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
import com.multiship.backend.service.externalsystems.LoginContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import java.util.HashMap;

/**
 * S4 — Oracle NDS JPA plumbing for the legacy DTC-sync path.
 *
 * <p><b>What changed vs the pre-S4 version:</b>
 * <ul>
 *   <li>DataSource used to be built from {@code spring.datasource.oracle.*}
 *       properties. Those are DELETED — this bean now sources its
 *       DataSource from the S1 {@link ExternalSystemRegistry},
 *       specifically the well-known {@code nds-default} row (seeded
 *       by V78, edited by admin via {@code /settings/external-systems}).</li>
 *   <li>Same activation gate as before ({@code multiship.oracle.enabled=true}) —
 *       when false, this whole config is skipped and no Oracle beans exist,
 *       matching pre-S4 default-off behaviour.</li>
 *   <li>When enabled but the framework row is inactive / missing password,
 *       the registry throws with a clear message pointing operators at
 *       {@code /settings/external-systems}. Cleaner than the old
 *       "empty URL" boot failure.</li>
 * </ul>
 *
 * <p><b>Roadmap:</b> the JPA path itself (repo interface, entity, EMF
 * bootstrap) is unchanged so existing DTC-sync behaviour is preserved.
 * Full rewrite to a JdbcTemplate-based repo — freeing us from the EMF
 * bootstrap that requires a DataSource at Spring-context startup — is
 * scheduled as S4b when we have runtime evidence the framework path is
 * healthy.
 */
@Slf4j
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "multiship.oracle.enabled", havingValue = "true")
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.multiship.backend.repository.oracle",
        entityManagerFactoryRef = "oracleEntityManagerFactory",
        transactionManagerRef = "oracleTransactionManager"
)
public class OracleDataSourceConfig {

    /**
     * Connection name of the well-known NDS row (V78 seed). If ops
     * needs multiple NDS servers in parallel, this becomes a property.
     */
    public static final String NDS_CONNECTION_NAME = "nds-default";

    /**
     * Registry-backed Oracle DataSource. Fetched from
     * {@code registry.connect("nds-default", PRODUCTION)}. Throws
     * during context startup if the framework row is missing / inactive
     * / missing password — the operator fix is
     * {@code /settings/external-systems}.
     */
    @Bean(name = "oracleDataSource")
    public DataSource oracleDataSource(ExternalSystemRegistry registry) {
        try {
            Object handle = registry.connect(NDS_CONNECTION_NAME,
                    LoginContext.withProfile("PRODUCTION"));
            if (!(handle instanceof DataSource ds)) {
                throw new IllegalStateException(
                        "ExternalSystemRegistry returned non-DataSource handle for '"
                                + NDS_CONNECTION_NAME + "': " + handle.getClass().getName());
            }
            log.info("nds-oracle-datasource: bound to external-systems row '{}' (PRODUCTION login)",
                    NDS_CONNECTION_NAME);
            return ds;
        } catch (Exception e) {
            log.error("nds-oracle-datasource: FAILED to bind to '{}': {}. "
                            + "Fix at /settings/external-systems (set active=true + productionPassword secret) "
                            + "or disable DTC sync via multiship.oracle.enabled=false.",
                    NDS_CONNECTION_NAME, e.getMessage());
            throw e;
        }
    }

    @Bean(name = "oracleEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean oracleEntityManagerFactory(
            @Qualifier("oracleDataSource") DataSource dataSource) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.multiship.backend.model.oracle");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(false);  // Read-only
        em.setJpaVendorAdapter(adapter);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", "org.hibernate.dialect.OracleDialect");
        properties.put("hibernate.jdbc.batch_size", "20");
        properties.put("hibernate.default_batch_fetch_size", "16");
        properties.put("hibernate.hbm2ddl.auto", "none");
        em.setJpaPropertyMap(properties);

        return em;
    }

    @Bean(name = "oracleTransactionManager")
    public PlatformTransactionManager oracleTransactionManager(
            @Qualifier("oracleEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
