package com.datagraph.bank.service;

import com.datagraph.bank.controller.RiskAnalyticsController;
import com.datagraph.bank.security.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CollaborativeAmlIntegrationTest {
    @Test
    void gatewayForwardsPayloadToCollaborativeWorkerRoute() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext("/v1/collaborative-aml/detect", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"algorithm\":{\"id\":\"SIM_CSGM\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
        });
        server.start();
        try {
            ObjectMapper mapper = new ObjectMapper();
            var gateway = new RiskAnalyticsGateway(mapper, "http://127.0.0.1:" + server.getAddress().getPort());
            var payload = mapper.readTree("{\"threshold\":0.3,\"accounts\":[]}");
            var result = gateway.execute("COLLABORATIVE_AML", payload);
            assertThat(result.path("algorithm").path("id").asText()).isEqualTo("SIM_CSGM");
            assertThat(mapper.readTree(body.get())).isEqualTo(payload);
        } finally { server.stop(0); }
    }

    @Test
    void crossInstitutionSimulationRequiresSystemAdminBeforeAnyStorageWrite() {
        var jdbc = mock(JdbcTemplate.class);
        var currentUser = mock(CurrentUser.class);
        doThrow(new SecurityException("Current role cannot perform this operation"))
                .when(currentUser).requireAnyRole("sadmin");
        var controller = new RiskAnalyticsController(jdbc, currentUser, new ObjectMapper(),
                mock(RiskAnalyticsGateway.class), mock(ContractProjectionService.class),
                mock(VersionedContractService.class), mock(Task23GraphAnalyticsService.class),
                mock(Task23ScopeAnalyticsService.class));
        var request = new RiskAnalyticsController.RunRequest(null, "AML", "COLLABORATIVE_AML", "fixture",
                "CSGMGraph/1.0", Map.of(), null, null, "SIM_CSGM", "1.0.0", null, Map.of());
        assertThatThrownBy(() -> controller.execute(request)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(jdbc);
    }
}
