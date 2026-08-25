package com.multiship.backend.service.carriers;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the {@link StampsConnector} SWSIM env-routing bug.
 *
 * <p>Origin: {@code validateAddress} was hardcoded to
 * {@code carrierProperties.getStamps().getApiBaseUrl()} (prod), missing the
 * {@code isSandbox(environment) ? sandbox : prod} branch every other SWSIM
 * endpoint used. Result: a SANDBOX operator's address validation call fired
 * against the prod SWSIM host — env bleed from a test session onto real
 * production data.
 *
 * <p>The bug was invisible at unit-test level (the call is real HTTP, and
 * mocking the {@code HttpClients.newBuilder()} static chain is invasive
 * enough that no test caught the drift). A source-scan test is the cheap
 * catch: any future SWSIM endpoint that reintroduces the hardcoded-prod
 * pattern fails this test.
 */
class StampsEnvRoutingTest {

    private static final Path STAMPS_CONNECTOR =
            Paths.get("src", "main", "java", "com", "multiship", "backend",
                    "service", "carriers", "StampsConnector.java");

    /**
     * The bug shape: a `String swsimUrl = ...getApiBaseUrl()` declaration
     * that ISN'T guarded by an `isSandbox(environment) ?` ternary.
     *
     * <p>Uses a lookbehind so we only match declarations that write to a
     * new {@code swsimUrl} variable — plain calls to {@code getApiBaseUrl()}
     * inside the ternary itself (the correct pattern) are left alone.
     */
    private static final Pattern HARDCODED_PROD_URL = Pattern.compile(
            "String\\s+swsimUrl\\s*=\\s*carrierProperties\\.getStamps\\(\\)\\.getApiBaseUrl\\(\\)");

    /**
     * The correct shape: a ternary that selects sandbox vs prod based on
     * {@code isSandbox(environment)}. Every SWSIM endpoint that talks to
     * a remote host should look like this.
     */
    private static final Pattern SANDBOX_TERNARY = Pattern.compile(
            "String\\s+swsimUrl\\s*=\\s*isSandbox\\s*\\(\\s*environment\\s*\\)");

    @Test
    void noHardcodedProdUrl_forSwsimEndpoints() throws Exception {
        String source = Files.readString(STAMPS_CONNECTOR, StandardCharsets.UTF_8);
        Matcher m = HARDCODED_PROD_URL.matcher(source);
        int hits = 0;
        StringBuilder locations = new StringBuilder();
        while (m.find()) {
            hits++;
            int lineNo = 1 + (int) source.substring(0, m.start()).chars().filter(c -> c == '\n').count();
            locations.append("  line ").append(lineNo).append(": ").append(m.group()).append('\n');
        }
        assertEquals(0, hits,
                "Found SWSIM endpoint(s) that hardcode the prod URL without a sandbox branch. "
                        + "Every remote-SWSIM call must use `isSandbox(environment) ? sandbox : prod` "
                        + "or SANDBOX operators will bleed onto prod SWSIM. Fix these:\n" + locations);
    }

    @Test
    void everySwsimEndpoint_branchesOnEnvironment() throws Exception {
        // Counterpart to the negative test: assert that the correct
        // ternary IS present enough times to cover the SWSIM endpoints
        // (getAccessToken, createShipment, getRates, validateAddress,
        // voidShipment, schedulePickup, closeOutDay = 7 minimum; plus
        // trackShipment which reads the same URL). If someone adds a new
        // SWSIM operation, they should add the ternary too.
        String source = Files.readString(STAMPS_CONNECTOR, StandardCharsets.UTF_8);
        long ternaryUses = SANDBOX_TERNARY.matcher(source).results().count();
        assertTrue(ternaryUses >= 7,
                "Expected at least 7 SWSIM endpoints to use `isSandbox(environment) ?` for URL "
                        + "selection; found " + ternaryUses + ". A drop below 7 suggests a new "
                        + "endpoint was added without env routing.");
    }
}
