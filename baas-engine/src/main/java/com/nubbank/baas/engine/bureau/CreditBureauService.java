package com.nubbank.baas.engine.bureau;

import com.nubbank.baas.engine.bureau.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreditBureauService {

    private final CreditBureauRepository bureauRepo;
    private final CreditBureauMappingRepository mappingRepo;

    @Transactional
    public CreditBureauIntegration create(CreditBureauRequest req) {
        requireContext();
        return bureauRepo.save(CreditBureauIntegration.builder()
            .name(req.name()).implClass(req.implClass()).country(req.country()).build());
    }

    @Transactional
    public CreditBureauIntegration executeCommand(UUID id, String command) {
        requireContext();
        CreditBureauIntegration b = bureauRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("BUREAU_NOT_FOUND", "Credit bureau not found"));
        switch (command.toLowerCase()) {
            case "activate" -> b.setActive(true);
            case "deactivate" -> b.setActive(false);
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown: " + command);
        }
        return bureauRepo.save(b);
    }

    @Transactional(readOnly = true)
    public List<CreditBureauIntegration> listAll() { requireContext(); return bureauRepo.findAll(); }

    @Transactional
    public CreditBureauProductMapping addMapping(UUID bureauId, BureauMappingRequest req) {
        requireContext();
        CreditBureauIntegration bureau = bureauRepo.findById(bureauId)
            .orElseThrow(() -> BaasException.notFound("BUREAU_NOT_FOUND", "Credit bureau not found"));
        return mappingRepo.save(CreditBureauProductMapping.builder()
            .creditBureau(bureau).loanProductId(req.loanProductId())
            .creditCheckMandatory(req.creditCheckMandatory() != null && req.creditCheckMandatory())
            .build());
    }

    @Transactional
    public void deleteMapping(UUID id) {
        requireContext();
        if (!mappingRepo.existsById(id))
            throw BaasException.notFound("MAPPING_NOT_FOUND", "Mapping not found");
        mappingRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
