package com.athnasia.swarmwatchsvc.tools.knowledge;

import com.athnasia.swarmwatchsvc.domain.AgentState;
import com.athnasia.swarmwatchsvc.provider.ProviderException;
import com.athnasia.swarmwatchsvc.provider.Secrets;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiKnowledgeResourcesTest {
    record Request(String method, String path, String contentType, String body, String auth) {}
    record Snapshot(String store, String file, String pendingStore, String pendingFile) {}
    final ObjectMapper json = new ObjectMapper();
    final List<Request> requests = new CopyOnWriteArrayList<>();
    final List<Snapshot> checkpoints = new ArrayList<>();
    final Map<String, Integer> failures = new HashMap<>();
    final Deque<String> ingestion = new ArrayDeque<>();
    final Secrets keys = new Secrets() {
        public String openAiKey() { return "fixture-openai"; }
        public String inkboxKey() { throw new AssertionError("File search must not access Inkbox."); }
    };
    HttpServer server;
    String base;
    int files;
    int stores;
    long indexingResponseDelayMillis;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(method, path, exchange.getRequestHeaders().getFirst("Content-Type"), body,
                    exchange.getRequestHeaders().getFirst("Authorization")));
            int status = failures.getOrDefault(method + " " + path, 200);
            String reply = "{}";
            if (status != 200) reply = "{\"error\":{\"message\":\"fixture-openai private provider response\"}}";
            else if (method.equals("POST") && path.equals("/v1/files"))
                reply = "{\"id\":\"file-" + ++files + "\",\"object\":\"file\",\"purpose\":\"assistants\",\"filename\":\"context.txt\"}";
            else if (method.equals("POST") && path.equals("/v1/vector_stores"))
                reply = "{\"id\":\"vs_" + ++stores + "\",\"object\":\"vector_store\",\"status\":\"in_progress\",\"file_counts\":{\"in_progress\":1,\"completed\":0,\"failed\":0,\"cancelled\":0,\"total\":1}}";
            else if (method.equals("GET") && path.matches("/v1/vector_stores/vs_[0-9]+/files/file-[0-9]+")) {
                if (indexingResponseDelayMillis > 0) try { Thread.sleep(indexingResponseDelayMillis); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); exchange.close(); return; }
                String state = ingestion.isEmpty() ? "completed" : ingestion.removeFirst();
                reply = "{\"id\":\"" + path.substring(path.lastIndexOf('/') + 1) + "\",\"object\":\"vector_store.file\",\"status\":\"" + state + "\",\"last_error\":null,\"usage_bytes\":32}";
            } else if (method.equals("DELETE")) reply = "{\"deleted\":true}";
            else { status = 400; reply = "{\"error\":{\"message\":\"Unexpected fixture request\"}}"; }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            byte[] response = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
    @AfterEach void stop() { server.stop(0); }
    AgentState agent() {
        var agent = new AgentState(); agent.id = "agent-1"; agent.tenantId = "tenant-1";
        agent.name = "Scout"; agent.company = "Acme"; agent.companyMission = "Ship trustworthy software — safely";
        agent.goal = "Verify releases"; return agent;
    }
    OpenAiKnowledgeResources provider() {
        return new OpenAiKnowledgeResources(HttpClient.newHttpClient(), json, keys, base, Duration.ofSeconds(3), Duration.ZERO);
    }
    Runnable checkpoint(AgentState agent) {
        return () -> checkpoints.add(new Snapshot(agent.vectorStoreId, agent.knowledgeFileId,
                agent.pendingKnowledgeVectorStoreId, agent.pendingKnowledgeFileId));
    }
    List<String> operations() { return requests.stream().map(r -> r.method() + " " + r.path()).toList(); }

    @Test void provisionsPrivateSeedFileAndWaitsForIndexingBeforeActivatingItsStore() throws Exception {
        ingestion.addAll(List.of("in_progress", "completed"));
        var agent = agent();
        provider().ensureReady(agent, checkpoint(agent));
        assertThat(agent.vectorStoreId).isEqualTo("vs_1");
        assertThat(agent.knowledgeFileId).isEqualTo("file-1");
        assertThat(agent.knowledgeContextHash).isNotBlank();
        assertThat(agent.pendingKnowledgeFileId).isNull();
        assertThat(operations()).containsExactly("POST /v1/files", "POST /v1/vector_stores",
                "GET /v1/vector_stores/vs_1/files/file-1", "GET /v1/vector_stores/vs_1/files/file-1");
        var upload = requests.getFirst();
        assertThat(upload.contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(upload.auth()).isEqualTo("Bearer fixture-openai");
        assertThat(upload.body()).contains("name=\"purpose\"\r\n\r\nassistants\r\n", "name=\"file\"; filename=\"",
                "Content-Type: text/plain; charset=UTF-8", "Scout", "Acme", "Ship trustworthy software — safely", "Verify releases");
        var store = json.readTree(requests.get(1).body());
        assertThat(store.path("file_ids").get(0).asText()).isEqualTo("file-1");
        assertThat(store.path("metadata").path("tenant_id").asText()).isEqualTo("tenant-1");
        assertThat(store.path("metadata").path("agent_id").asText()).isEqualTo("agent-1");
        assertThat(checkpoints).containsExactly(new Snapshot(null, null, null, "file-1"),
                new Snapshot(null, null, "vs_1", "file-1"), new Snapshot("vs_1", "file-1", null, null));
    }

    @Test void unchangedContextMakesNoRequestsAndAnotherAgentReceivesDifferentResources() {
        var provider = provider(); var first = agent();
        provider.ensureReady(first, checkpoint(first));
        int completed = requests.size();
        provider.ensureReady(first, checkpoint(first));
        assertThat(requests).hasSize(completed);
        var other = agent(); other.id = "agent-2"; other.tenantId = "tenant-2";
        provider.ensureReady(other, checkpoint(other));
        assertThat(other.vectorStoreId).isEqualTo("vs_2").isNotEqualTo(first.vectorStoreId);
        assertThat(other.knowledgeFileId).isEqualTo("file-2").isNotEqualTo(first.knowledgeFileId);
        assertThat(requests.stream().filter(r -> r.path().equals("/v1/vector_stores")).toList().getLast().body())
                .contains("tenant-2", "agent-2").doesNotContain("tenant-1", "agent-1");
    }

    @Test void missionRefreshIndexesNewResourcesBeforeSwitchingAndRemovingOldOnes() {
        var provider = provider(); var agent = agent();
        provider.ensureReady(agent, checkpoint(agent)); requests.clear(); checkpoints.clear();
        String previousHash = agent.knowledgeContextHash;
        agent.companyMission = "Build dependable tools";
        provider.ensureReady(agent, checkpoint(agent));
        assertThat(agent.vectorStoreId).isEqualTo("vs_2");
        assertThat(agent.knowledgeFileId).isEqualTo("file-2");
        assertThat(agent.knowledgeContextHash).isNotEqualTo(previousHash);
        assertThat(requests.getFirst().body()).contains("Build dependable tools").doesNotContain("Ship trustworthy");
        assertThat(operations()).containsExactly("POST /v1/files", "POST /v1/vector_stores",
                "GET /v1/vector_stores/vs_2/files/file-2", "DELETE /v1/vector_stores/vs_1", "DELETE /v1/files/file-1");
        assertThat(checkpoints).contains(new Snapshot("vs_1", "file-1", "vs_2", "file-2"),
                new Snapshot("vs_2", "file-2", "vs_1", "file-1"));
    }

    @Test void sameTenantPeersHaveSeparateStoresAndDeletionOnlyRemovesTheSelectedAgentsResources() {
        var provider = provider(); var first = agent(); var peer = agent(); peer.id = "agent-peer";
        provider.ensureReady(first, checkpoint(first));
        provider.ensureReady(peer, checkpoint(peer)); requests.clear();
        provider.delete(first, checkpoint(first));
        assertThat(operations()).containsExactly("DELETE /v1/vector_stores/vs_1", "DELETE /v1/files/file-1");
        assertThat(peer.vectorStoreId).isEqualTo("vs_2");
        assertThat(peer.knowledgeFileId).isEqualTo("file-2");
    }

    @Test void failedIndexingCompensatesNewResourcesAndPreservesPreviouslyReadyStore() {
        var provider = provider(); var agent = agent();
        provider.ensureReady(agent, checkpoint(agent)); requests.clear();
        agent.companyMission = "A new mission"; ingestion.add("failed");
        assertThatThrownBy(() -> provider.ensureReady(agent, checkpoint(agent)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("index");
        assertThat(agent.vectorStoreId).isEqualTo("vs_1");
        assertThat(agent.knowledgeFileId).isEqualTo("file-1");
        assertThat(agent.pendingKnowledgeVectorStoreId).isNull();
        assertThat(agent.pendingKnowledgeFileId).isNull();
        assertThat(operations()).endsWith("DELETE /v1/vector_stores/vs_2", "DELETE /v1/files/file-2");
    }

    @Test void failedStoreCreationRemovesUploadedFileAndSanitizesProviderError() {
        failures.put("POST /v1/vector_stores", 500);
        var agent = agent();
        assertThatThrownBy(() -> provider().ensureReady(agent, checkpoint(agent)))
                .isInstanceOf(ProviderException.class).hasMessageNotContaining("fixture-openai").hasMessageNotContaining("private provider response");
        assertThat(operations()).containsExactly("POST /v1/files", "POST /v1/vector_stores", "DELETE /v1/files/file-1");
        assertThat(agent.knowledgeFileId).isNull();
        assertThat(agent.pendingKnowledgeFileId).isNull();
    }

    @Test void indexingWaitHasDeadlineAndCompensatesInsteadOfLeavingUsableStore() {
        for (int i = 0; i < 50; i++) ingestion.add("in_progress");
        var provider = new OpenAiKnowledgeResources(HttpClient.newHttpClient(), json, keys, base, Duration.ofMillis(120), Duration.ofMillis(10));
        var agent = agent();
        assertThatThrownBy(() -> provider.ensureReady(agent, checkpoint(agent)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("index");
        assertThat(requests.stream().filter(r -> r.method().equals("GET")).count()).isBetween(1L, 15L);
        assertThat(operations()).endsWith("DELETE /v1/vector_stores/vs_1", "DELETE /v1/files/file-1");
        assertThat(agent.vectorStoreId).isNull();
    }

    @Test void indexingHttpTimeoutReportsTheIndexingDeadlineAndCompensates() {
        indexingResponseDelayMillis = 250;
        var provider = new OpenAiKnowledgeResources(HttpClient.newHttpClient(), json, keys, base, Duration.ofMillis(80), Duration.ZERO);
        var agent = agent();
        assertThatThrownBy(() -> provider.ensureReady(agent, checkpoint(agent)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("indexing did not finish in time");
        assertThat(operations()).endsWith("DELETE /v1/vector_stores/vs_1", "DELETE /v1/files/file-1");
        assertThat(agent.vectorStoreId).isNull();
    }

    @Test void deletionRetainsFailedIdsAndRetries404WithoutTouchingOtherResources() {
        var agent = agent(); var provider = provider();
        provider.ensureReady(agent, checkpoint(agent)); requests.clear();
        failures.put("DELETE /v1/files/file-1", 500);
        assertThatThrownBy(() -> provider.delete(agent, checkpoint(agent))).isInstanceOf(ProviderException.class);
        assertThat(agent.vectorStoreId).isNull();
        assertThat(agent.knowledgeFileId).isEqualTo("file-1");
        failures.put("DELETE /v1/files/file-1", 404);
        provider.delete(agent, checkpoint(agent));
        provider.delete(agent, checkpoint(agent));
        assertThat(operations()).containsExactly("DELETE /v1/vector_stores/vs_1", "DELETE /v1/files/file-1", "DELETE /v1/files/file-1");
        assertThat(agent.knowledgeFileId).isNull();
        assertThat(agent.knowledgeContextHash).isNull();
    }

    @Test void failedCompensationIsCheckpointedAndRetriedBeforeAnotherUpload() {
        failures.put("POST /v1/vector_stores", 500); failures.put("DELETE /v1/files/file-1", 500);
        var agent = agent(); var provider = provider();
        assertThatThrownBy(() -> provider.ensureReady(agent, checkpoint(agent))).isInstanceOf(ProviderException.class);
        assertThat(agent.pendingKnowledgeFileId).isEqualTo("file-1");
        failures.clear(); requests.clear();
        provider.ensureReady(agent, checkpoint(agent));
        assertThat(operations()).startsWith("DELETE /v1/files/file-1", "POST /v1/files");
        assertThat(agent.knowledgeFileId).isEqualTo("file-2");
        assertThat(agent.pendingKnowledgeFileId).isNull();
    }

    @Test void failedOldResourceCleanupRetainsNewReadyStoreAndRetriesWithoutUploadingAgain() {
        var agent = agent(); var provider = provider();
        provider.ensureReady(agent, checkpoint(agent));
        agent.companyMission = "A replacement mission";
        failures.put("DELETE /v1/vector_stores/vs_1", 500);
        assertThatThrownBy(() -> provider.ensureReady(agent, checkpoint(agent))).isInstanceOf(ProviderException.class);
        assertThat(agent.vectorStoreId).isEqualTo("vs_2");
        assertThat(agent.knowledgeFileId).isEqualTo("file-2");
        assertThat(agent.pendingKnowledgeVectorStoreId).isEqualTo("vs_1");
        assertThat(agent.pendingKnowledgeFileId).isEqualTo("file-1");
        failures.put("DELETE /v1/vector_stores/vs_1", 404); requests.clear();
        provider.ensureReady(agent, checkpoint(agent));
        assertThat(operations()).containsExactly("DELETE /v1/vector_stores/vs_1", "DELETE /v1/files/file-1");
        assertThat(agent.vectorStoreId).isEqualTo("vs_2");
        assertThat(agent.pendingKnowledgeVectorStoreId).isNull();
        assertThat(agent.pendingKnowledgeFileId).isNull();
    }
}
