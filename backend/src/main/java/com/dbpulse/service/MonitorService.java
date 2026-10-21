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
    private final IncidentService incidents;
    private final AtomicReference<CollectorState> state =
        new AtomicReference<>(new CollectorState("NOT_CONFIGURED", null, "MSSQL_NOT_CONFIGURED"));

    public MonitorService(MssqlTargetProperties target, MssqlCollector collector,
                          MetricRepository metrics, IncidentService incidents) {
        this.target = target;
        this.collector = collector;
        this.metrics = metrics;
        this.incidents = incidents;
        target.onTargetChanged(() -> {
            state.set(target.isEnabled()
                ? new CollectorState("INITIALIZING", null, null)
                : new CollectorState("NOT_CONFIGURED", null, "MSSQL_NOT_CONFIGURED"));
            if (incidents != null) incidents.resetTransientSignals();
        });
        if (target.isEnabled()) {
            state.set(new CollectorState("INITIALIZING", null, null));
        }
    }

    @Scheduled(fixedDelayString = "${dbpulse.collector.interval-ms:5000}", initialDelay = 2000)
    public void poll() {
        target.withReadLease(() -> {
        if (!target.isEnabled()) {
            state.set(new CollectorState("NOT_CONFIGURED", null, "MSSQL_NOT_CONFIGURED"));
            return;
        }

        try {
            MetricSnapshot snapshot = collector.collect();
            metrics.save(target.getId(), snapshot);
            state.set(new CollectorState("ONLINE", snapshot.collectedAt(), null));
            try { incidents.onSample(target.getId(), snapshot); }
            catch (Exception incidentError) {
                log.warn("Incident persistence failed ({})", incidentError.getClass().getSimpleName());
            }
        } catch (Exception ex) {
            // Expose only a stable error code in the API; details stay in local server logs.
            String category = ex instanceof IllegalStateException
                ? "CONFIGURATION_ERROR"
                : "COLLECTION_FAILED";
            state.set(new CollectorState("OFFLINE", state.get().lastCollectedAt(), category));
            try { incidents.onCollectionError(target.getId(), Instant.now()); }
            catch (Exception incidentError) {
                log.warn("Incident persistence failed ({})", incidentError.getClass().getSimpleName());
            }

            if (ex instanceof java.sql.SQLException sqlEx) {
                log.warn(
                    "DBPulse MSSQL collection failed. SQLState={}, ErrorCode={}",
                    sqlEx.getSQLState(), sqlEx.getErrorCode()
                );
            } else {
                log.warn(
                    "DBPulse collection failed ({})",
                    ex.getClass().getSimpleName()
                );
            }
        }
    
        });
    }

    public CollectorState state() {
        CollectorState current = state.get();
        // UI activation happens after application startup. Between the
        // successful connection probe and the next scheduled DMV poll,
        // distinguish "collector starting" from "no DB configured".
        if (target.isEnabled() && "NOT_CONFIGURED".equals(current.status())) {
            return new CollectorState("INITIALIZING", null, null);
        }
        return current;
    }

    public record CollectorState(String status, Instant lastCollectedAt, String errorCode) {}
}
