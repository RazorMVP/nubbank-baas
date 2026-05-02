package com.nubbank.baas.engine.rate;

import com.nubbank.baas.engine.accounting.GlAccountRepository;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.rate.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RateService {

    private final FloatingRateRepository rateRepo;
    private final TaxComponentRepository taxCompRepo;
    private final TaxGroupRepository taxGroupRepo;
    private final GlAccountRepository glAccountRepo;

    @Transactional
    public FloatingRate createFloatingRate(FloatingRateRequest req) {
        requireContext();
        FloatingRate rate = FloatingRate.builder()
            .name(req.name())
            .baseLendingRate(req.isBaseLendingRate() != null && req.isBaseLendingRate())
            .build();
        for (FloatingRateRequest.PeriodRequest pr : req.periods()) {
            rate.getPeriods().add(FloatingRatePeriod.builder()
                .floatingRate(rate)
                .fromDate(pr.fromDate())
                .interestRate(pr.interestRate())
                .differentialToBaseLending(pr.isDifferentialToBaseLending() != null
                    && pr.isDifferentialToBaseLending())
                .build());
        }
        return rateRepo.save(rate);
    }

    @Transactional
    public FloatingRate updateFloatingRate(UUID id, FloatingRateRequest req) {
        requireContext();
        FloatingRate rate = rateRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("RATE_NOT_FOUND", "Floating rate not found"));
        rate.setName(req.name());
        if (req.isBaseLendingRate() != null) rate.setBaseLendingRate(req.isBaseLendingRate());
        // Replace-all pattern — cascade + orphanRemoval handles DB deletes
        rate.getPeriods().clear();
        for (FloatingRateRequest.PeriodRequest pr : req.periods()) {
            rate.getPeriods().add(FloatingRatePeriod.builder()
                .floatingRate(rate)
                .fromDate(pr.fromDate())
                .interestRate(pr.interestRate())
                .differentialToBaseLending(pr.isDifferentialToBaseLending() != null
                    && pr.isDifferentialToBaseLending())
                .build());
        }
        return rateRepo.save(rate);
    }

    @Transactional(readOnly = true)
    public List<FloatingRate> listFloatingRates() { requireContext(); return rateRepo.findAll(); }

    @Transactional
    public void deleteFloatingRate(UUID id) {
        requireContext();
        if (!rateRepo.existsById(id))
            throw BaasException.notFound("RATE_NOT_FOUND", "Floating rate not found");
        rateRepo.deleteById(id);
    }

    @Transactional
    public TaxComponent createTaxComponent(TaxComponentRequest req) {
        requireContext();
        TaxComponent tc = TaxComponent.builder()
            .name(req.name()).percentage(req.percentage()).startDate(req.startDate())
            .build();
        if (req.creditAccountId() != null)
            tc.setCreditAccount(glAccountRepo.findById(req.creditAccountId()).orElse(null));
        if (req.debitAccountId() != null)
            tc.setDebitAccount(glAccountRepo.findById(req.debitAccountId()).orElse(null));
        return taxCompRepo.save(tc);
    }

    @Transactional(readOnly = true)
    public List<TaxComponent> listTaxComponents() { requireContext(); return taxCompRepo.findAll(); }

    @Transactional
    public void deleteTaxComponent(UUID id) {
        requireContext();
        if (!taxCompRepo.existsById(id))
            throw BaasException.notFound("TAX_COMPONENT_NOT_FOUND", "Tax component not found");
        taxCompRepo.deleteById(id);
    }

    @Transactional
    public TaxGroup createTaxGroup(TaxGroupRequest req) {
        requireContext();
        TaxGroup group = TaxGroup.builder().name(req.name()).build();
        for (TaxGroupRequest.MappingRequest mr : req.components()) {
            TaxComponent comp = taxCompRepo.findById(mr.componentId())
                .orElseThrow(() -> BaasException.notFound("TAX_COMPONENT_NOT_FOUND",
                    "Tax component " + mr.componentId() + " not found"));
            group.getMappings().add(TaxGroupMapping.builder()
                .taxGroup(group).taxComponent(comp).startDate(mr.startDate()).build());
        }
        return taxGroupRepo.save(group);
    }

    @Transactional(readOnly = true)
    public List<TaxGroup> listTaxGroups() { requireContext(); return taxGroupRepo.findAll(); }

    @Transactional
    public void deleteTaxGroup(UUID id) {
        requireContext();
        if (!taxGroupRepo.existsById(id))
            throw BaasException.notFound("TAX_GROUP_NOT_FOUND", "Tax group not found");
        taxGroupRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
