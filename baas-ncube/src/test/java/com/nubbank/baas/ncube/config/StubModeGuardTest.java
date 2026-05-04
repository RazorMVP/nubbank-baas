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
}
