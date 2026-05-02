package com.nubbank.baas.engine.system;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "code_values",
    uniqueConstraints = @UniqueConstraint(columnNames = {"code_id", "value"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CodeValue {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "code_id", nullable = false)
    @JsonIgnore private Code code;
    @Column(name = "code_id", insertable = false, updatable = false) private UUID codeId;
    @Column(nullable = false, length = 200) private String value;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private int position;
    @Column(nullable = false) private boolean active;
    @PrePersist void onCreate() { active = true; }
}
