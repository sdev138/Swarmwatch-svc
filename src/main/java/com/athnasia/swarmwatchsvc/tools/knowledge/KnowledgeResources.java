package com.athnasia.swarmwatchsvc.tools.knowledge;

import com.athnasia.swarmwatchsvc.domain.AgentState;

public interface KnowledgeResources {
    void ensureReady(AgentState agent, Runnable checkpoint);
    void delete(AgentState agent, Runnable checkpoint);
}
