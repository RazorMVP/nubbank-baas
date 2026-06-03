package com.nubbank.baas.fep.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    T data,
    Meta meta,
    List<ApiError> errors
) {
    public record Meta(String requestId, Instant timestamp) {}
    public record ApiError(String code, String message, String field, String docsUrl) {}

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data,
            new Meta(java.util.UUID.randomUUID().toString(), Instant.now()),
            null);
    }

    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(null,
            new Meta(java.util.UUID.randomUUID().toString(), Instant.now()),
            List.of(new ApiError(code, message, null,
                "https://developers.nubbank.com/docs/error-reference#" + code)));
    }

    public static <T> ApiResponse<T> fieldError(String code, String message, String field) {
        return new ApiResponse<>(null,
            new Meta(java.util.UUID.randomUUID().toString(), Instant.now()),
            List.of(new ApiError(code, message, field,
                "https://developers.nubbank.com/docs/error-reference#" + code)));
    }
}
