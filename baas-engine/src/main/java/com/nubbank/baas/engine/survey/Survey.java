package com.nubbank.baas.engine.survey;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "surveys")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Survey {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(unique = true, nullable = false, length = 100) private String key;
    @Column(nullable = false, length = 200) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "country_code", length = 3) private String countryCode;
    @Column(nullable = false) private boolean active;
    @Builder.Default
    @OneToMany(mappedBy = "survey", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNo ASC")
    private List<SurveyQuestion> questions = new ArrayList<>();
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); active = true; }
}
