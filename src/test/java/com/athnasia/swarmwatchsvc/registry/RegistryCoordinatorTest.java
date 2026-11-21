package com.athnasia.swarmwatchsvc.registry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class RegistryCoordinatorTest {
    private final MutableClock clock = new MutableClock();
    private final MemoryRegistryStore store = new MemoryRegistryStore();
    private final FixtureRegistrar registrar = new FixtureRegistrar();
    private final RegistryCoordinator coordinator = new RegistryCoordinator(store, registrar, clock, false);

    @Test
    void localModeIsExplicitAndCannotCallCloudAdapters() {
        RegistryCoordinator local = new RegistryCoordinator(store, registrar, clock, true);
        assertEquals("local", local.register("tenant", "agent", "Sam", "Example", "Goal", "local@inkboxmail.com", "LOCAL-NO-CALLS").status());
        local.tick();
        assertEquals(0, registrar.advances);
        assertNull(local.view("tenant", "agent").identifier());
        assertEquals("LOCAL-NO-CALLS", local.view("tenant", "agent").phone_number());
    }

    @Test
    void pollingNeverRunsEarlierThanThirtySecondsEvenAfterDuplicateCreateOrRetry() {
        register();
        coordinator.tick();
        assertEquals("pending", coordinator.view("tenant", "agent").status());
        assertEquals(1, registrar.advances);
        clock.advance(29);
        register();
        coordinator.retry("tenant", "agent");
        coordinator.tick();
        assertEquals(1, registrar.advances);
        clock.advance(1);
        coordinator.tick();
        assertEquals(2, registrar.advances);
    }

    @Test
    void tenMinuteTimeoutIsDurableAndStopsAllFurtherProviderPollingUntilRetry() {
        register();
        coordinator.tick();
        clock.advance(600);
        coordinator.tick();
        assertEquals("error", coordinator.view("tenant", "agent").status());
        assertTrue(coordinator.view("tenant", "agent").error().contains("10 minutes"));
        assertEquals(1, registrar.advances);
        coordinator.tick();
        assertEquals(1, registrar.advances);
        coordinator.retry("tenant", "agent");
        coordinator.tick();
        assertEquals(2, registrar.advances);
    }

    @Test
    void newCoordinatorResumesPersistedStageWithoutCreatingAnotherAccount() {
        registrar.next = Registration.Stage.RESOLVE;
        register();
        coordinator.tick();
        RegistryCoordinator restarted = new RegistryCoordinator(store, registrar, clock, false);
        registrar.next = Registration.Stage.ACTIVE;
        clock.advance(30);
        restarted.tick();
        assertEquals("active", restarted.view("tenant", "agent").status());
        assertEquals(Registration.Stage.RESOLVE, registrar.previous);
        restarted.tick();
        assertEquals(2, registrar.advances);
    }

    @Test
    void failureKeepsAgentAndPhoneWithActionableErrorAndRetryRetainsStage() {
        register();
        registrar.failure = new RegistryFailure("Nanda entry creation failed (HTTP 503). Check provider setup and retry.");
        coordinator.tick();
        RegistryView failed = coordinator.view("tenant", "agent");
        assertEquals("error", failed.status());
        assertEquals("+14155550100", failed.phone_number());
        assertTrue(failed.error().contains("503"));
        registrar.failure = null;
        clock.advance(30);
        coordinator.retry("tenant", "agent");
        coordinator.tick();
        assertEquals("pending", coordinator.view("tenant", "agent").status());
        assertEquals(Registration.Stage.ACCOUNT, registrar.previous);
    }

    @Test
    void unexpectedProviderExceptionsCannotPersistCredentialValues() {
        register();
        registrar.failure = new IllegalStateException("secret-password-from-sdk");
        coordinator.tick();
        assertFalse(coordinator.view("tenant", "agent").error().contains("secret-password"));
    }

    @Test
    void unsuccessfulRegistryRemovalBlocksPhoneDeletionAndSupportsRetry() {
        registrar.next = Registration.Stage.ACTIVE;
        register();
        coordinator.tick();
        registrar.failure = new RegistryFailure("Registry deletion failed.");
        assertThrows(RegistryFailure.class, () -> coordinator.beforeDelete("tenant", "agent"));
        assertEquals("error", coordinator.view("tenant", "agent").status());
        assertEquals(0, registrar.forgotten);
        registrar.failure = null;
        coordinator.beforeDelete("tenant", "agent");
        assertEquals(1, registrar.forgotten);
        coordinator.beforeDelete("tenant", "agent");
        assertEquals(2, registrar.removals);
        coordinator.deleted("tenant", "agent");
        assertTrue(store.find("tenant", "agent").isEmpty());
    }

    @Test
    void keysSeparateTenantsAndDeletingStateCannotBeResumedAsRegistration() {
        register();
        assertNull(coordinator.view("other-tenant", "agent"));
        coordinator.beforeDelete("tenant", "agent");
        assertThrows(RegistryFailure.class, () -> coordinator.retry("tenant", "agent"));
        coordinator.tick();
        assertEquals(0, registrar.advances);
    }

    @Test
    void passwordCleanupRetryDoesNotTryToLogInToAnAccountNeverCreated() {
        register();
        registrar.forgetFailure = new RegistryFailure("Secret deletion failed.");
        assertThrows(RegistryFailure.class, () -> coordinator.beforeDelete("tenant", "agent"));
        registrar.forgetFailure = null;
        coordinator.beforeDelete("tenant", "agent");
        assertEquals(0, registrar.removals);
        assertEquals(2, registrar.forgotten);
    }

    @Test
    void failedResolutionCannotBePolledAgainBeforeThirtySecondsByPressingRetry() {
        register();
        registrar.failure = new RegistryFailure("Nanda public resolution failed (HTTP 503).");
        coordinator.tick();
        registrar.failure = null;
        coordinator.retry("tenant", "agent");
        coordinator.tick();
        assertEquals(1, registrar.advances);
        clock.advance(29);
        coordinator.tick();
        assertEquals(1, registrar.advances);
        clock.advance(1);
        coordinator.tick();
        assertEquals(2, registrar.advances);
    }

    private void register() { coordinator.register("tenant", "agent", "Sam", "Example", "Goal", "sw-example@inkboxmail.com", "+14155550100"); }

    private static final class FixtureRegistrar implements Registrar {
        int advances;
        int removals;
        int forgotten;
        Registration.Stage next = Registration.Stage.RESOLVE;
        Registration.Stage previous;
        RuntimeException failure;
        RuntimeException forgetFailure;
        public Registration.Stage advance(Registration registration) {
            advances++;
            previous = registration.stage();
            if (failure != null) throw failure;
            return next;
        }
        public void remove(Registration registration) { removals++; if (failure != null) throw failure; }
        public void forget(Registration registration) { forgotten++; if (forgetFailure != null) throw forgetFailure; }
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-11-21T00:00:00Z");
        void advance(long seconds) { current = current.plusSeconds(seconds); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return current; }
    }
}
