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
