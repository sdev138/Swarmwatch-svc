package com.athnasia.swarmwatchsvc.provider;

import com.athnasia.swarmwatchsvc.domain.AgentState;
import java.util.List;
import java.util.Map;

public interface PhoneProvider {
    record Identity(String id, String handle, String email, String phone) {}
    record Call(String id, String status) {}
    record Transcript(String id, String callId, long seq, String createdAt, String party,
            String text, Map<String, Object> raw) {}
    Identity create(String id, String name, String company, String goal, String mission);
    void configure(AgentState agent);
    void delete(String handle);
    Call call(AgentState agent, String toNumber, String reason);
    List<Transcript> transcripts(AgentState agent);
}
