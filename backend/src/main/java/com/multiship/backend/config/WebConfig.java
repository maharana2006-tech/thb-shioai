package com.multiship.backend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    /** Sprint 50 Tier 0.5 PR B — enforces @RequiresScope on API-key callers. */
    private final ApiKeyScopeInterceptor apiKeyScopeInterceptor;

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages"); // Explicitly targets messages.properties file bundle
        messageSource.setDefaultEncoding("UTF-8");
        return messageSource;
    }

    @Bean
    @Override
    public LocalValidatorFactoryBean getValidator() {
        LocalValidatorFactoryBean bean = new LocalValidatorFactoryBean();
        bean.setValidationMessageSource(messageSource()); // Ties validation annotations to properties mappings
        return bean;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Sprint 50 Tier 0.5 PR B — restrict to the external API paths that
        // actually accept ApiKey auth. Internal operator endpoints don't
        // carry @RequiresScope so the interceptor is a no-op on them
        // anyway, but scoping the path list keeps the request pipeline
        // tighter and avoids surprising a future contributor.
        registry.addInterceptor(apiKeyScopeInterceptor)
                .addPathPatterns("/api/v1/external/**", "/api/v2/external/**");
    }
}