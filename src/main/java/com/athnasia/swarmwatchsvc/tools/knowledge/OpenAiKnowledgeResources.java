package com.athnasia.swarmwatchsvc.tools.knowledge;

import com.athnasia.swarmwatchsvc.domain.AgentState;
import com.athnasia.swarmwatchsvc.provider.ProviderException;
import com.athnasia.swarmwatchsvc.provider.Secrets;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class OpenAiKnowledgeResources implements KnowledgeResources {
    private final HttpClient client;
    private final ObjectMapper json;
    private final Secrets secrets;
    private final String base;
    private final Duration indexingTimeout;
    private final Duration pollInterval;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    public OpenAiKnowledgeResources(HttpClient client, ObjectMapper json, Secrets secrets, String base) {
        this(client, json, secrets, base, Duration.ofSeconds(60), Duration.ofMillis(500));
    }
    OpenAiKnowledgeResources(HttpClient client, ObjectMapper json, Secrets secrets, String base, Duration indexingTimeout, Duration pollInterval) {
        this.client = client; this.json = json; this.secrets = secrets; this.base = base;
        this.indexingTimeout = indexingTimeout; this.pollInterval = pollInterval;
    }
    public void ensureReady(AgentState agent, Runnable checkpoint) {
        deletePending(agent, checkpoint);
        String seed = KnowledgeContext.seed(agent);
        String hash = KnowledgeContext.hash(seed);
        if (hash.equals(agent.knowledgeContextHash) && agent.vectorStoreId != null && agent.knowledgeFileId != null) return;
        try {
            String boundary = "swarmwatch-" + UUID.randomUUID();
            String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"purpose\"\r\n\r\nassistants\r\n"
                    + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"agent-context.txt\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + seed + "\r\n--" + boundary + "--\r\n";
            agent.pendingKnowledgeFileId = id(send("POST", "/files", "multipart/form-data; boundary=" + boundary, multipart, false, REQUEST_TIMEOUT));
            checkpoint.run();
            agent.pendingKnowledgeVectorStoreId = id(sendJson("POST", "/vector_stores", Map.of(
                    "name", "swarmwatch-" + agent.id,
                    "file_ids", List.of(agent.pendingKnowledgeFileId),
                    "metadata", Map.of("tenant_id", agent.tenantId, "agent_id", agent.id))));
            checkpoint.run();
            awaitIndex(agent.pendingKnowledgeVectorStoreId, agent.pendingKnowledgeFileId);
        } catch (RuntimeException failure) {
            try { deletePending(agent, checkpoint); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        String oldStore = agent.vectorStoreId;
        String oldFile = agent.knowledgeFileId;
        agent.vectorStoreId = agent.pendingKnowledgeVectorStoreId;
        agent.knowledgeFileId = agent.pendingKnowledgeFileId;
        agent.knowledgeContextHash = hash;
        agent.pendingKnowledgeVectorStoreId = oldStore;
        agent.pendingKnowledgeFileId = oldFile;
        checkpoint.run();
        deletePending(agent, checkpoint);
    }
    public void delete(AgentState agent, Runnable checkpoint) {
        deletePending(agent, checkpoint);
        if (agent.vectorStoreId != null) {
            remove("/vector_stores/" + pathId(agent.vectorStoreId));
            agent.vectorStoreId = null;
            checkpoint.run();
        }
        if (agent.knowledgeFileId != null) {
            remove("/files/" + pathId(agent.knowledgeFileId));
            agent.knowledgeFileId = null;
            agent.knowledgeContextHash = null;
            checkpoint.run();
        }
    }
    private void deletePending(AgentState agent, Runnable checkpoint) {
        if (agent.pendingKnowledgeVectorStoreId != null) {
            remove("/vector_stores/" + pathId(agent.pendingKnowledgeVectorStoreId));
            agent.pendingKnowledgeVectorStoreId = null;
            checkpoint.run();
        }
        if (agent.pendingKnowledgeFileId != null) {
            remove("/files/" + pathId(agent.pendingKnowledgeFileId));
            agent.pendingKnowledgeFileId = null;
            checkpoint.run();
        }
    }
    private void awaitIndex(String store, String file) {
        long deadline = System.nanoTime() + indexingTimeout.toNanos();
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new ProviderException("OpenAI file search indexing did not finish in time. Retry the agent operation.");
            JsonNode indexed;
            try {
                indexed = send("GET", "/vector_stores/" + pathId(store) + "/files/" + pathId(file), null, null, true,
                        Duration.ofNanos(Math.min(remaining, REQUEST_TIMEOUT.toNanos())));
            } catch (ProviderException failure) {
                if (System.nanoTime() >= deadline)
                    throw new ProviderException("OpenAI file search indexing did not finish in time. Retry the agent operation.");
                throw failure;
            }
            String status = indexed.path("status").asText();
            if (status.equals("completed") && file.equals(indexed.path("id").asText())) return;
            if (!indexed.isNull() && !status.equals("in_progress"))
                throw new ProviderException("OpenAI file search indexing failed. Retry the agent operation.");
            long pause = Math.min(pollInterval.toNanos(), deadline - System.nanoTime());
            if (pause > 0) try { Thread.sleep(Duration.ofNanos(pause)); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new ProviderException("OpenAI file search indexing was interrupted. Retry the agent operation.");
            }
        }
    }
    private String id(JsonNode response) { return pathId(response.path("id").asText()); }
    private String pathId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,256}"))
            throw new ProviderException("OpenAI file search returned an invalid resource identifier.");
        return value;
    }
    private void remove(String path) { send("DELETE", path, null, null, true, REQUEST_TIMEOUT); }
    private JsonNode sendJson(String method, String path, Object payload) {
        try { return send(method, path, "application/json", json.writeValueAsString(payload), false, REQUEST_TIMEOUT); }
        catch (ProviderException failure) { throw failure; }
        catch (Exception failure) { throw new ProviderException("OpenAI file search request could not be prepared."); }
    }
    private JsonNode send(String method, String path, String contentType, String body, boolean allowNotFound, Duration timeout) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                    .header("Authorization", "Bearer " + secrets.openAiKey());
            if (contentType != null) builder.header("Content-Type", contentType);
            if (path.startsWith("/vector_stores")) builder.header("OpenAI-Beta", "assistants=v2");
            var request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (allowNotFound && response.statusCode() == 404) return json.nullNode();
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ProviderException("OpenAI file search request failed (HTTP " + response.statusCode() + "). Check account permissions, quota, and retry.");
            return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
        } catch (ProviderException failure) { throw failure; }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ProviderException("OpenAI file search request was interrupted. Retry the agent operation.");
        } catch (Exception failure) { throw new ProviderException("OpenAI file search request could not complete. Check connectivity and retry."); }
    }
}
