package com.nubbank.baas.engine.survey;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.*;

@Entity
@Table(name = "survey_questions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SurveyQuestion {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "survey_id", nullable = false)
    @JsonIgnore private Survey survey;
    @Column(columnDefinition = "TEXT", nullable = false) private String question;
    @Column(name = "sequence_no", nullable = false) private int sequenceNo;
    @Builder.Default
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequenceNo ASC")
    private List<SurveyResponse> responses = new ArrayList<>();
}
