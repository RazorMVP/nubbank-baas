package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.compliance.ComplianceService;
import com.nubbank.baas.engine.customer.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepo;
    private final ComplianceService complianceService;
    private final NameTokenizer nameTokenizer;

    @Transactional
    public CustomerResponse create(CreateCustomerRequest req) {
        requireContext();
        if (req.externalReference() != null &&
                customerRepo.existsByExternalReference(req.externalReference())) {
            throw BaasException.conflict("DUPLICATE_EXTERNAL_REFERENCE",
                "A customer with external_reference '" + req.externalReference() + "' already exists");
        }

        Customer customer = Customer.builder()
            .externalReference(req.externalReference())
            .firstNameEncrypted(req.firstName())    // Phase 2: encrypt with Jasypt
            .lastNameEncrypted(req.lastName())
            .emailEncrypted(req.email())
            .phoneEncrypted(req.phone())
            .bvnEncrypted(req.bvn())
            .ninEncrypted(req.nin())
            .gender(req.gender())
            .dateOfBirth(parseDob(req.dateOfBirth()))
            .nameSearchTokens(nameTokenizer.tokensForName(req.firstName(), req.lastName()))
            .build();

        Customer saved = customerRepo.save(customer);

        // Compliance screen — must never block customer creation in Phase 1
        try {
            complianceService.screenCustomer(saved.getId());
        } catch (Exception e) {
            log.warn("Compliance screen failed for customer {}: {}", saved.getId(), e.getMessage());
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public CustomerDetailResponse getById(UUID id) {
        requireContext();
        Customer c = customerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + id + " not found"));
        return new CustomerDetailResponse(c.getId(), c.getExternalReference(),
            c.getFirstNameEncrypted(), c.getLastNameEncrypted(), c.getEmailEncrypted(),
            c.getPhoneEncrypted(), c.getDateOfBirth(), c.getGender(),
            mask(c.getBvnEncrypted()), mask(c.getNinEncrypted()),
            c.getKycStatus(), c.getKycLevel(), c.getCreatedAt(), c.getUpdatedAt());
    }

    /** Show only the last 4 digits, never the full identity value. */
    private static String mask(String value) {
        if (value == null || value.isBlank()) return null;
        String last4 = value.length() <= 4 ? value : value.substring(value.length() - 4);
        return "•••••••" + last4;
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(int page, int size) {
        requireContext();
        return customerRepo.findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()))
            .map(this::toResponse);
    }

    /** Parse an optional ISO-8601 (yyyy-MM-dd) date of birth; a malformed value is a 400, not a 500. */
    private static java.time.LocalDate parseDob(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw BaasException.badRequest("INVALID_DATE_OF_BIRTH",
                "dateOfBirth must be ISO-8601 (yyyy-MM-dd)");
        }
    }

    private void requireContext() {
        if (PartnerContext.get() == null) {
            throw BaasException.unauthorized("MISSING_AUTH",
                "Authorization header required — use ApiKey or Bearer JWT");
        }
    }

    private CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(c.getId(), c.getExternalReference(),
            c.getFirstNameEncrypted(), c.getLastNameEncrypted(),
            c.getEmailEncrypted(), c.getKycStatus(), c.getKycLevel(), c.getCreatedAt());
    }
}
