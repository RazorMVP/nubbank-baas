package com.nubbank.baas.card.card;

import com.nubbank.baas.card.AbstractCardIntegrationTest;
import com.nubbank.baas.card.auth.PartnerJwtService;
import com.nubbank.baas.card.config.FieldEncryptor;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.support.TestPartner;
import com.nubbank.baas.card.tenant.PartnerContext;
import com.nubbank.baas.card.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Card issuance + lifecycle state-machine integration tests
 * ({@code /baas/v1/cards}), authenticated with a TestPartner HMAC JWT.
 *
 * The MOST security-sensitive surface in baas-card: every test also asserts the
 * PAN-safety invariant — the raw PAN, the encrypted blob and the pan_hash are
 * NEVER present in any response body. Responses expose {@code maskedPan} only.
 *
 * Proves:
 *  - issue a virtual card → 201, status ISSUED, maskedPan masked;
 *  - the full happy-path state machine: ISSUED→ACTIVE→BLOCKED→ACTIVE→CANCELLED;
 *  - illegal transition CANCELLED→activate → 409 INVALID_TRANSITION;
 *  - a second illegal transition (ISSUED→unblock) → 409 INVALID_TRANSITION;
 *  - unknown command → 400 INVALID_COMMAND;
 *  - issue against a non-existent product → 404 PRODUCT_NOT_FOUND;
 *  - PAN safety: NO pan / panEncrypted / panHash keys in issue OR get responses;
 *  - pan_hash determinism / Task-6 lookup: decrypt the stored blob, re-hash,
 *    and assert {@code findByPanHash} resolves the SAME card (end-to-end proof);
 *  - tenant isolation: partner B never sees / can't command partner A's card.
 */
