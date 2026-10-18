package com.multiship.backend.config;

import com.multiship.backend.service.externalsystems.ExternalSystemRegistry;
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
 *   <li>No application property gates this config. The beans always
 *       exist; the on/off switch for DTC sync is the {@code active} flag
 *       on the {@code nds-default} row in {@code /settings/external-systems},
 *       checked on every connection borrow.</li>
 *   <li>When the row is inactive / missing password, borrowing a
 *       connection throws with a clear message pointing operators at
 *       {@code /settings/external-systems}.</li>
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
     * Registry-backed Oracle DataSource. Resolves the live pool for
     * {@code nds-default} on each borrow (see
     * {@link RegistryBackedOracleDataSource}), so toggling the row's
     * {@code active} flag in {@code /settings/external-systems} takes
     * effect without a restart. Never touches Oracle at boot, so an
     * inactive / missing / misconfigured row can't stop the backend
     * from starting.
     */
    @Bean(name = "oracleDataSource")
    public DataSource oracleDataSource(ExternalSystemRegistry registry) {
        log.info("nds-oracle-datasource: bound to external-systems row '{}' (active flag checked per connection)",
                NDS_CONNECTION_NAME);
        return new RegistryBackedOracleDataSource(registry, NDS_CONNECTION_NAME);
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
        // PR-I1 — skip the EMF-bootstrap JDBC metadata probe. Hibernate
        // normally opens a connection during context init to detect dialect
        // + driver features; with an inactive stub DataSource that would
        // throw and crash boot. Dialect is already pinned above, so the
        // probe is unnecessary. Harmless for the live path too — pinned
        // dialect means Hibernate uses the explicit value either way.
        properties.put("hibernate.boot.allow_jdbc_metadata_access", "false");
        em.setJpaPropertyMap(properties);

        return em;
    }

    @Bean(name = "oracleTransactionManager")
    public PlatformTransactionManager oracleTransactionManager(
            @Qualifier("oracleEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
