package com.multiship.backend.service.mail;

import com.multiship.backend.model.NotificationTemplateEntity;
import com.multiship.backend.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A4.2 — resolves + renders + delegates to MailSender. */
class NotificationServiceTest {

    private NotificationTemplateRepository repo;
    private MailSender mailSender;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repo = mock(NotificationTemplateRepository.class);
        mailSender = mock(MailSender.class);
        service = new NotificationService(repo, new TemplateRenderer(), mailSender);
    }

    @Test
    void rendersSubjectAndBodyThenDelegates() {
        NotificationTemplateEntity tpl = new NotificationTemplateEntity();
        tpl.setTemplateKey("AUTH.VERIFY_EMAIL");
        tpl.setSubjectTemplate("Verify {{who}}");
        tpl.setBodyTemplate("Link: {{link}}");
        when(repo.findById("AUTH.VERIFY_EMAIL")).thenReturn(Optional.of(tpl));

        service.send("AUTH.VERIFY_EMAIL", "alice@example.com",
                Map.of("who", "Alice", "link", "https://ex/verify?t=abc"));

        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailSender).send(eq("alice@example.com"), subj.capture(), body.capture());
        assertEquals("Verify Alice", subj.getValue());
        assertEquals("Link: https://ex/verify?t=abc", body.getValue());
    }

    @Test
    void unknownKeyThrowsTemplateNotFound() {
        when(repo.findById("BOGUS")).thenReturn(Optional.empty());
        assertThrows(TemplateNotFoundException.class,
                () -> service.send("BOGUS", "x@ex", Map.of()));
    }
}
