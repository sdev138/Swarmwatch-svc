package com.athnasia.swarmwatchsvc.registry;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class RegistryCoordinator {
    private final RegistryStore store;
    private final Registrar registrar;
    private final Clock clock;
    private final boolean local;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    RegistryCoordinator(RegistryStore store, Registrar registrar, Clock clock, boolean local) {
        this.store = store;
        this.registrar = registrar;
        this.clock = clock;
        this.local = local;
    }

    private Object lock(String tenant, String agent) { return locks.computeIfAbsent(tenant + "/" + agent, ignored -> new Object()); }

    RegistryView register(String tenant, String agent, String name, String company, String goal, String email, String phone) {
        synchronized (lock(tenant, agent)) {
            Registration registration = store.find(tenant, agent).orElseGet(() -> {
                Instant now = clock.instant();
                var created = new Registration(tenant, agent, name, company, goal, email, phone,
                        local ? Registration.Stage.LOCAL : Registration.Stage.ACCOUNT, null, now, now, now);
                store.save(created);
                return created;
            });
            return registration.view();
        }
    }

    RegistryView view(String tenant, String agent) { return store.find(tenant, agent).map(Registration::view).orElse(null); }

    RegistryView retry(String tenant, String agent) {
        synchronized (lock(tenant, agent)) {
            Registration registration = store.find(tenant, agent).orElseThrow(() -> new RegistryFailure("Create the agent's registry state before retrying."));
            if (registration.stage() == Registration.Stage.DELETING || registration.stage() == Registration.Stage.CLEANUP || registration.stage() == Registration.Stage.REMOVED) {
                throw new RegistryFailure("Agent deletion needs to finish. Retry deleting the agent.");
            }
            if (registration.error() == null) return registration.view();
            Registration retried = registration.retry(clock.instant());
            store.save(retried);
            return retried.view();
        }
    }

    void tick() {
        for (Registration candidate : store.pending()) {
            synchronized (lock(candidate.tenantId(), candidate.agentId())) {
                Registration registration = store.find(candidate.tenantId(), candidate.agentId()).orElse(null);
                if (registration == null || !registration.pending()) continue;
                Instant now = clock.instant();
                if (now.isBefore(registration.nextAttemptAt())) continue;
                if (!now.isBefore(registration.startedAt().plusSeconds(600))) {
                    store.save(registration.fail("Nanda registration did not become active within 10 minutes. Check the registry and retry.", now));
                    continue;
                }
                Registration next;
                try {
                    next = registration.progress(registrar.advance(registration), clock.instant());
                } catch (RuntimeException failure) {
                    next = registration.progress(registration.stage(), clock.instant()).fail(safeError(failure), clock.instant());
                }
                store.save(next);
            }
        }
    }

    void beforeDelete(String tenant, String agent) {
        synchronized (lock(tenant, agent)) {
            Registration registration = store.find(tenant, agent).orElse(null);
            if (registration == null || registration.stage() == Registration.Stage.REMOVED) return;
            boolean mayHaveEntry = registration.stage() != Registration.Stage.ACCOUNT && registration.stage() != Registration.Stage.ACCOUNT_VERIFY && registration.stage() != Registration.Stage.LOCAL && registration.stage() != Registration.Stage.CLEANUP;
            Registration deleting = registration.progress(Registration.Stage.DELETING, clock.instant());
            store.save(deleting);
            try {
                if (!local) {
                    if (mayHaveEntry) registrar.remove(registration);
                    deleting = deleting.progress(Registration.Stage.CLEANUP, clock.instant());
                    store.save(deleting);
                    registrar.forget(registration);
                }
                store.save(deleting.progress(Registration.Stage.REMOVED, clock.instant()));
            } catch (RuntimeException failure) {
                String error = safeError(failure);
                store.save(deleting.fail(error, clock.instant()));
                throw new RegistryFailure(error);
            }
        }
    }

    void deleted(String tenant, String agent) {
        synchronized (lock(tenant, agent)) { store.delete(tenant, agent); }
    }

    private static String safeError(RuntimeException failure) {
        return failure instanceof RegistryFailure ? failure.getMessage()
                : "Nanda registration failed. Check provider connectivity and Secret Manager permissions, then retry.";
    }
}
