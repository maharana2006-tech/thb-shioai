package com.multiship.backend.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.Arrays;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   com.multiship.backend.service.ApiKeyService apiKeyService,
                                                   com.multiship.backend.repository.ApiKeyRepository apiKeyRepository)
            throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Open endpoints
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Sprint 46 — OAuth 2.0 token endpoint is public; the
                        // caller authenticates with client credentials in the
                        // body, not with a Bearer token.
                        .requestMatchers("/api/v1/oauth/token").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Sprint 36 — carrier webhook receiver: no JWT (carriers can't
                        // produce our tokens). Signature verification per carrier via
                        // HMAC-SHA256 in the request header.
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/carrier/**").permitAll()
                        // Admin-only credential management, decided before the
                        // request body is even parsed (403 beats 400).
                        .requestMatchers("/api/v1/carriers/connect", "/api/v1/carriers/disconnect",
                                "/api/v1/carriers/refresh-token").hasRole("ADMIN")
                        .requestMatchers("/api/v1/api-keys/**").hasRole("ADMIN")
                        // Public shipping API for external apps — API key (ROLE_API); ADMIN allowed for testing.
                        .requestMatchers("/api/v1/external/**").hasAnyRole("API", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/label").hasAnyRole("ADMIN", "USER")
                        // All other requests need authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handling -> handling
                        // Missing/invalid token -> 401, authenticated but wrong role -> 403.
                        .authenticationEntryPoint((request, response, ex) ->
                                writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHORIZED", "Authentication required. Please sign in again."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "You do not have permission to perform this action."))
                )
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, apiKeyRepository), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private static void writeJsonError(HttpServletResponse response, int status, String errorCode, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
                "{\"status\":\"error\",\"code\":%d,\"errorCode\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\",\"data\":null,\"errors\":null}",
                status, errorCode, message, Instant.now()));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.asList(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://[::1]:*"
        ));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Cache-Control", "Idempotency-Key", "X-API-Key"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