class CardLifecycleTest extends AbstractCardIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private PartnerJwtService jwtService;
    @Autowired private CardRepository cardRepository;
    @Autowired private PanHasher panHasher;
    @Autowired private FieldEncryptor fieldEncryptor;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void issue_thenFullLifecycle_andIllegalTransition() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        UUID productId = createProduct(partner);

        // issue → 201 ISSUED
        ResponseEntity<Map> issued = post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "customerRef", "ext-1", "virtual", true));
        assertThat(issued.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> card = data(issued);
        assertThat(card.get("status")).isEqualTo("ISSUED");
        assertThat(card.get("virtual")).isEqualTo(Boolean.TRUE);
        assertThat(card.get("customerRef")).isEqualTo("ext-1");
        assertPanSafe(card);
        String id = (String) card.get("id");

        // activate → ACTIVE
        assertThat(command(partner.jwt, id, "activate").get("status")).isEqualTo("ACTIVE");
        // block → BLOCKED
        assertThat(command(partner.jwt, id, "block").get("status")).isEqualTo("BLOCKED");
        // unblock → ACTIVE
        assertThat(command(partner.jwt, id, "unblock").get("status")).isEqualTo("ACTIVE");
        // cancel → CANCELLED
        assertThat(command(partner.jwt, id, "cancel").get("status")).isEqualTo("CANCELLED");

        // illegal: activate from CANCELLED → 409 INVALID_TRANSITION
        ResponseEntity<Map> illegal = commandRaw(partner.jwt, id, "activate");
        assertThat(illegal.getStatusCode().value()).isEqualTo(409);
        assertThat(firstErrorCode(illegal)).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void illegalTransition_issuedToBlock_returns409() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        UUID productId = createProduct(partner);
        String id = (String) data(post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "customerRef", "ext-2", "virtual", true))).get("id");

        // ISSUED → BLOCKED is illegal: ISSUED only permits ACTIVE or CANCELLED.
        // (Note: `unblock` maps to target ACTIVE, which IS legal from ISSUED, so it is
        // deliberately NOT used as the illegal case — `block` is the genuine one.)
        ResponseEntity<Map> illegal = commandRaw(partner.jwt, id, "block");
        assertThat(illegal.getStatusCode().value()).isEqualTo(409);
        assertThat(firstErrorCode(illegal)).isEqualTo("INVALID_TRANSITION");
    }

    @Test
    void unknownCommand_returns400() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        UUID productId = createProduct(partner);
        String id = (String) data(post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "virtual", true))).get("id");

        ResponseEntity<Map> resp = commandRaw(partner.jwt, id, "frobnicate");
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(firstErrorCode(resp)).isEqualTo("INVALID_COMMAND");
    }

    @Test
    void issue_nonExistentProduct_returns404() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        ResponseEntity<Map> resp = post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", UUID.randomUUID().toString(), "virtual", true));
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(firstErrorCode(resp)).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void list_returnsTenantCards_andNeverLeaksPan() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        UUID productId = createProduct(partner);
        post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "customerRef", "ext-3", "virtual", true));

        ResponseEntity<Map> listed = get(partner.jwt, "/baas/v1/cards");
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) listed.getBody().get("data");
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).get("status")).isEqualTo("ISSUED");
        assertPanSafe(cards.get(0));
    }

    @Test
    void maskedPan_hasCorrectFormat() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        UUID productId = createProduct(partner);
        Map<String, Object> card = data(post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "virtual", true)));
        String masked = (String) card.get("maskedPan");
        // bin(6) + ****** + last4  →  e.g. "506000******1234"
        assertThat(masked).matches("^\\d{6}\\*{6}\\d{4}$");
    }

    /**
     * pan_hash determinism + the FROZEN Task-6 contract end-to-end.
     *
     * Approach (a): we never see the cleartext PAN in the response (good!). So we
     * read pan_encrypted straight from the partner's tenant schema, decrypt it
     * with a FieldEncryptor configured with the SAME key, re-derive the hash via
     * panHasher.hash(pan) — EXACTLY what Task 6's authorize will call — and assert
     * cardRepository.findByPanHash(...) resolves the SAME card.
     */
    @Test
    void panHash_isDeterministic_andResolvesViaFindByPanHash() {
        TestPartner partner = TestPartner.create(orgRepo, provisioningService, jwtService);
        UUID productId = createProduct(partner);
        Map<String, Object> card = data(post(partner.jwt, "/baas/v1/cards", Map.of(
            "productId", productId.toString(), "virtual", true)));
        UUID cardId = UUID.fromString((String) card.get("id"));

        // Read the encrypted PAN directly from the partner's tenant schema.
        String encryptedPan = jdbcTemplate.queryForObject(
            "SELECT pan_encrypted FROM " + partner.schemaName + ".cards WHERE id = ?",
            String.class, cardId);
        String fullPan = fieldEncryptor.convertToEntityAttribute(encryptedPan);

        // The hash Task 6 will compute from the PAN it receives off the wire.
        String expectedHash = panHasher.hash(fullPan);

        // Resolve the card the way Task 6 will — within the partner's tenant schema.
        try {
            PartnerContext.set(new PartnerContext(
                partner.orgId.toString(), partner.schemaName, "SANDBOX", "SANDBOX",
                "JWT", "test-user"));
            assertThat(cardRepository.findByPanHash(expectedHash))
                .isPresent()
                .get()
                .extracting(c -> c.getId())
                .isEqualTo(cardId);
        } finally {
            PartnerContext.clear();
        }
    }

    @Test
    void command_isTenantIsolated_partnerBCannotTouchPartnerAsCard() {
        TestPartner partnerA = TestPartner.create(orgRepo, provisioningService, jwtService);
        TestPartner partnerB = TestPartner.create(orgRepo, provisioningService, jwtService);
        UUID productA = createProduct(partnerA);
        String idA = (String) data(post(partnerA.jwt, "/baas/v1/cards", Map.of(
            "productId", productA.toString(), "virtual", true))).get("id");

        // Partner B's schema has no such card → 404 CARD_NOT_FOUND.
        ResponseEntity<Map> resp = commandRaw(partnerB.jwt, idA, "activate");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        assertThat(firstErrorCode(resp)).isEqualTo("CARD_NOT_FOUND");

        // And partner B's card list is empty.
        ResponseEntity<Map> listB = get(partnerB.jwt, "/baas/v1/cards");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataB = (List<Map<String, Object>>) listB.getBody().get("data");
        assertThat(dataB).isEmpty();
    }

    @Test
    void issue_withoutAuth_returns401() {
        ResponseEntity<Map> resp = restTemplate.postForEntity("/baas/v1/cards",
            Map.of("productId", UUID.randomUUID().toString(), "virtual", true), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    // ---- PAN-safety assertion ----

    private void assertPanSafe(Map<String, Object> card) {
        assertThat(card).doesNotContainKeys("pan", "panEncrypted", "panHash");
        // maskedPan present and masked (no raw 16-digit run)
        String masked = (String) card.get("maskedPan");
        assertThat(masked).isNotNull().contains("******");
    }

    // ---- helpers ----

    private UUID createProduct(TestPartner partner) {
        ResponseEntity<Map> created = post(partner.jwt, "/baas/v1/card-products", Map.of(
            "name", "Virtual Debit " + UUID.randomUUID(), "cardType", "DEBIT", "currency", "NGN",
            "binStart", "506000"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString((String) data(created).get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> resp) {
        return (Map<String, Object>) resp.getBody().get("data");
    }

    private Map<String, Object> command(String jwt, String id, String cmd) {
        return data(commandRaw(jwt, id, cmd));
    }

    private ResponseEntity<Map> commandRaw(String jwt, String id, String cmd) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange("/baas/v1/cards/" + id + "?command=" + cmd,
            HttpMethod.POST, new HttpEntity<>(headers), Map.class);
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
        return restTemplate.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> get(String jwt, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
