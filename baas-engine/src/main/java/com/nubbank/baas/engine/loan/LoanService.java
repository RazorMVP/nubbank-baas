package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.account.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.loan.dto.*;
import com.nubbank.baas.engine.notification.events.LoanApprovedEvent;
import com.nubbank.baas.engine.notification.events.LoanDisbursedEvent;
import com.nubbank.baas.engine.product.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.virtualaccount.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoanService {

    private final LoanRepository loanRepo;
    private final LoanRepaymentScheduleRepository scheduleRepo;
    private final CustomerRepository customerRepo;
    private final LoanProductRepository loanProductRepo;
    private final AccountRepository accountRepo;
    private final TransactionRepository txRepo;
    private final VirtualAccountService virtualAccountService;
    private final EmiCalculator emiCalculator;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public LoanResponse apply(ApplyLoanRequest req) {
        requireContext();
        var customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        var product = loanProductRepo.findById(req.loanProductId())
            .orElseThrow(() -> BaasException.notFound("LOAN_PRODUCT_NOT_FOUND", "Loan product not found"));

        if (req.principalAmount().compareTo(product.getMinPrincipal()) < 0
                || req.principalAmount().compareTo(product.getMaxPrincipal()) > 0)
            throw BaasException.badRequest("PRINCIPAL_OUT_OF_RANGE",
                "Principal must be between " + product.getMinPrincipal() + " and " + product.getMaxPrincipal());

        String loanNumber = "LN" + virtualAccountService.assignNext(PartnerContext.get().schemaName());

        Account linkedAccount = null;
        if (req.linkedAccountId() != null)
            linkedAccount = accountRepo.findById(req.linkedAccountId())
                .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Linked account not found"));

        Loan loan = loanRepo.save(Loan.builder()
            .customer(customer).loanProduct(product)
            .loanAccountNumber(loanNumber)
            .principalAmount(req.principalAmount())
            .outstandingBalance(BigDecimal.ZERO)
            .interestRate(product.getNominalInterestRate())
            .numberOfRepayments(req.numberOfRepayments())
            .repaymentEvery(req.repaymentEvery() != null ? req.repaymentEvery() : 1)
            .repaymentFrequency(req.repaymentFrequency() != null ? req.repaymentFrequency() : "MONTHS")
            .linkedAccount(linkedAccount)
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .build());

        return toResponse(loan);
    }

    @Transactional
    public LoanResponse executeCommand(UUID id, String command, String note) {
        requireContext();
        Loan loan = loanRepo.findByIdForUpdate(id)
            .orElseThrow(() -> BaasException.notFound("LOAN_NOT_FOUND", "Loan not found"));

        switch (command.toLowerCase()) {
            case "approve" -> approve(loan);
            case "reject" -> {
                if (loan.getStatus() != LoanStatus.SUBMITTED && loan.getStatus() != LoanStatus.UNDER_REVIEW)
                    throw BaasException.badRequest("INVALID_STATUS", "Only SUBMITTED loans can be rejected");
                loan.setStatus(LoanStatus.REJECTED);
                loan.setRejectedOn(Instant.now());
                loan.setRejectionReason(note);
            }
            case "disburse" -> disburse(loan);
            case "writeoff" -> {
                if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.IN_ARREARS)
                    throw BaasException.badRequest("INVALID_STATUS", "Only ACTIVE loans can be written off");
                loan.setStatus(LoanStatus.WRITTEN_OFF);
            }
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        return toResponse(loanRepo.save(loan));
    }

    private void approve(Loan loan) {
        if (loan.getStatus() != LoanStatus.SUBMITTED && loan.getStatus() != LoanStatus.UNDER_REVIEW)
            throw BaasException.badRequest("INVALID_STATUS", "Only SUBMITTED loans can be approved");

        loan.setStatus(LoanStatus.APPROVED);
        loan.setApprovedPrincipal(loan.getPrincipalAmount());
        loan.setApprovedOn(Instant.now());
        loan.setExpectedDisbursementDate(LocalDate.now().plusDays(1));

        // Need to flush loan to get its managed ID for schedule FK
        loanRepo.flush();

        List<EmiCalculator.ScheduleItem> items = emiCalculator.generate(
            loan.getPrincipalAmount(), loan.getInterestRate(),
            loan.getNumberOfRepayments(), loan.getLoanProduct().getRepaymentType(),
            LocalDate.now().plusMonths(1), loan.getRepaymentEvery(), loan.getRepaymentFrequency());

        loan.setMaturityDate(items.get(items.size() - 1).dueDate());

        for (EmiCalculator.ScheduleItem item : items) {
            scheduleRepo.save(LoanRepaymentSchedule.builder()
                .loan(loan).installmentNo(item.installmentNo()).dueDate(item.dueDate())
                .principalDue(item.principalDue()).interestDue(item.interestDue()).totalDue(item.totalDue())
                .build());
        }

        // Publish — listeners use @TransactionalEventListener(AFTER_COMMIT) so
        // the notification only fires if this transaction commits successfully.
        eventPublisher.publishEvent(new LoanApprovedEvent(
            loan.getId(),
            loan.getCustomer() != null ? loan.getCustomer().getId() : null,
            loan.getPrincipalAmount(),
            PartnerContext.get().schemaName()));
    }

    private void disburse(Loan loan) {
        if (loan.getStatus() != LoanStatus.APPROVED)
            throw BaasException.badRequest("INVALID_STATUS", "Only APPROVED loans can be disbursed");
        if (loan.getLinkedAccount() == null)
            throw BaasException.badRequest("NO_LINKED_ACCOUNT", "Loan must have a linked account for disbursement");

        Account account = accountRepo.findByIdForUpdate(loan.getLinkedAccount().getId())
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Linked account not found"));
        if (account.getStatus() != AccountStatus.ACTIVE)
            throw BaasException.badRequest("ACCOUNT_NOT_ACTIVE", "Linked account must be ACTIVE");

        account.setBalance(account.getBalance().add(loan.getPrincipalAmount()));
        account.setAvailableBalance(account.getAvailableBalance().add(loan.getPrincipalAmount()));
        accountRepo.save(account);

        txRepo.save(Transaction.builder()
            .account(account).transactionType(TransactionType.CREDIT)
            .amount(loan.getPrincipalAmount()).runningBalance(account.getBalance())
            .currencyCode(loan.getCurrencyCode())
            .reference("LOAN-DISBURSEMENT-" + loan.getLoanAccountNumber())
            .description("Loan disbursement").build());

        loan.setStatus(LoanStatus.ACTIVE);
        loan.setDisbursementDate(LocalDate.now());
        loan.setDisbursedOn(Instant.now());
        loan.setOutstandingBalance(loan.getPrincipalAmount());

        eventPublisher.publishEvent(new LoanDisbursedEvent(
            loan.getId(),
            loan.getCustomer() != null ? loan.getCustomer().getId() : null,
            loan.getPrincipalAmount(),
            PartnerContext.get().schemaName()));
    }

    @Transactional
    public LoanResponse repay(UUID loanId, BigDecimal amount) {
        requireContext();
        Loan loan = loanRepo.findByIdForUpdate(loanId)
            .orElseThrow(() -> BaasException.notFound("LOAN_NOT_FOUND", "Loan not found"));

        if (loan.getStatus() != LoanStatus.ACTIVE && loan.getStatus() != LoanStatus.IN_ARREARS)
            throw BaasException.badRequest("INVALID_STATUS", "Loan must be ACTIVE or IN_ARREARS to accept repayments");

        List<LoanRepaymentSchedule> unpaid = scheduleRepo.findByLoanIdAndStatusInOrderByInstallmentNo(
            loanId, List.of(RepaymentStatus.PENDING, RepaymentStatus.PARTIALLY_PAID, RepaymentStatus.OVERDUE));

        BigDecimal remaining = amount;
        for (LoanRepaymentSchedule inst : unpaid) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal outstanding = inst.getTotalDue().subtract(inst.getTotalPaid());
            BigDecimal toApply = remaining.min(outstanding);
            // Interest first, then principal
            BigDecimal interestOutstanding = inst.getInterestDue().subtract(inst.getInterestPaid());
            BigDecimal interestToApply = toApply.min(interestOutstanding);
            BigDecimal principalToApply = toApply.subtract(interestToApply);
            inst.setInterestPaid(inst.getInterestPaid().add(interestToApply));
            inst.setPrincipalPaid(inst.getPrincipalPaid().add(principalToApply));
            inst.setTotalPaid(inst.getTotalPaid().add(toApply));
            if (inst.getTotalPaid().compareTo(inst.getTotalDue()) >= 0) {
                inst.setStatus(RepaymentStatus.PAID);
                inst.setCompletedOn(LocalDate.now());
            } else {
                inst.setStatus(RepaymentStatus.PARTIALLY_PAID);
            }
            scheduleRepo.save(inst);
            remaining = remaining.subtract(toApply);
        }

        BigDecimal applied = amount.subtract(remaining);
        loan.setOutstandingBalance(loan.getOutstandingBalance().subtract(applied));

        if (loan.getOutstandingBalance().compareTo(BigDecimal.valueOf(0.01)) <= 0) {
            loan.setOutstandingBalance(BigDecimal.ZERO);
            loan.setStatus(LoanStatus.CLOSED_OBLIGATIONS_MET);
        }

        return toResponse(loanRepo.save(loan));
    }

    @Transactional(readOnly = true)
    public LoanResponse getById(UUID id) {
        requireContext();
        return toResponse(loanRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("LOAN_NOT_FOUND", "Loan " + id + " not found")));
    }

    @Transactional(readOnly = true)
    public Page<LoanResponse> listByCustomer(UUID customerId, int page, int size) {
        requireContext();
        return loanRepo.findByCustomerId(customerId, PageRequest.of(page, size)).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<LoanResponse> listByStatus(String status, int page, int size) {
        requireContext();
        try {
            LoanStatus loanStatus = LoanStatus.valueOf(status.toUpperCase());
            return loanRepo.findByStatus(loanStatus, PageRequest.of(page, size)).map(this::toResponse);
        } catch (IllegalArgumentException e) {
            throw BaasException.badRequest("INVALID_STATUS", "Unknown status: " + status);
        }
    }

    @Transactional(readOnly = true)
    public Page<ScheduleLineResponse> getSchedule(UUID loanId, int page, int size) {
        requireContext();
        return scheduleRepo.findByLoanIdOrderByInstallmentNo(loanId, PageRequest.of(page, size))
            .map(s -> new ScheduleLineResponse(s.getId(), s.getInstallmentNo(), s.getDueDate(),
                s.getPrincipalDue(), s.getInterestDue(), s.getTotalDue(),
                s.getPrincipalPaid(), s.getInterestPaid(), s.getTotalPaid(), s.getStatus()));
    }

    private void requireContext() {
        if (PartnerContext.get() == null) throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private LoanResponse toResponse(Loan l) {
        return new LoanResponse(l.getId(), l.getCustomer().getId(), l.getLoanProduct().getId(),
            l.getLoanAccountNumber(), l.getPrincipalAmount(), l.getApprovedPrincipal(),
            l.getOutstandingBalance(), l.getInterestRate(), l.getNumberOfRepayments(),
            l.getDisbursementDate(), l.getMaturityDate(), l.getStatus(), l.getCurrencyCode(), l.getCreatedAt());
    }
}
