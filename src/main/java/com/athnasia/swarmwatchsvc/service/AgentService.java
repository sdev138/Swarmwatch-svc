package com.athnasia.swarmwatchsvc.service;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.athnasia.swarmwatchsvc.domain.*;
import com.athnasia.swarmwatchsvc.provider.PhoneProvider;
import com.athnasia.swarmwatchsvc.provider.ModelProvider;
import com.athnasia.swarmwatchsvc.provider.ProviderException;
import com.athnasia.swarmwatchsvc.provider.VoiceInstructions;
import com.athnasia.swarmwatchsvc.provider.ProviderAudit;
import com.athnasia.swarmwatchsvc.tools.ComputerExecutor;
import com.athnasia.swarmwatchsvc.tools.ComputerUseException;
import com.athnasia.swarmwatchsvc.tools.knowledge.KnowledgeResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.athnasia.swarmwatchsvc.repository.SwarmRepository;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;

@Service
public class AgentService {
    public static final String CHECK_IN_PROMPT = "How are you doing and what are you working on?";
    private static final Pattern PHONE_TOKEN = Pattern.compile("(?<![\\p{L}\\p{N}+])\\+[1-9][0-9]{7,14}(?![\\p{L}\\p{N}])");
    private final SwarmRepository repository;
    private final PhoneProvider phones;
    private final ApplicationEventPublisher events;
    private final ModelProvider model;
    private final ObjectMapper json;
    private KnowledgeResources knowledge;
    private ComputerExecutor computer;
    private final Map<String, Object> tenantLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> agentLocks = new ConcurrentHashMap<>();

