package com.nubbank.baas.engine.report;

import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.common.TenantJdbcTemplate;
import com.nubbank.baas.engine.report.dto.ReportRequest;
import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReportService {

    private final ReportRepository reportRepo;
    private final TenantJdbcTemplate tenantJdbc;

    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE",
        "ALTER", "CREATE", "EXEC", "CALL", "GRANT", "REVOKE"
    );

    private static final List<String> INJECTION_CHARS = List.of("'", ";", "--", "/*");

    @Transactional(readOnly = true)
    public Page<Report> listReports(int page, int size) {
        requireContext();
        return reportRepo.findByActiveTrue(PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Report getById(UUID id) {
        requireContext();
        return reportRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("REPORT_NOT_FOUND", "Report not found"));
    }

    @Transactional
    public Report create(ReportRequest req) {
        requireContext();
        validateSqlSelectOnly(req.reportSql());
        Report report = Report.builder()
            .name(req.name()).description(req.description())
            .reportSql(req.reportSql()).category(req.category()).build();
        if (req.parameters() != null) {
            for (ReportRequest.ParamDef pd : req.parameters()) {
                report.getParameters().add(ReportParameter.builder()
                    .report(report).paramName(pd.paramName())
                    .paramType(pd.paramType() != null ? pd.paramType() : "STRING")
                    .required(pd.required()).defaultValue(pd.defaultValue()).build());
            }
        }
        return reportRepo.save(report);
    }

    @Transactional
    public void delete(UUID id) {
        requireContext();
        Report r = reportRepo.findById(id)
            .orElseThrow(() -> BaasException.notFound("REPORT_NOT_FOUND", "Report not found"));
        r.setActive(false);
        reportRepo.save(r);
    }

    public List<Map<String, Object>> runReport(String reportName, Map<String, String> params) {
        requireContext();
        Report report = reportRepo.findByNameAndActiveTrue(reportName)
            .orElseThrow(() -> BaasException.notFound("REPORT_NOT_FOUND",
                "Report '" + reportName + "' not found"));

        // Validate all param values for injection
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                validateParamValue(entry.getKey(), entry.getValue());
            }
        }

        // Substitute ${paramName} placeholders with actual values
        String resolved = report.getReportSql();
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
            }
        }

        // Final SELECT-only check on the resolved SQL
        validateSqlSelectOnly(resolved);

        // TenantJdbcTemplate sets search_path before execution
        return tenantJdbc.queryForList(resolved);
    }

    private void validateSqlSelectOnly(String sql) {
        String upper = sql.trim().toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH"))
            throw BaasException.badRequest("INVALID_SQL",
                "Report SQL must start with SELECT or WITH");
        // Word-boundary match — "CREATE" must not match the substring inside "created_at"
        for (String keyword : BLOCKED_KEYWORDS) {
            if (upper.matches("(?s).*\\b" + keyword + "\\b.*"))
                throw BaasException.badRequest("BLOCKED_SQL_KEYWORD",
                    "Report SQL contains blocked keyword: " + keyword);
        }
    }

    private void validateParamValue(String key, String value) {
        if (value == null) return;
        for (String injChar : INJECTION_CHARS) {
            if (value.contains(injChar))
                throw BaasException.badRequest("SQL_INJECTION_DETECTED",
                    "Parameter '" + key + "' contains invalid characters");
        }
    }

    private void requireContext() {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");
    }
}
