package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.AccountRepository;
import com.nubbank.baas.engine.account.dto.OpenAccountRequest;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.role.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MakerCheckerTaskServiceTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired PartnerUserRepository partnerUserRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired MakerCheckerTaskService service;
    @Autowired MakerCheckerConfigRepository configRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired CustomerRepository customerRepo;
    @Autowired TransactionTemplate txTemplate;

    private PartnerOrganization org;
    private String schema;

    private void provision() {
        schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        org = orgRepo.save(PartnerOrganization.builder()
            .name("Svc").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("s@t.com").build());
        provisioning.provision(org.getId(), schema);
    }

    private UUID makeUser(String roleName) {
        UUID id = partnerUserRepo.save(PartnerUser.builder()
            .organization(org).email(UUID.randomUUID() + "@t.com")
            .passwordHash("x").role(roleName).active(true).build()).getId();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            Role r = roleRepo.findByName(roleName).orElseThrow();
            userRoleRepo.save(UserRole.builder().userId(id).role(r).build());
        } finally { PartnerContext.clear(); }
        return id;
    }

    private void actAs(UUID userId, List<String> authorities, Runnable body) {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", userId.toString()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            userId.toString(), null, authorities.stream().map(SimpleGrantedAuthority::new).toList()));
        try { body.run(); }
        finally { SecurityContextHolder.clearContext(); PartnerContext.clear(); }
    }

    private UUID seedCustomer() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            return customerRepo.save(Customer.builder()
                .firstNameEncrypted("Ada").lastNameEncrypted("Lovelace")
                .emailEncrypted("ada@t.com").phoneEncrypted("0800").build()).getId();
        } finally { PartnerContext.clear(); }
    }

    private void enableAccountOpen() {
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            MakerCheckerConfig c = configRepo.findById("ACCOUNT_OPEN").orElseThrow();
            c.setEnabled(true);
            configRepo.save(c);
        } finally { PartnerContext.clear(); }
    }

    private OpenAccountRequest req(UUID customerId) {
        return new OpenAccountRequest(customerId, "SAVINGS", null, "NGN", BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void notGuarded_whenConfigDisabled_returnsEmpty() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            assertThat(service.submitIfGuarded("ACCOUNT_OPEN", req(cust))).isEmpty());
    }

    @Test
    void notGuarded_whenSandbox_returnsEmpty() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "SANDBOX", "SANDBOX", "JWT", maker.toString()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            maker.toString(), null, List.of(new SimpleGrantedAuthority("CREATE_ACCOUNT"))));
        try { assertThat(service.submitIfGuarded("ACCOUNT_OPEN", req(cust))).isEmpty(); }
        finally { SecurityContextHolder.clearContext(); PartnerContext.clear(); }
    }

    @Test
    void guarded_apiKeyMaker_isRejected_noTaskPersisted() {
        provision();
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID apiKeyId = UUID.randomUUID();
        // API_KEY auth mode → caller is not a partner user → ineligible to be a maker.
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "API_KEY", apiKeyId.toString()));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            apiKeyId.toString(), null, List.of(new SimpleGrantedAuthority("CREATE_ACCOUNT"))));
        try {
            assertThatThrownBy(() -> service.submitIfGuarded("ACCOUNT_OPEN", req(cust)))
                .isInstanceOf(BaasException.class).hasMessageContaining("partner user");
        } finally { SecurityContextHolder.clearContext(); PartnerContext.clear(); }
        // nothing was persisted
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(service.list(TaskStatus.PENDING, null)).isEmpty(); }
        finally { PartnerContext.clear(); }
    }

    @Test
    void guarded_persistsPendingTask_noAccountCreated() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        actAs(maker, List.of("CREATE_ACCOUNT"), () -> {
            Optional<MakerCheckerTask> t = service.submitIfGuarded("ACCOUNT_OPEN", req(cust));
            assertThat(t).isPresent();
            assertThat(t.get().getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(t.get().getMadeBy()).isEqualTo(maker);
            assertThat(accountRepo.count()).isZero();
        });
    }

    @Test
    void approve_createsAccount_attributedToMaker() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();

        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());

        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () -> {
            MakerCheckerTask done = service.approve(taskId[0]);
            assertThat(done.getStatus()).isEqualTo(TaskStatus.APPROVED);
            assertThat(done.getCheckedBy()).isEqualTo(approver);
            assertThat(done.getResultId()).isNotNull();
        });
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isEqualTo(1); }
        finally { PartnerContext.clear(); }
    }

    @Test
    void approve_byMaker_isForbidden_fourEyes() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(maker, List.of("CREATE_ACCOUNT", "APPROVE_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("maker"));
    }

    @Test
    void approve_withoutApproveAuthority_isForbidden() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID other = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(other, List.of("CREATE_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("APPROVE_ACCOUNT"));
    }

    @Test
    void approve_revokedMaker_isBlocked() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { txTemplate.executeWithoutResult(s -> userRoleRepo.deleteByUserId(maker)); } finally { PartnerContext.clear(); }
        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("maker"));
    }

    @Test
    void approve_deactivatedMaker_isBlocked() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        // Deactivate the maker after submit (role intact → only the active=false branch trips).
        PartnerUser u = partnerUserRepo.findById(maker).orElseThrow();
        u.setActive(false);
        partnerUserRepo.save(u);
        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("maker"));
    }

    @Test
    void approve_drift_customerDeleted_rollsBack_taskStaysPending() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { customerRepo.deleteById(cust); } finally { PartnerContext.clear(); }
        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.approve(taskId[0])).isInstanceOf(BaasException.class));
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            assertThat(service.get(taskId[0]).getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(accountRepo.count()).isZero();
        } finally { PartnerContext.clear(); }
    }

    @Test
    void approve_twice_secondIsConflict_oneAccount() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(approver, List.of("APPROVE_ACCOUNT", "READ_ACCOUNT"), () -> {
            service.approve(taskId[0]);
            assertThatThrownBy(() -> service.approve(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("PENDING");
        });
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isEqualTo(1); } finally { PartnerContext.clear(); }
    }

    @Test
    void reject_marksRejected_noAccount() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID approver = makeUser(PartnerRoles.APPROVER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(approver, List.of("APPROVE_ACCOUNT"), () -> {
            MakerCheckerTask t = service.reject(taskId[0], "not now");
            assertThat(t.getStatus()).isEqualTo(TaskStatus.REJECTED);
            assertThat(t.getRejectReason()).isEqualTo("not now");
        });
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try { assertThat(accountRepo.count()).isZero(); } finally { PartnerContext.clear(); }
    }

    @Test
    void withdraw_byMakerOk_byOtherForbidden() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID other = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        actAs(other, List.of("CREATE_ACCOUNT"), () ->
            assertThatThrownBy(() -> service.withdraw(taskId[0]))
                .isInstanceOf(BaasException.class).hasMessageContaining("maker"));
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            assertThat(service.withdraw(taskId[0]).getStatus()).isEqualTo(TaskStatus.WITHDRAWN));
    }

    @Test
    void enableConfig_withNoEligibleApprover_isConflict() {
        provision();
        UUID admin = UUID.randomUUID();
        actAs(admin, List.of("MANAGE_MAKER_CHECKER"), () ->
            assertThatThrownBy(() -> service.updateConfig("ACCOUNT_OPEN", true))
                .isInstanceOf(BaasException.class).hasMessageContaining("NO_ELIGIBLE_APPROVER"));
    }

    @Test
    void enableConfig_withApproverPresent_succeeds() {
        provision();
        makeUser(PartnerRoles.APPROVER);
        UUID admin = UUID.randomUUID();
        actAs(admin, List.of("MANAGE_MAKER_CHECKER"), () ->
            assertThat(service.updateConfig("ACCOUNT_OPEN", true).isEnabled()).isTrue());
    }

    @Test
    void dryRun_flagsNowInvalidTask() {
        provision();
        UUID maker = makeUser(PartnerRoles.MAKER);
        UUID cust = seedCustomer();
        enableAccountOpen();
        UUID[] taskId = new UUID[1];
        actAs(maker, List.of("CREATE_ACCOUNT"), () ->
            taskId[0] = service.submitIfGuarded("ACCOUNT_OPEN", req(cust)).orElseThrow().getId());
        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            assertThat(service.dryRunInvalidReason(service.get(taskId[0]))).isNull();
            customerRepo.deleteById(cust);
            assertThat(service.dryRunInvalidReason(service.get(taskId[0]))).contains("Customer");
        } finally { PartnerContext.clear(); }
    }
}
