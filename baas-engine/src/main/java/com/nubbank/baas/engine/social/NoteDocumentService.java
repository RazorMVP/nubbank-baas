package com.nubbank.baas.engine.social;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.social.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NoteDocumentService {

    private final EntityNoteRepository noteRepo;
    private final EntityDocumentRepository docRepo;

    @Transactional
    public EntityNote addNote(String entityType, UUID entityId, NoteRequest req) {
        requireContext();
        return noteRepo.save(EntityNote.builder()
            .entityType(entityType.toUpperCase()).entityId(entityId).note(req.note()).build());
    }

    @Transactional(readOnly = true)
    public Page<EntityNote> listNotes(String entityType, UUID entityId, int page, int size) {
        requireContext();
        return noteRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            entityType.toUpperCase(), entityId, PageRequest.of(page, size));
    }

    @Transactional
    public void deleteNote(UUID id) {
        requireContext();
        if (!noteRepo.existsById(id))
            throw BaasException.notFound("NOTE_NOT_FOUND", "Note not found");
        noteRepo.deleteById(id);
    }

    @Transactional
    public EntityDocument addDocument(String entityType, UUID entityId, DocumentRequest req) {
        requireContext();
        return docRepo.save(EntityDocument.builder()
            .entityType(entityType.toUpperCase()).entityId(entityId)
            .fileName(req.fileName()).contentType(req.contentType())
            .fileSizeBytes(req.fileSizeBytes()).storagePath(req.storagePath())
            .description(req.description()).build());
    }

    @Transactional(readOnly = true)
    public Page<EntityDocument> listDocuments(String entityType, UUID entityId, int page, int size) {
        requireContext();
        return docRepo.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            entityType.toUpperCase(), entityId, PageRequest.of(page, size));
    }

    @Transactional
    public void deleteDocument(UUID id) {
        requireContext();
        if (!docRepo.existsById(id))
            throw BaasException.notFound("DOCUMENT_NOT_FOUND", "Document not found");
        docRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
