package com.athnasia.swarmwatchsvc.tools;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PlaywrightComputerExecutor implements ComputerExecutor {
    private record AgentKey(String tenant, String agent) { }
    private final BrowserSession.Factory browser;
    private final Map<AgentKey, BrowserSession> sessions = new HashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().daemon().name("isolated-computer").factory(), new ThreadPoolExecutor.AbortPolicy());

    public PlaywrightComputerExecutor() { this(new PlaywrightBrowser(new PublicUrlPolicy())); }
    PlaywrightComputerExecutor(BrowserSession.Factory browser) { this.browser = browser; }

    @Override
    public Result execute(String tenantId, String agentId, Map<String, Object> computerCall) {
        if (tenantId == null || tenantId.isBlank() || agentId == null || agentId.isBlank()) throw new ComputerUseException("Computer use requires an owned agent identity.");
        List<ComputerAction> actions = ComputerAction.batch(computerCall);
        return submit(() -> {
            AgentKey key = new AgentKey(tenantId, agentId);
            BrowserSession session = sessions.get(key);
            if (session == null) {
                if (sessions.size() >= 32) throw new ComputerUseException("The isolated browser session limit is reached. Release unused sessions and retry.");
                session = browser.open();
                sessions.put(key, session);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            for (ComputerAction action : actions) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new ComputerUseException("Computer action batch reached its time limit. Inspect the last screenshot before retrying.");
                session.perform(action);
            }
            return new Result("data:image/png;base64," + Base64.getEncoder().encodeToString(session.screenshot()), session.currentUrl());
        });
    }

    @Override
    public void release(String tenantId, String agentId) {
        submit(() -> { BrowserSession session = sessions.remove(new AgentKey(tenantId, agentId)); if (session != null) session.close(); return null; });
    }

    private <T> T submit(Callable<T> action) {
        Future<T> future;
        try { future = worker.submit(action); }
        catch (RuntimeException busy) { throw new ComputerUseException("The isolated browser is busy or stopped. Retry the action."); }
        try { return future.get(35, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ComputerUseException("Computer use was cancelled.");
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new ComputerUseException("Computer use exceeded its time limit. Retry after the browser finishes stopping.");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ComputerUseException safe) throw safe;
            throw new ComputerUseException("Isolated browser execution failed. Check Chromium installation and retry.");
        }
    }

    @Override
    public void close() {
        if (worker.isShutdown()) return;
        try {
            submit(() -> {
                for (BrowserSession session : sessions.values()) session.close();
                sessions.clear();
                browser.close();
                return null;
            });
        } finally { worker.shutdownNow(); }
    }
}
