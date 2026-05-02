package com.nubbank.baas.engine.system;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.system.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SystemConfigService {

    private final SystemConfigurationRepository configRepo;
    private final CodeRepository codeRepo;
    private final CodeValueRepository codeValueRepo;
    private final PaymentTypeRepository paymentTypeRepo;
    private final HolidayRepository holidayRepo;

    @Transactional(readOnly = true)
    public Page<SystemConfiguration> listConfigs(int page, int size) {
        requireContext();
        return configRepo.findAll(PageRequest.of(page, size));
    }

    @Transactional
    public SystemConfiguration updateConfig(String key, SystemConfigUpdateRequest req) {
        requireContext();
        SystemConfiguration config = configRepo.findById(key)
            .orElseThrow(() -> BaasException.notFound("CONFIG_NOT_FOUND", "Config key '" + key + "' not found"));
        if (req.value() != null) config.setValue(req.value());
        if (req.enabled() != null) config.setEnabled(req.enabled());
        return configRepo.save(config);
    }

    @Transactional
    public Code createCode(CodeRequest req) {
        requireContext();
        if (codeRepo.findByName(req.name()).isPresent())
            throw BaasException.conflict("DUPLICATE_CODE", "Code '" + req.name() + "' already exists");
        return codeRepo.save(Code.builder().name(req.name()).systemDefined(false).build());
    }

    @Transactional(readOnly = true)
    public List<Code> listCodes() {
        requireContext();
        return codeRepo.findAll();
    }

    @Transactional
    public void deleteCode(UUID id) {
        requireContext();
        Code code = codeRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CODE_NOT_FOUND", "Code not found"));
        if (code.isSystemDefined())
            throw BaasException.badRequest("SYSTEM_DEFINED", "System-defined codes cannot be deleted");
        codeRepo.delete(code);
    }

    @Transactional
    public CodeValue addCodeValue(UUID codeId, CodeValueRequest req) {
        requireContext();
        Code code = codeRepo.findById(codeId)
            .orElseThrow(() -> BaasException.notFound("CODE_NOT_FOUND", "Code not found"));
        return codeValueRepo.save(CodeValue.builder()
            .code(code).value(req.value()).description(req.description())
            .position(req.position() != null ? req.position() : 0)
            .build());
    }

    @Transactional(readOnly = true)
    public List<CodeValue> listCodeValues(UUID codeId) {
        requireContext();
        return codeValueRepo.findByCodeIdOrderByPosition(codeId);
    }

    @Transactional
    public void deleteCodeValue(UUID id) {
        requireContext();
        if (!codeValueRepo.existsById(id))
            throw BaasException.notFound("CODE_VALUE_NOT_FOUND", "Code value not found");
        codeValueRepo.deleteById(id);
    }

    @Transactional
    public PaymentType createPaymentType(PaymentTypeRequest req) {
        requireContext();
        return paymentTypeRepo.save(PaymentType.builder()
            .name(req.name()).description(req.description())
            .cashPayment(req.isCashPayment() != null && req.isCashPayment())
            .systemDefined(false)
            .position(req.position() != null ? req.position() : 99)
            .build());
    }

    @Transactional(readOnly = true)
    public Page<PaymentType> listPaymentTypes(int page, int size) {
        requireContext();
        return paymentTypeRepo.findAll(PageRequest.of(page, size));
    }

    @Transactional
    public void deletePaymentType(UUID id) {
        requireContext();
        PaymentType pt = paymentTypeRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("PAYMENT_TYPE_NOT_FOUND", "Payment type not found"));
        if (pt.isSystemDefined())
            throw BaasException.badRequest("SYSTEM_DEFINED", "System-defined payment types cannot be deleted");
        paymentTypeRepo.delete(pt);
    }

    @Transactional
    public Holiday createHoliday(HolidayRequest req) {
        requireContext();
        return holidayRepo.save(Holiday.builder()
            .name(req.name()).fromDate(req.fromDate()).toDate(req.toDate())
            .repaymentSchedulingType(req.repaymentSchedulingType() != null
                ? req.repaymentSchedulingType() : "NEXT_WORKING_DAY")
            .description(req.description())
            .build());
    }

    @Transactional
    public Holiday activateHoliday(UUID id) {
        requireContext();
        Holiday h = holidayRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("HOLIDAY_NOT_FOUND", "Holiday not found"));
        h.setStatus("ACTIVE");
        return holidayRepo.save(h);
    }

    @Transactional(readOnly = true)
    public Page<Holiday> listHolidays(int page, int size) {
        requireContext();
        return holidayRepo.findAll(PageRequest.of(page, size));
    }

    @Transactional
    public void deleteHoliday(UUID id) {
        requireContext();
        if (!holidayRepo.existsById(id))
            throw BaasException.notFound("HOLIDAY_NOT_FOUND", "Holiday not found");
        holidayRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
