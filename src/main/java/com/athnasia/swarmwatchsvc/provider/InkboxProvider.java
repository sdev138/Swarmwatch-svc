package com.athnasia.swarmwatchsvc.provider;

import com.athnasia.swarmwatchsvc.domain.AgentState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

public class InkboxProvider implements PhoneProvider {
    private final ProviderHttp http;
    private final ObjectMapper json;
    private final Secrets secrets;
    private final String base;
    public InkboxProvider(HttpClient client, ObjectMapper json, Secrets secrets, String base) {
        this.http = new ProviderHttp(client, json); this.json = json; this.secrets = secrets; this.base = base;
    }
    private Map<String, String> headers() { return Map.of("X-API-Key", secrets.inkboxKey()); }
    private String path(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    public Identity create(String id, String name, String company, String goal, String mission) {
        VoiceInstructions.render(name, company, goal, mission);
        String handle = "sw-" + id;
        var request = new LinkedHashMap<String, Object>();
        request.put("agent_handle", handle); request.put("display_name", name);
        request.put("mailbox", Collections.singletonMap("sending_domain", null));
        request.put("phone_number", Map.of("type", "local", "incoming_call_action", "hosted_agent"));
        var response = http.send("Inkbox", "POST", base + "/identities", headers(), request, false);
        try {
            String providerId = required(response, "id");
            handle = required(response, "agent_handle");
            String email = required(response, "email_address");
            String phone = required(response.path("phone_number"), "number");
            var agent = new AgentState();
            agent.providerIdentityId = providerId; agent.name = name; agent.company = company; agent.goal = goal; agent.companyMission = mission;
            configure(agent);
            http.send("Inkbox", "PUT", base + "/phone/hosted-agent-config/authority-mode", headers(),
                    Map.of("agent_identity_id", providerId, "authority_mode", "contact_scoped"), false);
            return new Identity(providerId, handle, email, phone);
        } catch (RuntimeException failure) {
            try { delete(handle); } catch (RuntimeException cleanupFailure) {
                throw new ProviderException("Inkbox voice setup failed and identity cleanup needs retry. Delete the incomplete agent.");
            }
            throw failure;
        }
    }
    public void configure(AgentState agent) {
        http.send("Inkbox", "PUT", base + "/phone/hosted-agent-config", headers(), Map.of("agent_identity_id", agent.providerIdentityId,
                "instructions", VoiceInstructions.render(agent.name, agent.company, agent.goal, agent.companyMission)), false);
    }
    private String required(JsonNode value, String key) {
        String result = value.path(key).asText();
        if (result.isBlank()) throw new ProviderException("Inkbox returned an incomplete identity. Provisioning was not completed.");
        return result;
    }
    public void delete(String handle) { http.send("Inkbox", "DELETE", base + "/identities/" + path(handle), headers(), null, true); }
    public Call call(AgentState agent, String to, String reason) {
        var response = http.send("Inkbox", "POST", base + "/phone/place-call", headers(), Map.of("origination", "dedicated_number",
                "from_number", agent.phone, "to_number", to, "mode", "hosted_agent", "hosted_agent_authority_mode", "contact_scoped", "reason", reason), false);
        return new Call(required(response, "id"), required(response, "status"));
    }
    public List<Transcript> transcripts(AgentState agent) {
        var result = new ArrayList<Transcript>();
        for (int offset = 0; ; offset += 200) {
            var calls = http.send("Inkbox", "GET", base + "/phone/calls?agent_identity_id=" + path(agent.providerIdentityId)
                    + "&limit=200&offset=" + offset, headers(), null, false);
            if (!calls.isArray()) throw new ProviderException("Inkbox returned an invalid call list.");
            for (var call : calls) {
                String callId = required(call, "id");
                var segments = http.send("Inkbox", "GET", base + "/phone/calls/" + path(callId) + "/transcripts", headers(), null, false);
                if (!segments.isArray()) throw new ProviderException("Inkbox returned invalid call transcripts.");
                for (var segment : segments) {
                    if (!segment.path("seq").canConvertToLong() || !segment.path("text").isTextual())
                        throw new ProviderException("Inkbox returned an incomplete transcript segment.");
                    result.add(new Transcript(segment.path("id").asText(callId + ":" + segment.path("seq").asLong()), callId,
                            segment.path("seq").asLong(), segment.path("created_at").asText(Instant.now().toString()),
                            segment.path("party").asText(), segment.path("text").asText(),
                            json.convertValue(segment, new TypeReference<Map<String, Object>>() {})));
                }
            }
            if (calls.size() < 200) break;
        }
        return List.copyOf(result);
    }
}
