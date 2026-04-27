package com.nubbank.baas.ncube.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class BaasEngineClientConfig {

    @Value("${baas.engine.base-url}")
    private String baasEngineBaseUrl;

    @Bean("baasEngineRestTemplate")
    public RestTemplate baasEngineRestTemplate() {
        return new RestTemplate();
    }

    @Bean
    public String baasEngineBaseUrl() {
        return baasEngineBaseUrl;
    }
}
