package com.nubbank.baas.card.common;

import org.springframework.http.HttpStatus;

public class BaasException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    private BaasException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static BaasException notFound(String code, String message) {
        return new BaasException(code, message, HttpStatus.NOT_FOUND);
    }

    public static BaasException badRequest(String code, String message) {
        return new BaasException(code, message, HttpStatus.BAD_REQUEST);
    }

    public static BaasException conflict(String code, String message) {
        return new BaasException(code, message, HttpStatus.CONFLICT);
    }

    public static BaasException unauthorized(String code, String message) {
        return new BaasException(code, message, HttpStatus.UNAUTHORIZED);
    }

    public static BaasException forbidden(String code, String message) {
        return new BaasException(code, message, HttpStatus.FORBIDDEN);
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
