package com.multiship.backend.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** BUG-151 — springdoc must sit under /api so the SPA's /api route (dev proxy, prod reverse proxy) forwards it. */
class SpringdocPathsTest {

    @Test
    void swaggerAndApiDocsLiveUnderApiPrefix() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            props.load(in);
        }
        assertEquals("/api/swagger-ui.html", props.getProperty("springdoc.swagger-ui.path"));
        assertEquals("/api/v3/api-docs", props.getProperty("springdoc.api-docs.path"));
    }
}
