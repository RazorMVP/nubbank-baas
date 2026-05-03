package com.nubbank.baas.engine.accounting;

import com.nubbank.baas.engine.accounting.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AccountingRulesService {

    private final AccountingRuleRepository ruleRepo;
    private final ProvisioningCriteriaRepository criteriaRepo;
    private final GlAccountRepository glAccountRepo;

    @Transactional
    public AccountingRule createRule(AccountingRuleRequest req) {
        requireContext();
        AccountingRule rule = AccountingRule.builder()
            .name(req.name())
            .allowMultipleDebits(req.allowMultipleDebits() != null && req.allowMultipleDebits())
            .allowMultipleCredits(req.allowMultipleCredits() != null && req.allowMultipleCredits())
            .build();
        if (req.debitAccountId() != null)
            rule.setDebitAccount(glAccountRepo.findById(req.debitAccountId())
                .orElseThrow(() -> BaasException.notFound("GL_ACCOUNT_NOT_FOUND", "Debit GL account not found")));
        if (req.creditAccountId() != null)
            rule.setCreditAccount(glAccountRepo.findById(req.creditAccountId())
                .orElseThrow(() -> BaasException.notFound("GL_ACCOUNT_NOT_FOUND", "Credit GL account not found")));
        return ruleRepo.save(rule);
    }

    @Transactional(readOnly = true)
    public List<AccountingRule> listRules() {
        requireContext();
        return ruleRepo.findAll();
    }

    @Transactional
    public void deleteRule(UUID id) {
        requireContext();
        if (!ruleRepo.existsById(id)) throw BaasException.notFound("RULE_NOT_FOUND", "Accounting rule not found");
        ruleRepo.deleteById(id);
    }

    @Transactional
    public ProvisioningCriteria createCriteria(ProvisioningCriteriaRequest req) {
        requireContext();
        ProvisioningCriteria criteria = ProvisioningCriteria.builder().name(req.name()).build();
        for (ProvisioningCriteriaRequest.DefinitionRequest dr : req.definitions()) {
            ProvisioningCriteriaDefinition def = ProvisioningCriteriaDefinition.builder()
                .criteria(criteria).categoryName(dr.categoryName())
                .minAge(dr.minAge()).maxAge(dr.maxAge()).provisionPercentage(dr.provisionPercentage())
                .build();
            if (dr.liabilityAccountId() != null)
                def.setLiabilityAccount(glAccountRepo.findById(dr.liabilityAccountId()).orElse(null));
            if (dr.expenseAccountId() != null)
                def.setExpenseAccount(glAccountRepo.findById(dr.expenseAccountId()).orElse(null));
            criteria.getDefinitions().add(def);
        }
        return criteriaRepo.save(criteria);
    }

    @Transactional
    public ProvisioningCriteria updateCriteria(UUID id, ProvisioningCriteriaRequest req) {
        requireContext();
        ProvisioningCriteria criteria = criteriaRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CRITERIA_NOT_FOUND", "Provisioning criteria not found"));
        criteria.setName(req.name());
        // Replace-all pattern: clear then re-add
        criteria.getDefinitions().clear();
        for (ProvisioningCriteriaRequest.DefinitionRequest dr : req.definitions()) {
            ProvisioningCriteriaDefinition def = ProvisioningCriteriaDefinition.builder()
                .criteria(criteria).categoryName(dr.categoryName())
                .minAge(dr.minAge()).maxAge(dr.maxAge()).provisionPercentage(dr.provisionPercentage())
                .build();
            if (dr.liabilityAccountId() != null)
                def.setLiabilityAccount(glAccountRepo.findById(dr.liabilityAccountId()).orElse(null));
            if (dr.expenseAccountId() != null)
                def.setExpenseAccount(glAccountRepo.findById(dr.expenseAccountId()).orElse(null));
            criteria.getDefinitions().add(def);
        }
        return criteriaRepo.save(criteria);
    }

    @Transactional(readOnly = true)
    public List<ProvisioningCriteria> listCriteria() {
        requireContext();
        return criteriaRepo.findAll();
    }

    @Transactional
    public void deleteCriteria(UUID id) {
        requireContext();
        if (!criteriaRepo.existsById(id)) throw BaasException.notFound("CRITERIA_NOT_FOUND", "Provisioning criteria not found");
        criteriaRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null) throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
