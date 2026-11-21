package com.athnasia.swarmwatchsvc.repository;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.athnasia.swarmwatchsvc.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.datastore.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

public class DatastoreSwarmRepository implements SwarmRepository {
    private final Supplier<Datastore> source;
    private final ObjectMapper json;
    public DatastoreSwarmRepository(Supplier<Datastore> source, ObjectMapper json) { this.source = source; this.json = json; }
    private Datastore db() { return source.get(); }
    private Key tenant(String id) { return db().newKeyFactory().setKind("Tenant").newKey(id); }
    private Key agentKey(String tenant, String id) { return db().newKeyFactory().addAncestor(PathElement.of("Tenant", tenant)).setKind("Agent").newKey(id); }
    private Key binding(String subject) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8)));
            return db().newKeyFactory().setKind("GoogleUserBinding").newKey(hash);
        } catch (Exception error) { throw new IllegalStateException("Cannot derive identity key.", error); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception error) { throw new IllegalStateException("Cannot encode persistent state.", error); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception error) { throw new IllegalStateException("Cannot read persistent state.", error); }
    }
    public Optional<UserSession> user(String subject) {
        var entity = db().get(binding(subject));
        return entity == null ? Optional.empty() : Optional.of(read(entity.getString("user_json"), UserSession.class));
    }
    public UserSession bind(String subject, UserSession proposed) {
        var transaction = db().newTransaction();
        try {
            Key userKey = binding(subject);
            Key tenantKey = tenant(proposed.user_id());
            var existing = transaction.get(userKey);
            var occupied = transaction.get(tenantKey);
            if (existing != null) return read(existing.getString("user_json"), UserSession.class);
            if (occupied != null) throw new ApiException(409, "This signup UUID is already bound. Generate a new UUID and retry.");
            transaction.put(Entity.newBuilder(userKey).set("user_json", StringValue.of(write(proposed))).build());
            transaction.put(Entity.newBuilder(tenantKey).set("google_subject_hash", userKey.getName()).set("company_mission", "").build());
            transaction.commit();
            return proposed;
        } finally { if (transaction.isActive()) transaction.rollback(); }
    }
    public String mission(String id) {
        var entity = db().get(tenant(id));
        return entity != null && entity.contains("company_mission") ? entity.getString("company_mission") : "";
    }
    public void mission(String id, String mission) {
        var transaction = db().newTransaction();
        try {
            var key = tenant(id);
            var entity = transaction.get(key);
            if (entity == null) throw new ApiException(401, "Complete sign-in before configuring the mission.");
            transaction.put(Entity.newBuilder(entity).set("company_mission", StringValue.newBuilder(mission).setExcludeFromIndexes(true).build()).build());
            transaction.commit();
        } finally { if (transaction.isActive()) transaction.rollback(); }
    }
    private List<AgentState> queryAgents(StructuredQuery.Filter filter) {
        var query = Query.newEntityQueryBuilder().setKind("Agent");
        if (filter != null) query.setFilter(filter);
        var rows = db().run(query.build());
        var result = new ArrayList<AgentState>();
        rows.forEachRemaining(entity -> result.add(read(entity.getString("agent_json"), AgentState.class)));
        result.sort(Comparator.comparing(agent -> agent.createdAt));
        return List.copyOf(result);
    }
    public List<AgentState> agents(String tenantId) { return queryAgents(StructuredQuery.PropertyFilter.hasAncestor(tenant(tenantId))); }
    public List<AgentState> allAgents() { return queryAgents(null); }
    public Optional<AgentState> agent(String tenant, String id) {
        var entity = db().get(agentKey(tenant, id));
        return entity == null ? Optional.empty() : Optional.of(read(entity.getString("agent_json"), AgentState.class));
    }
    public void save(AgentState agent) {
        var responses = agent.responses.stream().skip(Math.max(0, agent.responses.size() - 20))
                .map(text -> (Value<?>) StringValue.newBuilder(text.length() > 4000 ? text.substring(0, 4000) : text).setExcludeFromIndexes(true).build()).toList();
        db().put(Entity.newBuilder(agentKey(agent.tenantId, agent.id))
                .set("agent_Id", agent.id).set("user_Id", agent.tenantId).set("agent_Name", agent.name)
                .set("agent_Sentiment", agent.sentiment).set("inkbox_email", agent.email).set("phone_number", agent.phone)
                .set("company", agent.company).set("goal", StringValue.newBuilder(agent.goal).setExcludeFromIndexes(true).build())
                .set("company_mission", StringValue.newBuilder(agent.companyMission).setExcludeFromIndexes(true).build())
                .set("agent_responses", ListValue.of(responses))
                .set("agent_json", StringValue.newBuilder(write(agent)).setExcludeFromIndexes(true).build()).build());
    }
    public List<ConversationEntry> conversation(String tenant, String id) {
        var rows = db().run(Query.newEntityQueryBuilder().setKind("Conversation")
                .setFilter(StructuredQuery.PropertyFilter.hasAncestor(agentKey(tenant, id))).build());
        var result = new ArrayList<ConversationEntry>();
        rows.forEachRemaining(entity -> {
            try { result.addAll(json.readValue(entity.getString("agent_conversation_json"), new TypeReference<List<ConversationEntry>>() {})); }
            catch (Exception error) { throw new IllegalStateException("Cannot read conversation chunk.", error); }
        });
        result.sort(Comparator.comparing((ConversationEntry entry) -> Instant.parse(entry.created_at())).thenComparing(ConversationEntry::id));
        return List.copyOf(result);
    }
    public boolean append(String tenant, String id, ConversationEntry entry) {
        Key parent = agentKey(tenant, id);
        Key key = Key.newBuilder(parent, "Conversation", entry.id()).build();
        var transaction = db().newTransaction();
        try {
            var agent = transaction.get(parent);
            var existing = transaction.get(key);
            if (agent == null) throw new ApiException(404, "Agent not found.");
            if (read(agent.getString("agent_json"), AgentState.class).deletionRequested) throw new ApiException(409, "Agent deletion is in progress.");
            if (existing != null) return false;
            transaction.put(Entity.newBuilder(key).set("agent_conversation_json",
                    StringValue.newBuilder(write(List.of(entry))).setExcludeFromIndexes(true).build()).build());
            transaction.commit();
            return true;
        } finally { if (transaction.isActive()) transaction.rollback(); }
    }
    public void delete(String tenant, String id) {
        var rows = db().run(Query.newKeyQueryBuilder().setKind("Conversation")
                .setFilter(StructuredQuery.PropertyFilter.hasAncestor(agentKey(tenant, id))).build());
        var batch = new ArrayList<Key>();
        while (rows.hasNext()) {
            batch.add(rows.next());
            if (batch.size() == 400) { db().delete(batch.toArray(Key[]::new)); batch.clear(); }
        }
        if (!batch.isEmpty()) db().delete(batch.toArray(Key[]::new));
        db().delete(agentKey(tenant, id));
    }
}
