package com.nubbank.baas.engine.openbanking;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.openbanking.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentRepository repo;
    private final CustomerRepository customerRepo;

    @Transactional
    public ConsentResponse create(CreateConsentRequest req) {
        requireContext();
        OpenBankingConsent consent = OpenBankingConsent.builder()
            .tppClientId(req.tppClientId()).tppName(req.tppName())
            .scopes(req.scopes()).expiryDate(req.expiryDate())
            .accessFrequency(req.accessFrequency())
            .build();
        if (req.customerId() != null)
            consent.setCustomer(customerRepo.findById(req.customerId()).orElse(null));
        return toResponse(repo.save(consent));
    }

    @Transactional(readOnly = true)
    public ConsentResponse getById(UUID id) {
        requireContext();
        return toResponse(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ConsentResponse> list(String tppClientId, int page, int size) {
        requireContext();
        if (tppClientId != null && !tppClientId.isBlank())
            return repo.findByTppClientId(tppClientId, PageRequest.of(page, size)).map(this::toResponse);
        return repo.findAll(PageRequest.of(page, size)).map(this::toResponse);
    }

    @Transactional
    public ConsentResponse authorise(UUID id) {
        requireContext();
        OpenBankingConsent consent = findOrThrow(id);
        if (consent.getStatus() != ConsentStatus.AWAITING_AUTHORISATION)
            throw BaasException.badRequest("INVALID_STATUS",
                "Only AWAITING_AUTHORISATION consents can be authorised");
        consent.setStatus(ConsentStatus.AUTHORISED);
        consent.setAuthorisedAt(Instant.now());
        return toResponse(repo.save(consent));
    }

    @Transactional
    public ConsentResponse revoke(UUID id) {
        requireContext();
        OpenBankingConsent consent = findOrThrow(id);
        if (consent.getStatus() == ConsentStatus.REVOKED)
            throw BaasException.badRequest("ALREADY_REVOKED", "Consent is already revoked");
        consent.setStatus(ConsentStatus.REVOKED);
        consent.setRevokedAt(Instant.now());
        return toResponse(repo.save(consent));
    }

    public void validateForAisp(UUID consentId) {
        requireContext();
        OpenBankingConsent consent = findOrThrow(consentId);
        if (consent.getStatus() != ConsentStatus.AUTHORISED)
            throw BaasException.forbidden("CONSENT_NOT_AUTHORISED", "Consent is not AUTHORISED");
        if (consent.getExpiryDate() != null && LocalDate.now().isAfter(consent.getExpiryDate()))
            throw BaasException.forbidden("CONSENT_EXPIRED", "Consent has expired");
    }

    public void validateForPisp(UUID consentId) {
        validateForAisp(consentId);
        OpenBankingConsent consent = findOrThrow(consentId);
        if (!consent.getScopes().contains("payments"))
            throw BaasException.forbidden("INSUFFICIENT_SCOPE",
                "Consent does not include 'payments' scope");
    }

    private OpenBankingConsent findOrThrow(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CONSENT_NOT_FOUND", "Consent " + id + " not found"));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }

    private ConsentResponse toResponse(OpenBankingConsent c) {
        return new ConsentResponse(c.getId(), c.getTppClientId(), c.getTppName(),
            c.getStatus(), c.getScopes(), c.getExpiryDate(), c.getAccessFrequency(),
            c.getAuthorisedAt(), c.getRevokedAt(), c.getCreatedAt());
    }
}
