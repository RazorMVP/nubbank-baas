package com.nubbank.baas.ncube.common;

import org.springframework.http.HttpStatus;

public class NcubeException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public NcubeException(String code, String message) {
        super(message);
        this.code = code;
        this.status = HttpStatus.BAD_REQUEST;
    }

    public NcubeException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
}
