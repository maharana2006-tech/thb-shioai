package com.multiship.backend.service.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

import java.util.Map;
import java.util.Set;

/**
 * A4.3 — AWS SES v2 provider. Builds an {@link SesClient} per-send using
 * the config's access key + secret; region is required (no default).
 *
 * <p>Required config keys: {@code region, from_address}.
 * Secret keys: {@code accessKeyId, secretAccessKey}.
 *
 * <p>Ops teams running on EC2 / EKS with an IAM role should still set
 * dummy secrets here for now (fail-fast on missing) — A4.5+ can add a
 * "use instance profile" toggle that skips StaticCredentialsProvider.
 */
@Slf4j
@Component
public class SesMailProvider implements MailProvider {

    public static final String KIND = "SES";

    public static final String KEY_REGION = "region";
    public static final String KEY_FROM = "from_address";
    public static final String KEY_ACCESS_KEY_ID = "accessKeyId";
    public static final String KEY_SECRET_ACCESS_KEY = "secretAccessKey";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Set<String> requiredConfigKeys() {
        return Set.of(KEY_REGION, KEY_FROM);
    }

    @Override
    public Set<String> secretConfigKeys() {
        return Set.of(KEY_ACCESS_KEY_ID, KEY_SECRET_ACCESS_KEY);
    }

    @Override
    public void send(String to, String subject, String body, Map<String, String> config) {
        String region = require(config, KEY_REGION);
        String from = require(config, KEY_FROM);
        String access = require(config, KEY_ACCESS_KEY_ID);
        String secret = require(config, KEY_SECRET_ACCESS_KEY);

        try (SesClient client = SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(access, secret)))
                .build()) {

            SendEmailRequest req = SendEmailRequest.builder()
                    .destination(Destination.builder().toAddresses(to).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                    .text(Content.builder().data(body).charset("UTF-8").build())
                                    .build())
                            .build())
                    .source(from)
                    .build();

            client.sendEmail(req);
            log.info("[mail:SES] delivered to={} subject={}", to, subject);
        } catch (SesException ex) {
            throw new MailSendException("SES send failed: " + ex.getMessage(), ex);
        }
    }

    private static String require(Map<String, String> config, String key) {
        String v = config.get(key);
        if (v == null || v.isBlank()) {
            throw new MailSendException("SES config missing required key: " + key);
        }
        return v;
    }
}
