package com.nubbank.baas.card.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Tenant-scoped repository for {@link CardProduct}. All queries are routed to the
 * authenticated partner's schema by Hibernate's tenant resolver — there is no
 * partnerId filter because the schema IS the boundary.
 */
public interface CardProductRepository extends JpaRepository<CardProduct, UUID> {

    boolean existsByName(String name);
}
