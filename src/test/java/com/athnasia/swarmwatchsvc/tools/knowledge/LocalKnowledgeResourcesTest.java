package com.athnasia.swarmwatchsvc.tools.knowledge;

import com.athnasia.swarmwatchsvc.domain.AgentState;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

class LocalKnowledgeResourcesTest {
    @Test void localProfileNeedsNoHttpClientOrSecretsAndKeepsResourcesConspicuouslySimulated() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local");
            context.register(KnowledgeConfiguration.class);
            context.refresh();
            var resources = context.getBean(KnowledgeResources.class);
            assertThat(resources).isInstanceOf(LocalKnowledgeResources.class);
            var agent = new AgentState(); agent.id = "agent-local"; agent.companyMission = "Build trustworthy tools";
            agent.name = "Scout"; agent.company = "Acme"; agent.goal = "Verify releases";
            var checkpoints = new AtomicInteger();
            resources.ensureReady(agent, checkpoints::incrementAndGet);
            assertThat(agent.vectorStoreId).isEqualTo("local-simulated-store-agent-local");
            assertThat(agent.knowledgeFileId).isEqualTo("local-simulated-file-agent-local");
            String firstVersion = agent.knowledgeContextHash;
            resources.ensureReady(agent, checkpoints::incrementAndGet);
            assertThat(checkpoints).hasValue(1);
            agent.companyMission = "A new shared mission";
            resources.ensureReady(agent, checkpoints::incrementAndGet);
            assertThat(agent.knowledgeContextHash).isNotEqualTo(firstVersion);
            assertThat(checkpoints).hasValue(2);
            resources.delete(agent, checkpoints::incrementAndGet);
            assertThat(agent.vectorStoreId).isNull();
            assertThat(agent.knowledgeFileId).isNull();
            assertThat(agent.knowledgeContextHash).isNull();
        }
    }
}
