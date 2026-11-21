package com.athnasia.swarmwatchsvc.registry;

import java.time.Instant;
import java.util.Locale;

record Registration(String tenantId, String agentId, String name, String company, String goal,
                    String email, String phone, Stage stage, String error,
                    Instant startedAt, Instant updatedAt, Instant nextAttemptAt) {
    enum Stage { ACCOUNT, ACCOUNT_VERIFY, ENTRY, ENTRY_VERIFY, RESOLVE, ACTIVE, LOCAL, DELETING, CLEANUP, REMOVED }

    String locator() { return "urn:air:host39.org:personal:" + email.toLowerCase(Locale.ROOT); }

    String handle() {
        String address = email.toLowerCase(Locale.ROOT);
        if (!address.matches("[a-z0-9._-]+@inkboxmail\\.com")) {
            throw new RegistryFailure("Nanda registration needs the agent's provisioned Inkbox mailbox.");
        }
        return address.substring(0, address.indexOf('@'));
    }

    String orgId() { return "inkbox-" + handle(); }

    Registration progress(Stage next, Instant now) {
        return new Registration(tenantId, agentId, name, company, goal, email, phone, next, null,
                startedAt, now, now.plusSeconds(30));
    }

    Registration fail(String message, Instant now) {
        return new Registration(tenantId, agentId, name, company, goal, email, phone, stage, message,
                startedAt, now, nextAttemptAt);
    }

    Registration retry(Instant now) {
        return new Registration(tenantId, agentId, name, company, goal, email, phone, stage, null,
                now, now, nextAttemptAt.isAfter(now) ? nextAttemptAt : now);
    }

    boolean pending() { return error == null && stage != Stage.ACTIVE && stage != Stage.LOCAL && stage != Stage.DELETING && stage != Stage.CLEANUP && stage != Stage.REMOVED; }

    RegistryView view() {
        String status = error != null ? "error" : stage == Stage.ACTIVE ? "active" : stage == Stage.LOCAL ? "local" : "pending";
        return new RegistryView(status, stage == Stage.LOCAL ? null : locator(), error, phone, updatedAt.toString());
    }
}
