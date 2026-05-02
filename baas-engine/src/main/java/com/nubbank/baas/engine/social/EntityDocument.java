package com.nubbank.baas.engine.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "entity_documents")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EntityDocument {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "entity_type", nullable = false, length = 100) private String entityType;
    @Column(name = "entity_id", nullable = false) private UUID entityId;
    @Column(name = "file_name", nullable = false, length = 255) private String fileName;
    @Column(name = "content_type", length = 100) private String contentType;
    @Column(name = "file_size_bytes") private Long fileSizeBytes;
    @Column(name = "storage_path", columnDefinition = "TEXT") private String storagePath;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
