package com.nubbank.baas.engine.campaign;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface SmsCampaignRepository extends JpaRepository<SmsCampaign, UUID> {}
