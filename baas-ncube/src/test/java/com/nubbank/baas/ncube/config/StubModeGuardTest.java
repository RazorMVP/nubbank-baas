package com.nubbank.baas.ncube.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StubModeGuardTest {

    @Test
    void stub_mode_with_prod_profile_throws() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        StubModeGuard guard = new StubModeGuard(env, false);
        assertThatThrownBy(guard::onStartup)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stub mode")
            .hasMessageContaining("prod");
    }

    @Test
    void live_mode_with_prod_profile_does_not_throw() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        assertThatNoException().isThrownBy(() -> new StubModeGuard(env, true).onStartup());
    }

    @Test
    void stub_mode_with_dev_profile_logs_warn_but_does_not_throw() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev"});
        assertThatNoException().isThrownBy(() -> new StubModeGuard(env, false).onStartup());
    }

    @Test
    void stub_mode_with_uppercase_PROD_profile_throws() {
        // Case-insensitive: "PROD" must trip the guard — ops slips include casing typos.
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"PROD"});
        StubModeGuard guard = new StubModeGuard(env, false);
        assertThatThrownBy(guard::onStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stub_mode_with_production_profile_throws() {
        // Prefix match: "production" must trip the guard.
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"production"});
        StubModeGuard guard = new StubModeGuard(env, false);
        assertThatThrownBy(guard::onStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stub_mode_with_prod_eu_profile_throws() {
        // Prefix match: regional production profiles like "prod-eu" must trip the guard.
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod-eu"});
        StubModeGuard guard = new StubModeGuard(env, false);
        assertThatThrownBy(guard::onStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stub_mode_with_dev_and_prod_in_active_profiles_throws() {
        // Multi-profile case: "prod" anywhere in the active profiles trips the guard.
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"dev", "prod"});
        StubModeGuard guard = new StubModeGuard(env, false);
        assertThatThrownBy(guard::onStartup).isInstanceOf(IllegalStateException.class);
    }
}
