package com.nubbank.baas.engine.teller;

import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.teller.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TellerService {

    private final TellerRepository tellerRepo;
    private final CashierRepository cashierRepo;
    private final TellerSessionRepository sessionRepo;
    private final CashTransactionRepository cashTxRepo;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;

    @Transactional
    public Teller createTeller(TellerRequest req) {
        requireContext();
        return tellerRepo.save(Teller.builder().name(req.name()).description(req.description()).build());
    }

    @Transactional
    public Teller executeCommand(UUID id, String command) {
        requireContext();
        Teller teller = tellerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("TELLER_NOT_FOUND", "Teller not found"));
        switch (command.toLowerCase()) {
            case "activate" -> {
                if (teller.getStatus() != TellerStatus.INACTIVE)
                    throw BaasException.badRequest("INVALID_STATUS", "Only INACTIVE tellers can be activated");
                teller.setStatus(TellerStatus.ACTIVE);
            }
            case "close" -> {
                if (teller.getStatus() != TellerStatus.ACTIVE)
                    throw BaasException.badRequest("INVALID_STATUS", "Only ACTIVE tellers can be closed");
                teller.setStatus(TellerStatus.CLOSED);
            }
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        return tellerRepo.save(teller);
    }

    @Transactional
    public Cashier addCashier(UUID tellerId, CashierRequest req) {
        requireContext();
        Teller teller = tellerRepo.findById(tellerId)
            .orElseThrow(() -> BaasException.notFound("TELLER_NOT_FOUND", "Teller not found"));
        return cashierRepo.save(Cashier.builder()
            .teller(teller).staffId(req.staffId()).description(req.description())
            .isFullDay(req.isFullDay() == null || req.isFullDay())
            .build());
    }

    @Transactional
    public TellerSession openSession(UUID tellerId, OpenSessionRequest req) {
        requireContext();
        Teller teller = tellerRepo.findById(tellerId)
            .orElseThrow(() -> BaasException.notFound("TELLER_NOT_FOUND", "Teller not found"));
        if (teller.getStatus() != TellerStatus.ACTIVE)
            throw BaasException.badRequest("TELLER_NOT_ACTIVE", "Teller must be ACTIVE");
        Cashier cashier = cashierRepo.findById(req.cashierId())
            .orElseThrow(() -> BaasException.notFound("CASHIER_NOT_FOUND", "Cashier not found"));
        LocalDate today = LocalDate.now();
        if (sessionRepo.findByCashierIdAndSessionDate(req.cashierId(), today).isPresent())
            throw BaasException.conflict("SESSION_ALREADY_OPEN", "Cashier already has an open session today");
        return sessionRepo.save(TellerSession.builder()
            .teller(teller).cashier(cashier).sessionDate(today)
            .openingBalance(req.openingBalance())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .build());
    }

    @Transactional
    public CashTransaction addCashTransaction(UUID tellerId, UUID sessionId, CashTransactionRequest req) {
        requireContext();
        TellerSession session = sessionRepo.findById(sessionId)
            .orElseThrow(() -> BaasException.notFound("SESSION_NOT_FOUND", "Session not found"));
        if (!"OPEN".equals(session.getStatus()))
            throw BaasException.badRequest("SESSION_NOT_OPEN", "Session must be OPEN");

        UUID accountId = req.accountId();
        if (accountId != null) {
            Account account = accountRepo.findByIdForUpdate(accountId)
                .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
            if ("CASH_IN".equals(req.transactionType())) {
                account.setBalance(account.getBalance().add(req.amount()));
                account.setAvailableBalance(account.getAvailableBalance().add(req.amount()));
                txRepo.save(Transaction.builder().account(account)
                    .transactionType(TransactionType.CREDIT).amount(req.amount())
                    .runningBalance(account.getBalance()).currencyCode(session.getCurrencyCode())
                    .description("Teller cash-in").build());
            } else if ("CASH_OUT".equals(req.transactionType())) {
                if (account.getBalance().compareTo(req.amount()) < 0)
                    throw BaasException.badRequest("INSUFFICIENT_BALANCE", "Insufficient account balance");
                account.setBalance(account.getBalance().subtract(req.amount()));
                account.setAvailableBalance(account.getAvailableBalance().subtract(req.amount()));
                txRepo.save(Transaction.builder().account(account)
                    .transactionType(TransactionType.DEBIT).amount(req.amount())
                    .runningBalance(account.getBalance()).currencyCode(session.getCurrencyCode())
                    .description("Teller cash-out").build());
            }
            accountRepo.save(account);
        }
        return cashTxRepo.save(CashTransaction.builder()
            .session(session).transactionType(req.transactionType())
            .amount(req.amount()).accountId(accountId).description(req.description())
            .build());
    }

    @Transactional
    public TellerSession settleSession(UUID tellerId, UUID sessionId, SettleRequest req) {
        requireContext();
        TellerSession session = sessionRepo.findById(sessionId)
            .orElseThrow(() -> BaasException.notFound("SESSION_NOT_FOUND", "Session not found"));
        if (!"OPEN".equals(session.getStatus()))
            throw BaasException.badRequest("SESSION_NOT_OPEN", "Only OPEN sessions can be settled");
        List<CashTransaction> txns = cashTxRepo.findBySessionId(sessionId);
        BigDecimal totalIn = txns.stream()
            .filter(t -> "CASH_IN".equals(t.getTransactionType()))
            .map(CashTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOut = txns.stream()
            .filter(t -> "CASH_OUT".equals(t.getTransactionType()))
            .map(CashTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal closingBalance = session.getOpeningBalance().add(totalIn).subtract(totalOut);
        session.setClosingBalance(closingBalance);
        session.setActualCash(req.actualCash());
        session.setDifference(req.actualCash().subtract(closingBalance));
        session.setStatus("CLOSED");
        session.setClosedAt(Instant.now());
        return sessionRepo.save(session);
    }

    @Transactional(readOnly = true)
    public Teller getById(UUID id) {
        requireContext();
        return tellerRepo.findById(id).orElseThrow(() -> BaasException.notFound("TELLER_NOT_FOUND", "Teller not found"));
    }

    @Transactional(readOnly = true)
    public List<Teller> listAll() { requireContext(); return tellerRepo.findAll(); }

    @Transactional(readOnly = true)
    public List<TellerSession> listSessions(UUID tellerId) { requireContext(); return sessionRepo.findByTellerId(tellerId); }

    private void requireContext() {
        if (PartnerContext.get() == null) throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
