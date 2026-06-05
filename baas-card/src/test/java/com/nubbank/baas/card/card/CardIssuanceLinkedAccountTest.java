package com.nubbank.baas.card.card;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.engine.EngineClient;
import com.nubbank.baas.card.engine.dto.AccountLookupResult;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Stage 5 Task 8 — issuance binds {@code linkedAccountId} only after validating the account
 * exists in the engine. The engine is mocked (no engine runs in card tests).
 */
class CardIssuanceLinkedAccountTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private CardRepository cardRepository;

    @MockitoBean private EngineClient engineClient;

    private TestPartner partner;
    private UUID productId;
    private final UUID linkedAccount = UUID.randomUUID();

    @BeforeEach
    void setup() {
        partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        productId = createProduct(partner);
    }

    @Test
    void rejectsUnknownAccount() {
        when(engineClient.accountLookup(any())).thenReturn(new AccountLookupResult(false, false, null));
        ResponseEntity<Map> resp = post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "customerRef", "ext-1",
            "virtual", true, "linkedAccountId", linkedAccount.toString()));
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(firstErrorCode(resp)).isEqualTo("LINKED_ACCOUNT_NOT_FOUND");
    }

    @Test
    void bindsLinkedAccountWhenValid() {
        when(engineClient.accountLookup(any())).thenReturn(new AccountLookupResult(true, true, "NGN"));
        ResponseEntity<Map> resp = post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "customerRef", "ext-2",
            "virtual", true, "linkedAccountId", linkedAccount.toString()));
        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        UUID cardId = UUID.fromString((String) data(resp).get("id"));

        try {
            PartnerContext.set(new PartnerContext(partner.orgId.toString(), partner.schemaName,
                "SANDBOX", "SANDBOX", "JWT", "test-user"));
            assertThat(cardRepository.findById(cardId).orElseThrow().getLinkedAccountId())
                .isEqualTo(linkedAccount);
        } finally {
            PartnerContext.clear();
        }
    }

    @Test
    void missingLinkedAccountIdIsRejected() {
        ResponseEntity<Map> resp = post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "virtual", true));   // no linkedAccountId
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ---- helpers ----

    private UUID createProduct(TestPartner p) {
        ResponseEntity<Map> created = post(p.jwt, "/baas/v1/card-products", Map.of(
            "name", "Virtual Debit " + UUID.randomUUID(), "cardType", "DEBIT", "currency", "NGN",
            "binStart", "506000"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString((String) data(created).get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> resp) {
        return (Map<String, Object>) resp.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private String firstErrorCode(ResponseEntity<Map> resp) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>) resp.getBody().get("errors");
        assertThat(errors).isNotEmpty();
        return (String) errors.get(0).get("code");
    }

    private ResponseEntity<Map> post(String jwt, String path, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
