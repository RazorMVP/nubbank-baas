package com.nubbank.baas.engine.cob;

import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/baas/v1/jobs")
@RequiredArgsConstructor
public class CobController {

    private final CobJobHistoryRepository historyRepo;
    private final CobService cobService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<CobJobHistory>>> listJobs(
            @RequestParam(required = false) String jobName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        PageRequest pr = PageRequest.of(page, size);
        Page<CobJobHistory> result = jobName != null && !jobName.isBlank()
            ? historyRepo.findByJobNameOrderByStartedAtDesc(jobName, pr)
            : historyRepo.findAllByOrderByStartedAtDesc(pr);
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/{jobName}/run")
    public ResponseEntity<ApiResponse<Map<String, String>>> runJob(@PathVariable String jobName) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        cobService.runJobManually(jobName);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Job " + jobName + " triggered")));
    }

    @GetMapping("/{jobName}/history")
    public ResponseEntity<ApiResponse<Page<CobJobHistory>>> jobHistory(
            @PathVariable String jobName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
        return ResponseEntity.ok(ApiResponse.ok(
            historyRepo.findByJobNameOrderByStartedAtDesc(jobName, PageRequest.of(page, size))));
    }
}
