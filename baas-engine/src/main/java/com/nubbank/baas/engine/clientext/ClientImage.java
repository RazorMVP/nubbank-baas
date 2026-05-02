package com.nubbank.baas.engine.clientext;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "client_images")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientImage {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", unique = true, nullable = false)
    @JsonIgnore private Customer customer;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @Column(name = "file_name", length = 255) private String fileName;
    @Column(name = "content_type", nullable = false, length = 100) private String contentType;
    @Column(name = "file_size_bytes") private Long fileSizeBytes;
    @Column(name = "storage_path", columnDefinition = "TEXT") private String storagePath;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "updated_at") private Instant updatedAt;
    @PrePersist void onCreate() {
        createdAt = Instant.now(); updatedAt = Instant.now();
        if (contentType == null) contentType = "image/jpeg";
    }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
}
