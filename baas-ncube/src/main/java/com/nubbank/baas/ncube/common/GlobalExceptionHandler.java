package com.nubbank.baas.ncube.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NcubeException.class)
    public ResponseEntity<Map<String, Object>> handleNcube(NcubeException ex) {
        log.warn("NcubeException: {} — {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
            .body(Map.of("error", ex.getCode(), "message", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldErrors().stream()
            .findFirst().orElse(new FieldError("unknown", "unknown", "Validation failed"));
        return ResponseEntity.badRequest()
            .body(Map.of("error", "VALIDATION_ERROR", "message", first.getDefaultMessage(),
                "field", first.getField()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        log.warn("Unsupported media type: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
            .body(Map.of("error", "UNSUPPORTED_MEDIA_TYPE",
                "message", "Content-Type must be application/vnd.cbn.openbanking.v1+json"));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("Not acceptable: supported={}", ex.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
            .body(Map.of("error", "NOT_ACCEPTABLE",
                "message", "Accept header must include application/vnd.cbn.openbanking.v1+json"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
            .body(Map.of("error", "INTERNAL_ERROR", "message", "An unexpected error occurred"));
    }
}
