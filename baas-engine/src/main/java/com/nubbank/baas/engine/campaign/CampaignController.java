package com.nubbank.baas.engine.campaign;

import com.nubbank.baas.engine.campaign.dto.*;
import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.common.BaasException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService service;

    @PostMapping("/baas/v1/smscampaigns")
    public ResponseEntity<ApiResponse<SmsCampaign>> createCampaign(
            @Valid @RequestBody SmsCampaignRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.createCampaign(req)));
    }

    @GetMapping("/baas/v1/smscampaigns")
    public ResponseEntity<ApiResponse<Page<SmsCampaign>>> listCampaigns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listCampaigns(page, size)));
    }

    @PostMapping("/baas/v1/smscampaigns/{id}")
    public ResponseEntity<ApiResponse<SmsCampaign>> campaignCommand(
            @PathVariable UUID id, @RequestParam String command) {
        if ("activate".equalsIgnoreCase(command))
            return ResponseEntity.ok(ApiResponse.ok(service.activateCampaign(id)));
        throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
    }

    @GetMapping("/baas/v1/smscampaigns/{id}/messages")
    public ResponseEntity<ApiResponse<Page<SmsMessage>>> listMessages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listMessages(id, page, size)));
    }

    @PostMapping("/baas/v1/reportmailingjobs")
    public ResponseEntity<ApiResponse<ReportMailingJob>> createMailingJob(
            @Valid @RequestBody ReportMailingJobRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(service.createMailingJob(req)));
    }

    @GetMapping("/baas/v1/reportmailingjobs")
    public ResponseEntity<ApiResponse<Page<ReportMailingJob>>> listMailingJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(service.listMailingJobs(page, size)));
    }

    @PostMapping("/baas/v1/reportmailingjobs/{id}")
    public ResponseEntity<ApiResponse<ReportMailingJob>> mailingJobCommand(
            @PathVariable UUID id, @RequestParam String command) {
        if ("run".equalsIgnoreCase(command))
            return ResponseEntity.ok(ApiResponse.ok(service.runNow(id)));
        throw BaasException.badRequest("UNKNOWN_COMMAND", "Unknown command: " + command);
    }
}
