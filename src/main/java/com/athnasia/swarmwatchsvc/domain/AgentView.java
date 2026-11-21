package com.athnasia.swarmwatchsvc.domain;

import java.util.List;

public record AgentView(String agent_Id, String user_Id, String agent_Name, double agent_Sentiment,
        String inkbox_email, String phone_number, List<String> agent_responses, String company,
        String goal, String status, String created_at, String last_checked_at,
        String assessment_reason, String last_error) {
    public static AgentView of(AgentState state, List<ConversationEntry> history) {
        return new AgentView(state.id, state.tenantId, state.name, state.sentiment, state.email,
                state.phone, history.stream().filter(e -> e.role().equals("assistant"))
                        .map(ConversationEntry::content).toList(), state.company, state.goal,
                state.status, state.createdAt, state.lastCheckedAt, state.assessmentReason, state.lastError);
    }
}
