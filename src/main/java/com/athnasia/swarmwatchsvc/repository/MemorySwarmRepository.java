package com.athnasia.swarmwatchsvc.repository;

import com.athnasia.swarmwatchsvc.domain.*;
import com.athnasia.swarmwatchsvc.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class MemorySwarmRepository implements SwarmRepository {
    private final ObjectMapper json;
    private final Map<String, UserSession> users = new HashMap<>();
    private final Map<String, String> missions = new HashMap<>();
    private final Map<String, AgentState> agents = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, ConversationEntry>> conversations = new HashMap<>();

    public MemorySwarmRepository(ObjectMapper json) { this.json = json; }
    private String key(String tenant, String id) { return tenant + "/" + id; }
    private AgentState copy(AgentState state) { return json.convertValue(state, AgentState.class); }
    public synchronized Optional<UserSession> user(String subject) { return Optional.ofNullable(users.get(subject)); }
    public synchronized UserSession bind(String subject, UserSession proposed) {
        if (users.containsKey(subject)) return users.get(subject);
        if (users.values().stream().anyMatch(user -> user.user_id().equalsIgnoreCase(proposed.user_id())))
            throw new ApiException(409, "This signup UUID is already bound. Generate a new UUID and retry.");
        users.put(subject, proposed); return proposed;
    }
    public synchronized String mission(String tenant) { return missions.getOrDefault(tenant, ""); }
    public synchronized void mission(String tenant, String mission) { missions.put(tenant, mission); }
    public synchronized List<AgentState> agents(String tenant) { return agents.values().stream().filter(a -> a.tenantId.equals(tenant)).map(this::copy).toList(); }
    public synchronized List<AgentState> allAgents() { return agents.values().stream().map(this::copy).toList(); }
    public synchronized Optional<AgentState> agent(String tenant, String id) { return Optional.ofNullable(agents.get(key(tenant, id))).map(this::copy); }
    public synchronized void save(AgentState agent) { agents.put(key(agent.tenantId, agent.id), copy(agent)); }
    public synchronized List<ConversationEntry> conversation(String tenant, String id) { return List.copyOf(conversations.getOrDefault(key(tenant, id), new LinkedHashMap<>()).values()); }
    public synchronized boolean append(String tenant, String id, ConversationEntry entry) {
        AgentState agent = agents.get(key(tenant, id));
        if (agent == null) throw new ApiException(404, "Agent not found.");
        if (agent.deletionRequested) throw new ApiException(409, "Agent deletion is in progress.");
        return conversations.computeIfAbsent(key(tenant, id), ignored -> new LinkedHashMap<>()).putIfAbsent(entry.id(), entry) == null;
    }
    public synchronized void delete(String tenant, String id) { agents.remove(key(tenant, id)); conversations.remove(key(tenant, id)); }
}
