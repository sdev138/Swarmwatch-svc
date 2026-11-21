package com.athnasia.swarmwatchsvc.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComputerExecutorTest {
    @Test
    void executesOrderedNativeBatchAndReturnsScreenshotAfterAllActions() {
        FixtureBrowser browser = new FixtureBrowser();
        try (ComputerExecutor executor = new PlaywrightComputerExecutor(browser)) {
            var result = executor.execute("tenant", "agent", call(List.of(
                    Map.of("type", "click", "x", 50, "y", 75, "button", "left"),
                    Map.of("type", "type", "text", "penguin"),
                    Map.of("type", "keypress", "keys", List.of("CTRL", "A")),
                    Map.of("type", "scroll", "x", 80, "y", 90, "scroll_x", 0, "scroll_y", 300),
                    Map.of("type", "screenshot"))));
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}), result.screenshotDataUrl());
            assertEquals("https://example.com/", result.currentUrl());
            FixtureSession session = browser.sessions.getFirst();
            assertEquals(List.of("click", "type", "keypress", "scroll", "screenshot"), session.actions.stream().map(ComputerAction::type).toList());
            assertEquals("penguin", session.actions.get(1).text());
            assertEquals(List.of("Control", "A"), session.actions.get(2).keys());
            assertEquals(300, session.actions.get(3).scrollY());
            assertEquals(1, session.screenshots);
        }
    }

    @Test
    void screenshotOnlyCallDoesNotChangePage() {
        FixtureBrowser browser = new FixtureBrowser();
        try (ComputerExecutor executor = new PlaywrightComputerExecutor(browser)) {
            executor.execute("tenant", "agent", call(List.of(Map.of("type", "screenshot"))));
            assertEquals(1, browser.sessions.getFirst().screenshots);
            assertEquals("screenshot", browser.sessions.getFirst().actions.getFirst().type());
        }
    }

    @Test
    void pendingSafetyChecksRequireHumanReviewAndCannotBeAutomaticallyAcknowledged() {
        FixtureBrowser browser = new FixtureBrowser();
        try (ComputerExecutor executor = new PlaywrightComputerExecutor(browser)) {
            Map<String, Object> call = Map.of("actions", List.of(Map.of("type", "screenshot")),
                    "pending_safety_checks", List.of(Map.of("id", "check", "code", "malicious_instructions", "message", "Review")));
            ComputerUseException error = assertThrows(ComputerUseException.class, () -> executor.execute("tenant", "agent", call));
            assertTrue(error.getMessage().contains("approval"));
            assertTrue(browser.sessions.isEmpty());
        }
    }

    @Test
    void validatesEntireBatchBeforeAnyActionAndRejectsShellOrOutOfViewportCoordinates() {
        FixtureBrowser browser = new FixtureBrowser();
        try (ComputerExecutor executor = new PlaywrightComputerExecutor(browser)) {
            assertThrows(ComputerUseException.class, () -> executor.execute("tenant", "agent", call(List.of(
                    Map.of("type", "type", "text", "do not type this"), Map.of("type", "shell", "command", "ls")))));
            assertThrows(ComputerUseException.class, () -> executor.execute("tenant", "agent", call(List.of(
                    Map.of("type", "click", "x", -1, "y", 20, "button", "left")))));
            assertTrue(browser.sessions.isEmpty());
        }
    }

    @Test
    void rejectsOversizedCallsAndHostClipboardOrFileShortcuts() {
        FixtureBrowser browser = new FixtureBrowser();
        try (ComputerExecutor executor = new PlaywrightComputerExecutor(browser)) {
            assertThrows(ComputerUseException.class, () -> executor.execute("tenant", "agent", call(java.util.Collections.nCopies(26, Map.of("type", "screenshot")))));
            for (List<String> keys : List.of(List.of("CTRL", "V"), List.of("META", "O"), List.of("CTRL", "SHIFT", "J"))) {
                assertThrows(ComputerUseException.class, () -> executor.execute("tenant", "agent", call(List.of(Map.of("type", "keypress", "keys", keys)))));
            }
            assertTrue(browser.sessions.isEmpty());
        }
    }

    @Test
    void sessionsCannotCrossTenantOrAgentBoundaryAndReleaseClosesOnlyOwnedSession() {
        FixtureBrowser browser = new FixtureBrowser();
        try (ComputerExecutor executor = new PlaywrightComputerExecutor(browser)) {
            Map<String, Object> screenshot = call(List.of(Map.of("type", "screenshot")));
            executor.execute("tenant-a", "agent", screenshot);
            executor.execute("tenant-b", "agent", screenshot);
            executor.execute("tenant-a", "agent-two", screenshot);
            executor.execute("tenant-a", "agent", screenshot);
            assertEquals(3, browser.sessions.size());
            assertEquals(2, browser.sessions.get(0).screenshots);
            executor.release("tenant-a", "agent");
            assertTrue(browser.sessions.get(0).closed);
            assertFalse(browser.sessions.get(1).closed);
            assertFalse(browser.sessions.get(2).closed);
            executor.execute("tenant-a", "agent", screenshot);
            assertEquals(4, browser.sessions.size());
        }
        assertTrue(browser.closed);
        assertTrue(browser.sessions.stream().allMatch(s -> s.closed));
    }

    @Test
    void mapsDoubleClickMoveDragAndMouseModifiersWithoutDroppingCoordinates() {
        FixtureBrowser browser = new FixtureBrowser();
        try (ComputerExecutor executor = new PlaywrightComputerExecutor(browser)) {
            executor.execute("tenant", "agent", call(List.of(
                    Map.of("type", "double_click", "x", 100, "y", 200),
                    Map.of("type", "move", "x", 120, "y", 240, "keys", List.of("SHIFT")),
                    Map.of("type", "drag", "path", List.of(Map.of("x", 1, "y", 2), Map.of("x", 3, "y", 4))),
                    Map.of("type", "wait"))));
            List<ComputerAction> actions = browser.sessions.getFirst().actions;
            assertEquals(100, actions.get(0).x());
            assertEquals(200, actions.get(0).y());
            assertEquals(List.of("Shift"), actions.get(1).keys());
            assertEquals(List.of(new ComputerAction.Point(1, 2), new ComputerAction.Point(3, 4)), actions.get(2).path());
            assertEquals("wait", actions.get(3).type());
        }
    }

    @Test
    void localExecutorReturnsExplicitSimulationWithoutStartingBrowser() {
        try (ComputerExecutor executor = new LocalComputerExecutor()) {
            var result = executor.execute("tenant", "agent", call(List.of(Map.of("type", "screenshot"))));
            assertTrue(result.screenshotDataUrl().startsWith("data:image/png;base64,"));
            assertEquals("local-simulated://computer", result.currentUrl());
        }
    }

    private static Map<String, Object> call(List<? extends Map<String, ?>> actions) { return Map.of("type", "computer_call", "actions", actions, "pending_safety_checks", List.of()); }

    private static final class FixtureBrowser implements BrowserSession.Factory {
        final List<FixtureSession> sessions = new ArrayList<>();
        boolean closed;
        public BrowserSession open() { var session = new FixtureSession(); sessions.add(session); return session; }
        public void close() { closed = true; }
    }

    private static final class FixtureSession implements BrowserSession {
        final List<ComputerAction> actions = new ArrayList<>();
        int screenshots;
        boolean closed;
        public void perform(ComputerAction action) { actions.add(action); }
        public byte[] screenshot() { screenshots++; return new byte[]{1, 2, 3}; }
        public String currentUrl() { return "https://example.com/"; }
        public void close() { closed = true; }
    }
}
