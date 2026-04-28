package com.nubbank.baas.engine.accounting;

import com.nubbank.baas.engine.accounting.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class GlAccountingService {

    private final GlAccountRepository glAccountRepo;
    private final JournalEntryRepository journalRepo;
    private final GlClosureRepository closureRepo;
    private final FinancialActivityAccountRepository activityRepo;

    @Transactional
    public GlAccountResponse createGlAccount(GlAccountRequest req) {
        requireContext();
        if (glAccountRepo.findByGlCode(req.glCode()).isPresent())
            throw BaasException.conflict("DUPLICATE_GL_CODE", "GL code '" + req.glCode() + "' already exists");
        GlAccount account = GlAccount.builder()
            .name(req.name()).glCode(req.glCode()).accountType(req.accountType())
            .accountUsage(req.accountUsage() != null ? req.accountUsage() : "DETAIL")
            .description(req.description())
            .manualJournalEntriesAllowed(req.manualJournalEntriesAllowed() != null ? req.manualJournalEntriesAllowed() : true)
            .build();
        if (req.parentId() != null) {
            account.setParent(glAccountRepo.findById(req.parentId())
                .orElseThrow(() -> BaasException.notFound("GL_ACCOUNT_NOT_FOUND", "Parent GL account not found")));
        }
        return toResponse(glAccountRepo.save(account));
    }

    @Transactional(readOnly = true)
    public Page<GlAccountResponse> listAccounts(int page, int size) {
        requireContext();
        return glAccountRepo.findByDisabledFalse(PageRequest.of(page, size)).map(this::toResponse);
    }

    @Transactional
    public GlAccountResponse updateGlAccount(UUID id, GlAccountRequest req) {
        requireContext();
        GlAccount account = glAccountRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("GL_ACCOUNT_NOT_FOUND", "GL account not found"));
        if (!account.getGlCode().equals(req.glCode()) && glAccountRepo.findByGlCode(req.glCode()).isPresent())
            throw BaasException.conflict("DUPLICATE_GL_CODE", "GL code already in use");
        account.setName(req.name()); account.setGlCode(req.glCode());
        if (req.accountUsage() != null) account.setAccountUsage(req.accountUsage());
        if (req.description() != null) account.setDescription(req.description());
        return toResponse(glAccountRepo.save(account));
    }

    @Transactional
    public void disableGlAccount(UUID id) {
        requireContext();
        GlAccount account = glAccountRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("GL_ACCOUNT_NOT_FOUND", "GL account not found"));
        account.setDisabled(true);
        glAccountRepo.save(account);
    }

    @Transactional
    public JournalEntry postManualJournalEntry(JournalEntryRequest req) {
        requireContext();
        BigDecimal totalDebits = req.lines().stream()
            .filter(l -> "DEBIT".equals(l.entryType()))
            .map(JournalEntryRequest.LineRequest::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = req.lines().stream()
            .filter(l -> "CREDIT".equals(l.entryType()))
            .map(JournalEntryRequest.LineRequest::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebits.compareTo(totalCredits) != 0)
            throw BaasException.badRequest("UNBALANCED_ENTRY",
                "Journal entry is unbalanced: debits=" + totalDebits + " credits=" + totalCredits);
        if (closureRepo.existsByClosingDateGreaterThanEqual(req.entryDate()))
            throw BaasException.badRequest("PERIOD_CLOSED", "Accounting period for " + req.entryDate() + " is closed");

        JournalEntry entry = JournalEntry.builder()
            .entryDate(req.entryDate()).reference(req.reference())
            .description(req.description()).manual(true).reversed(false)
            .build();
        for (JournalEntryRequest.LineRequest lineReq : req.lines()) {
            GlAccount glAccount = glAccountRepo.findById(lineReq.glAccountId())
                .orElseThrow(() -> BaasException.notFound("GL_ACCOUNT_NOT_FOUND",
                    "GL account " + lineReq.glAccountId() + " not found"));
            if (!glAccount.isManualJournalEntriesAllowed())
                throw BaasException.badRequest("MANUAL_ENTRIES_NOT_ALLOWED",
                    "GL account " + glAccount.getGlCode() + " does not allow manual entries");
            entry.getLines().add(JournalEntryLine.builder()
                .journal(entry).glAccount(glAccount)
                .entryType(lineReq.entryType()).amount(lineReq.amount())
                .currencyCode(lineReq.currencyCode() != null ? lineReq.currencyCode() : "NGN")
                .build());
        }
        return journalRepo.save(entry);
    }

    @Transactional
    public JournalEntry reverseJournalEntry(UUID id) {
        requireContext();
        JournalEntry original = journalRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("JOURNAL_NOT_FOUND", "Journal entry not found"));
        if (original.isReversed())
            throw BaasException.badRequest("ALREADY_REVERSED", "Journal entry already reversed");
        JournalEntry reversal = JournalEntry.builder()
            .entryDate(LocalDate.now())
            .description("Reversal of: " + original.getDescription())
            .reference("REV-" + original.getId().toString().substring(0, 8))
            .manual(true).reversed(false).reversedBy(original)
            .build();
        for (JournalEntryLine line : original.getLines()) {
            reversal.getLines().add(JournalEntryLine.builder()
                .journal(reversal).glAccount(line.getGlAccount())
                .entryType("DEBIT".equals(line.getEntryType()) ? "CREDIT" : "DEBIT")
                .amount(line.getAmount()).currencyCode(line.getCurrencyCode())
                .build());
        }
        original.setReversed(true);
        journalRepo.save(original);
        return journalRepo.save(reversal);
    }

    @Transactional
    public GlClosure createClosure(GlClosureRequest req) {
        requireContext();
        if (closureRepo.existsByClosingDateGreaterThanEqual(req.closingDate()))
            throw BaasException.conflict("PERIOD_ALREADY_CLOSED",
                "A closure already exists on or after " + req.closingDate());
        return closureRepo.save(GlClosure.builder()
            .closingDate(req.closingDate()).description(req.description()).build());
    }

    @Transactional(readOnly = true)
    public List<GlClosure> listAllClosures() {
        requireContext();
        return closureRepo.findAllByOrderByClosingDateDesc();
    }

    @Transactional
    public FinancialActivityAccount upsertFinancialActivity(FinancialActivityRequest req) {
        requireContext();
        GlAccount glAccount = glAccountRepo.findById(req.glAccountId())
            .orElseThrow(() -> BaasException.notFound("GL_ACCOUNT_NOT_FOUND", "GL account not found"));
        return activityRepo.findByActivityName(req.activityName())
            .map(faa -> { faa.setGlAccount(glAccount); return activityRepo.save(faa); })
            .orElseGet(() -> activityRepo.save(FinancialActivityAccount.builder()
                .activityName(req.activityName()).glAccount(glAccount).build()));
    }

    @Transactional(readOnly = true)
    public List<FinancialActivityAccount> listFinancialActivities() {
        requireContext();
        return activityRepo.findAll();
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private GlAccountResponse toResponse(GlAccount a) {
        return new GlAccountResponse(a.getId(), a.getName(), a.getGlCode(), a.getAccountType(),
            a.getAccountUsage(), a.getParent() != null ? a.getParent().getId() : null,
            a.isManualJournalEntriesAllowed(), a.isDisabled(), a.getCreatedAt());
    }
}
