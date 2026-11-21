package com.athnasia.swarmwatchsvc.api;

import com.athnasia.swarmwatchsvc.auth.AuthService;
import com.athnasia.swarmwatchsvc.domain.*;
import com.athnasia.swarmwatchsvc.service.AgentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final AuthService auth;
    private final AgentService agents;
    private final ObjectMapper json;
    private final Environment environment;
    private final String clientId;
    private final long interval;
    public ApiController(AuthService auth, AgentService agents, ObjectMapper json, Environment environment,
            @Value("${swarmwatch.google-client-id}") String clientId,
            @Value("${swarmwatch.check-interval-seconds}") long interval) {
        this.auth = auth; this.agents = agents; this.json = json; this.environment = environment; this.clientId = clientId; this.interval = interval;
    }
    public record Signup(String user_id) {}
    public record CreateAgent(@NotBlank @Size(max = 255) String agent_Name,
            @Size(max = 255) String company, @NotBlank @Size(max = 4000) String goal) {}
    public record Message(@NotBlank @Size(max = 16000) String message) {}
    public record Mission(@NotBlank @Size(max = 8000) String company_mission) {}
    public record Call(@NotBlank String to_number, @NotBlank @Size(max = 2000) String reason) {}
    public record Collaboration(@NotBlank String target_agent_id, @NotBlank @Size(max = 16000) String message) {}
    private String tenant(String authorization) { return auth.requireSession(authorization).user_id(); }

    @GetMapping("/config") public Map<String, Object> config() {
        return Map.of("local_mode", environment.matchesProfiles("local"), "google_client_id", clientId,
                "agent_model", "gpt-6-luna", "decision_model", "gpt-6-luna", "check_interval_seconds", interval);
    }
    @PostMapping("/session") public UserSession session(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Signup body) {
        return auth.session(authorization, body.user_id());
    }
    @GetMapping("/mission") public Mission mission(@RequestHeader(value = "Authorization", required = false) String authorization) { return new Mission(agents.mission(tenant(authorization))); }
    @PutMapping("/mission") public Mission mission(@RequestHeader(value = "Authorization", required = false) String authorization, @Valid @RequestBody Mission mission) {
        String tenant = tenant(authorization); agents.mission(tenant, mission.company_mission()); return new Mission(agents.mission(tenant));
    }
    @GetMapping("/agents") public List<AgentView> list(@RequestHeader(value = "Authorization", required = false) String authorization) { return agents.list(tenant(authorization)); }
    @PostMapping("/agents") public ResponseEntity<AgentView> create(@RequestHeader(value = "Authorization", required = false) String authorization, @Valid @RequestBody CreateAgent body) {
        return ResponseEntity.status(201).body(agents.create(tenant(authorization), body.agent_Name(), body.company(), body.goal()));
    }
    @GetMapping("/agents/{id}") public AgentView get(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) { return agents.requireOwned(tenant(authorization), id); }
    @GetMapping("/agents/{id}/phone-connection") public com.athnasia.swarmwatchsvc.provider.PhoneProvider.Connection phoneConnection(
            @RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return agents.phoneConnection(tenant(authorization), id);
    }
    @DeleteMapping("/agents/{id}") public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        agents.delete(tenant(authorization), id); return ResponseEntity.noContent().build();
    }
    @GetMapping("/agents/{id}/conversation") public Map<String, String> conversation(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) throws JsonProcessingException {
        return Map.of("agent_conversation_json", json.writeValueAsString(agents.conversation(tenant(authorization), id)));
    }
    @PostMapping("/agents/{id}/messages") public ResponseEntity<ConversationEntry> message(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id, @Valid @RequestBody Message body) {
        return ResponseEntity.accepted().body(agents.message(tenant(authorization), id, body.message()));
    }
    @PostMapping("/agents/{id}/check-in") public ResponseEntity<Map<String, String>> checkIn(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        agents.requestTurn(tenant(authorization), id); return ResponseEntity.accepted().body(Map.of("message", "Check-in queued."));
    }
    @PostMapping("/agents/{id}/calls") public ResponseEntity<com.athnasia.swarmwatchsvc.provider.PhoneProvider.Call> call(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id, @Valid @RequestBody Call body) {
        return ResponseEntity.accepted().body(agents.call(tenant(authorization), id, body.to_number(), body.reason()));
    }
    @PostMapping("/agents/{id}/collaborate") public ResponseEntity<Map<String, String>> collaborate(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id, @Valid @RequestBody Collaboration body) {
        agents.collaborate(tenant(authorization), id, body.target_agent_id(), body.message()); return ResponseEntity.accepted().body(Map.of("message", "Agent collaboration queued."));
    }
}
