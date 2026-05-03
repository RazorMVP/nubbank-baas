package com.nubbank.baas.engine.clientext;

import com.nubbank.baas.engine.clientext.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/baas/v1/clients/{customerId}")
@RequiredArgsConstructor
public class ClientExtController {

    private final ClientExtService service;

    @PostMapping("/identifiers")
    public ResponseEntity<ApiResponse<ClientIdentifier>> addIdentifier(
            @PathVariable UUID customerId, @Valid @RequestBody IdentifierRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addIdentifier(customerId, req)));
    }

    @GetMapping("/identifiers")
    public ResponseEntity<ApiResponse<List<ClientIdentifier>>> listIdentifiers(
            @PathVariable UUID customerId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listIdentifiers(customerId)));
    }

    @DeleteMapping("/identifiers/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteIdentifier(
            @PathVariable UUID customerId, @PathVariable UUID id) {
        service.deleteIdentifier(customerId, id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/addresses")
    public ResponseEntity<ApiResponse<ClientAddress>> addAddress(
            @PathVariable UUID customerId, @RequestBody AddressRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addAddress(customerId, req)));
    }

    @GetMapping("/addresses")
    public ResponseEntity<ApiResponse<List<ClientAddress>>> listAddresses(
            @PathVariable UUID customerId) {
        return ResponseEntity.ok(ApiResponse.ok(service.listAddresses(customerId)));
    }

    @PutMapping("/images")
    public ResponseEntity<ApiResponse<ClientImage>> upsertImage(
            @PathVariable UUID customerId, @RequestBody ImageMetaRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.upsertImage(customerId, req)));
    }

    @DeleteMapping("/images")
    public ResponseEntity<ApiResponse<Void>> deleteImage(@PathVariable UUID customerId) {
        service.deleteImage(customerId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
