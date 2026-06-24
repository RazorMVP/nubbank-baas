package com.nubbank.baas.engine.makerchecker;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.tenant.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class MakerCheckerPersistenceTest extends AbstractIntegrationTest {

    @Autowired PartnerOrganizationRepository orgRepo;
    @Autowired TenantProvisioningService provisioning;
    @Autowired MakerCheckerTaskRepository taskRepo;
    @Autowired MakerCheckerConfigRepository configRepo;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void task_roundTrips_andStatusFilterWorks() {
        String schema = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("P").status(PartnerStatus.PRO).tier(PartnerTier.PRO)
            .environment(PartnerEnvironment.PRODUCTION).schemaName(schema).contactEmail("p@t.com").build());
        provisioning.provision(org.getId(), schema);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schema, "PRO", "PRODUCTION", "JWT", null));
        try {
            UUID maker = UUID.randomUUID();
            MakerCheckerTask saved = taskRepo.save(MakerCheckerTask.builder()
                .commandType("ACCOUNT_OPEN").payload("{\"customerId\":\"x\"}")
                .madeBy(maker).status(TaskStatus.PENDING).build());

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getMadeAt()).isNotNull();

            List<MakerCheckerTask> pending = taskRepo.findByStatusOrderByMadeAtDesc(TaskStatus.PENDING);
            assertThat(pending).extracting(MakerCheckerTask::getId).contains(saved.getId());

            // findByIdForUpdate uses PESSIMISTIC_WRITE — must run inside a transaction.
            new TransactionTemplate(txManager).executeWithoutResult(s ->
                assertThat(taskRepo.findByIdForUpdate(saved.getId())).isPresent());

            assertThat(configRepo.findById("ACCOUNT_OPEN")).isPresent()
                .get().extracting(MakerCheckerConfig::isEnabled).isEqualTo(false);
        } finally {
            PartnerContext.clear();
        }
    }
}
