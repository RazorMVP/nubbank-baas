package com.nubbank.baas.engine.survey;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "survey_responses")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SurveyResponse {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "question_id", nullable = false)
    @JsonIgnore private SurveyQuestion question;
    @Column(nullable = false, length = 500) private String response;
    @Column(nullable = false) private int value;
    @Column(name = "sequence_no", nullable = false) private int sequenceNo;
}
