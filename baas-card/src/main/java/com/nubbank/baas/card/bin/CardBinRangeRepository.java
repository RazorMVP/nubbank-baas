package com.nubbank.baas.card.bin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface CardBinRangeRepository extends JpaRepository<CardBinRange, UUID> {

    /**
     * Range-match an already-normalized 8-char BIN against active ranges.
     * Ordered longest-{@code binStart}-first so that, if overlapping ranges ever
     * exist, the most specific (longest prefix) wins. Today all stored values are
     * a fixed 8 chars, so the ORDER BY is a forward-compatible no-op.
     */
    @Query("""
        SELECT b FROM CardBinRange b
        WHERE b.active = true AND b.binStart <= :bin AND b.binEnd >= :bin
        ORDER BY LENGTH(b.binStart) DESC""")
    List<CardBinRange> findMatching(@Param("bin") String bin);

    List<CardBinRange> findByPartnerIdOrderByBinStartAsc(UUID partnerId);
}
