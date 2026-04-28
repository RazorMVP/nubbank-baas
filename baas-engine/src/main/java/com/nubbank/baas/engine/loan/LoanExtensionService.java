package com.nubbank.baas.engine.loan;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.loan.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LoanExtensionService {

    private final LoanRepository loanRepo;
    private final LoanGuarantorRepository guarantorRepo;
    private final LoanCollateralRepository collateralRepo;
    private final LoanRescheduleRepository rescheduleRepo;
    private final CustomerRepository customerRepo;

    @Transactional
    public LoanGuarantor addGuarantor(UUID loanId, GuarantorRequest req) {
        requireContext();
        Loan loan = findLoanOrThrow(loanId);
        LoanGuarantor g = LoanGuarantor.builder()
            .loan(loan)
            .guarantorType(req.guarantorType() != null ? req.guarantorType() : "EXISTING_CUSTOMER")
            .firstName(req.firstName())
            .lastName(req.lastName())
            .email(req.email())
            .phone(req.phone())
            .build();
        if ("EXISTING_CUSTOMER".equalsIgnoreCase(req.guarantorType()) && req.customerId() != null) {
            var customer = customerRepo.findById(req.customerId())
                .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Guarantor customer not found"));
            g.setCustomer(customer);
        }
        return guarantorRepo.save(g);
    }

    @Transactional
    public LoanCollateral addCollateral(UUID loanId, CollateralRequest req) {
        requireContext();
        Loan loan = findLoanOrThrow(loanId);
        return collateralRepo.save(LoanCollateral.builder()
            .loan(loan)
            .description(req.description())
            .value(req.value())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .build());
    }

    @Transactional
    public LoanRescheduleRequest createReschedule(UUID loanId, RescheduleRequest req) {
        requireContext();
        Loan loan = findLoanOrThrow(loanId);
        return rescheduleRepo.save(LoanRescheduleRequest.builder()
            .loan(loan)
            .rescheduleFromDate(req.rescheduleFromDate())
            .newInterestRate(req.newInterestRate())
            .graceOnPrincipal(req.graceOnPrincipal() != null ? req.graceOnPrincipal() : 0)
            .graceOnInterest(req.graceOnInterest() != null ? req.graceOnInterest() : 0)
            .extraTerms(req.extraTerms() != null ? req.extraTerms() : 0)
            .recalculateInterest(req.recalculateInterest() != null ? req.recalculateInterest() : true)
            .reason(req.reason())
            .build());
    }

    @Transactional
    public LoanRescheduleRequest approveReschedule(UUID rescheduleId) {
        requireContext();
        LoanRescheduleRequest r = rescheduleRepo.findById(rescheduleId)
            .orElseThrow(() -> BaasException.notFound("RESCHEDULE_NOT_FOUND", "Reschedule request not found"));
        if (!"PENDING".equals(r.getStatus()))
            throw BaasException.badRequest("INVALID_STATUS", "Only PENDING requests can be approved");
        r.setStatus("APPROVED");
        return rescheduleRepo.save(r);
    }

    @Transactional(readOnly = true)
    public List<LoanGuarantor> listGuarantors(UUID loanId) {
        requireContext();
        return guarantorRepo.findByLoanId(loanId);
    }

    @Transactional(readOnly = true)
    public List<LoanCollateral> listCollaterals(UUID loanId) {
        requireContext();
        return collateralRepo.findByLoanId(loanId);
    }

    @Transactional(readOnly = true)
    public List<LoanRescheduleRequest> listReschedules(UUID loanId) {
        requireContext();
        return rescheduleRepo.findByLoanId(loanId);
    }

    @Transactional
    public void deleteGuarantor(UUID loanId, UUID guarantorId) {
        requireContext();
        LoanGuarantor g = guarantorRepo.findById(guarantorId)
            .orElseThrow(() -> BaasException.notFound("GUARANTOR_NOT_FOUND", "Guarantor not found"));
        if (!g.getLoan().getId().equals(loanId))
            throw BaasException.forbidden("FORBIDDEN", "Guarantor does not belong to this loan");
        guarantorRepo.delete(g);
    }

    @Transactional
    public void deleteCollateral(UUID loanId, UUID collateralId) {
        requireContext();
        LoanCollateral c = collateralRepo.findById(collateralId)
            .orElseThrow(() -> BaasException.notFound("COLLATERAL_NOT_FOUND", "Collateral not found"));
        if (!c.getLoan().getId().equals(loanId))
            throw BaasException.forbidden("FORBIDDEN", "Collateral does not belong to this loan");
        collateralRepo.delete(c);
    }

    private Loan findLoanOrThrow(UUID id) {
        return loanRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("LOAN_NOT_FOUND", "Loan not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
