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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A4.2/A4.4 — resolves + renders + delegates + journals every attempt. */
class NotificationServiceTest {

    private NotificationTemplateRepository repo;
    private MailSender mailSender;
    private MailConfigService mailConfig;
    private NotificationDeliveryLogService deliveryLog;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repo = mock(NotificationTemplateRepository.class);
        mailSender = mock(MailSender.class);
        mailConfig = mock(MailConfigService.class);
        deliveryLog = mock(NotificationDeliveryLogService.class);
        when(mailConfig.activeProvider()).thenReturn(Optional.empty());
        service = new NotificationService(repo, new TemplateRenderer(), mailSender, mailConfig, deliveryLog);
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
    void successRecordsSentRow() {
        NotificationTemplateEntity tpl = new NotificationTemplateEntity();
        tpl.setTemplateKey("AUTH.VERIFY_EMAIL");
        tpl.setSubjectTemplate("s");
        tpl.setBodyTemplate("b");
        when(repo.findById("AUTH.VERIFY_EMAIL")).thenReturn(Optional.of(tpl));

        service.send("AUTH.VERIFY_EMAIL", "x@ex", Map.of());

        verify(deliveryLog).recordSent(eq("AUTH.VERIFY_EMAIL"), eq("x@ex"),
                eq("s"), eq("b"), isNull(), isNull(), any(), isNull());
        verify(deliveryLog, never()).recordFailed(any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    void providerFailureRecordsFailedRowAndRethrows() {
        NotificationTemplateEntity tpl = new NotificationTemplateEntity();
        tpl.setTemplateKey("AUTH.VERIFY_EMAIL");
        tpl.setSubjectTemplate("s");
        tpl.setBodyTemplate("b");
        when(repo.findById("AUTH.VERIFY_EMAIL")).thenReturn(Optional.of(tpl));
        doThrow(new MailSendException("boom")).when(mailSender).send(any(), any(), any());

        assertThrows(MailSendException.class,
                () -> service.send("AUTH.VERIFY_EMAIL", "x@ex", Map.of()));

        verify(deliveryLog).recordFailed(eq("AUTH.VERIFY_EMAIL"), eq("x@ex"),
                eq("s"), eq("b"), isNull(), isNull(), any(), eq("boom"), isNull());
        verify(deliveryLog, never()).recordSent(any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void resendBypassesTemplateAndLinksRetryOfId() {
        service.resend("AUTH.VERIFY_EMAIL", "x@ex", "already-rendered subject", "already-rendered body", 42L);

        verify(mailSender).send("x@ex", "already-rendered subject", "already-rendered body");
        verify(deliveryLog).recordSent(eq("AUTH.VERIFY_EMAIL"), eq("x@ex"),
                eq("already-rendered subject"), eq("already-rendered body"),
                isNull(), isNull(), any(), eq(42L));
        verify(repo, never()).findById(any());
    }

    @Test
    void unknownKeyThrowsTemplateNotFound() {
        when(repo.findById("BOGUS")).thenReturn(Optional.empty());
        assertThrows(TemplateNotFoundException.class,
                () -> service.send("BOGUS", "x@ex", Map.of()));
    }
}
