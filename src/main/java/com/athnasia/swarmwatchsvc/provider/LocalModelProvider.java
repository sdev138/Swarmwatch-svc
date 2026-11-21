package com.athnasia.swarmwatchsvc.provider;

import com.athnasia.swarmwatchsvc.domain.*;
import java.util.List;
import java.util.UUID;

public class LocalModelProvider implements ModelProvider {
    public Reply reply(AgentState agent, List<ConversationEntry> history, String prompt, List<AgentView> peers) {
        return new Reply("simulated-" + UUID.randomUUID(), "SIMULATED LOCAL RESPONSE — " + agent.name
                + " is reviewing “" + agent.goal + "” under the shared mission “" + agent.companyMission
                + "”. Received: “" + prompt + "”. No provider action was performed.", List.of());
    }
    public Reply followup(AgentState agent, String previousResponseId, List<ToolResult> results, List<AgentView> peers) {
        return new Reply("simulated-" + UUID.randomUUID(), "SIMULATED LOCAL RESPONSE — fixture actions recorded; no real call was placed.", List.of());
    }
    public Assessment assess(AgentState agent, List<ConversationEntry> history) {
        return new Assessment(0.76, "SIMULATED LOCAL ASSESSMENT — deterministic fixture; no live model evaluated this agent.");
    }
}
