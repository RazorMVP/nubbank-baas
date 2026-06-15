package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.AccountStatusEventResponse;
import com.nubbank.baas.engine.account.dto.AccountTransitionRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AccountCommandTest {

    @Test
    void command_hasExactlyFreezeUnfreezeClose() {
        assertThat(AccountCommand.values())
            .containsExactly(AccountCommand.FREEZE, AccountCommand.UNFREEZE, AccountCommand.CLOSE);
    }

    @Test
    void transitionRequest_exposesReason() {
        assertThat(new AccountTransitionRequest("legal hold").reason()).isEqualTo("legal hold");
    }

    @Test
    void statusEventResponse_carriesAuditFields() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        AccountStatusEventResponse r =
            new AccountStatusEventResponse(id, "ACTIVE", "FROZEN", "why", "op", at);
        assertThat(r.fromStatus()).isEqualTo("ACTIVE");
        assertThat(r.toStatus()).isEqualTo("FROZEN");
        assertThat(r.changedAt()).isEqualTo(at);
    }
}
