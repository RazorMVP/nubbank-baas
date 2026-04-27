package com.nubbank.baas.ncube.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
            .body(Map.of("error", "INTERNAL_ERROR", "message", "An unexpected error occurred"));
    }
}
