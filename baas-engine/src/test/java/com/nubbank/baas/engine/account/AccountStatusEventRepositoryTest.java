package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AccountStatusEventRepositoryTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private AccountStatusEventRepository eventRepo;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private AccountRepository accountRepo;

    @Test
    void findByAccountId_returnsOldestFirst() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Ev").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("ev@test.com").build());
        provisioningService.provision(org.getId(), schema);

        try {
            PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
                "SANDBOX", "SANDBOX", "TEST", null));

            // account_status_events.account_id has a FK to accounts(id), and accounts.customer_id
            // has a NOT-NULL FK to customers(id); create real parent rows first.
            Customer customer = customerRepo.save(Customer.builder()
                .firstNameEncrypted("Test").lastNameEncrypted("User").build());
            Account account = accountRepo.save(Account.builder()
                .customer(customer).accountNumber("0123456789")
                .status(AccountStatus.ACTIVE).balance(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO).currencyCode("NGN")
                .minimumBalance(BigDecimal.ZERO).build());
            UUID accountId = account.getId();

            eventRepo.save(AccountStatusEvent.builder().accountId(accountId)
                .fromStatus("ACTIVE").toStatus("FROZEN").reason("first").changedBy("op").build());
            eventRepo.save(AccountStatusEvent.builder().accountId(accountId)
                .fromStatus("FROZEN").toStatus("ACTIVE").reason("second").changedBy("op").build());

            List<AccountStatusEvent> events = eventRepo.findByAccountIdOrderByChangedAtAsc(accountId);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getReason()).isEqualTo("first");
            assertThat(events.get(1).getReason()).isEqualTo("second");
        } finally {
            PartnerContext.clear();
        }
    }
}
