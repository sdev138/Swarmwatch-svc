package com.athnasia.swarmwatchsvc.provider;

import com.athnasia.swarmwatchsvc.domain.AgentState;
import java.util.List;
import java.util.UUID;

public class LocalPhoneProvider implements PhoneProvider {
    public Identity create(String id, String name, String company, String goal, String mission) {
        return new Identity("simulated-" + id, "local-" + id, "simulated-" + id.substring(0, 8) + "@example.invalid", "SIMULATED · no real phone");
    }
    public void configure(AgentState agent) {}
    public void delete(String handle) {}
    public Connection connection(AgentState agent) { return new Connection("local", "", "", ""); }
    public Call call(AgentState agent, String toNumber, String reason) { return new Call("simulated-" + UUID.randomUUID(), "simulated_no_call_placed"); }
    public List<Transcript> transcripts(AgentState agent) { return List.of(); }
}
