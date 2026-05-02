package com.nubbank.baas.engine.report.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ReportRequest(
    @NotBlank String name,
    String description,
    @NotBlank String reportSql,
    String category,
    List<ParamDef> parameters
) {
    public record ParamDef(String paramName, String paramType, boolean required, String defaultValue) {}
}
