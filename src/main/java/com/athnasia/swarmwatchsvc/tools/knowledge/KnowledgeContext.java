package com.athnasia.swarmwatchsvc.tools.knowledge;

import com.athnasia.swarmwatchsvc.domain.AgentState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class KnowledgeContext {
    private KnowledgeContext() {}
    static String seed(AgentState agent) {
        return "Agent: " + agent.name + "\nCompany: " + agent.company + "\nShared company mission: "
                + agent.companyMission + "\nOwner-directed agent goal: " + agent.goal + "\n";
    }
    static String hash(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) { throw new IllegalStateException("Cannot derive knowledge context version.", failure); }
    }
}
