package com.nubbank.baas.card.product;

import com.nubbank.baas.card.common.BaasException;
import com.nubbank.baas.card.product.dto.CardProductResponse;
import com.nubbank.baas.card.product.dto.CreateCardProductRequest;
import com.nubbank.baas.card.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
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

        return CardProductResponse.from(repo.save(product));
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
