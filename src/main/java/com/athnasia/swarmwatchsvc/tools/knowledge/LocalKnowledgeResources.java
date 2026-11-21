package com.athnasia.swarmwatchsvc.tools.knowledge;

import com.athnasia.swarmwatchsvc.domain.AgentState;

public class LocalKnowledgeResources implements KnowledgeResources {
    public void ensureReady(AgentState agent, Runnable checkpoint) {
        String hash = KnowledgeContext.hash(KnowledgeContext.seed(agent));
        if (hash.equals(agent.knowledgeContextHash) && agent.vectorStoreId != null && agent.knowledgeFileId != null) return;
        agent.vectorStoreId = "local-simulated-store-" + agent.id;
        agent.knowledgeFileId = "local-simulated-file-" + agent.id;
        agent.knowledgeContextHash = hash;
        checkpoint.run();
    }
    public void delete(AgentState agent, Runnable checkpoint) {
        agent.vectorStoreId = null; agent.knowledgeFileId = null; agent.knowledgeContextHash = null;
        agent.pendingKnowledgeVectorStoreId = null; agent.pendingKnowledgeFileId = null;
        checkpoint.run();
    }
}
