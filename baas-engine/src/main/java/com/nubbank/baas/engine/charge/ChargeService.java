package com.nubbank.baas.engine.charge;

import com.nubbank.baas.engine.charge.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChargeService {

    private final ChargeRepository repo;

    @Transactional
    public ChargeResponse create(ChargeRequest req) {
        requireContext();
        return toResponse(repo.save(Charge.builder()
            .name(req.name()).chargeType(req.chargeType())
            .calculationType(req.calculationType() != null ? req.calculationType() : CalculationType.FLAT)
            .amount(req.amount())
            .currencyCode(req.currencyCode() != null ? req.currencyCode() : "NGN")
            .build()));
    }

    @Transactional(readOnly = true)
    public ChargeResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ChargeResponse> list(int page, int size) {
        requireContext();
        return repo.findByActiveTrue(PageRequest.of(page, size)).map(this::toResponse);
    }

    @Transactional
    public ChargeResponse update(UUID id, ChargeRequest req) {
        requireContext();
        Charge c = findOrThrow(id);
        c.setName(req.name()); c.setChargeType(req.chargeType());
        if (req.calculationType() != null) c.setCalculationType(req.calculationType());
        c.setAmount(req.amount());
        if (req.currencyCode() != null) c.setCurrencyCode(req.currencyCode());
        return toResponse(repo.save(c));
    }

    @Transactional
    public void deactivate(UUID id) {
        requireContext();
        Charge c = findOrThrow(id);
        c.setActive(false);
        repo.save(c);
    }

    public Charge findOrThrow(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CHARGE_NOT_FOUND", "Charge " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null) throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private ChargeResponse toResponse(Charge c) {
        return new ChargeResponse(c.getId(), c.getName(), c.getChargeType(),
            c.getCalculationType(), c.getAmount(), c.getCurrencyCode(), c.isActive(), c.getCreatedAt());
    }
}
