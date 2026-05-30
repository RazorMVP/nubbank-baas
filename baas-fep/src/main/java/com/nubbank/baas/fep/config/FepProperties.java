package com.nubbank.baas.fep.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fep")
public record FepProperties(int tcpPort, Card card, String hmacSecret) {
    public record Card(String baseUrl) {}
}
