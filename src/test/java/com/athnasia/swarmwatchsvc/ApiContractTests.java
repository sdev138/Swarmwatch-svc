package com.athnasia.swarmwatchsvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "swarmwatch.check-interval-seconds=3600")
class ApiContractTests {
    @LocalServerPort int port;
    final HttpClient client = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    @Test
    void localApiRequiresExactTokenBindsUuidAndEnforcesAgentCap() throws Exception {
        assertThat(request("GET", "/api/agents", null, null).statusCode()).isEqualTo(401);
        assertThat(request("GET", "/api/agents", null, "not-valid").statusCode()).isEqualTo(401);
        String id = UUID.randomUUID().toString();
        var session = request("POST", "/api/session", "{\"user_id\":\"" + id + "\"}", "local-development-token");
        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(json.readTree(session.body()).path("user_id").asText()).isEqualTo(id);
        var returning = request("POST", "/api/session", "{\"user_id\":\"" + UUID.randomUUID() + "\"}", "local-development-token");
        assertThat(json.readTree(returning.body()).path("user_id").asText()).isEqualTo(id);
        assertThat(json.readTree(request("GET", "/api/agents", null, "local-development-token").body())).isEmpty();
        assertThat(request("POST", "/api/agents", "{\"agent_Name\":\"Before mission\",\"company\":\"Acme\",\"goal\":\"Track releases\"}", "local-development-token").statusCode()).isEqualTo(409);
        assertThat(request("PUT", "/api/mission", "{\"company_mission\":\"Ship reliable releases with evidence\"}", "local-development-token").statusCode()).isEqualTo(200);
        assertThat(json.readTree(request("GET", "/api/mission", null, "local-development-token").body()).path("company_mission").asText()).isEqualTo("Ship reliable releases with evidence");
        for (int i = 0; i < 3; i++) {
            var created = request("POST", "/api/agents", "{\"agent_Name\":\"Agent " + i + "\",\"company\":\"Acme\",\"goal\":\"Track releases\"}", "local-development-token");
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode agent = json.readTree(created.body());
            assertThat(agent.path("agent_Sentiment").asDouble()).isEqualTo(0.51);
            assertThat(agent.path("user_Id").asText()).isEqualTo(id);
            assertThat(agent.path("phone_number").asText()).contains("SIMULATED");
        }
        assertThat(request("POST", "/api/agents", "{\"agent_Name\":\"Fourth\",\"company\":\"Acme\",\"goal\":\"Track releases\"}", "local-development-token").statusCode()).isEqualTo(409);
    }

    HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
