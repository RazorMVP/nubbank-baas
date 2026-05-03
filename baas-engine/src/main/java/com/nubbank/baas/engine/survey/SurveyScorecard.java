package com.nubbank.baas.engine.survey;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.nubbank.baas.engine.customer.Customer;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "survey_scorecards")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SurveyScorecard {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "survey_id", nullable = false)
    @JsonIgnore private Survey survey;
    @Column(name = "survey_id", insertable = false, updatable = false) private UUID surveyId;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id", nullable = false)
    @JsonIgnore private Customer customer;
    @Column(name = "customer_id", insertable = false, updatable = false) private UUID customerId;
    @Column(name = "created_by", length = 255) private String createdBy;
    @Builder.Default
    @OneToMany(mappedBy = "scorecard", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SurveyScorecardScore> scores = new ArrayList<>();
    @Column(name = "created_at", updatable = false) private Instant createdAt;
    @PrePersist void onCreate() { createdAt = Instant.now(); }
}
