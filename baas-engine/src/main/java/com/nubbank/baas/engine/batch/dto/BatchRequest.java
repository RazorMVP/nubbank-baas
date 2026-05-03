package com.nubbank.baas.engine.batch.dto;

import java.util.List;
import java.util.Map;

public record BatchRequest(List<SubRequest> requests, Boolean enclosingTransaction) {
    public record SubRequest(
        int requestId,
        String relativeUrl,
        String method,
        Map<String, String> headers,
        Map<String, Object> body,
        Integer reference
    ) {}
}
