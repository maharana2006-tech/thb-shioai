package com.multiship.backend.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * Secondary Oracle NDS DataSource Configuration.
 * This connects to Oracle NDS (192.168.3.8:1521/tb10g) to fetch DTC orders.
 */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(
        basePackages = "com.multiship.backend.repository.oracle",
        entityManagerFactoryRef = "oracleEntityManagerFactory",
        transactionManagerRef = "oracleTransactionManager"
)
public class OracleDataSourceConfig {

    /**
     * Secondary Oracle DataSource for NDS.
     */
    @Bean(name = "oracleDataSource")
    public DataSource oracleDataSource(OracleDataSourceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.getUrl());
        config.setUsername(properties.getUsername());
        config.setPassword(properties.getPassword());
        config.setDriverClassName(properties.getDriverClassName());
        config.setMaximumPoolSize(properties.getMaxPoolSize());
        config.setMinimumIdle(5);
        config.setConnectionTimeout(properties.getConnectionTimeout());
        config.setMaxLifetime(1800000);
        config.setLeakDetectionThreshold(300000);
        return new HikariDataSource(config);
    }

    /**
     * EntityManagerFactory for Oracle (read-only).
     */
    @Bean(name = "oracleEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean oracleEntityManagerFactory(
            @Qualifier("oracleDataSource") DataSource dataSource) {

        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.multiship.backend.model.oracle");

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        adapter.setGenerateDdl(false);  // Read-only, no DDL generation
        em.setJpaVendorAdapter(adapter);

        Map<String, Object> properties = new HashMap<>();
        properties.put("hibernate.dialect", "org.hibernate.dialect.OracleDialect");
        properties.put("hibernate.jdbc.batch_size", "20");
        properties.put("hibernate.default_batch_fetch_size", "16");
        properties.put("hibernate.hbm2ddl.auto", "none");  // No schema updates
        em.setJpaPropertyMap(properties);

        return em;
    }

    /**
     * TransactionManager for Oracle (read-only operations).
     */
    @Bean(name = "oracleTransactionManager")
    public PlatformTransactionManager oracleTransactionManager(
            @Qualifier("oracleEntityManagerFactory") EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    /**
     * Oracle configuration properties.
     */
    @ConfigurationProperties(prefix = "spring.datasource.oracle")
    public static class OracleDataSourceProperties {
        private String url;
        private String username;
        private String password;
        private String driverClassName;
        private Integer maxPoolSize = 10;
        private Integer connectionTimeout = 10000;

        // Getters and Setters
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getDriverClassName() { return driverClassName; }
        public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }
        public Integer getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(Integer maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public Integer getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(Integer connectionTimeout) { this.connectionTimeout = connectionTimeout; }
    }
}
