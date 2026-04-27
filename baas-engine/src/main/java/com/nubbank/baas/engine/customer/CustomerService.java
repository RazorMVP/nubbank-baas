package com.nubbank.baas.engine.customer;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepo;

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
            .build();

        return toResponse(customerRepo.save(customer));
    }

    @Transactional(readOnly = true)
    public CustomerResponse getById(UUID id) {
        requireContext();
        return toResponse(customerRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND",
                "Customer " + id + " not found")));
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> list(int page, int size) {
        requireContext();
        return customerRepo.findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()))
            .map(this::toResponse);
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
