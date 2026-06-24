package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.dto.OpenAccountRequest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.makerchecker.MakerCheckerCommandType;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AccountOpenCommandHandlerTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired AccountOpenCommandHandler handler;

    private String provision() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("H").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("h@t.com").build());
        provisioning.provision(org.getId(), schema);
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        return schema;
    }

    @Test
    void declares_correctTypeAndAuthorities() {
        assertThat(handler.commandType()).isEqualTo(MakerCheckerCommandType.ACCOUNT_OPEN);
        assertThat(handler.requiredAuthorityToSubmit()).isEqualTo("CREATE_ACCOUNT");
        assertThat(handler.requiredAuthorityToApprove()).isEqualTo("APPROVE_ACCOUNT");
        assertThat(handler.payloadType()).isEqualTo(OpenAccountRequest.class);
    }

    @Test
    void validate_throwsWhenCustomerMissing() {
        try {
            provision();
            OpenAccountRequest req = new OpenAccountRequest(
                UUID.randomUUID(), "SAVINGS", null, "NGN", BigDecimal.ZERO, BigDecimal.ZERO);
            assertThatThrownBy(() -> handler.validate(req))
                .isInstanceOf(BaasException.class)
                .hasMessageContaining("Customer");
        } finally {
            PartnerContext.clear();
        }
    }
}
