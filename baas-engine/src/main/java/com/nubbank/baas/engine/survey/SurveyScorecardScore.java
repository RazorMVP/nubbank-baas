package com.nubbank.baas.engine.survey;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "survey_scorecard_scores")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SurveyScorecardScore {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "scorecard_id", nullable = false)
    @JsonIgnore private SurveyScorecard scorecard;
    @Column(name = "question_id", nullable = false) private UUID questionId;
    @Column(name = "response_id", nullable = false) private UUID responseId;
    @Column(nullable = false) private int score;
}
