package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.keycloak.OperatorJwtDecoderFactory;
import com.nubbank.baas.engine.auth.keycloak.TestJwks;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AccountAuthzTest extends AbstractIntegrationTest {

    static final TestJwks JWKS = new TestJwks();

    @TestConfiguration
    static class StubDecoderConfig {
        @Bean @Primary OperatorJwtDecoderFactory stubFactory() { return issuer -> JWKS.decoder(); }
    }

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roleRepo;
    @Autowired PermissionRepository permRepo;
    @Autowired UserRoleRepository userRoleRepo;
    @Autowired CustomerRepository customerRepo;

    private String issuer;
    private PartnerOrganization org;
    private UUID accountId;

    @BeforeEach
    void setup() {
        issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Acct Authz").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("z@test.com").build());
        provisioning.provision(org.getId(), schema);

        // Seed a customer + account directly in the tenant schema for the read/write targets.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
            "BASIC", "PRODUCTION", "TEST", null));
        UUID customerId = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Grace").lastNameEncrypted("Hopper").build()).getId();
        Account a = Account.builder()
            .customer(customerRepo.findById(customerId).orElseThrow())
            .accountNumber("0000000001").accountTypeLabel("Savings")
            .status(AccountStatus.ACTIVE).balance(java.math.BigDecimal.ZERO)
            .availableBalance(java.math.BigDecimal.ZERO).minimumBalance(java.math.BigDecimal.ZERO)
            .currencyCode("NGN").build();
        // accountRepo not autowired here on purpose — use the entity manager via the repo from context.
        accountId = saveAccount(schema, a);
        PartnerContext.clear();
    }

    // Persist the account through its repository inside the active PartnerContext.
    @Autowired AccountRepository accountRepo;
    private UUID saveAccount(String schema, Account a) {
        return accountRepo.save(a).getId();
    }

    private HttpHeaders bearer(String sub) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(JWKS.sign(issuer, sub, 300));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void operatorWithReadAccount_canList_butCannotFreeze() {
        UUID sub = UUID.randomUUID();

        PartnerContext.set(new PartnerContext(org.getId().toString(), org.getSchemaName(),
            "BASIC", "PRODUCTION", "OPERATOR_JWT", sub.toString()));
        try {
            Permission read = permRepo.findAll().stream()
                .filter(p -> p.getCode().equals("READ_ACCOUNT")).findFirst().orElseThrow();
            Role viewer = roleRepo.save(Role.builder().name("ACCT_VIEWER")
                .permissions(Set.of(read)).build());
            userRoleRepo.save(UserRole.builder().userId(sub).role(viewer).build());
        } finally {
            PartnerContext.clear();
        }

        // READ_ACCOUNT granted → list is 200
        ResponseEntity<Map> listResp = restTemplate.exchange("/baas/v1/accounts",
            HttpMethod.GET, new HttpEntity<>(bearer(sub.toString())), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // UPDATE_ACCOUNT NOT granted → freeze is 403
        ResponseEntity<Map> freezeResp = restTemplate.exchange(
            "/baas/v1/accounts/" + accountId + "/freeze", HttpMethod.POST,
            new HttpEntity<>(Map.of("reason", "hold"), bearer(sub.toString())), Map.class);
        assertThat(freezeResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        List<Map<String,Object>> errors =
            (List<Map<String,Object>>) freezeResp.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0).get("code")).isEqualTo("ACCESS_DENIED");
    }
}
