package com.athnasia.swarmwatchsvc.registry;

import com.athnasia.swarmwatchsvc.api.ApiException;
import com.athnasia.swarmwatchsvc.auth.AuthService;
import com.athnasia.swarmwatchsvc.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RegistryController {
    private final AuthService auth;
    private final AgentService agents;
    private final RegistryService registry;

    RegistryController(AuthService auth, AgentService agents, RegistryService registry) {
        this.auth = auth;
        this.agents = agents;
        this.registry = registry;
    }

    @GetMapping("/api/agents/{id}/registry")
    RegistryView view(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        var agent = agents.requireOwned(auth.requireSession(authorization).user_id(), id);
        return registry.view(agent);
    }

    @PostMapping("/api/agents/{id}/registry/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RegistryView retry(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        var agent = agents.requireOwned(auth.requireSession(authorization).user_id(), id);
        try { return registry.retry(agent); }
        catch (RegistryFailure failure) { throw new ApiException(409, failure.getMessage()); }
    }
}
