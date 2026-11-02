package com.multiship.backend.controller;

import com.multiship.backend.dto.MailProviderUpsertRequest;
import com.multiship.backend.service.mail.ConfiguredMailSender;
import com.multiship.backend.service.mail.MailConfigService;
import com.multiship.backend.service.mail.MailProvider;
import com.multiship.backend.service.mail.MailProviderRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BUG-137 — a mail provider with only a name can never send. */
class AdminMailProviderControllerTest {

    @Test
    void createWithoutTheKindsRequiredKeysIsRefusedOnThatKey() {
        MailConfigService config = mock(MailConfigService.class);
        MailProviderRegistry registry = mock(MailProviderRegistry.class);
        MailProvider smtp = mock(MailProvider.class);
        when(smtp.requiredConfigKeys()).thenReturn(Set.of("host"));
        when(registry.require("SMTP")).thenReturn(smtp);
        AdminMailProviderController controller = new AdminMailProviderController(config, registry, mock(ConfiguredMailSender.class));

        MailProviderUpsertRequest req = new MailProviderUpsertRequest();
        req.setKind("SMTP");
        req.setDisplayName("ZZ mail");
        req.setConfig(Map.of("host", " "));

        com.multiship.backend.exception.FieldValidationException ex = assertThrows(
                com.multiship.backend.exception.FieldValidationException.class, () -> controller.upsert(req, null));
        assertEquals("config.host", ex.getField());
    }
}
