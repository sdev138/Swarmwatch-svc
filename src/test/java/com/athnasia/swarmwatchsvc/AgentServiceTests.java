package com.athnasia.swarmwatchsvc;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.athnasia.swarmwatchsvc.domain.*;
import com.athnasia.swarmwatchsvc.provider.*;
import com.athnasia.swarmwatchsvc.repository.MemorySwarmRepository;
import com.athnasia.swarmwatchsvc.service.AgentService;
import com.athnasia.swarmwatchsvc.tools.*;
import com.athnasia.swarmwatchsvc.tools.knowledge.LocalKnowledgeResources;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class AgentServiceTests {
    final ObjectMapper json = new ObjectMapper();
    final MemorySwarmRepository repository = new MemorySwarmRepository(json);
    final FixturePhones phones = new FixturePhones();
    final FixtureModel model = new FixtureModel();
    final FixtureKnowledge knowledge = new FixtureKnowledge();
    final FixtureComputer computer = new FixtureComputer();
    final AgentService service = new AgentService(repository, phones, model, json, event -> {});
    @BeforeEach void mission() { service.knowledgeResources(knowledge); service.computerExecutor(computer); service.mission("alice", "Ship trustworthy software"); service.mission("bob", "Keep customer data private"); }
    AgentView create(String tenant, String name) { return service.create(tenant, name, "Acme", "Verify releases; call +14155550111 if the owner asks"); }

    @Test void simultaneousCreatesNeverExceedThree() throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) tasks.add(pool.submit(() -> { start.await(); try { create("alice", "Scout"); return true; } catch (ApiException error) { assertThat(error.status()).isEqualTo(409); return false; } }));
            start.countDown();
            int count = 0;
            for (var task : tasks) if (task.get(5, TimeUnit.SECONDS)) count++;
            assertThat(count).isEqualTo(3);
            assertThat(service.list("alice")).hasSize(3);
        }
    }
    @Test void deletionDuringProvisioningCannotResurrectAnAgent() throws Exception {
        phones.createEntered = new CountDownLatch(1); phones.createRelease = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creation = pool.submit(() -> create("alice", "Scout"));
            assertThat(phones.createEntered.await(2, TimeUnit.SECONDS)).isTrue();
            String id = repository.agents("alice").getFirst().id;
            var deletion = pool.submit(() -> service.delete("alice", id));
            try { assertThatThrownBy(() -> deletion.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { phones.createRelease.countDown(); }
            creation.get(2, TimeUnit.SECONDS); deletion.get(2, TimeUnit.SECONDS);
            assertThat(service.list("alice")).isEmpty();
            assertThat(phones.deletes).isEqualTo(1);
        } finally { phones.createRelease.countDown(); }
    }
    @Test void foreignTenantCannotReadMessageCallCollaborateOrDelete() {
        var alice = create("alice", "Alice"); var bob = create("bob", "Bob");
        for (Runnable action : List.<Runnable>of(
                () -> service.requireOwned("bob", alice.agent_Id()),
                () -> service.conversation("bob", alice.agent_Id()),
                () -> service.message("bob", alice.agent_Id(), "intrude"),
                () -> service.call("bob", alice.agent_Id(), "+14155550111", "intrude"),
                () -> service.collaborate("alice", alice.agent_Id(), bob.agent_Id(), "intrude"),
                () -> service.delete("bob", alice.agent_Id()))) {
            assertThatThrownBy(action::run).isInstanceOf(ApiException.class).satisfies(error -> assertThat(((ApiException) error).status()).isEqualTo(404));
        }
        assertThat(phones.calls).isZero(); assertThat(phones.deletes).isZero();
    }
    @Test void checkinUsesExactPromptAndPendingOwnerInputRunsFirst() {
        var agent = create("alice", "Scout");
        service.message("alice", agent.agent_Id(), "Prioritize the deployment");
        service.runTurn("alice", agent.agent_Id());
        service.runTurn("alice", agent.agent_Id());
        assertThat(model.prompts).containsExactly("Prioritize the deployment", "How are you doing and what are you working on?");
        assertThat(service.requireOwned("alice", agent.agent_Id()).agent_Sentiment()).isEqualTo(0.8);
    }
    @Test void failureRetainsScoreHistoryAndPendingInputForRetry() {
        var agent = create("alice", "Scout");
        service.message("alice", agent.agent_Id(), "Please verify the release");
        model.failReply = true;
        service.runTurn("alice", agent.agent_Id());
        var failed = service.requireOwned("alice", agent.agent_Id());
        assertThat(failed.agent_Sentiment()).isEqualTo(0.51);
        assertThat(failed.status()).isEqualTo("error");
        assertThat(failed.last_error()).isNotBlank();
        assertThat(service.conversation("alice", agent.agent_Id())).anyMatch(e -> e.content().equals("Please verify the release"));
        model.failReply = false;
        service.runTurn("alice", agent.agent_Id());
        assertThat(model.prompts).containsExactly("Please verify the release", "Please verify the release");
        assertThat(service.requireOwned("alice", agent.agent_Id()).last_error()).isNull();
    }
    @Test void assessmentFailureKeepsCompletedResponseAndDoesNotReplayOwnerInput() {
        var agent = create("alice", "Scout");
        service.message("alice", agent.agent_Id(), "Verify the release");
        model.failAssessment = true;
        service.runTurn("alice", agent.agent_Id());
        assertThat(service.requireOwned("alice", agent.agent_Id()).agent_responses()).hasSize(1);
        assertThat(service.requireOwned("alice", agent.agent_Id()).agent_Sentiment()).isEqualTo(0.51);
        model.failAssessment = false;
        service.runTurn("alice", agent.agent_Id());
        assertThat(model.prompts).containsExactly("Verify the release", "How are you doing and what are you working on?");
    }
    @Test void pollingTranscriptsIsIdempotentAndKeepsRawProvenance() {
        var agent = create("alice", "Scout");
        phones.transcripts = List.of(new PhoneProvider.Transcript("provider-segment", "call-id", 2, "2026-11-21T00:00:00Z", "remote", "Confirmed", Map.of("seq", 2, "party", "remote", "ts_ms", 1795219200000L)));
        service.runTurn("alice", agent.agent_Id()); service.runTurn("alice", agent.agent_Id());
        var transcript = service.conversation("alice", agent.agent_Id()).stream().filter(e -> e.channel().equals("phone")).toList();
        assertThat(transcript).hasSize(1);
        assertThat(transcript.getFirst().call_id()).isEqualTo("call-id");
        assertThat(transcript.getFirst().provenance()).containsEntry("seq", 2).containsEntry("party", "remote");
    }
    @Test void collaborationUsesPapAuthorEnvelopeWithinOneTenant() {
        var source = create("alice", "Scout"); var target = create("alice", "Analyst");
        service.collaborate("alice", source.agent_Id(), target.agent_Id(), "Review the release notes");
        var entry = service.conversation("alice", target.agent_Id()).getFirst();
        assertThat(entry.channel()).isEqualTo("protocol");
        assertThat(entry.protocol()).containsEntry("protocol_version", "1.0");
        assertThat(entry.protocol().get("message")).isInstanceOf(Map.class);
        assertThat(json.valueToTree(entry.protocol()).path("message").path("author").path("type").asText()).isEqualTo("agent");
        service.runTurn("alice", target.agent_Id());
        assertThat(model.prompts).containsExactly("Review the release notes");
    }
    @Test void ownerDirectedToolsRecordResultsAndFollowUpWithoutRepeatingSuccessfulCalls() {
        var agent = create("alice", "Scout");
        service.message("alice", agent.agent_Id(), "Call +14155550111 to confirm deployment");
        model.nextReply = new ModelProvider.Reply("resp-tool", "", List.of(new ModelProvider.ToolCall("tool-call", "place_call", Map.of("to_number", "+14155550111", "reason", "Confirm deployment"))));
        model.failFollowup = true;
        service.runTurn("alice", agent.agent_Id());
        assertThat(phones.calls).isEqualTo(1);
        assertThat(service.requireOwned("alice", agent.agent_Id()).last_error()).isNotBlank();
        model.failFollowup = false;
        service.runTurn("alice", agent.agent_Id());
        assertThat(phones.calls).isEqualTo(1);
        assertThat(model.lastResults).hasSize(1);
        assertThat(model.lastResults.getFirst().callId()).isEqualTo("tool-call");
        assertThat(service.conversation("alice", agent.agent_Id())).anyMatch(e -> e.content().contains("Tool place_call result"));
    }
    @Test void agentKnowledgeIsCreatedBeforeActivationRefreshedAndDeletedWithTheIdentity() {
        var agent = create("alice", "Scout");
        assertThat(repository.agent("alice", agent.agent_Id()).orElseThrow().vectorStoreId).isEqualTo("local-simulated-store-" + agent.agent_Id());
        service.mission("alice", "New shared mission"); service.runTurn("alice", agent.agent_Id());
        assertThat(knowledge.ensures).isEqualTo(2);
        service.delete("alice", agent.agent_Id());
        assertThat(knowledge.deletes).isEqualTo(1); assertThat(computer.releases).isEqualTo(1);
    }
    @Test void nativeComputerActionsReturnScreenshotsAndAuditWithoutPersistingBinary() {
        var agent = create("alice", "Scout");
        model.nextReply = new ModelProvider.Reply("resp-native", "", List.of(new ModelProvider.ToolCall("computer-call", "computer", Map.of("actions", List.of(Map.of("type", "screenshot"))))),
                List.of(Map.of("type", "web_search_call", "status", "completed", "action", Map.of("query", "release notes"))));
        service.runTurn("alice", agent.agent_Id());
        assertThat(computer.executions).isEqualTo(1);
        assertThat(model.lastResults.getFirst().nativeOutput().get("type")).isEqualTo("computer_call_output");
        var history = service.conversation("alice", agent.agent_Id());
        assertThat(history).anyMatch(entry -> entry.content().contains("web_search_call"));
        assertThat(history.toString()).contains("computer").doesNotContain("base64");
    }
    @Test void inventedPhoneDestinationIsRejectedAndScoreRetained() {
        var agent = create("alice", "Scout");
        model.nextReply = new ModelProvider.Reply("resp-tool", "", List.of(new ModelProvider.ToolCall("tool-call", "place_call", Map.of("to_number", "+14155550999", "reason", "Unrequested call"))));
        service.runTurn("alice", agent.agent_Id());
        assertThat(phones.calls).isZero();
        assertThat(service.requireOwned("alice", agent.agent_Id()).last_error()).isNotBlank();
        assertThat(service.requireOwned("alice", agent.agent_Id()).agent_Sentiment()).isEqualTo(0.51);
    }
    @Test void phoneAuthorizationMatchesTheEntireOwnerSuppliedE164Number() {
        var agent = service.create("alice", "Scout", "Acme", "Only call +14155550123 to confirm the release");
        model.nextReply = new ModelProvider.Reply("resp-prefix", "", List.of(new ModelProvider.ToolCall("prefix-call", "place_call",
                Map.of("to_number", "+1415555012", "reason", "Confirm the release"))));
        service.runTurn("alice", agent.agent_Id());
        assertThat(phones.calls).isZero();
        assertThat(service.requireOwned("alice", agent.agent_Id()).last_error()).contains("not supplied");
    }
    @Test void actionCapClearsContinuationSoAFreshOwnerMessageCanRun() {
        var agent = create("alice", "Scout");
        service.message("alice", agent.agent_Id(), "Original task");
        model.repeatTools = true;
        model.nextReply = new ModelProvider.Reply("resp-first", "", List.of(new ModelProvider.ToolCall("first-tool", "place_call", Map.of("to_number", "+14155550111", "reason", "Confirm deployment"))));
        service.runTurn("alice", agent.agent_Id());
        int callsAtCap = phones.calls;
        assertThat(service.requireOwned("alice", agent.agent_Id()).last_error()).contains("action limit");
        model.repeatTools = false;
        service.message("alice", agent.agent_Id(), "Stop calling and summarize the evidence");
        service.runTurn("alice", agent.agent_Id());
        assertThat(model.prompts.getLast()).isEqualTo("Stop calling and summarize the evidence");
        assertThat(phones.calls).isEqualTo(callsAtCap);
        assertThat(repository.agent("alice", agent.agent_Id()).orElseThrow().continuationResponseId).isNull();
    }
    @Test void missionUpdatesAreTenantIsolatedAndReachNextSystemPrompt() {
        var agent = create("alice", "Scout");
        service.mission("alice", "Protect reliability and privacy");
        service.runTurn("alice", agent.agent_Id());
        assertThat(model.lastMission).isEqualTo("Protect reliability and privacy");
        assertThat(phones.lastMission).isEqualTo("Protect reliability and privacy");
        assertThat(service.mission("bob")).isEqualTo("Keep customer data private");
    }
    @Test void oversizeCombinedVoiceInstructionsAreRejectedBeforeProviderProvisioningOrMissionChanges() {
        var agent = create("alice", "Scout");
        assertThatThrownBy(() -> service.mission("alice", "m".repeat(8000))).isInstanceOf(ApiException.class)
                .hasMessageContaining("8000");
        assertThat(service.mission("alice")).isEqualTo("Ship trustworthy software");
        service.mission("new-tenant", "m".repeat(7900));
        assertThatThrownBy(() -> service.create("new-tenant", "Scout", "Acme", "g".repeat(2000)))
                .isInstanceOf(ApiException.class).hasMessageContaining("8000");
        assertThat(service.list("new-tenant")).isEmpty();
    }
    @Test void deletionWaitsForTurnButPollingAndNewMessagesStayResponsive() throws Exception {
        var agent = create("alice", "Scout");
        model.entered = new CountDownLatch(1); model.release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var turn = pool.submit(() -> service.runTurn("alice", agent.agent_Id()));
            assertThat(model.entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.requireOwned("alice", agent.agent_Id()).status()).isEqualTo("working");
            service.message("alice", agent.agent_Id(), "Queued during provider work");
            var deletion = pool.submit(() -> service.delete("alice", agent.agent_Id()));
            assertThatThrownBy(() -> deletion.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            model.release.countDown(); turn.get(2, TimeUnit.SECONDS); deletion.get(2, TimeUnit.SECONDS);
            assertThat(service.list("alice")).isEmpty();
            assertThat(repository.conversation("alice", agent.agent_Id())).isEmpty();
        } finally { model.release.countDown(); }
    }
    @Test void providerDeletionFailureRetainsCleanupRecordAndBlocksFurtherActions() {
        var agent = create("alice", "Scout"); phones.failDelete = true;
        assertThatThrownBy(() -> service.delete("alice", agent.agent_Id())).isInstanceOf(ApiException.class);
        assertThat(service.requireOwned("alice", agent.agent_Id()).last_error()).isNotBlank();
        assertThatThrownBy(() -> service.message("alice", agent.agent_Id(), "Do more work")).isInstanceOf(ApiException.class);
        phones.failDelete = false; service.delete("alice", agent.agent_Id());
        assertThat(service.list("alice")).isEmpty();
    }
    static class FixturePhones extends LocalPhoneProvider {
        int calls; int deletes; boolean failDelete; String lastMission;
        CountDownLatch createEntered; CountDownLatch createRelease;
        List<PhoneProvider.Transcript> transcripts = List.of();
        @Override public Call call(AgentState agent, String to, String reason) { calls++; return new Call("phone-call-id", "ringing"); }
        @Override public void delete(String handle) { if (failDelete) throw new ProviderException("Cleanup unavailable"); deletes++; }
        @Override public List<PhoneProvider.Transcript> transcripts(AgentState agent) { return transcripts; }
        @Override public void configure(AgentState agent) { lastMission = agent.companyMission; }
        @Override public Identity create(String id, String name, String company, String goal, String mission) {
            if (createEntered != null) { createEntered.countDown(); try { createRelease.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new RuntimeException(error); } }
            return super.create(id, name, company, goal, mission);
        }
    }
    static class FixtureKnowledge extends LocalKnowledgeResources {
        int ensures; int deletes;
        @Override public void ensureReady(AgentState agent, Runnable checkpoint) { ensures++; super.ensureReady(agent, checkpoint); }
        @Override public void delete(AgentState agent, Runnable checkpoint) { deletes++; super.delete(agent, checkpoint); }
    }
    static class FixtureComputer implements ComputerExecutor {
        int executions; int releases;
        public Result execute(String tenant, String agent, Map<String, Object> call) { executions++; return new Result("data:image/png;base64,fixture", "https://example.com"); }
        public void release(String tenant, String agent) { releases++; }
        public void close() {}
    }
    static class FixtureModel implements ModelProvider {
        final List<String> prompts = new CopyOnWriteArrayList<>();
        boolean failReply; boolean failAssessment; boolean failFollowup; boolean repeatTools;
        Reply nextReply; List<ToolResult> lastResults; String lastMission;
        CountDownLatch entered; CountDownLatch release;
        public Reply reply(AgentState agent, List<ConversationEntry> history, String prompt, List<AgentView> peers) {
            prompts.add(prompt); lastMission = agent.companyMission;
            if (entered != null) { entered.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new RuntimeException(error); } }
            if (failReply) throw new ProviderException("Responses fixture unavailable");
            if (nextReply != null) { var reply = nextReply; nextReply = null; return reply; }
            return new Reply(UUID.randomUUID().toString(), "Working on " + prompt, List.of());
        }
        public Reply followup(AgentState agent, String previousResponseId, List<ToolResult> results, List<AgentView> peers) {
            lastResults = results;
            if (failFollowup) throw new ProviderException("Followup fixture unavailable");
            if (repeatTools) return new Reply(UUID.randomUUID().toString(), "", List.of(new ToolCall(UUID.randomUUID().toString(), "place_call", Map.of("to_number", "+14155550111", "reason", "Continue deployment check"))));
            return new Reply(UUID.randomUUID().toString(), "Tool completed and recorded", List.of());
        }
        public Assessment assess(AgentState agent, List<ConversationEntry> history) {
            if (failAssessment) throw new ProviderException("Decisions fixture unavailable");
            return new Assessment(0.8, "Observed authorized work supports the mission.");
        }
    }
}
