package com.nubbank.baas.engine.survey;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.customer.CustomerRepository;
import com.nubbank.baas.engine.survey.dto.*;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SurveyService {

    private final SurveyRepository surveyRepo;
    private final SurveyScorecardRepository scorecardRepo;
    private final CustomerRepository customerRepo;

    @Transactional
    public Survey create(SurveyRequest req) {
        requireContext();
        if (surveyRepo.findByKey(req.key()).isPresent())
            throw BaasException.conflict("DUPLICATE_KEY", "Survey key '" + req.key() + "' already exists");

        Survey survey = Survey.builder().key(req.key()).name(req.name())
            .description(req.description()).countryCode(req.countryCode()).build();

        for (SurveyRequest.QuestionRequest qr : req.questions()) {
            SurveyQuestion question = SurveyQuestion.builder()
                .survey(survey).question(qr.question()).sequenceNo(qr.sequenceNo()).build();
            if (qr.responses() != null) {
                for (SurveyRequest.ResponseRequest rr : qr.responses()) {
                    question.getResponses().add(SurveyResponse.builder()
                        .question(question).response(rr.response())
                        .value(rr.value()).sequenceNo(rr.sequenceNo()).build());
                }
            }
            survey.getQuestions().add(question);
        }
        return surveyRepo.save(survey);
    }

    @Transactional(readOnly = true)
    public List<Survey> listAll() { requireContext(); return surveyRepo.findAll(); }

    @Transactional(readOnly = true)
    public Survey getByKey(String key) {
        requireContext();
        return surveyRepo.findByKey(key)
            .orElseThrow(() -> BaasException.notFound("SURVEY_NOT_FOUND", "Survey '" + key + "' not found"));
    }

    @Transactional
    public SurveyScorecard submitScorecard(UUID surveyId, ScorecardRequest req) {
        requireContext();
        Survey survey = surveyRepo.findById(surveyId)
            .orElseThrow(() -> BaasException.notFound("SURVEY_NOT_FOUND", "Survey not found"));
        var customer = customerRepo.findById(req.customerId())
            .orElseThrow(() -> BaasException.notFound("CUSTOMER_NOT_FOUND", "Customer not found"));

        SurveyScorecard scorecard = SurveyScorecard.builder()
            .survey(survey).customer(customer).build();
        for (ScorecardRequest.ScoreEntry se : req.scores()) {
            scorecard.getScores().add(SurveyScorecardScore.builder()
                .scorecard(scorecard).questionId(se.questionId())
                .responseId(se.responseId()).score(se.score()).build());
        }
        return scorecardRepo.save(scorecard);
    }

    @Transactional
    public void delete(UUID id) {
        requireContext();
        if (!surveyRepo.existsById(id))
            throw BaasException.notFound("SURVEY_NOT_FOUND", "Survey not found");
        surveyRepo.deleteById(id);
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
