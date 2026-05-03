package com.nubbank.baas.engine.social;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.social.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MakerCheckerService {

    private final MakerCheckerRepository mcRepo;
    private final DataTableRepository dtRepo;

    @Transactional
    public MakerCheckerRequest create(MakerCheckerCreateRequest req) {
        requireContext();
        PartnerContext ctx = PartnerContext.get();
        UUID madeByUserId = ctx.userId() != null
            ? UUID.fromString(ctx.userId())
            : UUID.fromString(ctx.partnerId());
        return mcRepo.save(MakerCheckerRequest.builder()
            .entityType(req.entityType()).entityId(req.entityId())
            .action(req.action()).commandAsJson(req.commandAsJson())
            .madeByUserId(madeByUserId).build());
    }

    @Transactional
    public MakerCheckerRequest executeCommand(UUID id, String command) {
        requireContext();
        PartnerContext ctx = PartnerContext.get();
        // Checker must come from the JWT (PartnerContext.userId), NOT a request
        // parameter — otherwise an attacker could forge any UUID as the checker
        // and bypass the segregation-of-duties control.
        if (ctx.userId() == null)
            throw BaasException.unauthorized("USER_ID_REQUIRED",
                "Maker-checker actions require a user-bound JWT (sub claim)");
        UUID checkerUserId = UUID.fromString(ctx.userId());

        MakerCheckerRequest r = mcRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("MC_NOT_FOUND", "Request not found"));
        if (!"PENDING".equals(r.getStatus()))
            throw BaasException.badRequest("INVALID_STATUS", "Only PENDING requests can be actioned");

        // Segregation of duties: the maker cannot also be the checker.
        if (r.getMadeByUserId() != null && r.getMadeByUserId().equals(checkerUserId))
            throw BaasException.badRequest("SEGREGATION_OF_DUTIES",
                "The maker cannot also be the checker — a different user must approve/reject");

        switch (command.toLowerCase()) {
            case "approve" -> r.setStatus("APPROVED");
            case "reject" -> r.setStatus("REJECTED");
            default -> throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
        }
        r.setCheckedByUserId(checkerUserId);
        return mcRepo.save(r);
    }

    @Transactional(readOnly = true)
    public Page<MakerCheckerRequest> list(String status, int page, int size) {
        requireContext();
        if (status != null && !status.isBlank())
            return mcRepo.findByStatus(status.toUpperCase(), PageRequest.of(page, size));
        return mcRepo.findAll(PageRequest.of(page, size));
    }

    @Transactional
    public void delete(UUID id) {
        requireContext();
        MakerCheckerRequest r = mcRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("MC_NOT_FOUND", "Request not found"));
        if ("PENDING".equals(r.getStatus()))
            throw BaasException.badRequest("CANNOT_DELETE", "Cannot delete PENDING requests — reject first");
        mcRepo.delete(r);
    }

    @Transactional
    public DataTableRegistration registerDataTable(DataTableRequest req) {
        requireContext();
        return dtRepo.save(DataTableRegistration.builder()
            .registeredTableName(req.registeredTableName())
            .applicationTableName(req.applicationTableName())
            .allowMultipleRows(req.allowMultipleRows() != null && req.allowMultipleRows())
            .build());
    }

    @Transactional(readOnly = true)
    public List<DataTableRegistration> listDataTables() {
        requireContext();
        return dtRepo.findAll();
    }

    @Transactional
    public void deleteDataTable(UUID id) {
        requireContext();
        if (!dtRepo.existsById(id))
            throw BaasException.notFound("DATATABLE_NOT_FOUND", "DataTable registration not found");
        dtRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
