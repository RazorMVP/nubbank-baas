package com.nubbank.baas.engine.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.dto.AccountLookupRequest;
import com.nubbank.baas.engine.account.dto.CardDebitRequest;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 Task 5 — the internal money endpoints behind the HMAC seam. Verifies a valid
 * signed call reaches the service (debit + account-lookup map through the ApiResponse
 * envelope and PartnerContext is set from the body), and that an unsigned call is 401.
 */
class InternalCardMoneyControllerTest extends AbstractIntegrationTest {

    // Must match src/test/resources/application-test.yml app.internal-service.shared-secret.
    private static final String SECRET = "test-shared-secret-min-32-chars-long-okay";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private AccountRepository accountRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String orgId;
    private String schema;
    private UUID acctId;

    @BeforeEach
    void setup() {
        schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Internal Money Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("im@test.com").build());
        orgId = org.getId().toString();
        provisioningService.provision(org.getId(), schema);
        PartnerContext.set(new PartnerContext(orgId, schema, "SANDBOX", "SANDBOX", "TEST", null));
        Customer customer = customerRepo.save(Customer.builder()
            .firstNameEncrypted("A").lastNameEncrypted("B").build());
        acctId = accountRepo.save(Account.builder().customer(customer)
            .accountNumber(String.valueOf(System.nanoTime()).substring(0, 10))
            .balance(new BigDecimal("1000")).availableBalance(new BigDecimal("1000"))
            .currencyCode("NGN").minimumBalance(BigDecimal.ZERO).status(AccountStatus.ACTIVE)
            .build()).getId();
        PartnerContext.clear();
    }

    @SuppressWarnings("unchecked")
    @Test
    void signedCardDebitReturnsDebited() throws Exception {
        var req = new CardDebitRequest(orgId, schema, acctId, "stan1|TERM0001|0605120000",
            new BigDecimal("100.00"), "NGN");
        ResponseEntity<Map> resp = postSigned("/internal/v1/card-debit", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("outcome")).isEqualTo("DEBITED");

        PartnerContext.set(new PartnerContext(orgId, schema, "SANDBOX", "SANDBOX", "TEST", null));
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance()).isEqualByComparingTo("900.00");
        PartnerContext.clear();
    }

    @SuppressWarnings("unchecked")
    @Test
    void signedAccountLookupReturnsExists() throws Exception {
        var req = new AccountLookupRequest(orgId, schema, acctId);
        ResponseEntity<Map> resp = postSigned("/internal/v1/account-lookup", req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("exists")).isEqualTo(true);
        assertThat(data.get("active")).isEqualTo(true);
        assertThat(data.get("currencyCode")).isEqualTo("NGN");
    }

    @Test
    void unsignedCallIs401() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.exchange("/internal/v1/account-lookup",
            HttpMethod.POST, new HttpEntity<>("{}", h), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> postSigned(String path, Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        long ts = Instant.now().getEpochSecond();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String signed = "POST|" + path + "|" + ts + "|" + bodyHash;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Internal " + sig);
        h.set("X-Internal-Timestamp", String.valueOf(ts));
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(json, h), Map.class);
    }
}
