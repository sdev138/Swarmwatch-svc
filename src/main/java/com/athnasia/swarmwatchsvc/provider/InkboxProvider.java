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
        request.put("imessage_enabled", true);
        var response = http.send("Inkbox", "POST", base + "/identities", headers(), request, false);
        try {
            String providerId = required(response, "id");
            handle = required(response, "agent_handle");
            String email = required(response, "email_address");
            var agent = new AgentState();
            agent.providerIdentityId = providerId; agent.name = name; agent.company = company; agent.goal = goal; agent.companyMission = mission;
            configure(agent);
            http.send("Inkbox", "PUT", base + "/phone/hosted-agent-config/authority-mode", headers(),
                    Map.of("agent_identity_id", providerId, "authority_mode", "contact_scoped"), false);
            http.send("Inkbox", "PUT", base + "/phone/incoming-call-action", headers(),
                    Map.of("agent_identity_id", providerId, "incoming_call_action", "hosted_agent"), false);
            return new Identity(providerId, handle, email, "");
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
    public Connection connection(AgentState agent) {
        if (agent.phone != null && !agent.phone.isBlank())
            return new Connection("dedicated_number", agent.phone, "", "");
        var router = http.send("Inkbox", "GET", base + "/imessage/triage-number?agent_identity_id=" + path(agent.providerIdentityId), headers(), null, false);
        return new Connection("shared_imessage_number", "", required(router, "number"), required(router, "connect_command"));
    }
    public Call call(AgentState agent, String to, String reason) {
        var request = new LinkedHashMap<String, Object>();
        request.put("to_number", to); request.put("mode", "hosted_agent");
        request.put("hosted_agent_authority_mode", "contact_scoped"); request.put("reason", reason);
        if (agent.phone == null || agent.phone.isBlank()) {
            request.put("origination", "shared_imessage_number"); request.put("agent_identity_id", agent.providerIdentityId);
        } else {
            request.put("origination", "dedicated_number"); request.put("from_number", agent.phone);
        }
        var response = http.send("Inkbox", "POST", base + "/phone/place-call", headers(), request, false);
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
