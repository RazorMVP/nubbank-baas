package com.nubbank.baas.engine.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(String entityType, UUID entityId, String action,
                    String changedBy, String oldValues, String newValues) {
        try {
            repo.save(AuditLog.builder()
                .entityType(entityType).entityId(entityId)
                .action(action).changedBy(changedBy)
                .oldValues(oldValues).newValues(newValues)
                .build());
        } catch (Exception e) {
            log.error("Failed to write audit log entry: entityType={} entityId={} action={}",
                entityType, entityId, action, e);
        }
    }

    public String toJson(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { return String.valueOf(value); }
    }
}
