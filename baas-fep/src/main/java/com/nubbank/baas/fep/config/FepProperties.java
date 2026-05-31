package com.nubbank.baas.fep.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fep")
public record FepProperties(int tcpPort, Card card, @NotBlank String hmacSecret) {
    public record Card(@NotBlank String baseUrl) {}
}
