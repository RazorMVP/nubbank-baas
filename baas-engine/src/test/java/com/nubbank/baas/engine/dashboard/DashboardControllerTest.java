package com.nubbank.baas.engine.dashboard;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.Account;
import com.nubbank.baas.engine.account.AccountRepository;
import com.nubbank.baas.engine.account.AccountStatus;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.customer.KycStatus;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * DEF-1C-29 — {@code GET /baas/v1/dashboard/summary} returns the operations-console tiles,
 * scoped to the authenticated partner's schema. Card count is best-effort: card-service is
 * not running in engine tests, so {@code cardsIssued} degrades to null without failing.
 */
class DashboardControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountRepository accountRepo;

    private String jwt;
    private String schemaName;
    private UUID orgId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = PartnerOrganization.builder()
            .name("Dashboard Test Partner").status(PartnerStatus.SANDBOX)
            .tier(PartnerTier.SANDBOX).environment(PartnerEnvironment.SANDBOX)
            .schemaName(schemaName).contactEmail("dash@partner.com").build();
        org = orgRepo.save(org);
        orgId = org.getId();
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "dash@partner.com", "PARTNER_ADMIN",
            org.getId().toString(), "Dashboard Test Partner", schemaName, "SANDBOX", "SANDBOX");

        seedTenantData();
    }

    /** Seeds 2 customers (1 KYC-pending) + 1 active account (balance 1000) in the partner schema. */
    private void seedTenantData() {
        try {
            PartnerContext.set(new PartnerContext(
                orgId.toString(), schemaName, "SANDBOX", "SANDBOX", "JWT", "seed"));
            Customer pending = customerRepo.save(Customer.builder()
                .firstNameEncrypted("Ada").lastNameEncrypted("Pending")
                .kycStatus(KycStatus.PENDING_KYC).build());
            customerRepo.save(Customer.builder()
                .firstNameEncrypted("Bola").lastNameEncrypted("Active")
                .kycStatus(KycStatus.ACTIVE).build());
            accountRepo.save(Account.builder()
                .customer(pending).accountNumber("ACC-0000001")
                .status(AccountStatus.ACTIVE)
                .balance(new BigDecimal("1000.0000"))
                .availableBalance(new BigDecimal("1000.0000"))
                .minimumBalance(BigDecimal.ZERO).allowOverdraft(false)
                .currencyCode("NGN").build());
        } finally {
            PartnerContext.clear();
        }
    }

    @Test
    void summary_returnsTenantScopedTiles_cardsNullWhenCardServiceDown() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);

        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/dashboard/summary", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");

        assertThat(((Number) data.get("totalCustomers")).longValue()).isEqualTo(2);
        assertThat(((Number) data.get("kycPendingCustomers")).longValue()).isEqualTo(1);
        assertThat(((Number) data.get("totalAccounts")).longValue()).isEqualTo(1);
        assertThat(((Number) data.get("activeAccounts")).longValue()).isEqualTo(1);
        assertThat(new BigDecimal(data.get("totalDeposits").toString()))
            .isEqualByComparingTo("1000.0000");
        assertThat(((Number) data.get("totalLoans")).longValue()).isEqualTo(0);
        assertThat(((Number) data.get("activeLoans")).longValue()).isEqualTo(0);
        // card-service unreachable in tests → graceful null (key absent or null)
        assertThat(data.get("cardsIssued")).isNull();
    }

    @Test
    void summary_unauthenticated_returns401() {
        ResponseEntity<Map> response = restTemplate.exchange(
            "/baas/v1/dashboard/summary", HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
