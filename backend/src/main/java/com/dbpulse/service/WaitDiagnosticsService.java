package com.dbpulse.service;

import com.dbpulse.collector.MssqlWaitCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.WaitObservation;
import com.dbpulse.persistence.WaitRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class WaitDiagnosticsService {
    private static final Logger log = LoggerFactory.getLogger(WaitDiagnosticsService.class);
    private final MssqlTargetProperties target;
    private final MssqlWaitCollector collector;
    private final WaitRepository repository;
    private final AtomicReference<State> state = new AtomicReference<>(
        new State("INITIALIZING", null, null, "NOT_YET_SAMPLED"));

    public WaitDiagnosticsService(MssqlTargetProperties target,
                                  MssqlWaitCollector collector, WaitRepository repository) {
        this.target = target;
        this.collector = collector;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${dbpulse.waits.interval-ms:5000}", initialDelay = 6000)
    public void poll() {
        if (!target.isEnabled()) {
            state.set(new State("NOT_CONFIGURED", null, null, "MSSQL_NOT_CONFIGURED"));
            return;
        }
        try {
            WaitSampleResult result = store();
            state.set(new State("ONLINE", result.at(), result.id(), null));
        } catch (Exception ex) {
            var previous = state.get();
            state.set(new State("OFFLINE", previous.lastCollectedAt(), null,
                "WAIT_COLLECTION_FAILED"));
            log.warn("Wait diagnostics collection failed ({})", ex.getClass().getSimpleName());
        }
    }

    private WaitSampleResult store() throws java.sql.SQLException {
        var sample = collector.collect();
        var sampleId = repository.save(target.getId(), sample);
        return new WaitSampleResult(sample.collectedAt(), sampleId);
    }

    public WaitView latest() {
        var current = state.get();
        if (!"ONLINE".equals(current.status()) || current.sampleId() == null) {
            return new WaitView(current.status(), current.lastCollectedAt(),
                current.errorCode(), 0, false, List.of());
        }
        if (Duration.between(current.lastCollectedAt(), Instant.now()).compareTo(Duration.ofSeconds(20)) > 0) {
            return new WaitView("STALE", current.lastCollectedAt(),
                "STALE_SAMPLE", 0, false, List.of());
        }
        var snapshot = repository.readSnapshot(target.getId(), current.sampleId());
        return new WaitView("ONLINE", snapshot.collectedAt(), null,
            snapshot.observedCount(), snapshot.limitReached(), snapshot.observations());
    }

    @Scheduled(cron = "0 50 3 * * *", zone = "UTC")
    public void prune() {
        try { repository.deleteOldSamples(); }
        catch (Exception ex) {
            log.warn("Wait diagnostics retention cleanup failed ({})",
                ex.getClass().getSimpleName());
        }
    }

    private record WaitSampleResult(Instant at, long id) {}
    public record State(String status, Instant lastCollectedAt, Long sampleId, String errorCode) {}
    public record WaitView(String status, Instant collectedAt, String errorCode,
                           int observedCount, boolean limitReached, List<WaitObservation> observations) {}
}
