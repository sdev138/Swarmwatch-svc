package com.athnasia.swarmwatchsvc.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"swarmwatch.check-interval-seconds=3600", "swarmwatch.registry-fixture=true"})
class RegistryApiTest {
    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ownedLocalAgentExposesExplicitSimulatedRegistryAndAuthenticatedRetry() throws Exception {
        assertEquals(200, request("POST", "/api/session", "{\"user_id\":\"" + UUID.randomUUID() + "\"}", true).statusCode());
        assertEquals(200, request("PUT", "/api/mission", "{\"company_mission\":\"Help local businesses\"}", true).statusCode());
        var created = request("POST", "/api/agents", "{\"agent_Name\":\"Registry agent\",\"company\":\"Example\",\"goal\":\"Track releases\"}", true);
        assertEquals(201, created.statusCode());
        String id = json.readTree(created.body()).path("agent_Id").asText();
        String path = "/api/agents/" + id + "/registry";
        assertEquals(401, request("GET", path, null, false).statusCode());
        var response = request("GET", path, null, true);
        assertEquals(200, response.statusCode());
        var registry = json.readTree(response.body());
        assertEquals("local", registry.path("status").asText());
        assertTrue(registry.path("identifier").isNull());
        assertTrue(registry.path("phone_number").asText().contains("SIMULATED"));
        assertTrue(registry.path("error").isNull());
        assertFalse(registry.path("updated_at").asText().isBlank());
        assertEquals(202, request("POST", path + "/retry", null, true).statusCode());
        assertEquals(404, request("GET", "/api/agents/another-users-agent/registry", null, true).statusCode());
        assertEquals(404, request("POST", "/api/agents/another-users-agent/registry/retry", null, true).statusCode());
        assertEquals(204, request("DELETE", "/api/agents/" + id, null, true).statusCode());
        assertEquals(404, request("GET", path, null, true).statusCode());
    }

    private HttpResponse<String> request(String method, String path, String body, boolean authorized) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (authorized) request.header("Authorization", "Bearer local-development-token");
        if (body != null) request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
