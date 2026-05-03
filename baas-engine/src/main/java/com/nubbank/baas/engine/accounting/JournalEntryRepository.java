package com.nubbank.baas.engine.accounting;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {
    Page<JournalEntry> findByEntryDateBetweenOrderByEntryDateDesc(LocalDate from, LocalDate to, Pageable pageable);
    Page<JournalEntry> findAllByOrderByEntryDateDesc(Pageable pageable);
}
