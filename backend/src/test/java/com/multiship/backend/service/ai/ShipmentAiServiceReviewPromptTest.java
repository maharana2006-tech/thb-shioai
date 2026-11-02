package com.multiship.backend.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiship.backend.dto.ai.ReviewShipmentRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUG-026 (QA 2026-10-23) — on a US→US (domestic) shipment, the AI
 * review surface fired a HIGH "importer_of_record: Missing importer of
 * record for the DAP shipment" warning. Two cooperating roots:
 * <ol>
 *   <li>The FE sticky-saved the last operator-chosen Incoterm into
 *       localStorage and sent it on every subsequent shipment, even
 *       domestic ones. (FE fix in {@code useNewShipmentModel.ts}.)</li>
 *   <li>The backend prompt builder always wrote an {@code Incoterm:} +
 *       {@code Importer of record provided:} line to the model, even
 *       when the {@code International: no} line already said customs
 *       weren't in scope. The model then weighted the lingering
 *       Incoterm higher than the explicit "no". (This test's fix: skip
 *       both customs lines when the shipment is domestic + rewrite the
 *       prompt wording to be explicit.)</li>
 * </ol>
 *
 * <p>These tests pin the backend half via a mocked OpenAiClient so a
 * future refactor can't quietly re-introduce the customs context on a
 * domestic snapshot.
 */
class ShipmentAiServiceReviewPromptTest {

    private OpenAiClient openAi;
    private ShipmentAiService svc;

    @BeforeEach
    void setUp() {
        openAi = mock(OpenAiClient.class);
        when(openAi.completeJson(anyString(), anyString(), anyString()))
                .thenReturn(new ObjectMapper().createObjectNode().putArray("warnings").arrayNode());
        svc = new ShipmentAiService(openAi);
    }

    private static ReviewShipmentRequest req(String fromCountry, String toCountry, String incoterm) {
        ReviewShipmentRequest r = new ReviewShipmentRequest();
        r.setFromCountry(fromCountry);
        r.setToCountry(toCountry);
        r.setIncoterm(incoterm);
        r.setWeightLb(2.5);
        return r;
    }

    @Test
    void domesticShipment_omitsIncotermAndImporterLinesFromPrompt() {
        // US→US with a stray Incoterm sent by a buggy FE snapshot. The
        // builder must strip the customs context so the model doesn't
        // trip the importer-missing check on a parcel that isn't
        // crossing a border.
        svc.reviewShipment(req("US", "US", "DAP"));

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(openAi).completeJson(anyString(), user.capture(), anyString());
        String prompt = user.getValue();

        assertFalse(prompt.contains("Incoterm:"),
                "domestic prompt must NOT carry an Incoterm line: " + prompt);
        assertFalse(prompt.contains("Importer of record provided"),
                "domestic prompt must NOT carry an Importer line: " + prompt);
        assertTrue(prompt.contains("International: no"),
                "the International marker still goes to the model: " + prompt);
    }

    @Test
    void domesticShipment_systemPromptNeverMentionsCustoms() {
        // BUG-026 (R3) — the instructions themselves must not put DAP /
        // importer-of-record ideas in front of the model on a US→US parcel.
        svc.reviewShipment(req("US", "US", "DAP"));

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(openAi).completeJson(system.capture(), anyString(), anyString());
        String prompt = system.getValue().toLowerCase();

        assertFalse(prompt.contains("importer"), system.getValue());
        assertFalse(prompt.contains("incoterm"), system.getValue());
        assertFalse(prompt.contains("dap"), system.getValue());
        assertFalse(prompt.contains("hs code"), system.getValue());
        assertTrue(prompt.contains("domestic"), system.getValue());
    }

    @Test
    void internationalShipment_systemPromptKeepsCustomsChecks() {
        svc.reviewShipment(req("US", "CA", "DAP"));

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(openAi).completeJson(system.capture(), anyString(), anyString());

        assertTrue(system.getValue().contains("importer of record"), system.getValue());
        assertTrue(system.getValue().contains("HS codes"), system.getValue());
    }

    @Test
    void internationalShipment_stillIncludesIncotermAndImporterLines() {
        // Belt-and-braces: the strip-on-domestic rule must NOT reach
        // intl shipments. Customs context is required for the model to
        // flag real issues on an actual cross-border parcel.
        ReviewShipmentRequest r = req("US", "CA", "DDP");
        r.setImporterProvided(true);
        svc.reviewShipment(r);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(openAi).completeJson(anyString(), user.capture(), anyString());
        String prompt = user.getValue();

        assertTrue(prompt.contains("Incoterm: DDP"), prompt);
        assertTrue(prompt.contains("Importer of record provided: yes"), prompt);
        assertTrue(prompt.contains("International: yes"), prompt);
    }

    @Test
    void domesticShipment_withNullIncoterm_stillOmitsCustomsLines() {
        // Guard against a null-incoterm regression masking the strip
        // behaviour (the builder's `orQ` previously printed "n/a" even
        // on null; the omit path must stay unconditional for domestic).
        svc.reviewShipment(req("US", "US", null));

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(openAi).completeJson(anyString(), user.capture(), anyString());
        String prompt = user.getValue();

        assertFalse(prompt.contains("Incoterm:"), prompt);
        assertFalse(prompt.contains("Importer of record"), prompt);
    }

    @Test
    void internationalShipment_withoutImporter_logsNoImporter() {
        // Pre-fix behaviour preserved for intl: the "no importer"
        // signal still reaches the model when the operator hasn't
        // picked one AND the lane is cross-border. The FE now sends
        // {@code importerProvided: false} on intl without a resolved
        // importer; the model needs to see that line to fire the
        // (correct) warning.
        ReviewShipmentRequest r = req("US", "CA", "DAP");
        r.setImporterProvided(false);
        svc.reviewShipment(r);

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(openAi).completeJson(anyString(), user.capture(), anyString());
        String prompt = user.getValue();

        assertTrue(prompt.contains("Importer of record provided: no"), prompt);
    }
}
