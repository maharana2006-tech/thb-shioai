-- A4.2 — DB-stored email templates rendered via Handlebars.
--
-- Every call site that used to inline "Subject" + "Body\n\n" concatenation
-- now resolves a template by key and passes a variables map. The three
-- seed rows below cover the three send-sites present at A4.2 (invite,
-- verify-email, password-reset); more can be inserted without a code
-- change as new events are added.
--
-- Handlebars syntax: {{var}} for interpolation, {{#if x}}…{{/if}} for
-- conditionals, {{#each xs}}…{{/each}} for loops. Templates are cached
-- in-memory after first render.

CREATE TABLE notification_template (
    template_key      VARCHAR(60)  PRIMARY KEY,
    description       VARCHAR(200),
    subject_template  TEXT         NOT NULL,
    body_template     TEXT         NOT NULL,
    updated_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by        VARCHAR(120)
);

INSERT INTO notification_template (template_key, description, subject_template, body_template) VALUES
    (
        'AUTH.VERIFY_EMAIL',
        'Signup email-verification link. Vars: verifyLink, ttlHours.',
        'Verify your Multiship account',
        'Click to verify (expires in {{ttlHours}} hours):
{{verifyLink}}'
    ),
    (
        'AUTH.PASSWORD_RESET',
        'Password-reset link on forgot-password. Vars: resetLink, ttlMinutes.',
        'Password reset request',
        'A password reset was requested for your Multiship account.

Reset your password (link expires in {{ttlMinutes}} minutes):
{{resetLink}}

If you didn''t request this, ignore this email — your password will not change.'
    ),
    (
        'AUTH.USER_INVITE',
        'New-user invitation. Vars: acceptLink, role, clientCode, ttlDays, invitedBy.',
        'You''ve been invited to Multiship',
        'You''ve been invited to join Multiship as {{role}} for client {{clientCode}}.

Accept the invite (expires in {{ttlDays}} days):
{{acceptLink}}

{{#if invitedBy}}Invited by: {{invitedBy}}{{/if}}'
    );
