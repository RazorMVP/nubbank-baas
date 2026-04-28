package com.nubbank.baas.engine.deposit;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.deposit.dto.*;
import com.nubbank.baas.engine.product.DepositProductRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import com.nubbank.baas.engine.virtualaccount.VirtualAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecurringDepositService {

    private final RecurringDepositRepository repo;
    private final CustomerRepository customerRepo;
    private final DepositProductRepository productRepo;
    private final VirtualAccountService virtualAccountService;

    @Transactional
    public RecurringDepositResponse create(RecurringDepositRequest req) {
        requireContext();
        var customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        var product = productRepo.findById(req.productId())
            .orElseThrow(() -> BaasException.notFound("PRODUCT_NOT_FOUND", "Deposit product not found"));
        String accountNumber = virtualAccountService.assignNext(PartnerContext.get().schemaName());
        return toResponse(repo.save(RecurringDepositAccount.builder()
            .customer(customer).product(product).accountNumber(accountNumber)
            .mandatoryInstallment(req.mandatoryInstallment()).totalDeposited(BigDecimal.ZERO)
            .interestRate(product.getNominalInterestRate()).depositTerm(req.depositTerm())
            .depositTermUnit(req.depositTermUnit() != null ? req.depositTermUnit() : "MONTHS")
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .build()));
    }

    @Transactional
    public RecurringDepositResponse executeCommand(UUID id, String command) {
        requireContext();
        var rd = findOrThrow(id);
        switch (command.toLowerCase()) {
            case "approve" -> {
                if (rd.getStatus() != FixedDepositStatus.SUBMITTED)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only approve SUBMITTED deposits");
                rd.setStatus(FixedDepositStatus.APPROVED);
            }
            case "activate" -> {
                if (rd.getStatus() != FixedDepositStatus.APPROVED)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only activate APPROVED deposits");
                rd.setStatus(FixedDepositStatus.ACTIVE);
                rd.setStartDate(LocalDate.now());
                rd.setMaturityDate(LocalDate.now().plusMonths(rd.getDepositTerm()));
            }
            case "reject" -> {
                if (rd.getStatus() != FixedDepositStatus.SUBMITTED)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only reject SUBMITTED deposits");
                rd.setStatus(FixedDepositStatus.REJECTED);
            }
            case "mature" -> {
                if (rd.getStatus() != FixedDepositStatus.ACTIVE)
                    throw BaasException.badRequest("INVALID_STATUS", "Only ACTIVE deposits can mature");
                rd.setStatus(FixedDepositStatus.MATURED);
                BigDecimal r = rd.getInterestRate().divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
                BigDecimal t = BigDecimal.valueOf(rd.getDepositTerm()).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
                BigDecimal interest = rd.getTotalDeposited().multiply(r).multiply(t);
                rd.setMaturityAmount(rd.getTotalDeposited().add(interest).setScale(4, RoundingMode.HALF_UP));
            }
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        return toResponse(repo.save(rd));
    }

    @Transactional(readOnly = true)
    public RecurringDepositResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<RecurringDepositResponse> listByCustomer(UUID customerId, int page, int size) {
        requireContext();
        return repo.findByCustomerId(customerId, PageRequest.of(page, size)).map(this::toResponse);
    }

    private RecurringDepositAccount findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() ->
            BaasException.notFound("RD_NOT_FOUND", "Recurring deposit " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null) throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private RecurringDepositResponse toResponse(RecurringDepositAccount rd) {
        return new RecurringDepositResponse(rd.getId(), rd.getCustomer().getId(), rd.getProduct().getId(),
            rd.getAccountNumber(), rd.getMandatoryInstallment(), rd.getTotalDeposited(),
            rd.getMaturityAmount(), rd.getInterestRate(), rd.getDepositTerm(), rd.getDepositTermUnit(),
            rd.getStartDate(), rd.getMaturityDate(), rd.getStatus(), rd.getCurrencyCode(), rd.getCreatedAt());
    }
}
