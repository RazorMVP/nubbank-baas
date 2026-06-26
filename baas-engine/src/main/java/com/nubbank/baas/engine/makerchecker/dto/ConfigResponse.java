package com.nubbank.baas.engine.makerchecker.dto;

import com.nubbank.baas.engine.makerchecker.MakerCheckerConfig;

public record ConfigResponse(String commandType, boolean enabled) {
    public static ConfigResponse of(MakerCheckerConfig c) {
        return new ConfigResponse(c.getCommandType(), c.isEnabled());
    }
}
