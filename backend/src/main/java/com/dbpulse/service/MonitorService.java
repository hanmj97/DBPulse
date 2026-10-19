package com.dbpulse.service;

import com.dbpulse.collector.MssqlCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.MetricSnapshot;
import com.dbpulse.persistence.MetricRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MonitorService {
    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);
    private final MssqlTargetProperties target;
    private final MssqlCollector collector;
    private final MetricRepository metrics;
    private final AtomicReference<CollectorState> state =
        new AtomicReference<>(new CollectorState("NOT_CONFIGURED", null, "MSSQL_NOT_CONFIGURED"));

    public MonitorService(MssqlTargetProperties target, MssqlCollector collector,
                          MetricRepository metrics) {
        this.target = target;
        this.collector = collector;
        this.metrics = metrics;
        if (target.isEnabled()) {
            state.set(new CollectorState("INITIALIZING", null, null));
        }
    }

    @Scheduled(fixedDelayString = "${dbpulse.collector.interval-ms:5000}", initialDelay = 2000)
    public void poll() {
        if (!target.isEnabled()) {
            state.set(new CollectorState("NOT_CONFIGURED", null, "MSSQL_NOT_CONFIGURED"));
            return;
        }

        try {
            MetricSnapshot snapshot = collector.collect();
            metrics.save(target.getId(), snapshot);
            state.set(new CollectorState("ONLINE", snapshot.collectedAt(), null));
        } catch (Exception ex) {
            // Expose only a stable error code in the API; details stay in local server logs.
            String category = ex instanceof IllegalStateException
                ? "CONFIGURATION_ERROR"
                : "COLLECTION_FAILED";
            state.set(new CollectorState("OFFLINE", state.get().lastCollectedAt(), category));

            if (ex instanceof java.sql.SQLException sqlEx) {
                log.warn(
                    "DBPulse MSSQL collection failed for target {}. SQLState={}, ErrorCode={}, Message={}",
                    target.getId(), sqlEx.getSQLState(), sqlEx.getErrorCode(), sqlEx.getMessage(), sqlEx
                );
            } else {
                log.warn(
                    "DBPulse collection failed for target {} ({}): {}",
                    target.getId(), ex.getClass().getSimpleName(), ex.getMessage(), ex
                );
            }
        }
    }

    public CollectorState state() {
        return state.get();
    }

    public record CollectorState(String status, Instant lastCollectedAt, String errorCode) {}
}
