package com.multiship.backend.service.mail;

import com.multiship.backend.model.NotificationSubscriptionEntity;
import com.multiship.backend.model.NotificationSubscriptionEntity.NotificationSubscriptionId;
import com.multiship.backend.repository.NotificationSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * A4.5 — per-user × per-template subscription CRUD + the read used by
 * {@link NotificationService} at send time.
 *
 * <p>Default policy: no row → subscribed. Explicit {@code enabled=false} =
 * opted out. Ops workflow at /settings/notifications is entirely opt-out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSubscriptionService {

    private final NotificationSubscriptionRepository repo;

    /**
     * @return TRUE unless the user has an explicit {@code enabled=false} row.
     *   Only meaningful for templates where {@code opt_out_allowed} is TRUE;
     *   callers should skip this check otherwise.
     */
    public boolean isSubscribed(Long userId, String templateKey) {
        return repo.findById(new NotificationSubscriptionId(userId, templateKey))
                .map(NotificationSubscriptionEntity::isEnabled)
                .orElse(true);
    }

    public List<NotificationSubscriptionEntity> listForUser(Long userId) {
        return repo.findAllByIdUserId(userId);
    }

    @Transactional
    public NotificationSubscriptionEntity setForUser(Long userId, String templateKey,
                                                    boolean enabled, String actor) {
        NotificationSubscriptionId key = new NotificationSubscriptionId(userId, templateKey);
        NotificationSubscriptionEntity row = repo.findById(key).orElseGet(() ->
                NotificationSubscriptionEntity.builder().id(key).build());
        row.setEnabled(enabled);
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setUpdatedBy(actor);
        return repo.save(row);
    }
}
