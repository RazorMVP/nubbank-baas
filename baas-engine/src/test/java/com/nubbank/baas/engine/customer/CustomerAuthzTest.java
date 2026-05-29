package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.auth.keycloak.OperatorJwtDecoderFactory;
import com.nubbank.baas.engine.auth.keycloak.TestJwks;
import com.nubbank.baas.engine.partner.PartnerEnvironment;
import com.nubbank.baas.engine.partner.PartnerOrganization;
import com.nubbank.baas.engine.partner.PartnerOrganizationRepository;
import com.nubbank.baas.engine.partner.PartnerStatus;
import com.nubbank.baas.engine.partner.PartnerTier;
import com.nubbank.baas.engine.role.Permission;
import com.nubbank.baas.engine.role.PermissionRepository;
import com.nubbank.baas.engine.role.UserRole;
import com.nubbank.baas.engine.role.UserRoleRepository;
import com.nubbank.baas.engine.role.Role;
import com.nubbank.baas.engine.role.RoleRepository;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerAuthzTest extends AbstractIntegrationTest {

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

    private String issuer;
    private PartnerOrganization org;

    @BeforeEach
    void setup() {
        issuer = "https://auth.nubbank.test/realms/baas-partner-" + UUID.randomUUID();
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Authz Co").status(PartnerStatus.BASIC).tier(PartnerTier.BASIC)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema)
            .keycloakIssuer(issuer).contactEmail("z@test.com").build());
        provisioning.provision(org.getId(), schema);
    }

    private HttpHeaders bearer(String sub) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(JWKS.sign(issuer, sub, 300));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void operatorWithReadCustomer_canList_butCannotCreate() {
        UUID sub = UUID.randomUUID();

        // Direct repo calls require the tenant schema to be set in the ThreadLocal.
        com.nubbank.baas.engine.tenant.PartnerContext.set(
            new com.nubbank.baas.engine.tenant.PartnerContext(
                org.getId().toString(), org.getSchemaName(),
                "BASIC", "PRODUCTION", "OPERATOR_JWT", sub.toString()));
        try {
            Permission read = permRepo.findAll().stream()
                .filter(p -> p.getCode().equals("READ_CUSTOMER")).findFirst().orElseThrow();
            Role viewer = roleRepo.save(Role.builder().name("VIEWER").permissions(Set.of(read)).build());
            userRoleRepo.save(UserRole.builder().userId(sub).role(viewer).build());
        } finally {
            com.nubbank.baas.engine.tenant.PartnerContext.clear();
        }

        // HTTP calls go through PartnerContextFilter which sets PartnerContext from the JWT.
        ResponseEntity<Map> listResp = restTemplate.exchange("/baas/v1/customers",
            HttpMethod.GET, new HttpEntity<>(bearer(sub.toString())), Map.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/customers",
            HttpMethod.POST, new HttpEntity<>(Map.of("firstName","A","lastName","B",
                "email","a@b.com","phone","123","dateOfBirth","1990-01-01","nationalId","X1"),
                bearer(sub.toString())), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
