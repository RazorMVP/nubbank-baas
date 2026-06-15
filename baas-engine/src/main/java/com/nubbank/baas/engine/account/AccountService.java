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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final CustomerRepository customerRepo;
    private final VirtualAccountService virtualAccountService;
    private final ApplicationEventPublisher eventPublisher;
    private final CardAuthDebitRepository cardAuthDebitRepo;
    private final AccountStatusEventRepository statusEventRepo;

    @Transactional
    public AccountDetailResponse open(OpenAccountRequest req) {
        requireContext();
        Customer customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + req.customerId() + " not found"));

        String schema = PartnerContext.get().schemaName();
        String accountNumber = virtualAccountService.assignNext(schema);

        BigDecimal opening = req.openingDeposit() != null ? req.openingDeposit() : BigDecimal.ZERO;

        Account account = Account.builder()
            .customer(customer)
            .accountNumber(accountNumber)
            .accountTypeLabel(req.accountTypeLabel())
            .accountName(req.accountName() != null ? req.accountName()
                : customer.getFirstNameEncrypted() + " " + customer.getLastNameEncrypted())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .minimumBalance(req.minimumBalance() != null ? req.minimumBalance() : BigDecimal.ZERO)
            .balance(opening)
            .availableBalance(opening)
            .build();

        Account saved = accountRepo.save(account);

        if (opening.compareTo(BigDecimal.ZERO) > 0) {
            txRepo.save(Transaction.builder()
                .account(saved).transactionType(TransactionType.CREDIT)
                .amount(opening).runningBalance(saved.getBalance())
                .currencyCode(saved.getCurrencyCode())
                .reference("OPENING_DEPOSIT").description("Opening deposit").build());
        }

        eventPublisher.publishEvent(new AccountOpenedEvent(
            saved.getId(), customer.getId(), saved.getAccountNumber(), schema));
        return toDetail(saved);
    }

    @Transactional(readOnly = true)
    public AccountDetailResponse getById(UUID id) {
        requireContext();
        return toDetail(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<AccountSummaryResponse> list(int page, int size, String status, String search) {
        requireContext();
        AccountStatus statusFilter = null;
        if (status != null && !status.isBlank()) {
            try {
                statusFilter = AccountStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw BaasException.badRequest("INVALID_STATUS", "Unknown account status: " + status);
            }
        }
        String searchPattern = (search == null || search.isBlank())
            ? null : search.trim().toLowerCase(Locale.ROOT) + "%";
        return accountRepo.search(statusFilter, searchPattern, PageRequest.of(page, size))
            .map(this::toSummary);
    }

    private AccountSummaryResponse toSummary(Account a) {
        Customer c = a.getCustomer();
        return new AccountSummaryResponse(a.getId(), a.getAccountNumber(),
            c.getId(), customerName(c), a.getAccountTypeLabel(), a.getStatus(),
            a.getBalance(), a.getCurrencyCode());
    }

    @Transactional
    public AccountDetailResponse transition(UUID id, AccountCommand command, String reason) {
        requireContext();
        Account account = accountRepo.findByIdForUpdate(id)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND",
                "Account " + id + " not found"));
        AccountStatus from = account.getStatus();
        AccountStatus to = target(from, command);
        if (to == null) {
            throw BaasException.badRequest("INVALID_ACCOUNT_TRANSITION",
                "Cannot " + command + " an account in status " + from);
        }
        if (command == AccountCommand.CLOSE
                && account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw BaasException.conflict("ACCOUNT_BALANCE_NONZERO",
                "Account balance must be zero to close (current: " + account.getBalance() + ")");
        }
        account.setStatus(to);
        accountRepo.save(account);
        statusEventRepo.save(AccountStatusEvent.builder()
            .accountId(id).fromStatus(from.name()).toStatus(to.name())
            .reason(reason).changedBy(currentPrincipal()).build());
        return toDetail(account);
    }

    @Transactional(readOnly = true)
    public List<AccountStatusEventResponse> statusEvents(UUID id) {
        requireContext();
        if (!accountRepo.existsById(id)) {
            throw BaasException.notFound("ACCOUNT_NOT_FOUND", "Account " + id + " not found");
        }
        // oldest-first: the backoffice renders an ascending audit timeline
        return statusEventRepo.findByAccountIdOrderByChangedAtAsc(id).stream()
            .map(e -> new AccountStatusEventResponse(e.getId(), e.getFromStatus(), e.getToStatus(),
                e.getReason(), e.getChangedBy(), e.getChangedAt()))
            .toList();
    }

    /**
     * Lifecycle target state, or null if (from, command) is not a legal edge.
     * Close is reachable from ACTIVE only — a FROZEN account must be unfrozen first
     * (legal-hold realism). The zero-balance guard for CLOSE is enforced separately in
     * transition(), so a CLOSE from ACTIVE returns CLOSED here regardless of balance.
     */
    private static AccountStatus target(AccountStatus from, AccountCommand command) {
        return switch (command) {
            case FREEZE   -> from == AccountStatus.ACTIVE ? AccountStatus.FROZEN : null;
            case UNFREEZE -> from == AccountStatus.FROZEN ? AccountStatus.ACTIVE : null;
            case CLOSE    -> from == AccountStatus.ACTIVE ? AccountStatus.CLOSED : null;
        };
    }

    private String currentPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        Object principal = auth.getPrincipal();
        return (principal == null || "anonymousUser".equals(principal)) ? null : principal.toString();
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

    /**
     * Internal account-existence lookup (Stage 5) — used by card issuance to validate
     * {@code linkedAccountId} before binding a card to it.
     */
    @Transactional(readOnly = true)
    public AccountLookupResult lookupAccount(UUID accountId) {
        requireContext();
        return accountRepo.findById(accountId)
            .map(a -> new AccountLookupResult(true, a.getStatus() == AccountStatus.ACTIVE, a.getCurrencyCode()))
            .orElse(new AccountLookupResult(false, false, null));
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

    private AccountDetailResponse toDetail(Account a) {
        Customer c = a.getCustomer();
        return new AccountDetailResponse(a.getId(), a.getAccountNumber(),
            c.getId(), customerName(c), a.getAccountTypeLabel(), a.getStatus(),
            a.getBalance(), a.getAvailableBalance(), a.getCurrencyCode(),
            a.getMinimumBalance(), a.isAllowOverdraft(), a.getOverdraftLimit(),
            a.getCreatedAt());
    }

    private static String customerName(Customer c) {
        return Stream.of(c.getFirstNameEncrypted(), c.getLastNameEncrypted())
            .filter(s -> s != null && !s.isBlank())
            .collect(Collectors.joining(" "));
    }

    private TransactionResponse toTxResponse(Transaction t) {
        return new TransactionResponse(t.getId(), t.getAccount().getId(),
            t.getTransactionType(), t.getAmount(), t.getRunningBalance(),
            t.getCurrencyCode(), t.getReference(), t.getCreatedAt());
    }
}
