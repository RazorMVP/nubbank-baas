package com.nubbank.baas.engine.standing;

import com.nubbank.baas.engine.account.AccountRepository;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.standing.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StandingInstructionService {

    private final StandingInstructionRepository siRepo;
    private final BeneficiaryRepository benRepo;
    private final CustomerRepository customerRepo;
    private final AccountRepository accountRepo;

    @Transactional
    public StandingInstruction create(StandingInstructionRequest req) {
        requireContext();
        var customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        var source = accountRepo.findById(req.sourceAccountId())
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Source account not found"));
        var dest = accountRepo.findById(req.destinationAccountId())
            .orElseThrow(() -> BaasException.notFound("ACCOUNT_NOT_FOUND", "Destination account not found"));

        return siRepo.save(StandingInstruction.builder()
            .customer(customer).sourceAccount(source).destinationAccount(dest)
            .name(req.name())
            .instructionType(req.instructionType() != null ? req.instructionType() : "FIXED")
            .priority(req.priority() != null ? req.priority() : "MEDIUM")
            .amount(req.amount())
            .recurrenceFrequency(req.recurrenceFrequency() != null ? req.recurrenceFrequency() : "MONTHS")
            .recurrenceInterval(req.recurrenceInterval() != null ? req.recurrenceInterval() : 1)
            .validFrom(req.validFrom()).validTo(req.validTo())
            .build());
    }

    @Transactional
    public StandingInstruction executeCommand(UUID id, String command) {
        requireContext();
        StandingInstruction si = siRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("SI_NOT_FOUND", "Standing instruction not found"));
        switch (command.toLowerCase()) {
            case "disable" -> {
                if (!"ACTIVE".equals(si.getStatus()))
                    throw BaasException.badRequest("INVALID_STATUS", "Only ACTIVE instructions can be disabled");
                si.setStatus("DISABLED");
            }
            case "enable" -> {
                if (!"DISABLED".equals(si.getStatus()))
                    throw BaasException.badRequest("INVALID_STATUS", "Only DISABLED instructions can be enabled");
                si.setStatus("ACTIVE");
            }
            case "delete" -> si.setStatus("DELETED");
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        return siRepo.save(si);
    }

    @Transactional(readOnly = true)
    public Page<StandingInstruction> listByCustomer(UUID customerId, int page, int size) {
        requireContext();
        return siRepo.findByCustomerId(customerId, PageRequest.of(page, size));
    }

    @Transactional
    public Beneficiary addBeneficiary(UUID customerId, BeneficiaryRequest req) {
        requireContext();
        var customer = customerRepo.findById(customerId)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        return benRepo.save(Beneficiary.builder()
            .customer(customer).accountNumber(req.accountNumber())
            .accountName(req.accountName()).bankCode(req.bankCode())
            .bankName(req.bankName()).transferLimit(req.transferLimit())
            .build());
    }

    @Transactional(readOnly = true)
    public Page<Beneficiary> listBeneficiaries(UUID customerId, int page, int size) {
        requireContext();
        return benRepo.findByCustomerIdAndActiveTrue(customerId, PageRequest.of(page, size));
    }

    @Transactional
    public void deleteBeneficiary(UUID customerId, UUID beneficiaryId) {
        requireContext();
        Beneficiary b = benRepo.findByIdAndCustomerId(beneficiaryId, customerId)
            .orElseThrow(() -> BaasException.notFound("BENEFICIARY_NOT_FOUND",
                "Beneficiary not found or does not belong to this customer"));
        b.setActive(false);
        benRepo.save(b);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
