package com.nubbank.baas.engine.security;

import com.nubbank.baas.engine.AbstractIntegrationTest;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.partner.*;
import com.nubbank.baas.engine.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Verifies that PII fields on the Customer entity are stored as ciphertext in
 * the database, not plaintext. Reads the row directly via raw JDBC (bypassing
 * the JPA AttributeConverter) so we see what's actually on disk.
 */
class PiiEncryptionTest extends AbstractIntegrationTest {

    @Autowired private PartnerOrganizationRepository orgRepo;
    @Autowired private TenantProvisioningService provisioningService;
    @Autowired private CustomerRepository customerRepo;
    @Autowired private JdbcTemplate jdbc;

    private String schemaName;
    private UUID customerId;

    @BeforeEach
    void setup() {
        schemaName = "partner_" + UUID.randomUUID().toString().replace("-", "");
        PartnerOrganization org = orgRepo.save(PartnerOrganization.builder()
            .name("PII Test").status(PartnerStatus.SANDBOX).tier(PartnerTier.SANDBOX)
            .environment(PartnerEnvironment.SANDBOX).schemaName(schemaName)
            .contactEmail("pii@test.com").build());
        provisioningService.provision(org.getId(), schemaName);

        PartnerContext.set(new PartnerContext(org.getId().toString(), schemaName,
            "SANDBOX", "SANDBOX", "TEST", null));
        Customer saved = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Sensitive-First-Name")
            .lastNameEncrypted("Sensitive-Last-Name")
            .emailEncrypted("user@example.com")
            .bvnEncrypted("12345678901")
            .ninEncrypted("99988877766")
            .build());
        customerId = saved.getId();
        PartnerContext.clear();
    }

    @Test
    void rowOnDisk_storesCiphertext_notPlaintext() {
        // Read the raw row directly — bypasses the AttributeConverter so we
        // see what PostgreSQL actually has on disk.
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT first_name_encrypted, last_name_encrypted, email_encrypted, "
            + "bvn_encrypted, nin_encrypted "
            + "FROM " + schemaName + ".customers WHERE id = ?",
            customerId);

        assertThat(row.get("first_name_encrypted")).isNotEqualTo("Sensitive-First-Name");
        assertThat((String) row.get("first_name_encrypted")).doesNotContain("Sensitive");
        assertThat((String) row.get("last_name_encrypted")).doesNotContain("Sensitive");
        assertThat((String) row.get("email_encrypted")).doesNotContain("user@example");
        assertThat((String) row.get("bvn_encrypted")).doesNotContain("12345678901");
        assertThat((String) row.get("nin_encrypted")).doesNotContain("99988877766");
    }

    @Test
    void roundTrip_decryptsTransparently() {
        PartnerContext.set(new PartnerContext(orgRepo.findAll().get(0).getId().toString(),
            schemaName, "SANDBOX", "SANDBOX", "TEST", null));
        Customer loaded = customerRepo.findById(customerId).orElseThrow();
        assertThat(loaded.getFirstNameEncrypted()).isEqualTo("Sensitive-First-Name");
        assertThat(loaded.getLastNameEncrypted()).isEqualTo("Sensitive-Last-Name");
        assertThat(loaded.getEmailEncrypted()).isEqualTo("user@example.com");
        assertThat(loaded.getBvnEncrypted()).isEqualTo("12345678901");
        assertThat(loaded.getNinEncrypted()).isEqualTo("99988877766");
        PartnerContext.clear();
    }

    @Test
    void semanticSecurity_sameValueProducesDifferentCiphertexts() {
        // Save the same plaintext twice — different rows must have different
        // ciphertexts because each save uses a fresh random IV.
        PartnerContext.set(new PartnerContext(orgRepo.findAll().get(0).getId().toString(),
            schemaName, "SANDBOX", "SANDBOX", "TEST", null));
        UUID id1 = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Alice").lastNameEncrypted("Same").build()).getId();
        UUID id2 = customerRepo.save(Customer.builder()
            .firstNameEncrypted("Alice").lastNameEncrypted("Same").build()).getId();
        PartnerContext.clear();

        String ct1 = jdbc.queryForObject(
            "SELECT first_name_encrypted FROM " + schemaName + ".customers WHERE id = ?",
            String.class, id1);
        String ct2 = jdbc.queryForObject(
            "SELECT first_name_encrypted FROM " + schemaName + ".customers WHERE id = ?",
            String.class, id2);
        assertThat(ct1).isNotEqualTo(ct2);
    }
}
