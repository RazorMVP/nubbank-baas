package com.nubbank.baas.engine.survey;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SurveyRepository extends JpaRepository<Survey, UUID> {
    Optional<Survey> findByKey(String key);
}
