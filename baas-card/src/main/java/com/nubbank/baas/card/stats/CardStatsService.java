package com.nubbank.baas.card.stats;

import com.nubbank.baas.card.card.CardRepository;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Counts cards in a partner schema for the engine's dashboard aggregate (DEF-1C-29).
 *
 * <p>Sets the {@link PartnerContext} from the request's {@code schemaName} so Hibernate routes
 * the count to the right schema, and ALWAYS clears it in {@code finally} — a leaked context
 * would mis-route a subsequent request on the same worker thread.
 */
@Service
@RequiredArgsConstructor
public class CardStatsService {

    private final CardRepository cardRepository;

    public long cardsIssued(String partnerId, String schemaName) {
        String environment = schemaName != null && schemaName.startsWith("sandbox_")
            ? "SANDBOX" : "PRODUCTION";
        PartnerContext.set(new PartnerContext(
            partnerId, schemaName, "INTERNAL", environment, "INTERNAL", null));
        try {
            return cardRepository.count();
        } finally {
            PartnerContext.clear();
        }
    }
}
