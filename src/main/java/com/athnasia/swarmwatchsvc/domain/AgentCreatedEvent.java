package com.athnasia.swarmwatchsvc.domain;

public record AgentCreatedEvent(String tenantId, String agentId, String name, String company,
        String goal, String email, String phone) {}
