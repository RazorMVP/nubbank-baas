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
public class FixedDepositService {

    private final FixedDepositRepository repo;
    private final CustomerRepository customerRepo;
    private final DepositProductRepository productRepo;
    private final VirtualAccountService virtualAccountService;

    @Transactional
    public FixedDepositResponse create(FixedDepositRequest req) {
        requireContext();
        var customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        var product = productRepo.findById(req.productId())
            .orElseThrow(() -> BaasException.notFound("PRODUCT_NOT_FOUND", "Deposit product not found"));
        String accountNumber = virtualAccountService.assignNext(PartnerContext.get().schemaName());
        var fd = FixedDepositAccount.builder()
            .customer(customer).product(product).accountNumber(accountNumber)
            .depositAmount(req.depositAmount()).interestRate(product.getNominalInterestRate())
            .depositTerm(req.depositTerm())
            .depositTermUnit(req.depositTermUnit() != null ? req.depositTermUnit() : "MONTHS")
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .build();
        return toResponse(repo.save(fd));
    }

    @Transactional
    public FixedDepositResponse executeCommand(UUID id, String command) {
        requireContext();
        var fd = findOrThrow(id);
        switch (command.toLowerCase()) {
            case "approve" -> {
                if (fd.getStatus() != FixedDepositStatus.SUBMITTED)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only approve SUBMITTED deposits");
                fd.setStatus(FixedDepositStatus.APPROVED);
            }
            case "activate" -> {
                if (fd.getStatus() != FixedDepositStatus.APPROVED)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only activate APPROVED deposits");
                fd.setStatus(FixedDepositStatus.ACTIVE);
                fd.setDepositDate(LocalDate.now());
                fd.setMaturityDate(computeMaturityDate(LocalDate.now(), fd.getDepositTerm(), fd.getDepositTermUnit()));
                fd.setMaturityAmount(computeMaturityAmount(fd.getDepositAmount(), fd.getInterestRate(), fd.getDepositTerm()));
            }
            case "reject" -> {
                if (fd.getStatus() != FixedDepositStatus.SUBMITTED)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only reject SUBMITTED deposits");
                fd.setStatus(FixedDepositStatus.REJECTED);
            }
            case "prematureclose" -> {
                if (fd.getStatus() != FixedDepositStatus.ACTIVE)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only premature-close ACTIVE deposits");
                fd.setStatus(FixedDepositStatus.PREMATURE_CLOSED);
            }
            case "mature" -> {
                if (fd.getStatus() != FixedDepositStatus.ACTIVE)
                    throw BaasException.badRequest("INVALID_STATUS", "Can only mature ACTIVE deposits");
                fd.setStatus(FixedDepositStatus.MATURED);
            }
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        return toResponse(repo.save(fd));
    }

    @Transactional(readOnly = true)
    public FixedDepositResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<FixedDepositResponse> listByCustomer(UUID customerId, int page, int size) {
        requireContext();
        return repo.findByCustomerId(customerId, PageRequest.of(page, size)).map(this::toResponse);
    }

    private LocalDate computeMaturityDate(LocalDate from, int term, String unit) {
        return "MONTHS".equalsIgnoreCase(unit) ? from.plusMonths(term) : from.plusDays(term);
    }

    private BigDecimal computeMaturityAmount(BigDecimal principal, BigDecimal annualRate, int termMonths) {
        // Simple interest: P * (1 + r * t)
        BigDecimal r = annualRate.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        BigDecimal t = BigDecimal.valueOf(termMonths).divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);
        BigDecimal interest = principal.multiply(r).multiply(t);
        return principal.add(interest).setScale(4, RoundingMode.HALF_UP);
    }

    private FixedDepositAccount findOrThrow(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> BaasException.notFound("FD_NOT_FOUND", "Fixed deposit " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null) throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private FixedDepositResponse toResponse(FixedDepositAccount fd) {
        return new FixedDepositResponse(fd.getId(), fd.getCustomer().getId(), fd.getProduct().getId(),
            fd.getAccountNumber(), fd.getDepositAmount(), fd.getMaturityAmount(),
            fd.getInterestRate(), fd.getDepositTerm(), fd.getDepositTermUnit(),
            fd.getDepositDate(), fd.getMaturityDate(), fd.getStatus(),
            fd.getCurrencyCode(), fd.getCreatedAt());
    }
}
