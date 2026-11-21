package com.athnasia.swarmwatchsvc.repository;

import com.athnasia.swarmwatchsvc.domain.*;
import java.util.List;
import java.util.Optional;

public interface SwarmRepository {
    Optional<UserSession> user(String subject);
    UserSession bind(String subject, UserSession proposed);
    String mission(String tenantId);
    void mission(String tenantId, String mission);
    List<AgentState> agents(String tenantId);
    List<AgentState> allAgents();
    Optional<AgentState> agent(String tenantId, String agentId);
    void save(AgentState agent);
    List<ConversationEntry> conversation(String tenantId, String agentId);
    boolean append(String tenantId, String agentId, ConversationEntry entry);
    void delete(String tenantId, String agentId);
}
