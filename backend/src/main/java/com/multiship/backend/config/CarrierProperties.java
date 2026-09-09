package com.multiship.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "carrier")
public class CarrierProperties {

    @NotBlank
    private String defaultCarrierCode;

    @NotBlank
    private String defaultEnvironment;

    @NotNull
    private Integer connectionTimeoutSeconds;

    @NotNull
    private Integer readTimeoutSeconds;

    @Valid
    @NestedConfigurationProperty
    private final FedEx fedEx = new FedEx();

    @Valid
    @NestedConfigurationProperty
    private final Ups ups = new Ups();

    @Valid
    @NestedConfigurationProperty
    private final Stamps stamps = new Stamps();

    @Valid
    @NestedConfigurationProperty
    private final Dhl dhl = new Dhl();

    @Valid
    @NestedConfigurationProperty
    private final ShipperDefaults shipper = new ShipperDefaults();

    @Getter
    @Setter
    public static class FedEx {

        @NotBlank
        private String apiBaseUrl;

        @NotBlank
        private String sandboxUrl;

        @NotBlank
        private String authUrl;

        @NotBlank
        private String apiVersion;

        @NotBlank
        private String shipmentPath;

        @NotBlank
        private String trackingPath;

        @NotBlank
        private String tokenPath;

        @NotBlank
        private String logoUrl;

        @NotBlank
        private String documentationUrl;

        @NotBlank
        private String connectionGuide;

        @NotBlank
        private String defaultServiceType;

        @NotBlank
        private String defaultPackageType;

        @NotBlank
        private String labelResponseOption;
    }

    @Getter
    @Setter
    public static class Ups {

        @NotBlank
        private String apiBaseUrl;

        @NotBlank
        private String sandboxUrl;

        @NotBlank
        private String authUrl;

        /**
         * UPS's CIE (customer integration environment) OAuth endpoint. UPS
         * issues Consumer Keys for a specific environment — a key created on
         * the CIE / sandbox side 401s ("ClientId is Invalid") against the
         * production onlinetools host and vice versa. Selected when the
         * caller's environment == SANDBOX.
         */
        @NotBlank
        private String sandboxAuthUrl;

        @NotBlank
        private String apiVersion;

        @NotBlank
        private String shipmentPath;

        @NotBlank
        private String trackingPath;

        @NotBlank
        private String tokenPath;

        @NotBlank
        private String logoUrl;

        @NotBlank
        private String documentationUrl;

        @NotBlank
        private String connectionGuide;

        @NotBlank
        private String defaultServiceType;

        @NotBlank
        private String defaultPackageType;

        @NotBlank
        private String labelResponseOption;
    }

    @Getter
    @Setter
    public static class Stamps {

        @NotBlank
        private String apiBaseUrl;

        @NotBlank
        private String sandboxUrl;

        @NotBlank
        private String authUrl;

        /**
         * SWSIM staging endpoint. Selected when the caller's environment is
         * SANDBOX so testing credentials aren't sent to production.
         */
        @NotBlank
        private String sandboxAuthUrl;

        @NotBlank
        private String apiVersion;

        @NotBlank
        private String shipmentPath;

        @NotBlank
        private String trackingPath;

        @NotBlank
        private String tokenPath;

        @NotBlank
        private String logoUrl;

        @NotBlank
        private String documentationUrl;

        @NotBlank
        private String connectionGuide;

        @NotBlank
        private String defaultServiceType;

        @NotBlank
        private String defaultPackageType;

        @NotBlank
        private String labelResponseOption;

