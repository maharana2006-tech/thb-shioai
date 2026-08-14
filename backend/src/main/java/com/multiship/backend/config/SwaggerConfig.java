package com.multiship.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    /**
     * Sprint 51 AC-M7 — dedicated OpenAPI group for the public external v2
     * surface. Backs the snapshot-diff regression test
     * ({@code ExternalV2OpenApiSnapshotTest}) that pins the wire contract
     * against unintended shape changes. Also lets integrators pull just the
     * v2 surface via {@code /v3/api-docs/external-v2} without the internal
     * admin endpoints noise.
     */
    @Bean
    public GroupedOpenApi externalV2GroupedOpenApi() {
        return GroupedOpenApi.builder()
                .group("external-v2")
                .pathsToMatch("/api/v2/**")
                .build();
    }

    private static final String API_DESCRIPTION = """
            Multi-tenant shipping label management API.

            ### Conventions (apply to every endpoint)
            - **Auth**: JWT bearer token from `POST /api/v1/auth/login`. Roles: `ADMIN` (full), \
            `USER` (orders + labels), `TENANT` (own orders, read-only, must pass `tenantId`).
            - **Envelope**: every JSON response is wrapped in \
            `{status, code, message, timestamp, data, errors, errorCode}`.
            - **Errors**: branch on the HTTP status and the machine-readable `errorCode` \
            (stable contract — e.g. `ORDER_NOT_FOUND`, `LABEL_ALREADY_GENERATED`, \
            `NEEDS_CARRIER_DETAILS`, `NO_DEFAULT_ACCOUNT`, `CARRIER_FAILURE`, \
            `USERNAME_TAKEN`, `VALIDATION_ERROR`), never on `message` text.
            - **Carrier codes**: one canonical vocabulary — `UPS`, `FEDEX`, `USPS`. \
            Legacy ship-via codes (P80/F77/L01) are tolerated on input but never emitted.
            - **Idempotency**: label generation is idempotent — see `POST /api/v1/orders/{orderNo}/label`.
            - **Lists**: `GET /api/v1/orders` is the single list endpoint; server-side \
            pagination, sorting, and filtering with `{content, pageNumber, pageSize, totalElements, totalPages}`.
            """;

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Multiship API")
                        .version("v1")
                        .description(API_DESCRIPTION))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT from POST /api/v1/auth/login (24h expiry).")));
    }
}
