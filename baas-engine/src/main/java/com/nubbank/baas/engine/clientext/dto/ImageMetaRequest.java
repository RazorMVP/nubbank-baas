package com.nubbank.baas.engine.clientext.dto;

public record ImageMetaRequest(String fileName, String contentType,
    Long fileSizeBytes, String storagePath) {}
