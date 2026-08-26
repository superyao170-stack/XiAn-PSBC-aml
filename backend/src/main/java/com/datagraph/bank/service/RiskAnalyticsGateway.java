package com.datagraph.bank.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class RiskAnalyticsGateway {
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public RiskAnalyticsGateway(ObjectMapper mapper,
            @Value("${analytics.base-url:http://localhost:18082}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public JsonNode execute(String runType, JsonNode payload) {
        String path = switch (runType.toUpperCase()) {
            case "FEATURE_SNAPSHOT" -> "/v1/features/snapshot";
            case "EVENT_CHAIN" -> "/v1/event-chains/mine";
            case "BEHAVIOR_MATRIX" -> "/v1/behavior-matrix/build";
            case "RISK_DIFFUSION" -> "/v1/risk-diffusion/run";
            case "INCREMENTAL_LEARNING" -> "/v1/incremental-learning/run";
            case "META_PATH_DETECTION" -> "/v1/meta-paths/detect";
            case "TEMPORAL_ANOMALY" -> "/v1/temporal-anomalies/detect";
            case "LOCAL_HYPERGRAPH" -> "/v1/hypergraph/local-infer";
            case "CASCADE_INFERENCE" -> "/v1/cascade/infer";
            case "CASE_MATTER_EXPLANATION" -> "/v1/case-matters/explain";
            case "INFERENCE" -> "/v1/inference";
            case "EVALUATION" -> "/v1/evaluations/run";
            default -> throw new IllegalArgumentException("Unsupported analytics run type: " + runType);
        };
        return post(path, payload);
    }

    public JsonNode replay(JsonNode payload) {
        return post("/v1/replay", payload);
    }

    private JsonNode post(String path, JsonNode payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("Analytics service returned HTTP " + response.statusCode()
                        + ": " + response.body());
            return mapper.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("Analytics service call failed: " + ex.getMessage(), ex);
        }
    }
}
