package com.multiship.backend.service.carriers.exceptions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Sprint 49 Tier 2 — status → typed-exception mapping guard.
 *
 * <p>The service layer switches on these types to decide the HTTP
 * response (429 passes Retry-After, 401 triggers OAuth refresh in
 * Tier 3, 400 surfaces validation errors). Silent mis-mapping would
 * defeat the fix — every branch has an assertion here.
 */
class CarrierExceptionMapperTest {

    @Test
    void mapsResourceAccessExceptionToTimeout() {
        // Spring wraps IOException / SocketTimeoutException as ResourceAccessException.
        ResourceAccessException src = new ResourceAccessException("read timed out",
                new IOException("timeout"));
        CarrierException mapped = CarrierExceptionMapper.map("UPS", src, "createShipment");
        assertInstanceOf(CarrierTimeoutException.class, mapped);
        assertEquals("UPS", mapped.getCarrierCode());
    }

    @Test
    void maps401ToAuth() {
        HttpClientErrorException src = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        CarrierException mapped = CarrierExceptionMapper.map("FEDEX", src, "createShipment");
        assertInstanceOf(CarrierAuthException.class, mapped);
    }

    @Test
    void maps403ToAuth() {
        HttpClientErrorException src = HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        CarrierException mapped = CarrierExceptionMapper.map("UPS", src, "createShipment");
        assertInstanceOf(CarrierAuthException.class, mapped);
    }

    @Test
    void maps429ToRateLimitWithRetryAfter() {
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", "42");
        HttpClientErrorException src = HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", h,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        CarrierException mapped = CarrierExceptionMapper.map("DHL", src, "rate");
        assertInstanceOf(CarrierRateLimitException.class, mapped);
        assertEquals(42, ((CarrierRateLimitException) mapped).getRetryAfterSeconds());
    }

    @Test
    void maps400ToValidation() {
        HttpClientErrorException src = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY,
                "{\"error\":\"invalid address\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        CarrierException mapped = CarrierExceptionMapper.map("STAMPS", src, "createShipment");
        assertInstanceOf(CarrierValidationException.class, mapped);
        assertEquals("{\"error\":\"invalid address\"}",
                ((CarrierValidationException) mapped).getCarrierErrorBody());
    }

    @Test
    void maps500ToServer() {
        HttpServerErrorException src = HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "boom", HttpHeaders.EMPTY,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        CarrierException mapped = CarrierExceptionMapper.map("FEDEX", src, "createShipment");
        assertInstanceOf(CarrierServerException.class, mapped);
        assertEquals(500, ((CarrierServerException) mapped).getCarrierStatusCode());
    }

    @Test
    void mapsGenericExceptionToBase() {
        // Anything unclassified degrades to the base type — never lost.
        CarrierException mapped = CarrierExceptionMapper.map("UPS",
                new RuntimeException("weird parser"), "parse");
        assertEquals(CarrierException.class, mapped.getClass());
    }
}
