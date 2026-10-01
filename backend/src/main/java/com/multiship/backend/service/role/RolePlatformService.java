package com.multiship.backend.service.role;

import com.multiship.backend.model.RoleEntity;
import com.multiship.backend.repository.RoleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * V115 — in-memory cache over the {@code role} table.
 *
 * <p>Bootstrap defaults mirror the pre-V115 Set.of literals so first
 * boot after the migration and any DB outage still returns correct
 * answers. Admin add-row via SQL / future UI promotes a role to
 * is_invitable without a redeploy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RolePlatformService {

    /** Pre-V115 behaviour: all three known codes are valid; USER + TENANT
     *  are invitable, ADMIN is not. */
    static final Set<String> BOOTSTRAP_ALL = Set.of("USER", "TENANT", "ADMIN");
    static final Set<String> BOOTSTRAP_INVITABLE = Set.of("USER", "TENANT");

    private final RoleRepository repo;

    private volatile Set<String> allCodes = BOOTSTRAP_ALL;
    private volatile Set<String> invitableCodes = BOOTSTRAP_INVITABLE;

    @PostConstruct
    void initIfAvailable() {
        try { reload(); } catch (Exception ignore) { /* ARE will retry */ }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadOnReady() {
        reload();
    }

    public void reload() {
        try {
            var rows = repo.findAll();
            if (rows.isEmpty()) {
                log.info("role: no rows seeded; keeping bootstrap defaults");
                return;
            }
            Set<String> all = rows.stream()
                    .map(r -> r.getCode().trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            Set<String> invitable = rows.stream()
                    .filter(r -> Boolean.TRUE.equals(r.getIsInvitable()))
                    .map(r -> r.getCode().trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            allCodes = all;
            invitableCodes = invitable;
            log.info("role: loaded {} role(s), {} invitable", all.size(), invitable.size());
        } catch (Exception ex) {
            log.warn("role: could not load; keeping prior sets: {}", ex.getMessage());
        }
    }

    public boolean isValidRole(String code) {
        return code != null && allCodes.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    public boolean isInvitableRole(String code) {
        return code != null && invitableCodes.contains(code.trim().toUpperCase(Locale.ROOT));
    }

    public Set<String> allRoles() { return allCodes; }
    public Set<String> invitableRoles() { return invitableCodes; }

    // Test seams.
    void setAllForTest(Set<String> s) { this.allCodes = s; }
    void setInvitableForTest(Set<String> s) { this.invitableCodes = s; }
}
