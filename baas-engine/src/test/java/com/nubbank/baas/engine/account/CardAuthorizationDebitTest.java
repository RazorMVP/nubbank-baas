package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.account.dto.CardDebitRequest;
import com.nubbank.baas.engine.account.dto.CardDebitResult;
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
 * Stage 5 Task 3 — the atomic, idempotent card-authorization debit. Exercises every
 * outcome branch (DEBITED, INSUFFICIENT against both floors, ACCOUNT_INVALID,
 * CURRENCY_MISMATCH) and the cross-service idempotency guarantee.
 */
class CardAuthorizationDebitTest extends AbstractIntegrationTest {

    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepo;
    @Autowired private CardAuthDebitRepository cardAuthDebitRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;

    private String orgId;
    private String schema;
    private Customer customer;
    private UUID acctId;     // ACTIVE NGN, balance 1000, min 0, no overdraft

    @BeforeEach
    void setup() {
        schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("CardDebit Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("cd@test.com").build());
        orgId = org.getId().toString();
        provisioningService.provision(org.getId(), schema);
        // Leave context set for the test body; AbstractIntegrationTest clears it in @AfterEach.
        PartnerContext.set(new PartnerContext(orgId, schema, "SANDBOX", "SANDBOX", "TEST", null));
        customer = customerRepo.save(Customer.builder()
            .firstNameEncrypted("A").lastNameEncrypted("B").build());
        acctId = newAccount("1000", "NGN", false, null, AccountStatus.ACTIVE);
    }

    private UUID newAccount(String balance, String ccy, boolean overdraft,
                            String overdraftLimit, AccountStatus status) {
        return accountRepo.save(Account.builder().customer(customer)
            .accountNumber(String.valueOf(System.nanoTime()).substring(0, 10))
            .balance(new BigDecimal(balance)).availableBalance(new BigDecimal(balance))
            .currencyCode(ccy).minimumBalance(BigDecimal.ZERO)
            .allowOverdraft(overdraft)
            .overdraftLimit(overdraftLimit == null ? null : new BigDecimal(overdraftLimit))
            .status(status).build()).getId();
    }

    private CardDebitRequest req(UUID acct, String amount, String ccy, String key) {
        return new CardDebitRequest(orgId, schema, acct, key, new BigDecimal(amount), ccy);
    }

    @Test
    void debitsActiveAccount() {
        CardDebitResult r = accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "k1"));
        assertThat(r.outcome()).isEqualTo(CardAuthOutcome.DEBITED);
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance()).isEqualByComparingTo("900.0000");
        assertThat(cardAuthDebitRepo.findByAuthKey("k1")).get()
            .extracting(CardAuthDebit::getTransactionId).isNotNull();
    }

    @Test
    void idempotentOnAuthKey() {
        accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "k2"));
        CardDebitResult again = accountService.cardAuthorizationDebit(req(acctId, "100.0000", "NGN", "k2"));
        assertThat(again.outcome()).isEqualTo(CardAuthOutcome.DEBITED);
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance())
            .isEqualByComparingTo("900.0000");   // debited exactly once
    }

    @Test
    void insufficientBelowMinimumBalance() {
        CardDebitResult r = accountService.cardAuthorizationDebit(req(acctId, "5000.0000", "NGN", "k3"));
        assertThat(r.outcome()).isEqualTo(CardAuthOutcome.INSUFFICIENT);
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance())
            .isEqualByComparingTo("1000.0000");  // unchanged
    }

    @Test
    void overdraftFloorAllowsThenBlocks() {
        UUID od = newAccount("1000", "NGN", true, "200", AccountStatus.ACTIVE);  // floor -200
        assertThat(accountService.cardAuthorizationDebit(req(od, "1150.0000", "NGN", "od1")).outcome())
            .isEqualTo(CardAuthOutcome.DEBITED);                                  // 1000-1150 = -150 >= -200
        assertThat(accountRepo.findById(od).orElseThrow().getBalance()).isEqualByComparingTo("-150.0000");
        assertThat(accountService.cardAuthorizationDebit(req(od, "100.0000", "NGN", "od2")).outcome())
            .isEqualTo(CardAuthOutcome.INSUFFICIENT);                             // -150-100 = -250 < -200
        assertThat(accountRepo.findById(od).orElseThrow().getBalance()).isEqualByComparingTo("-150.0000");
    }

    @Test
    void accountInvalidWhenMissing() {
        assertThat(accountService.cardAuthorizationDebit(
            req(UUID.randomUUID(), "10.0000", "NGN", "k4")).outcome())
            .isEqualTo(CardAuthOutcome.ACCOUNT_INVALID);
    }

    @Test
    void accountInvalidWhenFrozen() {
        UUID frozen = newAccount("1000", "NGN", false, null, AccountStatus.FROZEN);
        assertThat(accountService.cardAuthorizationDebit(req(frozen, "10.0000", "NGN", "k5")).outcome())
            .isEqualTo(CardAuthOutcome.ACCOUNT_INVALID);
    }

    @Test
    void currencyMismatchDeclines() {
        assertThat(accountService.cardAuthorizationDebit(req(acctId, "10.0000", "USD", "k6")).outcome())
            .isEqualTo(CardAuthOutcome.CURRENCY_MISMATCH);
        assertThat(accountRepo.findById(acctId).orElseThrow().getBalance())
            .isEqualByComparingTo("1000.0000");  // unchanged
    }
}
