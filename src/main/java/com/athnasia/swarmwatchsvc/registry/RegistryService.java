package com.athnasia.swarmwatchsvc.registry;

import com.athnasia.swarmwatchsvc.domain.AgentCreatedEvent;
import com.athnasia.swarmwatchsvc.domain.AgentDeletedEvent;
import com.athnasia.swarmwatchsvc.domain.AgentDeletingEvent;
import com.athnasia.swarmwatchsvc.domain.AgentView;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
class RegistryService {
    private final RegistryCoordinator coordinator;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("nanda-registration").factory());

    RegistryService(RegistryCoordinator coordinator) { this.coordinator = coordinator; }

    @EventListener(ApplicationReadyEvent.class)
    void start() { worker.scheduleWithFixedDelay(this::tick, 0, 30, TimeUnit.SECONDS); }

    @EventListener
    void created(AgentCreatedEvent event) {
        try {
            coordinator.register(event.tenantId(), event.agentId(), event.name(), event.company(), event.goal(), event.email(), event.phone());
            worker.execute(this::tick);
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(RegistryService.class).warn("Registry state initialization unavailable; status or retry can restore it.");
        }
    }

    RegistryView view(AgentView agent) {
        RegistryView view = coordinator.view(agent.user_Id(), agent.agent_Id());
        return view == null ? register(agent) : view;
    }

    RegistryView retry(AgentView agent) {
        if (coordinator.view(agent.user_Id(), agent.agent_Id()) == null) register(agent);
        RegistryView view = coordinator.retry(agent.user_Id(), agent.agent_Id());
        worker.execute(this::tick);
        return view;
    }

    private RegistryView register(AgentView agent) {
        RegistryView view = coordinator.register(agent.user_Id(), agent.agent_Id(), agent.agent_Name(), agent.company(), agent.goal(), agent.inkbox_email(), agent.phone_number());
        worker.execute(this::tick);
        return view;
    }

    @EventListener
    void deleting(AgentDeletingEvent event) { coordinator.beforeDelete(event.tenantId(), event.agentId()); }

    @EventListener
    void deleted(AgentDeletedEvent event) { coordinator.deleted(event.tenantId(), event.agentId()); }

    private void tick() {
        try { coordinator.tick(); }
        catch (RuntimeException failure) {
            LoggerFactory.getLogger(RegistryService.class).warn("Registry worker unavailable; durable pending state will be retried.");
        }
    }

    @PreDestroy
    void stop() { worker.shutdownNow(); }
}
