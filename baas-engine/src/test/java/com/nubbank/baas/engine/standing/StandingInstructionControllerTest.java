package com.nubbank.baas.engine.standing;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.auth.PartnerJwtService;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StandingInstructionControllerTest extends AbstractIntegrationTest {

    @Autowired private PartnerJwtService jwtService;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountRepository accountRepo;

    private String jwt;
    private UUID customerId;
    private UUID sourceAccId;
    private UUID destAccId;

    @BeforeEach
    void setup() {
        String schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("SI Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("si@test.com").build());
        provisioningService.provision(org.getId(), schemaName);
        jwt = jwtService.issue(UUID.randomUUID().toString(), "si@test.com",
            "PARTNER_ADMIN", org.getId().toString(), "SI Test",
            schemaName, "SANDBOX", "SANDBOX");

        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        var customer = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Taiwo").lastNameEncrypted("Lawal").build());
        customerId = customer.getId();
        sourceAccId = accountRepo.save(Account.builder().customer(customer)
            .accountNumber("0580001111").balance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO).currencyCode("NGN")
            .minimumBalance(BigDecimal.ZERO).build()).getId();
        destAccId = accountRepo.save(Account.builder().customer(customer)
            .accountNumber("0580002222").balance(BigDecimal.ZERO)
            .availableBalance(BigDecimal.ZERO).currencyCode("NGN")
            .minimumBalance(BigDecimal.ZERO).build()).getId();
        PartnerContext.clear();
    }

    @Test
    void createStandingInstruction_disable_enable() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> createResp = restTemplate.exchange("/baas/v1/standinginstructions",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "customerId", customerId.toString(),
                "sourceAccountId", sourceAccId.toString(),
                "destinationAccountId", destAccId.toString(),
                "name", "Monthly Savings",
                "instructionType", "FIXED",
                "amount", 5000.0,
                "recurrenceFrequency", "MONTHS",
                "recurrenceInterval", 1
            ), h), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = ((Map<?, ?>) createResp.getBody().get("data")).get("id").toString();
        assertThat(((Map<?, ?>) createResp.getBody().get("data")).get("status")).isEqualTo("ACTIVE");

        ResponseEntity<Map> disableResp = restTemplate.exchange(
            "/baas/v1/standinginstructions/" + id + "?command=disable",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) disableResp.getBody().get("data")).get("status")).isEqualTo("DISABLED");

        ResponseEntity<Map> enableResp = restTemplate.exchange(
            "/baas/v1/standinginstructions/" + id + "?command=enable",
            HttpMethod.POST, new HttpEntity<>(h), Map.class);
        assertThat(((Map<?, ?>) enableResp.getBody().get("data")).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void addBeneficiary_listAndDelete() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(jwt); h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> addResp = restTemplate.exchange(
            "/baas/v1/clients/" + customerId + "/beneficiaries",
            HttpMethod.POST,
            new HttpEntity<>(Map.of(
                "accountNumber", "0123456789",
                "accountName", "John Doe",
                "bankCode", "058",
                "bankName", "GTBank"
            ), h), Map.class);
        assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String benId = ((Map<?, ?>) addResp.getBody().get("data")).get("id").toString();

        ResponseEntity<Map> listResp = restTemplate.exchange(
            "/baas/v1/clients/" + customerId + "/beneficiaries",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat((List<?>) ((Map<?, ?>) listResp.getBody().get("data")).get("content"))
            .hasSize(1);

        restTemplate.exchange(
            "/baas/v1/clients/" + customerId + "/beneficiaries/" + benId,
            HttpMethod.DELETE, new HttpEntity<>(h), Map.class);

        ResponseEntity<Map> afterDelete = restTemplate.exchange(
            "/baas/v1/clients/" + customerId + "/beneficiaries",
            HttpMethod.GET, new HttpEntity<>(h), Map.class);
        assertThat((List<?>) ((Map<?, ?>) afterDelete.getBody().get("data")).get("content"))
            .isEmpty();
    }
}
