package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.dto.CardDebitRequest;
import com.nubbank.baas.engine.customer.Customer;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 5 Task 4 — the idempotent reversal credit. Covers credit-back, idempotent double
 * credit, not-found, and crediting a declined (never-DEBITED) auth.
 */
class CardAuthorizationCreditTest extends AbstractIntegrationTest {

    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private CardAuthDebitRepository cardAuthDebitRepo;
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
            .name("CardCredit Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("cc@test.com").build());
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
    }

    private CardDebitRequest debit(String amount, String key) {
        return new CardDebitRequest(orgId, schema, acctId, key, new BigDecimal(amount), "NGN");
    }

    @Test
    void creditsAndMarksReversed() {
        accountService.cardAuthorizationDebit(debit("100.0000", "c1"));   // balance 900
        assertThat(accountService.cardAuthorizationCredit("c1").located()).isTrue();
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance()).isEqualByComparingTo("1000.0000");
        assertThat(cardAuthDebitRepo.findByAuthKey("c1").orElseThrow().isReversed()).isTrue();
    }

    @Test
    void doubleCreditIsNoOp() {
        accountService.cardAuthorizationDebit(debit("100.0000", "c2"));
        accountService.cardAuthorizationCredit("c2");
        assertThat(accountService.cardAuthorizationCredit("c2").located()).isTrue();
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance())
            .isEqualByComparingTo("1000.0000");   // credited exactly once
    }

    @Test
    void notFoundReturnsNotLocated() {
        assertThat(accountService.cardAuthorizationCredit("never-happened").located()).isFalse();
    }

    @Test
    void creditOfDeclinedAuthIsNotLocated() {
        accountService.cardAuthorizationDebit(debit("5000.0000", "c3"));   // INSUFFICIENT, no debit
        assertThat(accountService.cardAuthorizationCredit("c3").located()).isFalse();
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance()).isEqualByComparingTo("1000.0000");
    }
}
