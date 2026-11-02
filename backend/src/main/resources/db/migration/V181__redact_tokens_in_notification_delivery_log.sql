-- BUG-161 — rows written before the BUG-133 redaction still hold the live
-- invite / verify-email / reset-password token in the stored body. Apply the
-- same masking NotificationDeliveryLogService.redactSensitiveTokens does on
-- write, so an admin reading the log can no longer lift a one-time link.
UPDATE notification_delivery_log
   SET body = regexp_replace(
                regexp_replace(body, '(/invite/)[A-Za-z0-9._-]{16,}', '\1[redacted]', 'g'),
                '([?&]token=)[A-Za-z0-9._-]{16,}', '\1[redacted]', 'g')
 WHERE body ~ '(/invite/|[?&]token=)[A-Za-z0-9._-]{16,}';
