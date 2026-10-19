package com.dbpulse.service;

import com.dbpulse.collector.MssqlBlockingChainCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.BlockingEdge;
import com.dbpulse.persistence.BlockingChainRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Independent from the incident counter and Query Store data pipelines. */
@Service
public class BlockingChainService {
    private static final Logger log = LoggerFactory.getLogger(BlockingChainService.class);
    private final MssqlTargetProperties target;
    private final MssqlBlockingChainCollector collector;
    private final BlockingChainRepository repository;
    private final AtomicReference<SnapshotState> state = new AtomicReference<>(
        new SnapshotState("INITIALIZING", null, null, "NOT_YET_SAMPLED"));

    public BlockingChainService(MssqlTargetProperties target,
                                MssqlBlockingChainCollector collector,
                                BlockingChainRepository repository) {
        this.target = target;
        this.collector = collector;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${dbpulse.blocking.interval-ms:5000}", initialDelay = 4000)
    public void poll() {
        if (!target.isEnabled()) {
            state.set(new SnapshotState("NOT_CONFIGURED", null, null, "MSSQL_NOT_CONFIGURED"));
            return;
        }
        try {
            var sample = collector.collect();
            long id = repository.save(target.getId(), sample);
            state.set(new SnapshotState("ONLINE", sample.collectedAt(), id, null));
        } catch (Exception ex) {
            var prev = state.get();
            state.set(new SnapshotState("OFFLINE", prev.lastCollectedAt(), null,
                "BLOCKING_COLLECTION_FAILED"));
            log.warn("Blocking chain collection failed ({})", ex.getClass().getSimpleName());
        }
    }

    public BlockingView current() {
        SnapshotState s = state.get();
        if (!"ONLINE".equals(s.status()) || s.sampleId() == null) {
            return new BlockingView(s.status(), s.lastCollectedAt(), s.errorCode(), List.of());
        }
        if (Duration.between(s.lastCollectedAt(), Instant.now()).compareTo(Duration.ofSeconds(20)) > 0) {
            return new BlockingView("STALE", s.lastCollectedAt(), "STALE_SAMPLE", List.of());
        }
        return new BlockingView("ONLINE", s.lastCollectedAt(), null,
            repository.edges(s.sampleId(), target.getId()));
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    public void prune() {
        try {
            repository.deleteOldSamples();
        } catch (Exception ex) {
            log.warn("Blocking sample cleanup failed ({})", ex.getClass().getSimpleName());
        }
    }

    public record SnapshotState(String status, Instant lastCollectedAt,
                                Long sampleId, String errorCode) {}
    public record BlockingView(String status, Instant collectedAt,
                               String errorCode, List<BlockingEdge> edges) {}
}
