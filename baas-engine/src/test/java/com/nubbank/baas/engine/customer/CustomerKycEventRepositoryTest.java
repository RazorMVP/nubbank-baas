package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CustomerKycEventRepositoryTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerKycEventRepository eventRepo;
    @Autowired private CustomerRepository customerRepo;

    @Test
    void findByCustomerId_returnsNewestFirst() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("Ev").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schema)
            .contactEmail("ev@test.com").build());
        provisioningService.provision(org.getId(), schema);

        try {
            PartnerContext.set(new PartnerContext(org.getId().toString(), schema,
                "SANDBOX", "SANDBOX", "TEST", null));

            // customer_kyc_events.customer_id has a FK to customers(id); create a real row first
            Customer customer = customerRepo.save(Customer.builder()
                .firstNameEncrypted("Test").lastNameEncrypted("User").build());
            UUID customerId = customer.getId();

            eventRepo.save(CustomerKycEvent.builder().customerId(customerId)
                .fromStatus("PENDING_KYC").toStatus("ACTIVE").reason("first").changedBy("op").build());
            eventRepo.save(CustomerKycEvent.builder().customerId(customerId)
                .fromStatus("ACTIVE").toStatus("SUSPENDED").reason("second").changedBy("op").build());

            List<CustomerKycEvent> events = eventRepo.findByCustomerIdOrderByChangedAtDesc(customerId);
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getReason()).isEqualTo("second");
        } finally {
            PartnerContext.clear();
        }
    }
}
