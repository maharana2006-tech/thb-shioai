package com.multiship.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A4.1 — payload for the "Send test to me" button on /settings/mail. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MailTestSendRequest {

    @NotBlank(message = "recipient required")
    @Email(message = "recipient must be a valid email")
    private String to;

    private String subject;
    private String body;
}
