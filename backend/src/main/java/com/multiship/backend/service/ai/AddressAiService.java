package com.multiship.backend.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiship.backend.dto.external.ExternalAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

/**
 * Turns a free-text address blob into a structured {@link ExternalAddress}.
 * Suggestion-only: the caller (the manual-shipment form) shows the result for
 * the user to confirm/edit — the model never commits a shipment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressAiService {

    private final OpenAiClient openAi;

    private static final String SYSTEM_PROMPT = """
        You extract a single postal shipping address from messy free text (email
        signatures, order confirmations, chat messages). Return ONLY a JSON object
        with EXACTLY these keys, all string values, empty string "" when unknown:
        {
          "name": "", "company": "", "phone": "", "email": "",
          "addressLine1": "", "addressLine2": "", "city": "", "state": "",
          "postalCode": "", "countryCode": ""
        }
        state = state/province/region (use the local code if obvious, e.g. NY, KA).
        countryCode = ISO 3166-1 alpha-2, uppercase; infer from city/postcode if not written.
        Do not invent data that is not present. No commentary, no extra keys.
        """;

    public ExternalAddress parse(String text) {
        if (!StringUtils.hasText(text)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No text supplied to parse.");
        }
        JsonNode a = openAi.completeJson(SYSTEM_PROMPT, text.trim());
        ExternalAddress out = new ExternalAddress();
        out.setName(str(a, "name"));
        out.setCompany(str(a, "company"));
        out.setPhone(str(a, "phone"));
        out.setEmail(str(a, "email"));
        out.setAddressLine1(str(a, "addressLine1"));
        out.setAddressLine2(str(a, "addressLine2"));
        out.setCity(str(a, "city"));
        out.setState(str(a, "state"));
        out.setPostalCode(str(a, "postalCode"));
        String cc = str(a, "countryCode");
        out.setCountryCode(cc == null ? null : cc.trim().toUpperCase(Locale.ROOT));
        return out;
    }

    /** Read a text field, returning null (not "") so empty values don't clobber existing form data. */
    private static String str(JsonNode node, String key) {
        String v = node.path(key).asText("");
        return StringUtils.hasText(v) ? v.trim() : null;
    }
}
