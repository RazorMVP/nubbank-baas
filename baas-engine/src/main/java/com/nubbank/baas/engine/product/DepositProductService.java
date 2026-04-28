package com.nubbank.baas.engine.product;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.product.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DepositProductService {

    private final DepositProductRepository repo;

    @Transactional
    public DepositProductResponse create(DepositProductRequest req) {
        requireContext();
        if (repo.findByShortName(req.shortName()).isPresent())
            throw BaasException.conflict("DUPLICATE_SHORT_NAME",
                "Deposit product with shortName '" + req.shortName() + "' already exists");
        return toResponse(repo.save(DepositProduct.builder()
            .name(req.name()).shortName(req.shortName())
            .accountType(req.accountType() != null ? req.accountType() : AccountType.SAVINGS)
            .minimumBalance(req.minimumBalance() != null ? req.minimumBalance() : BigDecimal.ZERO)
            .nominalInterestRate(req.nominalInterestRate() != null ? req.nominalInterestRate() : BigDecimal.ZERO)
            .allowOverdraft(req.allowOverdraft()).overdraftLimit(req.overdraftLimit())
            .build()));
    }

    @Transactional(readOnly = true)
    public DepositProductResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<DepositProductResponse> list(int page, int size) {
        requireContext();
        return repo.findByActiveTrue(PageRequest.of(page, size)).map(this::toResponse);
    }

    @Transactional
    public DepositProductResponse update(UUID id, DepositProductRequest req) {
        requireContext();
        DepositProduct p = findOrThrow(id);
        if (!p.getShortName().equals(req.shortName()) && repo.findByShortName(req.shortName()).isPresent())
            throw BaasException.conflict("DUPLICATE_SHORT_NAME", "Short name in use");
        p.setName(req.name()); p.setShortName(req.shortName());
        if (req.accountType() != null) p.setAccountType(req.accountType());
        if (req.minimumBalance() != null) p.setMinimumBalance(req.minimumBalance());
        if (req.nominalInterestRate() != null) p.setNominalInterestRate(req.nominalInterestRate());
        p.setAllowOverdraft(req.allowOverdraft()); p.setOverdraftLimit(req.overdraftLimit());
        return toResponse(repo.save(p));
    }

    @Transactional
    public void deactivate(UUID id) {
        requireContext();
        DepositProduct p = findOrThrow(id);
        p.setActive(false);
        repo.save(p);
    }

    private DepositProduct findOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() ->
            BaasException.notFound("DEPOSIT_PRODUCT_NOT_FOUND", "Deposit product " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private DepositProductResponse toResponse(DepositProduct p) {
        return new DepositProductResponse(p.getId(), p.getName(), p.getShortName(), p.getAccountType(),
            p.getMinimumBalance(), p.getNominalInterestRate(), p.isAllowOverdraft(), p.getOverdraftLimit(),
            p.isActive(), p.getCreatedAt());
    }
}
