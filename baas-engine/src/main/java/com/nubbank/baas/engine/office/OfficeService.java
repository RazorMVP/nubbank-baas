package com.nubbank.baas.engine.office;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.office.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OfficeService {

    private final OfficeRepository officeRepo;
    private final StaffRepository staffRepo;

    @Transactional
    public Office createOffice(OfficeRequest req) {
        requireContext();
        Office office = Office.builder()
            .name(req.name()).openingDate(req.openingDate()).externalId(req.externalId())
            .build();

        if (req.parentId() != null) {
            Office parent = officeRepo.findById(req.parentId())
                .orElseThrow(() -> BaasException.notFound("OFFICE_NOT_FOUND", "Parent office not found"));
            office.setParent(parent);
            office = officeRepo.save(office);
            String parentHierarchy = parent.getHierarchy() != null
                ? parent.getHierarchy() : "." + parent.getId() + ".";
            office.setHierarchy(parentHierarchy + office.getId() + ".");
        } else {
            office = officeRepo.save(office);
            office.setHierarchy("." + office.getId() + ".");
        }
        return officeRepo.save(office);
    }

    @Transactional(readOnly = true)
    public List<Office> listOffices() {
        requireContext();
        return officeRepo.findAll();
    }

    @Transactional
    public Office updateOffice(UUID id, OfficeRequest req) {
        requireContext();
        Office office = officeRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("OFFICE_NOT_FOUND", "Office not found"));
        office.setName(req.name());
        if (req.openingDate() != null) office.setOpeningDate(req.openingDate());
        return officeRepo.save(office);
    }

    @Transactional
    public Staff createStaff(StaffRequest req) {
        requireContext();
        Staff staff = Staff.builder()
            .firstName(req.firstName()).lastName(req.lastName())
            .loanOfficer(req.isLoanOfficer() != null && req.isLoanOfficer())
            .externalId(req.externalId()).joiningDate(req.joiningDate())
            .build();
        if (req.officeId() != null) {
            Office office = officeRepo.findById(req.officeId())
                .orElseThrow(() -> BaasException.notFound("OFFICE_NOT_FOUND", "Office not found"));
            staff.setOffice(office);
        }
        return staffRepo.save(staff);
    }

    @Transactional(readOnly = true)
    public Page<Staff> listStaff(int page, int size) {
        requireContext();
        return staffRepo.findByActiveTrue(PageRequest.of(page, size));
    }

    @Transactional
    public Staff updateStaff(UUID id, StaffRequest req) {
        requireContext();
        Staff staff = staffRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("STAFF_NOT_FOUND", "Staff not found"));
        staff.setFirstName(req.firstName());
        staff.setLastName(req.lastName());
        if (req.isLoanOfficer() != null) staff.setLoanOfficer(req.isLoanOfficer());
        if (req.officeId() != null)
            staff.setOffice(officeRepo.findById(req.officeId()).orElse(null));
        return staffRepo.save(staff);
    }

    @Transactional
    public void deleteStaff(UUID id) {
        requireContext();
        Staff staff = staffRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("STAFF_NOT_FOUND", "Staff not found"));
        staff.setActive(false);
        staffRepo.save(staff);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
