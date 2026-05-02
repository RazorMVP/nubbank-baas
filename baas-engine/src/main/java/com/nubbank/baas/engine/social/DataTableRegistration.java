package com.nubbank.baas.engine.social;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_table_registrations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DataTableRegistration {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "registered_table_name", unique = true, nullable = false, length = 200)
    private String registeredTableName;
    @Column(name = "application_table_name", nullable = false, length = 200)
    private String applicationTableName;
    @Column(name = "allow_multiple_rows", nullable = false) private boolean allowMultipleRows;
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
