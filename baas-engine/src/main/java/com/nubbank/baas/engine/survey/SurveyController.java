package com.nubbank.baas.engine.survey;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.survey.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SurveyController {

    private final SurveyService service;

    @PostMapping("/baas/v1/surveys")
    public ResponseEntity<ApiResponse<Survey>> create(@Valid @RequestBody SurveyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @GetMapping("/baas/v1/surveys")
    public ResponseEntity<ApiResponse<List<Survey>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/baas/v1/surveys/key/{key}")
    public ResponseEntity<ApiResponse<Survey>> getByKey(@PathVariable String key) {
        return ResponseEntity.ok(ApiResponse.ok(service.getByKey(key)));
    }

    @DeleteMapping("/baas/v1/surveys/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @PostMapping("/baas/v1/surveys/{id}/scorecards")
    public ResponseEntity<ApiResponse<SurveyScorecard>> submitScorecard(
            @PathVariable UUID id, @Valid @RequestBody ScorecardRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.submitScorecard(id, req)));
    }
}
