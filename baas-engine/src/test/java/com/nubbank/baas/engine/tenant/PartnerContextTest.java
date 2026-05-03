package com.nubbank.baas.engine.tenant;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PartnerContextTest {

    @Test
    void threadLocal_setAndGet_returnsCorrectContext() {
        var ctx = new PartnerContext("partner-id-1", "partner_abc123", "PRO", "PRODUCTION", "API_KEY", null);
        PartnerContext.set(ctx);
        assertThat(PartnerContext.get()).isEqualTo(ctx);
        assertThat(PartnerContext.get().schemaName()).isEqualTo("partner_abc123");
        PartnerContext.clear();
    }

    @Test
    void clear_removesContext() {
        PartnerContext.set(new PartnerContext("id", "schema", "BASIC", "SANDBOX", "JWT", null));
        PartnerContext.clear();
        assertThat(PartnerContext.get()).isNull();
    }

    @Test
    void get_withoutSet_returnsNull() {
        PartnerContext.clear();
        assertThat(PartnerContext.get()).isNull();
    }

    @Test
    void isSandbox_returnsCorrectly() {
        var sandboxCtx = new PartnerContext("id", "schema", "SANDBOX", "SANDBOX", "JWT", null);
        var prodCtx = new PartnerContext("id", "schema", "BASIC", "PRODUCTION", "JWT", null);
        assertThat(sandboxCtx.isSandbox()).isTrue();
        assertThat(prodCtx.isSandbox()).isFalse();
    }
}
