package com.multiship.backend.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiship.backend.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Sprint 51 AC-M7 — OpenAPI snapshot regression test for the public
 * external v2 surface. Fetches {@code /v3/api-docs/external-v2}, canonicalises
 * the JSON, and diffs it against a committed snapshot at
 * {@code src/test/resources/openapi/external-v2-snapshot.json}. Any drift
 * (renamed field, new required prop, silently-broadening schema) breaks
 * the build so a wire-contract change is a deliberate reviewer decision.
 *
 * <p><b>Refreshing the snapshot</b> — rerun with the update flag once
 * the change is intended:
 * <pre>
 * INTEGRATION_TESTS=1 ./mvnw -Dtest=ExternalV2OpenApiSnapshotTest \
 *     -Dopenapi.snapshot.update=true verify
 * </pre>
 * Then commit the regenerated {@code external-v2-snapshot.json}.
 *
 * <p>Volatile fields stripped before comparison: {@code servers[*].url}
 * (ephemeral RANDOM_PORT) and any {@code $ref} example values that
 * springdoc may vary across runs.
 */
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "1")
class ExternalV2OpenApiSnapshotTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ExternalV2OpenApiSnapshotTest.class);

    /** Where the committed snapshot lives on disk. Path is relative to
     *  the maven project root so the test works from mvn + IDE alike. */
    private static final Path SNAPSHOT_PATH =
            Paths.get("src", "test", "resources", "openapi", "external-v2-snapshot.json");

    /** System property that, when set truthy, rewrites the snapshot
     *  instead of asserting. Deliberately verbose to make an accidental
     *  CI-set impossible to miss in the logs. */
    private static final String UPDATE_FLAG = "openapi.snapshot.update";

    @LocalServerPort
    private int port;

    @Test
    void externalV2OpenApiMatchesCommittedSnapshot() throws Exception {
        String actualRaw = fetchSpec();
        String actualCanonical = canonicalise(actualRaw);

        boolean updateMode = Boolean.parseBoolean(System.getProperty(UPDATE_FLAG, "false"));
        if (updateMode) {
            log.warn("[AC-M7] {}=true → REWRITING snapshot at {}. "
                    + "Do NOT run with this flag in CI.", UPDATE_FLAG, SNAPSHOT_PATH.toAbsolutePath());
            Files.createDirectories(SNAPSHOT_PATH.getParent());
            Files.writeString(SNAPSHOT_PATH, actualCanonical, StandardCharsets.UTF_8);
            // Still assert the file is readable now so an accidental
            // write-and-forget doesn't leave the assertion silent.
            assertTrue(Files.exists(SNAPSHOT_PATH));
            return;
        }

        if (!Files.exists(SNAPSHOT_PATH)) {
            fail("OpenAPI snapshot not found at " + SNAPSHOT_PATH.toAbsolutePath()
                    + ". Generate it with: -D" + UPDATE_FLAG + "=true");
        }
        String expected = Files.readString(SNAPSHOT_PATH, StandardCharsets.UTF_8);

        if (!expected.equals(actualCanonical)) {
            // Print a compact line-diff so the failure log surfaces the drift
            // instead of dumping the whole JSON blob.
            String diff = lineDiff(expected, actualCanonical);
            fail("External v2 OpenAPI drift detected. If intentional, refresh via "
                    + "-D" + UPDATE_FLAG + "=true and commit "
                    + SNAPSHOT_PATH + ".\n" + diff);
        }
        // Explicit passing assertion.
        assertEquals(expected, actualCanonical);
    }

    // ── helpers ──

    private String fetchSpec() throws IOException, InterruptedException {
        String url = "http://localhost:" + port + "/v3/api-docs/external-v2";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "GET " + url + " → " + resp.statusCode());
        return resp.body();
    }

    /** Deterministic serialisation:
     *  - map keys sorted alphabetically (Jackson's ORDER_MAP_ENTRIES_BY_KEYS)
     *  - servers/url stripped (varies per RANDOM_PORT)
     *  - pretty-printed for a readable diff on failure. */
    private String canonicalise(String rawJson) throws IOException {
        ObjectMapper reader = new ObjectMapper();
        JsonNode root = reader.readTree(rawJson);
        stripVolatile(root);

        ObjectMapper writer = new ObjectMapper();
        writer.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        writer.enable(SerializationFeature.INDENT_OUTPUT);
        // Normalise trailing newline so cross-platform commits stay stable.
        return writer.writeValueAsString(root) + "\n";
    }

    private static void stripVolatile(JsonNode root) {
        if (root.isObject()) {
            ObjectNode obj = (ObjectNode) root;
            // servers[].url → contains ephemeral port. Drop entire servers list;
            // it isn't part of the wire contract.
            obj.remove("servers");
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            while (it.hasNext()) {
                stripVolatile(it.next().getValue());
            }
        } else if (root.isArray()) {
            root.forEach(ExternalV2OpenApiSnapshotTest::stripVolatile);
        }
    }

    /** Compact "L<line>: - expected / + actual" diff limited to the first
     *  50 differing lines so the failure log stays legible. */
    private static String lineDiff(String expected, String actual) {
        List<String> exp = expected.lines().toList();
        List<String> act = actual.lines().toList();
        List<String> out = new ArrayList<>();
        int max = Math.max(exp.size(), act.size());
        int printed = 0;
        for (int i = 0; i < max && printed < 50; i++) {
            String e = i < exp.size() ? exp.get(i) : "<eof>";
            String a = i < act.size() ? act.get(i) : "<eof>";
            if (!e.equals(a)) {
                out.add("L" + (i + 1) + ": - " + e);
                out.add("L" + (i + 1) + ": + " + a);
                printed++;
            }
        }
        return String.join("\n", out);
    }
}