        /**
         * Auctane Stamps.com / Endicia has TWO wire APIs:
         * <ul>
         *   <li>{@code SWSIM} — legacy SOAP API at
         *       {@code https://swsim.stamps.com/swsim/swsimv135.asmx}.
         *       Auth is {@code AuthenticateUser} with a GUID
         *       {@code IntegrationID}. All shipping / tracking / rating goes
         *       through SOAP envelopes. This is what the connector historically
         *       targeted and what legacy accounts still use.</li>
         *   <li>{@code SERA} — the newer OAuth-2.0 REST API at
         *       {@code https://api.stampsendicia.com/sera/v1} (auth host
         *       {@code signin.stampsendicia.com}). Uses standard OAuth 2.0
         *       tokens; the {@code client_id} is an opaque string, NOT a GUID.
         *       Enable per-account by flipping this flag; the SWSIM path stays
         *       untouched for accounts that haven't migrated.</li>
         * </ul>
         * Defaults to {@code SWSIM} so existing accounts keep working; a
         * property override to {@code SERA} switches the auth wire.
         */
        @NotBlank
        private String apiFlavor = "SWSIM";

        /**
         * SERA OAuth 2.0 token endpoint (production). Called with a
         * {@code client_credentials} grant during "Verify credentials" when
         * {@link #apiFlavor} is {@code SERA}. Ignored on SWSIM.
         */
        private String seraAuthUrl;

        /**
         * SERA OAuth 2.0 token endpoint (sandbox — {@code signin.testing.stampsendicia.com}).
         * Selected when the caller's environment is SANDBOX so testing
         * credentials never hit the production auth host.
         */
        private String seraSandboxAuthUrl;

        /**
         * SERA REST API base (production). Used for label / balance / manifest
         * calls when {@link #apiFlavor} is {@code SERA}.
         */
        private String seraApiBaseUrl;

        /** SERA REST API base (sandbox). */
        private String seraSandboxApiBaseUrl;

        /**
         * SERA authorization endpoint (production) — the browser-redirect
         * entry point for the {@code authorization_code} flow. Per
         * developer.stamps.com the URL is
         * {@code https://signin.stampsendicia.com/authorize} (production) or
         * {@code https://signin.testing.stampsendicia.com/authorize}
         * (sandbox). We redirect the operator here with
         * {@code response_type=code} and a signed {@code state} value; on
         * consent the server 302s back to {@link #seraRedirectUri} with a
         * {@code code} query parameter that we exchange at
         * {@link #seraAuthUrl} for the access + refresh tokens.
         */
        private String seraAuthorizeUrl;

        /** SERA authorization endpoint (sandbox). */
        private String seraSandboxAuthorizeUrl;

        /**
         * Callback endpoint the operator's browser is redirected to after
         * consent. Must be registered as an allowed redirect URI on the
         * Stamps.com developer portal for the client_id in question.
         * Typical dev value: {@code http://localhost:8081/api/v1/carrier-accounts/stamps-sera/callback}.
         * Env-configurable via {@code carrier.stamps.sera-redirect-uri}.
         */
        private String seraRedirectUri;

        /**
         * OAuth scopes requested during the authorize step. Per SERA docs,
         * {@code offline_access} is required to receive a refresh token;
         * without it the token exchange returns access_token only and the
         * connector can't refresh past its 1-hour expiry.
         */
        private String seraScope = "offline_access";
    }

    @Getter
    @Setter
    public static class Dhl {

        @NotBlank
        private String apiBaseUrl;

        @NotBlank
        private String sandboxUrl;

        @NotBlank
        private String authUrl;

        /** Sandbox endpoint — DHL uses a /test suffix, same host as production. */
        @NotBlank
        private String sandboxAuthUrl;

        @NotBlank
        private String apiVersion;

        @NotBlank
        private String shipmentPath;

        @NotBlank
        private String trackingPath;

        @NotBlank
        private String tokenPath;

        @NotBlank
        private String logoUrl;

        @NotBlank
        private String documentationUrl;

        @NotBlank
        private String connectionGuide;

        @NotBlank
        private String defaultServiceType;

        @NotBlank
        private String defaultPackageType;

        @NotBlank
        private String labelResponseOption;
    }

    @Getter
    @Setter
    public static class ShipperDefaults {

        @NotBlank
        private String name;

        @NotBlank
        private String phone;

        @NotBlank
        private String addressLine1;

        private String addressLine2;

        @NotBlank
        private String city;

        @NotBlank
        private String state;

        @NotBlank
        private String postalCode;

        @NotBlank
        private String countryCode;
    }
}
