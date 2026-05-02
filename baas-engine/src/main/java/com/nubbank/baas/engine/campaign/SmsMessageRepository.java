package com.nubbank.baas.engine.campaign;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SmsMessageRepository extends JpaRepository<SmsMessage, UUID> {
    Page<SmsMessage> findByCampaignId(UUID campaignId, Pageable pageable);
}
