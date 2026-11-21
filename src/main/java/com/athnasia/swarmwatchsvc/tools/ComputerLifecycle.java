package com.athnasia.swarmwatchsvc.tools;

import com.athnasia.swarmwatchsvc.domain.AgentDeletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
class ComputerLifecycle {
    private final ComputerExecutor computer;
    ComputerLifecycle(ComputerExecutor computer) { this.computer = computer; }
    @EventListener void deleted(AgentDeletedEvent event) { computer.release(event.tenantId(), event.agentId()); }
}
