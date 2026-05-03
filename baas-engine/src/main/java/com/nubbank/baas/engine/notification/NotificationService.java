package com.nubbank.baas.engine.notification;

import com.nubbank.baas.engine.notification.events.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.data.domain.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationEventRepository repo;

    @Transactional
    public NotificationEvent logNotification(String eventType, String entityType, UUID entityId,
                                              NotificationChannel channel, String recipient,
                                              String subject, String payload) {
        return repo.save(NotificationEvent.builder()
            .eventType(eventType).entityType(entityType).entityId(entityId)
            .channel(channel).recipient(recipient).subject(subject)
            .payload(payload).status("PENDING").build());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLoanApproved(LoanApprovedEvent event) {
        safeLog("LOAN_APPROVED", "LOAN", event.loanId(), NotificationChannel.EMAIL, null,
            "Loan Approved",
            "{\"loanId\":\"" + event.loanId() + "\",\"amount\":" + event.amount() + "}",
            event.schemaName());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLoanDisbursed(LoanDisbursedEvent event) {
        safeLog("LOAN_DISBURSED", "LOAN", event.loanId(), NotificationChannel.EMAIL, null,
            "Loan Disbursed",
            "{\"loanId\":\"" + event.loanId() + "\",\"amount\":" + event.amount() + "}",
            event.schemaName());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountOpened(AccountOpenedEvent event) {
        safeLog("ACCOUNT_OPENED", "ACCOUNT", event.accountId(), NotificationChannel.EMAIL, null,
            "Account Opened",
            "{\"accountId\":\"" + event.accountId() + "\",\"accountNumber\":\""
                + event.accountNumber() + "\"}",
            event.schemaName());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        safeLog("PAYMENT_COMPLETED", "PAYMENT", event.paymentId(), NotificationChannel.EMAIL, null,
            "Payment Completed",
            "{\"paymentId\":\"" + event.paymentId() + "\",\"amount\":" + event.amount() + "}",
            event.schemaName());
    }

    @Transactional(readOnly = true)
    public Page<NotificationEvent> list(String status, int page, int size) {
        PageRequest pr = PageRequest.of(page, size);
        if (status != null && !status.isBlank())
            return repo.findByStatusOrderByCreatedAtDesc(status.toUpperCase(), pr);
        return repo.findAllByOrderByCreatedAtDesc(pr);
    }

    private void safeLog(String eventType, String entityType, UUID entityId,
                          NotificationChannel channel, String recipient,
                          String subject, String payload, String schemaName) {
        try {
            PartnerContext.set(new PartnerContext("system", schemaName, "BASIC", "PRODUCTION", "EVENT", null));
            logNotification(eventType, entityType, entityId, channel, recipient, subject, payload);
        } catch (Exception e) {
            log.error("Failed to log notification event: {}", eventType, e);
        } finally {
            PartnerContext.clear();
        }
    }
}
