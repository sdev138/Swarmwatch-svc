package com.athnasia.swarmwatchsvc;

import com.athnasia.swarmwatchsvc.domain.*;
import com.athnasia.swarmwatchsvc.provider.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class ProviderContractTests {
    record Request(String method, String path, JsonNode body, String auth) {}
    final ObjectMapper json = new ObjectMapper();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    HttpServer server;
    String base;
    String scoreReply = "{\"answers\":[{\"type\":\"score\",\"name\":\"goal_alignment\",\"score\":3.1,\"confidence\":0.8,\"probabilities\":[]}]}";
    boolean failConfiguration;
    String responseReply;
    final Secrets keys = new Secrets() { public String openAiKey() { return "fixture-openai"; } public String inkboxKey() { return "fixture-inkbox"; } };

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), path, body.isBlank() ? json.nullNode() : json.readTree(body),
                    Optional.ofNullable(exchange.getRequestHeaders().getFirst("Authorization")).orElse(exchange.getRequestHeaders().getFirst("X-API-Key"))));
            int status = 200;
            String reply;
            if (path.equals("/v1/responses")) {
                reply = responseReply == null ? "{\"id\":\"resp-fixture\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Release verified; one blocker remains.\"}]}]}" : responseReply;
            } else if (path.equals("/v1/decisions")) reply = scoreReply;
            else if (path.equals("/api/v1/identities") && exchange.getRequestMethod().equals("POST"))
                reply = "{\"id\":\"ink-id\",\"agent_handle\":\"sw-agent-id\",\"email_address\":\"agent@inkboxmail.com\",\"phone_number\":{\"id\":\"number-id\",\"number\":\"+14155550100\"}}";
            else if (path.equals("/api/v1/phone/hosted-agent-config") && failConfiguration) { status = 502; reply = "{\"detail\":\"provider failure fixture-inkbox\"}"; }
            else if (path.startsWith("/api/v1/phone/calls?")) reply = "[{\"id\":\"call-id\",\"status\":\"completed\",\"direction\":\"inbound\"}]";
            else if (path.equals("/api/v1/phone/calls/call-id/transcripts"))
                reply = "[{\"id\":\"segment-id\",\"call_id\":\"call-id\",\"seq\":0,\"party\":\"remote\",\"phone_number\":\"+14155550111\",\"ts_ms\":1795219200000,\"text\":\"Appointment confirmed\",\"created_at\":\"2026-11-21T00:00:00Z\"}]";
            else if (path.equals("/api/v1/phone/place-call")) reply = "{\"id\":\"call-id\",\"status\":\"ringing\"}";
            else if (exchange.getRequestMethod().equals("DELETE")) { status = 204; reply = ""; }
            else reply = "{}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    AgentState agent() {
        var a = new AgentState(); a.id = "agent-id"; a.tenantId = "tenant"; a.name = "Scout"; a.company = "Acme";
        a.goal = "Verify releases"; a.phone = "+14155550100"; a.providerIdentityId = "ink-id"; a.providerHandle = "sw-agent-id";
        a.companyMission = "Ship trustworthy software";
        a.vectorStoreId = "vs-agent-owned";
        return a;
    }
    @Test void responsesUsesHighReasoningAndToolsThenDecisionsNormalizesItsOrderedScore() {
        var provider = new OpenAiProvider(HttpClient.newHttpClient(), json, keys, base + "/v1");
        var history = List.of(ConversationEntry.message("user", "Verify the release", "dashboard"));
        var reply = provider.reply(agent(), history, "How are you doing and what are you working on?", List.of());
        assertThat(reply.text()).contains("Release verified");
        var response = requests.getFirst();
        assertThat(response.path()).isEqualTo("/v1/responses");
        assertThat(response.auth()).isEqualTo("Bearer fixture-openai");
        assertThat(response.body().path("model").asText()).isEqualTo("gpt-6-luna");
        assertThat(response.body().path("reasoning").path("effort").asText()).isEqualTo("high");
        assertThat(response.body().path("instructions").asText()).contains("Ship trustworthy software", "Verify releases");
        assertThat(response.body().path("tools").findValuesAsText("name")).contains("place_call", "collaborate");
        var toolTypes = new ArrayList<String>();
        response.body().path("tools").forEach(tool -> toolTypes.add(tool.path("type").asText()));
        assertThat(toolTypes).contains("web_search", "shell", "file_search", "computer", "tool_search");
        for (var tool : response.body().path("tools")) {
            if (tool.path("type").asText().equals("shell")) assertThat(tool.path("environment").path("type").asText()).isEqualTo("container_auto");
            if (tool.path("type").asText().equals("file_search")) assertThat(tool.path("vector_store_ids").get(0).asText()).isEqualTo("vs-agent-owned");
            if (tool.path("type").asText().equals("function")) assertThat(tool.path("defer_loading").asBoolean()).isTrue();
        }
        assertThat(response.body().path("input").toString()).contains("Verify the release");
        var assessment = provider.assess(agent(), history);
        assertThat(assessment.score()).isEqualTo(0.78);
        assertThat(assessment.reason()).isNotBlank();
        var decision = requests.getLast();
        assertThat(decision.path()).isEqualTo("/v1/decisions");
        assertThat(decision.body().path("model").asText()).isEqualTo("gpt-6-luna");
        assertThat(decision.body().path("questions").get(0).path("type").asText()).isEqualTo("score");
        assertThat(decision.body().path("questions").get(0).path("levels")).hasSize(5);
    }
    @Test void decisionsRejectsOutOfRangeRefusalsAndMissingAnswers() {
        var provider = new OpenAiProvider(HttpClient.newHttpClient(), json, keys, base + "/v1");
        for (String response : List.of("{\"answers\":[]}", "{\"answers\":[{\"type\":\"refusal\",\"name\":\"goal_alignment\"}]}",
                "{\"answers\":[{\"type\":\"score\",\"name\":\"goal_alignment\",\"score\":4.01}]}")) {
            scoreReply = response;
            assertThatThrownBy(() -> provider.assess(agent(), List.of())).isInstanceOf(ProviderException.class);
        }
    }
    @Test void followupMatchesFunctionOutputToPreviousResponse() {
        var provider = new OpenAiProvider(HttpClient.newHttpClient(), json, keys, base + "/v1");
        provider.followup(agent(), "resp-tools", List.of(new ModelProvider.ToolResult("call-1", "{\"id\":\"phone-call\"}")), List.of());
        var body = requests.getFirst().body();
        assertThat(body.path("previous_response_id").asText()).isEqualTo("resp-tools");
        assertThat(body.path("input").get(0).path("type").asText()).isEqualTo("function_call_output");
        assertThat(body.path("input").get(0).path("call_id").asText()).isEqualTo("call-1");
    }
    @Test void inkboxProvisioningCallsAndTranscriptRequestsMatchDocumentedApi() {
        var provider = new InkboxProvider(HttpClient.newHttpClient(), json, keys, base + "/api/v1");
        var identity = provider.create("agent-id", "Scout", "Acme", "Verify releases", "Ship trustworthy software");
        assertThat(identity.email()).isEqualTo("agent@inkboxmail.com");
        assertThat(identity.phone()).isEqualTo("+14155550100");
        var create = requests.getFirst();
        assertThat(create.auth()).isEqualTo("fixture-inkbox");
        assertThat(create.body().path("mailbox").has("sending_domain")).isTrue();
        assertThat(create.body().path("mailbox").path("sending_domain").isNull()).isTrue();
        assertThat(create.body().path("phone_number").path("type").asText()).isEqualTo("local");
        assertThat(create.body().path("phone_number").path("incoming_call_action").asText()).isEqualTo("hosted_agent");
        assertThat(requests.stream().filter(r -> r.path().equals("/api/v1/phone/hosted-agent-config")).findFirst().orElseThrow().body().path("instructions").asText()).contains("Scout", "Acme", "Verify releases", "Ship trustworthy software");
        var call = provider.call(agent(), "+14155550111", "Confirm an appointment");
        assertThat(call.id()).isEqualTo("call-id");
        var callBody = requests.getLast().body();
        assertThat(callBody.path("from_number").asText()).isEqualTo("+14155550100");
        assertThat(callBody.path("mode").asText()).isEqualTo("hosted_agent");
        assertThat(callBody.path("reason").asText()).isEqualTo("Confirm an appointment");
        var transcripts = provider.transcripts(agent());
        assertThat(transcripts).hasSize(1);
        assertThat(transcripts.getFirst().raw()).containsEntry("party", "remote").containsEntry("seq", 0);
        assertThat(requests.stream().anyMatch(r -> r.path().contains("calls?agent_identity_id=ink-id"))).isTrue();
        provider.delete(identity.handle());
        assertThat(requests.getLast().path()).isEqualTo("/api/v1/identities/sw-agent-id");
        assertThat(requests.getLast().method()).isEqualTo("DELETE");
    }
    @Test void failedVoiceConfigurationCompensatesIdentityWithoutLeakingProviderBody() {
        failConfiguration = true;
        var provider = new InkboxProvider(HttpClient.newHttpClient(), json, keys, base + "/api/v1");
        assertThatThrownBy(() -> provider.create("agent-id", "Scout", "Acme", "Verify releases", "Ship trustworthy software"))
                .isInstanceOf(ProviderException.class).hasMessageNotContaining("fixture-inkbox");
        assertThat(requests.getLast().method()).isEqualTo("DELETE");
    }
    @Test void hostedToolCallsAreAuditedAndNativeComputerCallIsReturnedForExecution() {
        responseReply = "{\"id\":\"resp-native\",\"status\":\"completed\",\"output\":["
                + "{\"type\":\"reasoning\",\"summary\":[{\"text\":\"private reasoning must not be stored\"}]},"
                + "{\"type\":\"web_search_call\",\"id\":\"web-id\",\"status\":\"completed\",\"action\":{\"query\":\"release notes\"}},"
                + "{\"type\":\"shell_call_output\",\"call_id\":\"shell-id\",\"output\":[{\"stdout\":\"release verified\",\"stderr\":\"\",\"outcome\":{\"type\":\"exit\",\"exit_code\":0}}]},"
                + "{\"type\":\"computer_call\",\"call_id\":\"computer-id\",\"actions\":[{\"type\":\"screenshot\"}],\"status\":\"completed\"}]}";
        var provider = new OpenAiProvider(HttpClient.newHttpClient(), json, keys, base + "/v1");
        var reply = provider.reply(agent(), List.of(), "Inspect release evidence", List.of());
        assertThat(reply.calls()).hasSize(1);
        assertThat(reply.calls().getFirst().name()).isEqualTo("computer");
        assertThat(reply.calls().getFirst().arguments()).containsEntry("call_id", "computer-id");
        assertThat(reply.activity().toString()).contains("release notes", "release verified").doesNotContain("private reasoning");
    }
    @Test void computerOutputUsesNativeScreenshotSchemaAndIsNotPersistedAsBinary() {
        var nativeOutput = Map.<String, Object>of("type", "computer_call_output", "call_id", "computer-id",
                "output", Map.of("type", "computer_screenshot", "image_url", "data:image/png;base64,fixture", "detail", "original"));
        var result = new ModelProvider.ToolResult("computer-id", "Screenshot captured", nativeOutput);
        var provider = new OpenAiProvider(HttpClient.newHttpClient(), json, keys, base + "/v1");
        provider.followup(agent(), "resp-native", List.of(result), List.of());
        assertThat(requests.getFirst().body().path("input").get(0).path("type").asText()).isEqualTo("computer_call_output");
        assertThat(requests.getFirst().body().path("input").get(0).path("output").path("image_url").asText()).startsWith("data:image/png");
        assertThat(json.valueToTree(result).toString()).doesNotContain("base64").contains("computer_call_output");
    }
    @Test void modelRequestsBoundRecentContextAndKeepTheNewestEvidence() {
        var provider = new OpenAiProvider(HttpClient.newHttpClient(), json, keys, base + "/v1");
        var history = new ArrayList<ConversationEntry>();
        for (int i = 0; i < 30; i++) history.add(ConversationEntry.message("assistant", "Old " + i + ": " + "x".repeat(16000), "dashboard"));
        history.add(ConversationEntry.message("user", "LATEST EVIDENCE: deployment verified", "dashboard"));
        provider.reply(agent(), history, "Summarize the latest evidence", List.of());
        provider.assess(agent(), history);
        for (var request : requests) {
            assertThat(request.body().toString()).hasSizeLessThan(150000);
            assertThat(request.body().path("input").toString()).contains("LATEST EVIDENCE").doesNotContain("Old 0:");
        }
        assertThat(history).hasSize(31);
    }
}
