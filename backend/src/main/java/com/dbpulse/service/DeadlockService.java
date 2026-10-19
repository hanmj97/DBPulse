package com.dbpulse.service;

import com.dbpulse.collector.MssqlDeadlockCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.DeadlockRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Async XE event ingestion. Collector health does not change the existing
 * DMV/Blocking Chain/Query Store pipelines.
 */
@Service
public class DeadlockService {
    private static final Logger log = LoggerFactory.getLogger(DeadlockService.class);
    private final MssqlTargetProperties target;
    private final MssqlDeadlockCollector collector;
    private final DeadlockRepository repository;
    private final AtomicReference<DeadlockState> state = new AtomicReference<>(
        new DeadlockState("INITIALIZING", null, "NOT_YET_COLLECTED"));

    public DeadlockService(MssqlTargetProperties target,
                           MssqlDeadlockCollector collector,
                           DeadlockRepository repository) {
        this.target = target;
        this.collector = collector;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${dbpulse.deadlocks.interval-ms:15000}", initialDelay = 7000)
    public void poll() {
        if (!target.isEnabled()) {
            state.set(new DeadlockState("NOT_CONFIGURED", null, "MSSQL_NOT_CONFIGURED"));
            return;
        }
        try {
            var events = collector.collect();
            int newCount = repository.saveNew(target.getId(), events);
            state.set(new DeadlockState("ONLINE", Instant.now(), null));
            if (newCount > 0) log.info("Collected {} new deadlock events", newCount);
        } catch (Exception ex) {
            var before = state.get();
            state.set(new DeadlockState("OFFLINE", before.lastCollectedAt(), "DEADLOCK_COLLECTION_FAILED"));
            log.warn("Deadlock event collection failed ({})", ex.getClass().getSimpleName());
        }
    }

    public DeadlockState state() { return state.get(); }

    @Scheduled(cron = "0 40 3 * * *", zone = "UTC")
    public void prune() {
        try { repository.deleteOld(); }
        catch (Exception ex) {
            log.warn("Deadlock retention cleanup failed ({})", ex.getClass().getSimpleName());
        }
    }

    public record DeadlockState(String status, Instant lastCollectedAt, String errorCode) {}
}
