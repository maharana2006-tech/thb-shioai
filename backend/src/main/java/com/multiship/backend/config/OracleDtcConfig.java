package com.multiship.backend.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for Oracle DTC view name.
 * Supports switching between test and production views via Spring profiles.
 *
 * Usage:
 *   Development (default): TB_SHIPX_DTC_UVW_TEST
 *   Production: activate with --spring.profiles.active=prod
 */
@Component
@Getter
public class OracleDtcConfig {

    @Value("${oracle.dtc.view-name}")
    private String dtcViewName;
}
