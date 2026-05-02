package com.nubbank.baas.engine.batch;

import com.nubbank.baas.engine.batch.dto.BatchRequest;
import com.nubbank.baas.engine.common.ApiResponse;
import com.nubbank.baas.engine.common.BaasException;
import com.nubbank.baas.engine.tenant.PartnerContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@RestController
@RequestMapping("/baas/v1/batches")
@RequiredArgsConstructor
public class BatchApiController {

    private final RestTemplate restTemplate;

    @Value("${server.port:8080}")
    private int serverPort;

    @PostMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> processBatch(
            @RequestBody BatchRequest req,
            @RequestParam(defaultValue = "false") boolean enclosingTransaction,
            HttpServletRequest httpRequest) {
        if (PartnerContext.get() == null)
            throw BaasException.unauthorized("MISSING_AUTH", "Authorization required");

        String authHeader = httpRequest.getHeader("Authorization");
        List<Map<String, Object>> responses = new ArrayList<>();

        for (BatchRequest.SubRequest subReq : req.requests()) {
            try {
                String url = "http://localhost:" + serverPort + subReq.relativeUrl();

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (subReq.headers() != null) subReq.headers().forEach(headers::set);
                // Forward the original Authorization header so the sub-request runs
                // in the same partner tenant context as the parent batch call.
                if (authHeader != null) headers.set("Authorization", authHeader);

                HttpMethod method = HttpMethod.valueOf(subReq.method() != null
                    ? subReq.method().toUpperCase() : "GET");

                ResponseEntity<Map> response = restTemplate.exchange(
                    url, method,
                    new HttpEntity<>(subReq.body(), headers), Map.class);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("requestId", subReq.requestId());
                result.put("statusCode", response.getStatusCode().value());
                result.put("body", response.getBody());
                responses.add(result);

            } catch (RestClientException | IllegalArgumentException e) {
                // RestClientException covers HTTP transport errors and non-2xx responses
                // IllegalArgumentException covers bad MethodValueOf/URL parsing.
                // Errors and other RuntimeExceptions propagate normally.
                Map<String, Object> errorResult = new LinkedHashMap<>();
                errorResult.put("requestId", subReq.requestId());
                errorResult.put("statusCode", 500);
                errorResult.put("error", e.getMessage());
                responses.add(errorResult);
                if (enclosingTransaction)
                    throw BaasException.badRequest("BATCH_FAILED",
                        "Batch request " + subReq.requestId() + " failed: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(responses));
    }
}
