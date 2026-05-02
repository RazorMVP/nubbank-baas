package com.nubbank.baas.engine.social;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.social.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class NoteDocumentController {

    private final NoteDocumentService service;

    @PostMapping("/baas/v1/{entityType}/{entityId}/notes")
    public ResponseEntity<ApiResponse<EntityNote>> addNote(
            @PathVariable String entityType, @PathVariable UUID entityId,
            @Valid @RequestBody NoteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addNote(entityType, entityId, req)));
    }

    @GetMapping("/baas/v1/{entityType}/{entityId}/notes")
    public ResponseEntity<ApiResponse<Page<EntityNote>>> listNotes(
            @PathVariable String entityType, @PathVariable UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listNotes(entityType, entityId, page, size)));
    }

    @DeleteMapping("/baas/v1/{entityType}/{entityId}/notes/{noteId}")
    public ResponseEntity<ApiResponse<Void>> deleteNote(
            @PathVariable String entityType, @PathVariable UUID entityId, @PathVariable UUID noteId) {
        service.deleteNote(noteId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/{entityType}/{entityId}/documents")
    public ResponseEntity<ApiResponse<EntityDocument>> addDocument(
            @PathVariable String entityType, @PathVariable UUID entityId,
            @Valid @RequestBody DocumentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.addDocument(entityType, entityId, req)));
    }

    @GetMapping("/baas/v1/{entityType}/{entityId}/documents")
    public ResponseEntity<ApiResponse<Page<EntityDocument>>> listDocuments(
            @PathVariable String entityType, @PathVariable UUID entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(
            service.listDocuments(entityType, entityId, page, size)));
    }

    @DeleteMapping("/baas/v1/{entityType}/{entityId}/documents/{docId}")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(
            @PathVariable String entityType, @PathVariable UUID entityId, @PathVariable UUID docId) {
        service.deleteDocument(docId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
