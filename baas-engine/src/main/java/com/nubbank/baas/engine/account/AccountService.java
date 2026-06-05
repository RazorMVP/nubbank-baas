package com.nubbank.baas.engine.account;

import com.nubbank.baas.engine.account.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.*;
import com.nubbank.baas.engine.notification.events.AccountOpenedEvent;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.virtualaccount.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final CustomerRepository customerRepo;
    private final VirtualAccountService virtualAccountService;
    private final ApplicationEventPublisher eventPublisher;
    private final CardAuthDebitRepository cardAuthDebitRepo;

    @Transactional
    public AccountResponse open(OpenAccountRequest req) {
        requireContext();
        Customer customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + req.customerId() + " not found"));

        String schema = PartnerContext.get().schemaName();
        String accountNumber = virtualAccountService.assignNext(schema);

        Account account = Account.builder()
            .customer(customer)
            .accountNumber(accountNumber)
            .accountTypeLabel(req.accountTypeLabel())
            .accountName(req.accountName() != null ? req.accountName()
                : customer.getFirstNameEncrypted() + " " + customer.getLastNameEncrypted())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .minimumBalance(req.minimumBalance() != null ? req.minimumBalance() : BigDecimal.ZERO)
            .build();

        Account saved = accountRepo.save(account);
        eventPublisher.publishEvent(new AccountOpenedEvent(
            saved.getId(), customer.getId(), saved.getAccountNumber(), schema));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public AccountResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public TransactionResponse deposit(UUID accountId, TransactionRequest req) {
        requireContext();
        Account account = accountRepo.findByIdForUpdate(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE",
                "Account must be ACTIVE to accept deposits");
        }

        account.setBalance(account.getBalance().add(req.amount()));
        account.setAvailableBalance(account.getAvailableBalance().add(req.amount()));
        accountRepo.save(account);

        return toTxResponse(txRepo.save(Transaction.builder()
            .account(account).transactionType(TransactionType.CREDIT)
            .amount(req.amount()).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .reference(req.reference()).description(req.description()).build()));
    }

    @Transactional
    public TransactionResponse withdraw(UUID accountId, TransactionRequest req) {
        requireContext();
        Account account = accountRepo.findByIdForUpdate(accountId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE",
                "Account must be ACTIVE for withdrawals");
        }

        BigDecimal floor = account.isAllowOverdraft() && account.getOverdraftLimit() != null
            ? account.getOverdraftLimit().negate()
            : account.getMinimumBalance();

        if (account.getBalance().subtract(req.amount()).compareTo(floor) < 0) {
            throw BaasException.badRequest("INSUFFICIENT_BALANCE",
                "Insufficient balance for this withdrawal");
        }

        account.setBalance(account.getBalance().subtract(req.amount()));
        account.setAvailableBalance(account.getAvailableBalance().subtract(req.amount()));
        accountRepo.save(account);

        return toTxResponse(txRepo.save(Transaction.builder()
            .account(account).transactionType(TransactionType.DEBIT)
            .amount(req.amount()).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .reference(req.reference()).description(req.description()).build()));
    }

    /**
     * Internal card-authorization debit (Stage 5). Atomic + idempotent on
     * {@code req.authKey()}: a repeat call with the same key returns the stored outcome
     * and moves no money. The engine is the money-dedupe authority. Single-message model —
     * the authorization IS the debit. {@code req.currency()} is ISO 4217 alphabetic.
     */
    @Transactional
    public CardDebitResult cardAuthorizationDebit(CardDebitRequest req) {
        requireContext();
        var existing = cardAuthDebitRepo.findByAuthKey(req.authKey());
        if (existing.isPresent()) {
            return new CardDebitResult(existing.get().getOutcome());   // idempotent: no second debit
        }
        Account account = accountRepo.findByIdForUpdate(req.accountId()).orElse(null);
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            return record(req, CardAuthOutcome.ACCOUNT_INVALID, null);
        }
        if (!account.getCurrencyCode().equals(req.currency())) {
            return record(req, CardAuthOutcome.CURRENCY_MISMATCH, null);
        }
        BigDecimal floor = account.isAllowOverdraft() && account.getOverdraftLimit() != null
            ? account.getOverdraftLimit().negate()
            : account.getMinimumBalance();
        if (account.getBalance().subtract(req.amount()).compareTo(floor) < 0) {
            return record(req, CardAuthOutcome.INSUFFICIENT, null);
        }
        account.setBalance(account.getBalance().subtract(req.amount()));
        account.setAvailableBalance(account.getAvailableBalance().subtract(req.amount()));
        accountRepo.save(account);
        Transaction txn = txRepo.save(Transaction.builder()
            .account(account).transactionType(TransactionType.DEBIT)
            .amount(req.amount()).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .reference("CARD_AUTH").description("Card authorization " + req.authKey())
            .build());
        return record(req, CardAuthOutcome.DEBITED, txn.getId());
    }

    private CardDebitResult record(CardDebitRequest req, CardAuthOutcome outcome, UUID txnId) {
        cardAuthDebitRepo.save(CardAuthDebit.builder()
            .authKey(req.authKey()).accountId(req.accountId()).amount(req.amount())
            .currencyCode(req.currency()).outcome(outcome).transactionId(txnId).reversed(false).build());
        return new CardDebitResult(outcome);
    }

    /**
     * Internal card-authorization credit / reversal (Stage 5). Idempotent on {@code authKey}:
     * credits the original debit back exactly once. {@code located=false} when no DEBITED row
     * exists for the key (card maps to DE39 25). Crediting an already-reversed or
     * never-debited row is a no-op.
     */
    @Transactional
    public CardCreditResult cardAuthorizationCredit(String authKey) {
        requireContext();
        CardAuthDebit row = cardAuthDebitRepo.findByAuthKey(authKey).orElse(null);
        if (row == null || row.getOutcome() != CardAuthOutcome.DEBITED) {
            return new CardCreditResult(false);
        }
        if (row.isReversed()) {
            return new CardCreditResult(true);   // idempotent: already credited
        }
        Account account = accountRepo.findByIdForUpdate(row.getAccountId())
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
        account.setBalance(account.getBalance().add(row.getAmount()));
        account.setAvailableBalance(account.getAvailableBalance().add(row.getAmount()));
        accountRepo.save(account);
        txRepo.save(Transaction.builder()
            .account(account).transactionType(TransactionType.CREDIT)
            .amount(row.getAmount()).runningBalance(account.getBalance())
            .currencyCode(account.getCurrencyCode())
            .reference("CARD_REVERSAL").description("Card reversal " + authKey).build());
        row.setReversed(true);
        cardAuthDebitRepo.save(row);
        return new CardCreditResult(true);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(UUID accountId, int page, int size) {
        requireContext();
        findOrThrow(accountId);
        return txRepo.findByAccountIdOrderByCreatedAtDesc(accountId,
            PageRequest.of(page, size)).map(this::toTxResponse);
    }

    private Account findOrThrow(UUID id) {
        return accountRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND",
                "Account " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(a.getId(), a.getCustomer().getId(),
            a.getAccountNumber(), a.getAccountTypeLabel(), a.getStatus(),
            a.getBalance(), a.getAvailableBalance(), a.getCurrencyCode(), a.getCreatedAt());
    }

    private TransactionResponse toTxResponse(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccount().getId(),
            t.getTransactionType(), t.getAmount(), t.getRunningBalance(),
            t.getCurrencyCode(), t.getReference(), t.getCreatedAt());
    }
}
