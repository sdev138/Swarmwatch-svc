package com.athnasia.swarmwatchsvc.provider;

import com.athnasia.swarmwatchsvc.domain.*;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;

public interface ModelProvider {
    record ToolCall(String callId, String name, Map<String, Object> arguments) {}
    record ToolResult(String callId, String output, String nativeType, @JsonIgnore Map<String, Object> nativeOutput) {
        public ToolResult(String callId, String output) { this(callId, output, null, null); }
        public ToolResult(String callId, String output, Map<String, Object> nativeOutput) {
            this(callId, output, nativeOutput == null ? null : (String) nativeOutput.get("type"), nativeOutput);
        }
    }
    record Reply(String responseId, String text, List<ToolCall> calls, List<Map<String, Object>> activity) {
        public Reply(String responseId, String text, List<ToolCall> calls) { this(responseId, text, calls, List.of()); }
    }
    record Assessment(double score, String reason) {}
    Reply reply(AgentState agent, List<ConversationEntry> history, String prompt, List<AgentView> peers);
    Reply followup(AgentState agent, String previousResponseId, List<ToolResult> results, List<AgentView> peers);
    Assessment assess(AgentState agent, List<ConversationEntry> history);
}
