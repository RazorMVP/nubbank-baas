package com.nubbank.baas.fep.audit;

import com.nubbank.baas.fep.AbstractFepIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Stage 5 Task 14 — the best-effort FEP audit writer. Round-trips a row through H2 (asserting
 * BIN + last4 only, no full PAN) and proves a write failure is swallowed.
 */
class AuthorizationAuditServiceTest extends AbstractFepIntegrationTest {

    @Autowired private AuthorizationAuditService auditService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void recordsRowWithBinAndLast4Only() {
        String stan = "00" + (System.nanoTime() % 10000);
        auditService.record(new FepAuthorizationLog(
            Instant.now(), "0100", stan, "TERM0001",
            "50600012", "7890", UUID.randomUUID().toString(), "partner_acme",
            5000L, "566", "APPROVE", "00", false, 12));

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT bin, pan_last4, response_code, decision, reversal FROM fep.authorization_log WHERE stan = ?",
            stan);
        assertThat(row.get("bin")).isEqualTo("50600012");
        assertThat(row.get("pan_last4")).isEqualTo("7890");
        assertThat(row.get("response_code")).isEqualTo("00");
        assertThat(row.get("decision")).isEqualTo("APPROVE");
        assertThat(row.get("reversal")).isEqualTo(false);
        // PAN safety: the table physically cannot hold a full PAN — assert no such column exists.
        assertThat(jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE lower(table_name) = 'authorization_log'",
            String.class))
            .map(String::toLowerCase)
            .noneMatch(c -> c.equals("pan") || c.equals("pan_encrypted"));
    }

    @Test
    void writeFailureIsSwallowed() {
        // A JdbcTemplate over a broken datasource throws — record(...) must NOT propagate it.
        org.springframework.jdbc.datasource.DriverManagerDataSource broken =
            new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:does-not-exist;IFEXISTS=TRUE");
        broken.setDriverClassName("org.h2.Driver");
        AuthorizationAuditService failing = new AuthorizationAuditService(new JdbcTemplate(broken));

        assertThatCode(() -> failing.record(new FepAuthorizationLog(
            Instant.now(), "0100", "000001", "TERM0001", "50600012", "7890",
            null, null, 1L, "566", "DECLINE", "91", false, 5)))
            .doesNotThrowAnyException();
    }
}
