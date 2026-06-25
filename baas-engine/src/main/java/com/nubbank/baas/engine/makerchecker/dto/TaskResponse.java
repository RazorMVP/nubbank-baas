package com.nubbank.baas.engine.makerchecker.dto;

import com.nubbank.baas.engine.makerchecker.MakerCheckerTask;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
    UUID id, String commandType, String status, UUID madeBy, Instant madeAt,
    UUID checkedBy, Instant checkedAt, UUID resultId, String rejectReason
) {
    public static TaskResponse of(MakerCheckerTask t) {
        return new TaskResponse(t.getId(), t.getCommandType(), t.getStatus().name(),
            t.getMadeBy(), t.getMadeAt(), t.getCheckedBy(), t.getCheckedAt(), t.getResultId(), t.getRejectReason());
    }
}