    public AgentService(SwarmRepository repository, PhoneProvider phones, ModelProvider model, ObjectMapper json, ApplicationEventPublisher events) {
        this.repository = repository;
        this.phones = phones;
        this.events = events;
        this.model = model;
        this.json = json;
    }
    @Autowired public void knowledgeResources(KnowledgeResources resources) { this.knowledge = resources; }
    @Autowired public void computerExecutor(ComputerExecutor executor) { this.computer = executor; }
    private Object lock(String tenant, String id) { return agentLocks.computeIfAbsent(tenant + "/" + id, ignored -> new Object()); }
    private AgentState owned(String tenant, String id) {
        return repository.agent(tenant, id).orElseThrow(() -> new ApiException(404, "Agent not found."));
    }
    private AgentState active(String tenant, String id) {
        var agent = owned(tenant, id);
        if (agent.deletionRequested) throw new ApiException(409, "Agent deletion needs to finish. Retry deleting this agent.");
        return agent;
    }
    public AgentView requireOwned(String tenant, String id) { return view(owned(tenant, id)); }
    private AgentView view(AgentState state) { return AgentView.of(state, repository.conversation(state.tenantId, state.id)); }
    public List<AgentView> list(String tenant) { return repository.agents(tenant).stream().map(this::view).toList(); }
    public String mission(String tenant) { return repository.mission(tenant); }
    public void mission(String tenant, String mission) {
        synchronized (tenantLocks.computeIfAbsent(tenant, ignored -> new Object())) {
        for (var agent : repository.agents(tenant)) VoiceInstructions.render(agent.name, agent.company, agent.goal, mission.trim());
        repository.mission(tenant, mission.trim());
        for (var item : repository.agents(tenant)) synchronized (lock(tenant, item.id)) {
            var agent = owned(tenant, item.id);
            if (agent.deletionRequested || agent.providerIdentityId == null) continue;
            agent.companyMission = mission.trim();
            try { phones.configure(agent); agent.voiceMission = agent.companyMission; }
            catch (RuntimeException failure) {
                agent.status = "error";
                agent.lastError = "Shared mission saved, but phone instructions could not update. The next turn will retry.";
            }
            repository.save(agent);
        }
        }
    }
    public AgentView create(String tenant, String name, String company, String goal) {
        synchronized (tenantLocks.computeIfAbsent(tenant, ignored -> new Object())) {
            String mission = repository.mission(tenant);
            if (mission.isBlank()) throw new ApiException(409, "Set your shared company mission before creating agents.");
            if (repository.agents(tenant).size() >= 3) throw new ApiException(409, "You can manage at most three agents. Delete an agent to create another.");
            var agent = new AgentState();
            agent.id = UUID.randomUUID().toString();
            agent.tenantId = tenant;
            agent.name = name.trim();
            agent.company = company == null || company.isBlank() ? "Independent agent" : company.trim();
            agent.goal = goal.trim();
            agent.companyMission = mission;
            agent.createdAt = Instant.now().toString();
            agent.providerHandle = "sw-" + agent.id;
            agent.email = "";
            agent.phone = "";
            agent.status = "working";
            VoiceInstructions.render(agent.name, agent.company, agent.goal, mission);
            synchronized (lock(tenant, agent.id)) {
            repository.save(agent);
            String stage = "Inkbox identity and phone setup";
            try {
                var identity = phones.create(agent.id, agent.name, agent.company, agent.goal, mission);
                agent.providerIdentityId = identity.id();
                agent.providerHandle = identity.handle();
                agent.email = identity.email();
                agent.phone = identity.phone();
                agent.voiceMission = mission;
                repository.save(agent);
                stage = "OpenAI file-search setup";
                if (knowledge != null) knowledge.ensureReady(agent, () -> repository.save(agent));
                agent.status = "active";
                repository.save(agent);
            } catch (RuntimeException failure) {
                String reason = "Agent provisioning failed during " + stage + ": " + visible(failure);
                LoggerFactory.getLogger(AgentService.class).warn("Agent provisioning failed; stage={}; failureType={}; reason={}",
                        stage, failure.getClass().getSimpleName(), visible(failure));
                try {
                    if (knowledge != null) knowledge.delete(agent, () -> repository.save(agent));
                    phones.delete(agent.providerHandle);
                    repository.delete(tenant, agent.id);
                } catch (RuntimeException cleanupFailure) {
                    agent.deletionRequested = true;
                    agent.status = "error";
                    agent.lastError = reason + " Cleanup needs retry. Delete this agent to retry cleanup.";
                    LoggerFactory.getLogger(AgentService.class).warn("Provisioning cleanup failed; failureType={}; reason={}",
                            cleanupFailure.getClass().getSimpleName(), visible(cleanupFailure));
                    repository.save(agent);
                }
                throw new ApiException(503, reason + " Refresh agents; any incomplete identity remains available for cleanup.");
            }
            events.publishEvent(new AgentCreatedEvent(tenant, agent.id, agent.name, agent.company, agent.goal, agent.email, agent.phone));
            return view(agent);
            }
        }
    }
    public void delete(String tenant, String id) {
        synchronized (lock(tenant, id)) {
            var agent = owned(tenant, id);
            agent.deletionRequested = true;
            agent.status = "deleting";
            repository.save(agent);
            try {
                events.publishEvent(new AgentDeletingEvent(tenant, id));
                if (knowledge != null) knowledge.delete(agent, () -> repository.save(agent));
                if (computer != null) computer.release(tenant, id);
                phones.delete(agent.providerHandle);
                repository.delete(tenant, id);
            } catch (RuntimeException failure) {
                agent.status = "error";
                agent.lastError = "Deletion did not finish. The identity and cleanup record are retained; retry deletion.";
                repository.save(agent);
                throw new ApiException(503, agent.lastError);
            }
            events.publishEvent(new AgentDeletedEvent(tenant, id));
        }
    }
    public List<ConversationEntry> conversation(String tenant, String id) {
        owned(tenant, id);
        return repository.conversation(tenant, id);
    }
    public ConversationEntry message(String tenant, String id, String content) {
        active(tenant, id);
        var entry = ConversationEntry.message("user", content.trim(), "dashboard");
        repository.append(tenant, id, entry);
        events.publishEvent(new AgentWorkRequestedEvent(tenant, id));
        return entry;
    }
    public List<AgentState> allAgents() { return repository.allAgents(); }
    public void requestTurn(String tenant, String id) { active(tenant, id); events.publishEvent(new AgentWorkRequestedEvent(tenant, id)); }
    public void runTurn(String tenant, String id) {
        synchronized (lock(tenant, id)) {
            var optional = repository.agent(tenant, id);
            if (optional.isEmpty() || optional.get().deletionRequested) return;
            var agent = optional.get();
            if (agent.providerIdentityId == null) {
                agent.status = "error"; agent.lastError = "Provisioning was interrupted. Delete this agent to retry provider cleanup."; repository.save(agent); return;
            }
            double priorScore = agent.sentiment;
            agent.status = "working";
            agent.lastCheckedAt = Instant.now().toString();
            repository.save(agent);
            try {
                agent.companyMission = repository.mission(tenant);
                if (knowledge != null) knowledge.ensureReady(agent, () -> repository.save(agent));
                if (!Objects.equals(agent.voiceMission, agent.companyMission)) {
                    phones.configure(agent); agent.voiceMission = agent.companyMission; repository.save(agent);
                }
                captureTranscripts(agent);
                var history = repository.conversation(tenant, id);
                if (agent.turnPrompt == null) {
                    var pending = pending(history);
                    agent.turnPrompt = pending.map(ConversationEntry::content).orElse(CHECK_IN_PROMPT);
                    agent.turnChannel = pending.map(ConversationEntry::channel).orElse("check_in");
                    agent.turnInputIds = pending.map(entry -> List.of(entry.id())).orElse(List.of());
                    if (pending.isEmpty()) repository.append(tenant, id, ConversationEntry.message("system", CHECK_IN_PROMPT, "check_in"));
                    repository.save(agent);
                }
                var peers = repository.agents(tenant).stream().map(peer -> AgentView.of(peer, List.of())).toList();
                ModelProvider.Reply reply;
                if (agent.continuationResponseId == null) {
                    var context = history.stream().filter(entry -> !agent.turnInputIds.contains(entry.id())).toList();
                    reply = model.reply(agent, context, agent.turnPrompt, peers);
                } else {
                    finishTools(agent);
                    reply = model.followup(agent, agent.continuationResponseId, List.copyOf(agent.pendingToolResults), peers);
                }
                while (true) {
                    appendReply(agent, reply);
                    if (reply.calls().isEmpty()) break;
                    if (++agent.toolRounds > 20) {
                        String reason = "Agent reached the per-turn action limit. This turn ended without replaying executed actions; send a new instruction to continue.";
                        repository.append(tenant, id, new ConversationEntry(UUID.randomUUID().toString(), "system", reason, agent.turnChannel,
                                Instant.now().toString(), null, null, null, Map.of("consumed_input_ids", List.copyOf(agent.turnInputIds), "turn_failed", true), Map.of()));
                        clearTurn(agent); repository.save(agent);
                        throw new ProviderException(reason);
                    }
                    agent.continuationResponseId = reply.responseId();
                    agent.pendingToolCalls = new ArrayList<>(reply.calls());
                    agent.pendingToolResults = new ArrayList<>();
                    repository.save(agent);
                    finishTools(agent);
                    reply = model.followup(agent, agent.continuationResponseId, List.copyOf(agent.pendingToolResults), peers);
                }
                if (!agent.turnInputIds.isEmpty()) repository.append(tenant, id, new ConversationEntry(
                        "processed:" + agent.turnInputIds.getFirst(), "system", "Queued input processed.", agent.turnChannel,
                        Instant.now().toString(), null, null, null, Map.of("consumed_input_ids", List.copyOf(agent.turnInputIds)), Map.of()));
                boolean toolFailed = agent.turnToolFailed;
                clearTurn(agent);
                repository.save(agent);
                if (toolFailed) throw new ProviderException(agent.lastError == null ? "An agent action failed. Previous sentiment is retained." : agent.lastError);
                var assessment = model.assess(agent, repository.conversation(tenant, id));
                if (!Double.isFinite(assessment.score()) || assessment.score() < 0 || assessment.score() > 1)
                    throw new ProviderException("Assessment score was invalid. Previous sentiment is retained.");
                agent.sentiment = BigDecimal.valueOf(assessment.score()).setScale(2, RoundingMode.HALF_UP).doubleValue();
                agent.assessmentReason = assessment.reason();
                repository.append(tenant, id, new ConversationEntry(UUID.randomUUID().toString(), "assessment", assessment.reason(), "check_in",
                        Instant.now().toString(), agent.sentiment, null, null, Map.of("source", "decisions"), Map.of()));
                agent.status = "active"; agent.lastError = null;
                repository.save(agent);
                if (pending(repository.conversation(tenant, id)).isPresent()) events.publishEvent(new AgentWorkRequestedEvent(tenant, id));
            } catch (RuntimeException failure) {
                agent.sentiment = priorScore; agent.status = "error"; agent.lastError = visible(failure);
                repository.save(agent);
            }
        }
    }
    private void clearTurn(AgentState agent) {
        agent.turnPrompt = null; agent.turnChannel = null; agent.turnInputIds = new ArrayList<>();
        agent.continuationResponseId = null; agent.pendingToolCalls = new ArrayList<>(); agent.pendingToolResults = new ArrayList<>();
        agent.toolRounds = 0; agent.turnToolFailed = false;
    }
    private Optional<ConversationEntry> pending(List<ConversationEntry> history) {
        var consumed = new HashSet<String>();
        for (var entry : history) if (entry.provenance().get("consumed_input_ids") instanceof List<?> ids)
            ids.forEach(value -> consumed.add(value.toString()));
        return history.stream().filter(entry -> entry.role().equals("user") && (entry.channel().equals("dashboard") || entry.channel().equals("protocol")) && !consumed.contains(entry.id())).findFirst();
    }
    private void captureTranscripts(AgentState agent) {
        for (var transcript : phones.transcripts(agent)) {
            var provenance = new LinkedHashMap<String, Object>(transcript.raw());
            provenance.put("source", "inkbox"); provenance.put("provider_created_at", transcript.createdAt());
            repository.append(agent.tenantId, agent.id, new ConversationEntry("phone:" + transcript.callId() + ":" + transcript.seq(),
                    transcript.party().equals("local") ? "assistant" : "user", transcript.text(), "phone", Instant.now().toString(), null,
                    transcript.callId(), transcript.seq(), provenance, Map.of()));
        }
    }
    private void appendReply(AgentState agent, ModelProvider.Reply reply) {
        int index = 0;
        for (var raw : reply.activity()) {
            var item = ProviderAudit.sanitize(raw);
            String type = Objects.toString(item.get("type"), "activity");
            String detail = encode(item);
            if (detail.length() > 800) detail = detail.substring(0, 800) + "…";
            repository.append(agent.tenantId, agent.id, new ConversationEntry("activity:" + reply.responseId() + ":" + index++, "system",
                    "Tool " + type + ": " + detail, agent.turnChannel, Instant.now().toString(), null, null, null,
                    Map.of("source", "openai", "response_id", reply.responseId(), "item", item), Map.of()));
        }
        if (reply.text().isBlank()) return;
        if (repository.append(agent.tenantId, agent.id, new ConversationEntry("response:" + reply.responseId(), "assistant", reply.text(),
                agent.turnChannel, Instant.now().toString(), null, null, null, Map.of("response_id", reply.responseId()), Map.of()))) {
            agent.addResponse(reply.text()); repository.save(agent);
        }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException("Cannot encode agent action."); }
    }
    private String visible(RuntimeException failure) {
        return failure instanceof ProviderException || failure instanceof ApiException || failure instanceof ComputerUseException ? failure.getMessage() : "Agent work could not complete. Previous sentiment and conversation are retained; retry the check-in.";
    }
    private void finishTools(AgentState agent) {
        for (var tool : agent.pendingToolCalls) {
            if (agent.pendingToolResults.stream().anyMatch(result -> result.callId().equals(tool.callId()))) continue;
            var history = repository.conversation(agent.tenantId, agent.id);
            String startId = "tool-start:" + tool.callId();
            var durableResult = history.stream().filter(entry -> entry.id().equals("tool-result:" + tool.callId())).findFirst();
            if (durableResult.isPresent()) {
                var metadata = durableResult.get().provenance();
                agent.pendingToolResults.add(new ModelProvider.ToolResult(tool.callId(), Objects.toString(metadata.get("output"), "Recorded action result"),
                        metadata.get("native_type") instanceof String value ? value : null, null));
                if (Boolean.TRUE.equals(metadata.get("failed"))) agent.turnToolFailed = true;
                continue;
            }
            boolean started = history.stream().anyMatch(entry -> entry.id().equals(startId));
            if (!started) repository.append(agent.tenantId, agent.id, new ConversationEntry(startId, "system", "Tool " + tool.name() + " requested: " + encode(tool.arguments()),
                    agent.turnChannel, Instant.now().toString(), null, null, null, Map.of("tool_call_id", tool.callId(), "arguments", ProviderAudit.sanitize(tool.arguments())), Map.of()));
            ModelProvider.ToolResult result;
            if (tool.name().equals("computer")) {
                if (computer == null) throw new ProviderException("Computer executor is unavailable.");
                ComputerExecutor.Result observation;
                if (started) {
                    agent.turnToolFailed = true; agent.lastError = "Interrupted computer actions were not repeated; a fresh screenshot was captured for review.";
                    observation = screenshot(agent);
                } else {
                    try { observation = computer.execute(agent.tenantId, agent.id, tool.arguments()); }
                    catch (RuntimeException failure) {
                        agent.turnToolFailed = true; agent.lastError = visible(failure);
                        observation = screenshot(agent);
                    }
                }
                String output = encode(agent.turnToolFailed ? Map.of("current_url", observation.currentUrl(), "error", agent.lastError)
                        : Map.of("current_url", observation.currentUrl(), "status", "Computer actions executed; screenshot returned to Luna."));
                result = nativeResult(tool.callId(), output, observation);
            } else {
                String output;
                if (started) {
                    agent.turnToolFailed = true; agent.lastError = "An interrupted action has an unknown outcome and was not repeated. Check provider records before another attempt.";
                    output = encode(Map.of("error", agent.lastError));
                } else {
                    try { output = encode(executeTool(agent, tool, history)); }
                    catch (RuntimeException failure) { agent.turnToolFailed = true; agent.lastError = visible(failure); output = encode(Map.of("error", agent.lastError)); }
                }
                result = new ModelProvider.ToolResult(tool.callId(), output);
            }
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("tool_call_id", tool.callId()); metadata.put("output", result.output()); metadata.put("failed", agent.turnToolFailed);
            if (result.nativeType() != null) metadata.put("native_type", result.nativeType());
            repository.append(agent.tenantId, agent.id, new ConversationEntry("tool-result:" + tool.callId(), "system", "Tool " + tool.name() + " result: " + result.output(),
                    agent.turnChannel, Instant.now().toString(), null, null, null, metadata, Map.of()));
            agent.pendingToolResults.add(result);
            repository.save(agent);
        }
        for (int i = 0; i < agent.pendingToolResults.size(); i++) {
            var result = agent.pendingToolResults.get(i);
            if ("computer_call_output".equals(result.nativeType()) && result.nativeOutput() == null)
                agent.pendingToolResults.set(i, nativeResult(result.callId(), result.output(), screenshot(agent)));
        }
    }
    private ComputerExecutor.Result screenshot(AgentState agent) {
        if (computer == null) throw new ProviderException("Computer executor is unavailable.");
        return computer.execute(agent.tenantId, agent.id, Map.of("actions", List.of(Map.of("type", "screenshot"))));
    }
    private ModelProvider.ToolResult nativeResult(String callId, String output, ComputerExecutor.Result observation) {
        return new ModelProvider.ToolResult(callId, output, Map.of("type", "computer_call_output", "call_id", callId,
                "output", Map.of("type", "computer_screenshot", "image_url", observation.screenshotDataUrl(), "detail", "original")));
    }
    private Object executeTool(AgentState agent, ModelProvider.ToolCall tool, List<ConversationEntry> history) {
        if (tool.name().equals("place_call")) {
            String number = argument(tool, "to_number");
            boolean authorized = ownerSuppliedNumber(agent.goal, number) || history.stream().anyMatch(entry -> entry.role().equals("user") && entry.channel().equals("dashboard") && ownerSuppliedNumber(entry.content(), number));
            if (!authorized) throw new ApiException(400, "Phone destination was not supplied by the owner in the goal or a dashboard message.");
            return call(agent.tenantId, agent.id, number, argument(tool, "reason"));
        }
        if (tool.name().equals("collaborate")) {
            collaborate(agent.tenantId, agent.id, argument(tool, "target_agent_id"), argument(tool, "message"));
            return Map.of("status", "queued", "message", "Same-tenant agent message queued.");
        }
        throw new ApiException(400, "Unsupported agent action.");
    }
    private boolean ownerSuppliedNumber(String text, String number) { return PHONE_TOKEN.matcher(text).results().anyMatch(match -> match.group().equals(number)); }
    private String argument(ModelProvider.ToolCall tool, String key) {
        if (!(tool.arguments().get(key) instanceof String value) || value.isBlank()) throw new ApiException(400, "Agent action requires " + key + ".");
        return value;
    }
    public PhoneProvider.Call call(String tenant, String id, String to, String reason) {
        synchronized (lock(tenant, id)) {
            var agent = active(tenant, id);
            if (to == null || !to.matches("\\+[1-9][0-9]{7,14}")) throw new ApiException(400, "Use an E.164 phone number, for example +14155550100.");
            if (reason == null || reason.isBlank() || reason.length() > 2000) throw new ApiException(400, "Provide a call reason between 1 and 2000 characters.");
            var result = phones.call(agent, to, reason.trim());
            repository.append(tenant, id, new ConversationEntry("call:" + result.id(), "system", "Outbound call " + result.status() + " to " + to + ". Reason: " + reason,
                    "phone", Instant.now().toString(), null, result.id(), null, Map.of("direction", "outbound", "to_number", to, "status", result.status()), Map.of()));
            return result;
        }
    }
    public void collaborate(String tenant, String source, String target, String content) {
        synchronized (lock(tenant, source)) {
        active(tenant, source); active(tenant, target);
        if (source.equals(target)) throw new ApiException(400, "Choose a different agent for collaboration.");
        if (content == null || content.isBlank() || content.length() > 16000) throw new ApiException(400, "Agent message must contain 1 to 16000 characters.");
        String id = UUID.randomUUID().toString();
        var envelope = Map.<String, Object>of("protocol_version", "1.0", "id", id,
                "message", Map.of("author", Map.of("type", "agent"), "text", content), "source_agent_id", source, "target_agent_id", target);
        repository.append(tenant, target, new ConversationEntry(id, "user", content, "protocol", Instant.now().toString(), null, null, null,
                Map.of("direction", "inbound", "source_agent_id", source), envelope));
        repository.append(tenant, source, new ConversationEntry("sent:" + id, "system", "Sent to agent " + target + ": " + content, "protocol", Instant.now().toString(), null, null, null,
                Map.of("direction", "outbound", "target_agent_id", target), envelope));
        events.publishEvent(new AgentWorkRequestedEvent(tenant, target));
        }
    }
}
