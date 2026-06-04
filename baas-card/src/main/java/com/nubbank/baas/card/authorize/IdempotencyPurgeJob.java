package com.nubbank.baas.card.authorize;

import com.nubbank.baas.card.partner.PartnerOrganization;
import com.nubbank.baas.card.partner.PartnerOrganizationRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Daily purge of expired {@link AuthorizationIdempotency} rows (F3 retention).
 *
 * <p>The idempotency table is per-tenant, so a context-less scheduled job cannot reach
 * it (Hibernate would route to {@code public}, where the table does not exist). This
 * job therefore ENUMERATES every provisioned schema (each partner has a
 * {@code partner_<hex>} production schema and a {@code sandbox_<hex>} sandbox schema),
 * sets {@link PartnerContext} per schema, deletes rows older than {@link #RETENTION},
 * and ALWAYS clears the context in a {@code finally}.
 */
@Component
@RequiredArgsConstructor
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);
    private static final Duration RETENTION = Duration.ofHours(24);

    private final PartnerOrganizationRepository orgRepo;
    private final AuthorizationIdempotencyRepository idemRepo;

    /** Daily at 03:30. */
    @Scheduled(cron = "0 30 3 * * *")
    public void scheduledPurge() {
        purgeAllTenants();
    }

    /** Package/public for tests — purge every provisioned schema. */
    public void purgeAllTenants() {
        Instant cutoff = Instant.now().minus(RETENTION);
        List<PartnerOrganization> orgs = orgRepo.findAll();
        int totalDeleted = 0;
        for (PartnerOrganization org : orgs) {
            String prodSchema = org.getSchemaName();
            if (prodSchema == null || !prodSchema.startsWith("partner_")) {
                log.warn("Idempotency purge skipped org {} — unexpected schema name {}",
                    org.getId(), prodSchema);
                continue;
            }
            String sandboxSchema = "sandbox_" + prodSchema.substring("partner_".length());
            totalDeleted += purgeSchema(org, prodSchema, cutoff);
            totalDeleted += purgeSchema(org, sandboxSchema, cutoff);
        }
        if (totalDeleted > 0) {
            log.info("Idempotency purge removed {} expired rows across {} partners",
                totalDeleted, orgs.size());
        }
    }

    private int purgeSchema(PartnerOrganization org, String schema, Instant cutoff) {
        try {
            String env = schema.startsWith("sandbox_") ? "SANDBOX" : "PRODUCTION";
            PartnerContext.set(new PartnerContext(
                org.getId().toString(), schema, "INTERNAL", env, "INTERNAL", null));
            return idemRepo.deleteOlderThan(cutoff);
        } catch (RuntimeException ex) {
            log.warn("Idempotency purge skipped schema {}: {}", schema, ex.getMessage());
            return 0;
        } finally {
            PartnerContext.clear();
        }
    }
}
