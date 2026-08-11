package com.multiship.backend.service;

import com.multiship.backend.config.AccessScopePolicy;
import com.multiship.backend.config.JwtAuthenticationFilter;
import com.multiship.backend.repository.UserInviteRepository;
import com.multiship.backend.service.mail.MailSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Sprint 50 Tier 0.5 PR E — locks in the Pattern A tenant clamp on
 * UserInviteService#mint. Scoped USER (in the unlikely case they're
 * allowed to invite) cannot mint an invite for a foreign tenant.
 * ADMIN — the only role that reaches this in practice — is an operator
 * and passes through unchanged.
 */
class UserInviteServiceTest {

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void scopedUserCannotMintInviteForForeignTenant() {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        var principal = User.withUsername("acmeuser").password("").authorities(authorities).build();
        var token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(new JwtAuthenticationFilter.AuthDetails("ACME"));
        SecurityContextHolder.getContext().setAuthentication(token);

        UserInviteService service = new UserInviteService(
                mock(UserInviteRepository.class),
                mock(MailSender.class),
                new TenantScopeEnforcer(new AccessScopePolicy(true)));

        assertThrows(AccessDeniedException.class,
                () -> service.mint("bob@example.com", "OTHER", "USER", "acmeuser",
                        "https://app.example"));
    }
}
