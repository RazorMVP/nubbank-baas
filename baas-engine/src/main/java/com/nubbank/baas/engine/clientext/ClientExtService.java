package com.nubbank.baas.engine.clientext;

import com.nubbank.baas.engine.clientext.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ClientExtService {

    private final CustomerRepository customerRepo;
    private final ClientIdentifierRepository identRepo;
    private final ClientAddressRepository addrRepo;
    private final ClientImageRepository imageRepo;

    @Transactional
    public ClientIdentifier addIdentifier(UUID customerId, IdentifierRequest req) {
        requireContext();
        var customer = customerRepo.findById(customerId)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        return identRepo.save(ClientIdentifier.builder()
            .customer(customer).documentType(req.documentType())
            .documentKey(req.documentKey()).description(req.description())
            .expiryDate(req.expiryDate()).build());
    }

    @Transactional(readOnly = true)
    public List<ClientIdentifier> listIdentifiers(UUID customerId) {
        requireContext();
        return identRepo.findByCustomerIdAndActiveTrue(customerId);
    }

    @Transactional
    public void deleteIdentifier(UUID customerId, UUID id) {
        requireContext();
        ClientIdentifier ident = identRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("IDENTIFIER_NOT_FOUND", "Identifier not found"));
        if (!ident.getCustomer().getId().equals(customerId))
            throw BaasException.forbidden("FORBIDDEN", "Identifier does not belong to this customer");
        ident.setActive(false);
        identRepo.save(ident);
    }

    @Transactional
    public ClientAddress addAddress(UUID customerId, AddressRequest req) {
        requireContext();
        var customer = customerRepo.findById(customerId)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        return addrRepo.save(ClientAddress.builder()
            .customer(customer).addressType(req.addressType() != null ? req.addressType() : "HOME")
            .street(req.street()).city(req.city()).stateProvince(req.stateProvince())
            .countryCode(req.countryCode()).postalCode(req.postalCode()).build());
    }

    @Transactional(readOnly = true)
    public List<ClientAddress> listAddresses(UUID customerId) {
        requireContext();
        return addrRepo.findByCustomerIdAndActiveTrue(customerId);
    }

    @Transactional
    public ClientImage upsertImage(UUID customerId, ImageMetaRequest req) {
        requireContext();
        var customer = customerRepo.findById(customerId)
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));
        return imageRepo.findByCustomerId(customerId)
            .map(img -> {
                img.setFileName(req.fileName()); img.setContentType(req.contentType());
                img.setFileSizeBytes(req.fileSizeBytes()); img.setStoragePath(req.storagePath());
                return imageRepo.save(img);
            })
            .orElseGet(() -> imageRepo.save(ClientImage.builder()
                .customer(customer).fileName(req.fileName())
                .contentType(req.contentType() != null ? req.contentType() : "image/jpeg")
                .fileSizeBytes(req.fileSizeBytes()).storagePath(req.storagePath()).build()));
    }

    @Transactional
    public void deleteImage(UUID customerId) {
        requireContext();
        imageRepo.findByCustomerId(customerId).ifPresent(imageRepo::delete);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
