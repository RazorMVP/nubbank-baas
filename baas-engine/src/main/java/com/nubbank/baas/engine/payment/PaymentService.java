package com.nubbank.baas.engine.payment;

import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.notification.events.PaymentCompletedEvent;
import com.nubbank.baas.engine.payment.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepo;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PaymentResponse transfer(TransferRequest req) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");

        // Idempotency check — before any locks are acquired
        if (req.idempotencyKey() != null) {
            var existing = paymentRepo.findByIdempotencyKey(req.idempotencyKey());
            if (existing.isPresent()) return toResponse(existing.get());
        }

        if (req.sourceAccountId().equals(req.destinationAccountId())) {
            throw BaasException.badRequest("SAME_ACCOUNT_TRANSFER",
                "Source and destination must be different accounts");
        }

        // Lock both accounts in consistent UUID order to prevent deadlocks.
        // Without this, two concurrent transfers between A↔B can each lock their
        // source first and then block waiting for the other's lock — classic deadlock.
        UUID firstId = req.sourceAccountId().compareTo(req.destinationAccountId()) < 0
            ? req.sourceAccountId() : req.destinationAccountId();
        UUID secondId = firstId.equals(req.sourceAccountId())
            ? req.destinationAccountId() : req.sourceAccountId();

        Account first = accountRepo.findByIdForUpdate(firstId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND",
                "Account not found: " + firstId));
        Account second = accountRepo.findByIdForUpdate(secondId)
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND",
                "Account not found: " + secondId));

        Account source = first.getId().equals(req.sourceAccountId()) ? first : second;
        Account destination = first.getId().equals(req.destinationAccountId()) ? first : second;

        if (source.getStatus() != AccountStatus.ACTIVE)
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE", "Source account is not ACTIVE");
        if (destination.getStatus() != AccountStatus.ACTIVE)
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE", "Destination account is not ACTIVE");

        BigDecimal floor = source.isAllowOverdraft() && source.getOverdraftLimit() != null
            ? source.getOverdraftLimit().negate() : source.getMinimumBalance();
        if (source.getBalance().subtract(req.amount()).compareTo(floor) < 0)
            throw BaasException.badRequest("INSUFFICIENT_BALANCE", "Insufficient balance");

        // Debit source
        source.setBalance(source.getBalance().subtract(req.amount()));
        source.setAvailableBalance(source.getAvailableBalance().subtract(req.amount()));
        accountRepo.save(source);

        // Credit destination
        destination.setBalance(destination.getBalance().add(req.amount()));
        destination.setAvailableBalance(destination.getAvailableBalance().add(req.amount()));
        accountRepo.save(destination);

        // Create payment record
        Payment payment = Payment.builder()
            .sourceAccount(source).destinationAccount(destination)
            .amount(req.amount())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .paymentType(PaymentType.INTERNAL).status(PaymentStatus.COMPLETED)
            .reference(req.reference()).description(req.description())
            .idempotencyKey(req.idempotencyKey()).build();
        payment = paymentRepo.save(payment);

        // Create immutable double-entry ledger records
        txRepo.save(Transaction.builder().account(source)
            .transactionType(TransactionType.DEBIT).amount(req.amount())
            .runningBalance(source.getBalance()).paymentId(payment.getId()).build());
        txRepo.save(Transaction.builder().account(destination)
            .transactionType(TransactionType.CREDIT).amount(req.amount())
            .runningBalance(destination.getBalance()).paymentId(payment.getId()).build());

        eventPublisher.publishEvent(new PaymentCompletedEvent(
            payment.getId(), source.getId(), destination.getId(),
            payment.getAmount(), PartnerContext.get().schemaName()));

        return toResponse(payment);
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getId(),
            p.getSourceAccount() != null ? p.getSourceAccount().getId() : null,
            p.getDestinationAccount() != null ? p.getDestinationAccount().getId() : null,
            p.getAmount(), p.getCurrencyCode(), p.getPaymentType(), p.getStatus(),
            p.getReference(), p.getCreatedAt());
    }
}
