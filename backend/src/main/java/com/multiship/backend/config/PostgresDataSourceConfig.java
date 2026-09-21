package com.multiship.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.hibernate.autoconfigure.HibernateProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernateSettings;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jpa.autoconfigure.JpaProperties;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
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
 * Primary PostgreSQL DataSource Configuration.
 * This is the main database for the application (label_batch, orders, etc).
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = {"com.multiship.backend.repository"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = ".*\\.oracle\\..*"
        ),
        entityManagerFactoryRef = "postgresEntityManagerFactory",
        transactionManagerRef = "postgresTransactionManager"
)
public class PostgresDataSourceConfig {

    /**
     * Primary PostgreSQL DataSource — built from spring.datasource.* and the
     * spring.datasource.hikari.* pool settings exactly as Spring Boot's own
     * auto-configuration would (it steps aside once a second DataSource
     * exists, so this keeps the pool size, timeouts and leak detection that
     * application.properties sets instead of hard-coding them).
     */
    @Primary
    @Bean(name = "postgresDataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource postgresDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * Primary EntityManagerFactory for PostgreSQL, with the settings Spring
     * Boot would apply — every spring.jpa.* property, ddl-auto, and the
     * CamelCase→snake_case naming (orderNo → order_no). Without them
     * Hibernate maps fields to camelCase columns and ddl-auto=update starts
     * adding those columns to the live tables.
     *
     * <p>Hibernate asks Spring for its AttributeConverters (SpringBeanContainer),
     * so EncryptedStringConverter gets its CryptoService — without it, carrier
     * secrets would be written in plain text and read back as null.
     */
    @Primary
    @Bean(name = "postgresEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean postgresEntityManagerFactory(
            @Qualifier("postgresDataSource") DataSource dataSource,
            JpaProperties jpaProperties,
            HibernateProperties hibernateProperties,
            ConfigurableListableBeanFactory beanFactory) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.multiship.backend.model");
        // The Oracle view entities belong to the Oracle factory only — never
        // map (or ddl-update) them against Postgres.
        em.setPersistenceUnitPostProcessors(pui ->
                pui.getManagedClassNames().removeIf(name -> name.contains(".model.oracle.")));

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setShowSql(jpaProperties.isShowSql());
        if (jpaProperties.getDatabasePlatform() != null) {
            adapter.setDatabasePlatform(jpaProperties.getDatabasePlatform());
        }
        em.setJpaVendorAdapter(adapter);

        Map<String, Object> properties = new HashMap<>(hibernateProperties.determineHibernateProperties(
                jpaProperties.getProperties(), new HibernateSettings().ddlAuto(() -> "none")));
        properties.put(AvailableSettings.BEAN_CONTAINER, new SpringBeanContainer(beanFactory));
        em.setJpaPropertyMap(properties);
        return em;
    }

    /**
     * Primary TransactionManager for PostgreSQL.
     */
    @Primary
    @Bean(name = "postgresTransactionManager")
    public PlatformTransactionManager postgresTransactionManager(
            @Qualifier("postgresEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
