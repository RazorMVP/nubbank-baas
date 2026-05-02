package com.nubbank.baas.engine.survey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface SurveyScorecardRepository extends JpaRepository<SurveyScorecard, UUID> {
    List<SurveyScorecard> findBySurveyId(UUID surveyId);
}
