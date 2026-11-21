package com.athnasia.swarmwatchsvc;

import com.athnasia.swarmwatchsvc.domain.*;
import com.athnasia.swarmwatchsvc.repository.DatastoreSwarmRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.datastore.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatastoreContractTests {
    @Test void agentWriteContainsRequestedTypedFieldsAndBoundedRecentResponses() {
        var datastore = mock(Datastore.class);
        when(datastore.newKeyFactory()).thenAnswer(ignored -> new KeyFactory("fixture-project"));
        var repository = new DatastoreSwarmRepository(() -> datastore, new ObjectMapper());
        var agent = new AgentState();
        agent.id = "agent-id"; agent.tenantId = "tenant-id"; agent.name = "Scout"; agent.company = "Acme";
        agent.goal = "Verify the release"; agent.companyMission = "Protect reliability"; agent.email = "scout@inkboxmail.com"; agent.phone = "+14155550100";
        agent.createdAt = "2026-11-21T00:00:00Z";
        for (int i = 0; i < 30; i++) agent.addResponse("Response " + i);
        repository.save(agent);
        var captured = ArgumentCaptor.forClass(Entity.class); verify(datastore).put(captured.capture());
        Entity entity = captured.getValue();
        assertThat(entity.getKey().getAncestors()).containsExactly(PathElement.of("Tenant", "tenant-id"));
        assertThat(entity.getString("agent_Id")).isEqualTo("agent-id");
        assertThat(entity.getString("user_Id")).isEqualTo("tenant-id");
        assertThat(entity.getString("agent_Name")).isEqualTo("Scout");
        assertThat(entity.getDouble("agent_Sentiment")).isEqualTo(0.51);
        assertThat(entity.getString("inkbox_email")).isEqualTo("scout@inkboxmail.com");
        assertThat(entity.getString("phone_number")).isEqualTo("+14155550100");
        assertThat(entity.getString("company")).isEqualTo("Acme");
        assertThat(entity.<StringValue>getList("agent_responses")).hasSize(20);
        assertThat(entity.<StringValue>getList("agent_responses").getFirst().get()).isEqualTo("Response 10");
    }
    @Test void conversationAppendHasOnlyJsonPropertyAndUsesAgentAncestor() throws Exception {
        var datastore = mock(Datastore.class); var transaction = mock(Transaction.class);
        when(datastore.newKeyFactory()).thenAnswer(ignored -> new KeyFactory("fixture-project"));
        when(datastore.newTransaction()).thenReturn(transaction);
        var json = new ObjectMapper(); var agent = new AgentState(); agent.id = "agent-id"; agent.tenantId = "tenant-id";
        when(transaction.get(any(Key.class))).thenAnswer(call -> {
            Key key = call.getArgument(0); return key.getKind().equals("Agent") ? Entity.newBuilder(key).set("agent_json", json.writeValueAsString(agent)).build() : null;
        });
        var repository = new DatastoreSwarmRepository(() -> datastore, json);
        repository.append("tenant-id", "agent-id", ConversationEntry.message("user", "Hello", "dashboard"));
        var captured = ArgumentCaptor.forClass(Entity.class); verify(transaction).put(captured.capture());
        var chunk = captured.getValue();
        assertThat(chunk.getNames()).containsExactly("agent_conversation_json");
        assertThat(chunk.getValue("agent_conversation_json").excludeFromIndexes()).isTrue();
        assertThat(chunk.getKey().getAncestors()).containsExactly(PathElement.of("Tenant", "tenant-id"), PathElement.of("Agent", "agent-id"));
        assertThat(json.readTree(chunk.getString("agent_conversation_json")).get(0).path("content").asText()).isEqualTo("Hello");
    }
}
