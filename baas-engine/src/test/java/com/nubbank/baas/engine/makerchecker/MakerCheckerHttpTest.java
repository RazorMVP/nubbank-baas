package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.AccountRepository;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class MakerCheckerHttpTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository partnerUserRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired MakerCheckerConfigRepository configRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired CustomerRepository customerRepo;

    private PartnerOrganization org;
    private String schema;

    private void provision() {
        schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Http").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("http@t.com").build());
        provisioning.provision(org.getId(), schema);
    }

    /** Persist a partner user (DB-generated id) + their role, then issue a JWT whose subject is that id. */
    private String tokenFor(String roleName) {
        UUID userId = partnerUserRepo.save(PartnerUser.builder().organization(org)
            .email(UUID.randomUUID() + "@t.com").passwordHash("x").role(roleName).active(true).build()).getId();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { userRoleRepo.save(UserRole.builder().userId(userId)
            .role(roleRepo.findByName(roleName).orElseThrow()).build()); }
        finally { PartnerContext.clear(); }
        return partnerJwtService.issue(userId.toString(), userId + "@t.com", roleName,
            org.getId().toString(), org.getName(), schema, "PRO", "PRODUCTION");
    }

    private UUID seedCustomer() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { return customerRepo.save(Customer.builder()
            .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace")
            .emailEncrypted("ada@t.com").phoneEncrypted("0800").build()).getId(); }
        finally { PartnerContext.clear(); }
    }

    private void enableAccountOpen() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { MakerCheckerConfig c = configRepo.findById("ACCOUNT_OPEN").orElseThrow();
              c.setEnabled(true); configRepo.save(c); }
        finally { PartnerContext.clear(); }
    }

    private HttpHeaders bearer(String jwt) {
        HttpHeaders h = new HttpHeaders(); h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void guardedPostAccounts_returns202_thenApproveCreatesAccount() {
        provision();
        String makerJwt = tokenFor(PartnerRoles.MAKER);
        String approverJwt = tokenFor(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();

        String body = "{\"customerId\":\"" + cust + "\",\"accountTypeLabel\":\"SAVINGS\",\"currencyCode\":\"NGN\"}";
        ResponseEntity<Map> create = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, bearer(makerJwt)), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Map data = (Map) create.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PENDING");
        String taskId = (String) data.get("id");

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isZero(); } finally { PartnerContext.clear(); }

        ResponseEntity<Map> selfApprove = restTemplate.exchange("/baas/v1/maker-checker/tasks/" + taskId + "/approve",
            HttpMethod.POST, new HttpEntity<>(bearer(makerJwt)), Map.class);
        assertThat(selfApprove.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> approve = restTemplate.exchange("/baas/v1/maker-checker/tasks/" + taskId + "/approve",
            HttpMethod.POST, new HttpEntity<>(bearer(approverJwt)), Map.class);
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isEqualTo(1); } finally { PartnerContext.clear(); }
    }

    @Test
    void unguardedPostAccounts_returns201_directly() {
        provision();
        String makerJwt = tokenFor(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        String body = "{\"customerId\":\"" + cust + "\",\"accountTypeLabel\":\"SAVINGS\",\"currencyCode\":\"NGN\"}";
        ResponseEntity<Map> create = restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, bearer(makerJwt)), Map.class);
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Map) create.getBody().get("data")).containsKey("accountNumber");
    }

    @Test
    void inbox_listsPendingTask() {
        provision();
        String makerJwt = tokenFor(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        String body = "{\"customerId\":\"" + cust + "\",\"accountTypeLabel\":\"SAVINGS\",\"currencyCode\":\"NGN\"}";
        restTemplate.exchange("/baas/v1/accounts", HttpMethod.POST,
            new HttpEntity<>(body, bearer(makerJwt)), Map.class);

        ResponseEntity<Map> list = restTemplate.exchange("/baas/v1/maker-checker/tasks?status=PENDING",
            HttpMethod.GET, new HttpEntity<>(bearer(makerJwt)), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> tasks = (List<?>) list.getBody().get("data");
        assertThat(tasks).hasSize(1);
    }

    @Test
    void inbox_unknownType_returns400() {
        provision();
        String makerJwt = tokenFor(PartnerRoles.MAKER);
        ResponseEntity<Map> r = restTemplate.exchange("/baas/v1/maker-checker/tasks?type=BOGUS_CMD",
            HttpMethod.GET, new HttpEntity<>(bearer(makerJwt)), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
