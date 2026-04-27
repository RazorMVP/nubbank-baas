package com.nubbank.baas.engine.virtualaccount;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.common.BaasException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class VirtualAccountServiceTest extends AbstractIntegrationTest {

    @Autowired
    private VirtualAccountService virtualAccountService;

    @Autowired
    private VirtualAccountRepository poolRepo;

    @Test
    void assignNext_returnsUniqueNubanAccountNumber() {
        String account = virtualAccountService.assignNext("partner_test001");
        assertThat(account).hasSize(10);                          // NUBAN = 10 digits
        assertThat(account).startsWith("058");                    // bank code
        assertThat(account).matches("[0-9]{10}");                 // all numeric
    }

    @Test
    void assignNext_marksPoolEntryAsAssigned() {
        String account = virtualAccountService.assignNext("partner_test002");
        VirtualAccountPool entry = poolRepo.findAll().stream()
            .filter(p -> p.getAccountNumber().equals(account))
            .findFirst().orElseThrow();
        assertThat(entry.isAssigned()).isTrue();
        assertThat(entry.getAssignedToSchema()).isEqualTo("partner_test002");
        assertThat(entry.getAssignedAt()).isNotNull();
    }

    @Test
    void assignNext_consecutiveCalls_returnDifferentNumbers() {
        Set<String> assigned = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            assigned.add(virtualAccountService.assignNext("partner_test003"));
        }
        assertThat(assigned).hasSize(5); // all unique
    }
}
