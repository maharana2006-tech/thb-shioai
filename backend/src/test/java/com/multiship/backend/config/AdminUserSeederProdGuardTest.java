package com.multiship.backend.config;

import com.multiship.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vuln 1 — the seeder must refuse to use its hardcoded 'Admin@123' default
 * when the 'prod' profile is active. Dev / CI keep the convenience.
 */
class AdminUserSeederProdGuardTest {

    @Test
    void prod_profile_without_env_throws_rather_than_seed_default() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Environment env = mock(Environment.class);
        when(users.count()).thenReturn(0L);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});

        AdminUserSeeder seeder = new AdminUserSeeder(users, encoder, env);
        IllegalStateException ex = assertThrows(IllegalStateException.class, seeder::run);
        assertEquals(true, ex.getMessage().contains("ADMIN_SEED_PASSWORD"));
        verify(users, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void non_prod_profile_without_env_falls_back_to_dev_default() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Environment env = mock(Environment.class);
        when(users.count()).thenReturn(0L);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
        when(encoder.encode(any())).thenReturn("hashed");

        AdminUserSeeder seeder = new AdminUserSeeder(users, encoder, env);
        seeder.run();  // must not throw
        verify(users).save(any());
    }

    @Test
    void existing_users_short_circuits_before_the_prod_check() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Environment env = mock(Environment.class);
        when(users.count()).thenReturn(1L);  // installation already has users

        AdminUserSeeder seeder = new AdminUserSeeder(users, encoder, env);
        seeder.run();  // must not throw, even on prod
        verify(users, org.mockito.Mockito.never()).save(any());
    }
}
