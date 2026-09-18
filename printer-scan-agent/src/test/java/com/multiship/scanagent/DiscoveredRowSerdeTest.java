package com.multiship.scanagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.scanagent.model.DiscoveredRow;
import com.multiship.scanagent.model.PollEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredRowSerdeTest {

    // Regression: the backend expects field names byte-for-byte
    // (PrinterScanService.DiscoveredRow record). Renaming any field
    // silently drops the value on the server. Guard the wire contract.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesEveryFieldEvenWhenNull() throws Exception {
        DiscoveredRow row = new DiscoveredRow(
                "192.168.1.10", 9100, "Zebra-01", "warehouse-A",
                "RAW_9100", "ZPL", null, null,
                "pdl=application/vnd.zebra-zpl\n");
        String json = MAPPER.writeValueAsString(List.of(row));

        assertTrue(json.contains("\"host\":\"192.168.1.10\""), json);
        assertTrue(json.contains("\"port\":9100"), json);
        assertTrue(json.contains("\"name\":\"Zebra-01\""), json);
        assertTrue(json.contains("\"location\":\"warehouse-A\""), json);
        assertTrue(json.contains("\"connectionGuess\":\"RAW_9100\""), json);
        assertTrue(json.contains("\"formatGuess\":\"ZPL\""), json);
        assertTrue(json.contains("\"paperGuess\":null"), json);
        assertTrue(json.contains("\"queuePath\":null"), json);
        assertTrue(json.contains("\"rawTxt\":\"pdl=application/vnd.zebra-zpl\\n\""), json);
    }

    @Test
    void deserializesPollEnvelopeWithScanRequestedTrue() throws Exception {
        String body = "{\"status\":\"success\",\"code\":200,\"data\":{\"scanRequested\":true},\"other\":\"ignored\"}";

        PollEnvelope env = MAPPER.readValue(body, PollEnvelope.class);

        assertNotNull(env.data());
        assertTrue(env.data().scanRequested());
        assertEquals("success", env.status());
    }

    @Test
    void deserializesPollEnvelopeWithScanRequestedFalse() throws Exception {
        String body = "{\"status\":\"success\",\"code\":200,\"data\":{\"scanRequested\":false}}";

        PollEnvelope env = MAPPER.readValue(body, PollEnvelope.class);

        assertNotNull(env.data());
        assertEquals(false, env.data().scanRequested());
    }
}
