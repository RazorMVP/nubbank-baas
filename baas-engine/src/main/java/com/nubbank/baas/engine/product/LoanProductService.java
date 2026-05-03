package com.nubbank.baas.engine.product;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.product.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoanProductService {

    private final LoanProductRepository repo;

    @Transactional
    public LoanProductResponse create(LoanProductRequest req) {
        requireContext();
        if (repo.findByShortName(req.shortName()).isPresent())
            throw BaasException.conflict("DUPLICATE_SHORT_NAME",
                "Loan product with shortName '" + req.shortName() + "' already exists");
        if (req.defaultPrincipal().compareTo(req.minPrincipal()) < 0
                || req.defaultPrincipal().compareTo(req.maxPrincipal()) > 0)
            throw BaasException.badRequest("INVALID_DEFAULT_PRINCIPAL",
                "defaultPrincipal must be between minPrincipal and maxPrincipal");
        return toResponse(repo.save(LoanProduct.builder()
            .name(req.name()).shortName(req.shortName()).description(req.description())
            .minPrincipal(req.minPrincipal()).maxPrincipal(req.maxPrincipal())
            .defaultPrincipal(req.defaultPrincipal()).nominalInterestRate(req.nominalInterestRate())
            .repaymentType(req.repaymentType() != null ? req.repaymentType() : RepaymentType.ANNUITY)
            .numberOfRepayments(req.numberOfRepayments())
            .repaymentEvery(req.repaymentEvery() != null ? req.repaymentEvery() : 1)
            .repaymentFrequency(req.repaymentFrequency() != null ? req.repaymentFrequency() : "MONTHS")
            .build()));
    }

    @Transactional(readOnly = true)
    public LoanProductResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<LoanProductResponse> list(int page, int size) {
        requireContext();
        return repo.findByActiveTrue(PageRequest.of(page, size)).map(this::toResponse);
    }

    @Transactional
    public LoanProductResponse update(UUID id, LoanProductRequest req) {
        requireContext();
        LoanProduct p = findOrThrow(id);
        if (!p.getShortName().equals(req.shortName()) && repo.findByShortName(req.shortName()).isPresent())
            throw BaasException.conflict("DUPLICATE_SHORT_NAME", "Short name '" + req.shortName() + "' already in use");
        if (req.defaultPrincipal().compareTo(req.minPrincipal()) < 0
                || req.defaultPrincipal().compareTo(req.maxPrincipal()) > 0)
            throw BaasException.badRequest("INVALID_DEFAULT_PRINCIPAL",
                "defaultPrincipal must be between minPrincipal and maxPrincipal");
        p.setName(req.name()); p.setShortName(req.shortName()); p.setDescription(req.description());
        p.setMinPrincipal(req.minPrincipal()); p.setMaxPrincipal(req.maxPrincipal());
        p.setDefaultPrincipal(req.defaultPrincipal()); p.setNominalInterestRate(req.nominalInterestRate());
        if (req.repaymentType() != null) p.setRepaymentType(req.repaymentType());
        p.setNumberOfRepayments(req.numberOfRepayments());
        if (req.repaymentEvery() != null) p.setRepaymentEvery(req.repaymentEvery());
        if (req.repaymentFrequency() != null) p.setRepaymentFrequency(req.repaymentFrequency());
        return toResponse(repo.save(p));
    }

    @Transactional
    public void deactivate(UUID id) {
        requireContext();
        LoanProduct p = findOrThrow(id);
        p.setActive(false);
        repo.save(p);
    }

    private LoanProduct findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() ->
            BaasException.notFound("LOAN_PRODUCT_NOT_FOUND", "Loan product " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private LoanProductResponse toResponse(LoanProduct p) {
        return new LoanProductResponse(p.getId(), p.getName(), p.getShortName(), p.getDescription(),
            p.getMinPrincipal(), p.getMaxPrincipal(), p.getDefaultPrincipal(), p.getNominalInterestRate(),
            p.getRepaymentType(), p.getNumberOfRepayments(), p.getRepaymentEvery(), p.getRepaymentFrequency(),
            p.isActive(), p.getCreatedAt());
    }
}
