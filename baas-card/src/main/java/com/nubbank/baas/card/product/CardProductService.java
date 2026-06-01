package com.nubbank.baas.card.product;

import com.nubbank.baas.card.common.BaasException;
import com.nubbank.baas.card.product.dto.CardProductResponse;
import com.nubbank.baas.card.product.dto.CreateCardProductRequest;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Card-product management, tenant-scoped.
 *
 * Mirrors the engine {@code CustomerService} idiom: every entry point calls
 * {@link #requireContext()} first, then operates inside the authenticated
 * partner's schema. The duplicate-name check and all queries run against that
 * schema only — Hibernate's tenant resolver routes them automatically, so there
 * is NO partnerId column or filter. The schema IS the isolation boundary.
 */
@Service
@RequiredArgsConstructor
public class CardProductService {

    private final CardProductRepository repo;

    @Transactional
    public CardProductResponse create(CreateCardProductRequest req) {
        requireContext();
        // Fast path: gives the clean 409 in the common (non-concurrent) case.
        if (repo.existsByName(req.name())) {
            throw BaasException.conflict("DUPLICATE_PRODUCT",
                "A card product named '" + req.name() + "' already exists");
        }

        CardProduct product = CardProduct.builder()
            .name(req.name())
            .cardType(req.cardType())
            .currency(req.currency())
            .binStart(req.binStart())
            .defaultDailyLimit(req.defaultDailyLimit())
            .build();

        // Authoritative guard against the TOCTOU race: two concurrent requests can both
        // pass existsByName above; the DB unique constraint on card_products.name is the
        // real arbiter. saveAndFlush forces the INSERT (and the constraint) to fire HERE,
        // synchronously inside this try, rather than being deferred to tx commit where the
        // exception would escape this catch. We narrowly re-map ONLY the name-unique
        // violation — the sole plausible constraint on this insert — back to the SAME 409.
        // Other integrity violations (NOT NULL, FK, CHECK) must keep their honest status,
        // so this catch is intentionally NOT a blanket handler.
        try {
            return CardProductResponse.from(repo.saveAndFlush(product));
        } catch (DataIntegrityViolationException e) {
            throw BaasException.conflict("DUPLICATE_PRODUCT",
                "A card product named '" + req.name() + "' already exists");
        }
    }

    @Transactional(readOnly = true)
    public List<CardProductResponse> list() {
        requireContext();
        return repo.findAll().stream()
            .map(CardProductResponse::from)
            .toList();
    }

    private void requireContext() {
        if (PartnerContext.get() == null) {
            throw BaasException.unauthorized("MISSING_AUTH",
                "Authorization header required — use ApiKey or Bearer JWT");
        }
    }
}
