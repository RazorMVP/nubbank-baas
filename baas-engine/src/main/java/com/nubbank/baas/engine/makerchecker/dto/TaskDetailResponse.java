package com.nubbank.baas.engine.makerchecker.dto;

import com.nubbank.baas.engine.makerchecker.MakerCheckerTask;
import java.time.Instant;
import java.util.UUID;

public record TaskDetailResponse(
    UUID id, String commandType, String payload, String status, UUID madeBy, Instant madeAt,
    UUID checkedBy, Instant checkedAt, UUID resultId, String rejectReason,
    boolean valid, String wouldFailBecause
) {
    public static TaskDetailResponse of(MakerCheckerTask t, String invalidReason) {
        return new TaskDetailResponse(t.getId(), t.getCommandType(), t.getPayload(), t.getStatus().name(),
            t.getMadeBy(), t.getMadeAt(), t.getCheckedBy(), t.getCheckedAt(), t.getResultId(), t.getRejectReason(),
            invalidReason == null, invalidReason);
    }
}
