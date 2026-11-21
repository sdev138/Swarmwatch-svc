package com.athnasia.swarmwatchsvc.service;

import com.athnasia.swarmwatchsvc.domain.AgentWorkRequestedEvent;
import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.subjects.Subject;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AgentRuntime implements AutoCloseable {
    private record Work(String tenant, String id) {}
    private final AgentService agents;
    private final long interval;
    private final Subject<Work> queue = PublishSubject.<Work>create().toSerialized();
    private final Set<Work> queued = ConcurrentHashMap.newKeySet();
    private final Set<Work> rerun = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("swarmwatch-agent-worker").factory());
    private final Disposable worker;
    private Disposable timer;
    public AgentRuntime(AgentService agents, @Value("${swarmwatch.check-interval-seconds}") long interval) {
        this.agents = agents; this.interval = Math.max(1, interval);
        worker = queue.observeOn(Schedulers.from(executor)).concatMapCompletable(work -> Completable.fromAction(() -> agents.runTurn(work.tenant(), work.id()))
                .doOnError(error -> LoggerFactory.getLogger(AgentRuntime.class).warn("Agent worker could not persist its result ({})", error.getClass().getSimpleName()))
                .onErrorComplete().doFinally(() -> {
                    queued.remove(work);
                    if (rerun.remove(work)) enqueue(work, false);
                })).subscribe();
    }
    @EventListener(ApplicationReadyEvent.class) public void start() {
        resume();
        timer = Flowable.interval(interval, interval, TimeUnit.SECONDS).subscribe(tick -> resume());
    }
    private void resume() {
        try { for (var agent : agents.allAgents()) if (!agent.deletionRequested) enqueue(new Work(agent.tenantId, agent.id), false); }
        catch (RuntimeException failure) { LoggerFactory.getLogger(AgentRuntime.class).warn("Existing agents could not resume; check Datastore configuration ({})", failure.getClass().getSimpleName()); }
    }
    @EventListener public void requested(AgentWorkRequestedEvent event) { enqueue(new Work(event.tenantId(), event.agentId()), true); }
    private void enqueue(Work work, boolean repeatIfRunning) {
        if (queued.add(work)) queue.onNext(work);
        else if (repeatIfRunning) rerun.add(work);
    }
    @PreDestroy @Override public void close() {
        if (timer != null) timer.dispose();
        worker.dispose(); queue.onComplete(); executor.shutdownNow();
    }
}
