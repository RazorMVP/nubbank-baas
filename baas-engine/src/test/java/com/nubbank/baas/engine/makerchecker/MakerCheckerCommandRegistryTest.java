package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.common.BaasException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class MakerCheckerCommandRegistryTest {

    private MakerCheckerCommandHandler stub(String type) {
        return new MakerCheckerCommandHandler() {
            public String commandType() { return type; }
            public String requiredAuthorityToSubmit() { return "CREATE_X"; }
            public String requiredAuthorityToApprove() { return "APPROVE_X"; }
            public Class<?> payloadType() { return String.class; }
            public void validate(Object payload) { }
            public UUID execute(Object payload) { return UUID.randomUUID(); }
        };
    }

    @Test
    void require_resolvesKnownHandler() {
        var registry = new MakerCheckerCommandRegistry(List.of(stub("ACCOUNT_OPEN")));
        assertThat(registry.require("ACCOUNT_OPEN").commandType()).isEqualTo("ACCOUNT_OPEN");
    }

    @Test
    void require_throwsBadRequest_forUnknownType() {
        var registry = new MakerCheckerCommandRegistry(List.of(stub("ACCOUNT_OPEN")));
        assertThatThrownBy(() -> registry.require("WIRE_TRANSFER"))
            .isInstanceOf(BaasException.class)
            .hasMessageContaining("WIRE_TRANSFER");
    }

    @Test
    void find_returnsHandlerOrEmpty() {
        var registry = new MakerCheckerCommandRegistry(List.of(stub("ACCOUNT_OPEN")));
        assertThat(registry.find("ACCOUNT_OPEN")).isPresent();
        assertThat(registry.find("WIRE_TRANSFER")).isEmpty();
    }

    @Test
    void resolvesAmongMultipleHandlers() {
        var registry = new MakerCheckerCommandRegistry(List.of(stub("ACCOUNT_OPEN"), stub("WIRE_TRANSFER")));
        assertThat(registry.require("ACCOUNT_OPEN").commandType()).isEqualTo("ACCOUNT_OPEN");
        assertThat(registry.require("WIRE_TRANSFER").commandType()).isEqualTo("WIRE_TRANSFER");
    }

    @Test
    void duplicateCommandType_failsFastNamingTheType() {
        assertThatThrownBy(() -> new MakerCheckerCommandRegistry(List.of(stub("ACCOUNT_OPEN"), stub("ACCOUNT_OPEN"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ACCOUNT_OPEN");
    }
}
