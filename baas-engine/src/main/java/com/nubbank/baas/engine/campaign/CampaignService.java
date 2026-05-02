package com.nubbank.baas.engine.campaign;

import com.nubbank.baas.engine.campaign.dto.*;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CampaignService {

    private final SmsCampaignRepository campaignRepo;
    private final SmsMessageRepository messageRepo;
    private final ReportMailingJobRepository mailingRepo;

    @Transactional
    public SmsCampaign createCampaign(SmsCampaignRequest req) {
        requireContext();
        return campaignRepo.save(SmsCampaign.builder()
            .name(req.name())
            .campaignType(req.campaignType() != null ? req.campaignType() : "INDIVIDUAL")
            .triggerType(req.triggerType() != null ? req.triggerType() : "DIRECT")
            .messageTemplate(req.messageTemplate()).recurrence(req.recurrence())
            .build());
    }

    @Transactional
    public SmsCampaign activateCampaign(UUID id) {
        requireContext();
        SmsCampaign c = campaignRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("CAMPAIGN_NOT_FOUND", "Campaign not found"));
        if (!"PENDING".equals(c.getStatus()) && !"WAITING_FOR_ACTIVATION".equals(c.getStatus()))
            throw BaasException.badRequest("INVALID_STATUS",
                "Campaign cannot be activated from status: " + c.getStatus());
        c.setStatus("ACTIVE");
        c.setActivationDate(LocalDate.now());
        return campaignRepo.save(c);
    }

    @Transactional(readOnly = true)
    public Page<SmsCampaign> listCampaigns(int page, int size) {
        requireContext();
        return campaignRepo.findAll(PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Page<SmsMessage> listMessages(UUID campaignId, int page, int size) {
        requireContext();
        return messageRepo.findByCampaignId(campaignId, PageRequest.of(page, size));
    }

    @Transactional
    public ReportMailingJob createMailingJob(ReportMailingJobRequest req) {
        requireContext();
        return mailingRepo.save(ReportMailingJob.builder()
            .name(req.name()).reportName(req.reportName())
            .emailRecipients(req.emailRecipients())
            .outputType(req.outputType() != null ? req.outputType() : "CSV")
            .recurrence(req.recurrence()).build());
    }

    @Transactional
    public ReportMailingJob runNow(UUID id) {
        requireContext();
        ReportMailingJob job = mailingRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("JOB_NOT_FOUND", "Report mailing job not found"));
        job.setRunCount(job.getRunCount() + 1);
        job.setPreviousRunStart(Instant.now());
        job.setPreviousRunStatus("RUNNING");
        job = mailingRepo.save(job);
        job.setPreviousRunEnd(Instant.now());
        job.setPreviousRunStatus("SUCCESS");
        return mailingRepo.save(job);
    }

    @Transactional(readOnly = true)
    public Page<ReportMailingJob> listMailingJobs(int page, int size) {
        requireContext();
        return mailingRepo.findAll(PageRequest.of(page, size));
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
