package com.dbpulse.service;

import com.dbpulse.collector.MssqlQueryStoreCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.QueryPerformanceRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Independent of the 5s DMV collector: Query Store can be unavailable while DMV monitoring works. */
@Service
public class QueryPerformanceService {
    private static final Logger log = LoggerFactory.getLogger(QueryPerformanceService.class);
    private final MssqlTargetProperties target;
    private final MssqlQueryStoreCollector queryStore;
    private final QueryPerformanceRepository repository;
    private final AtomicReference<AnalysisState> state = new AtomicReference<>(
        new AnalysisState("INITIALIZING", null, 0, null));

    public QueryPerformanceService(MssqlTargetProperties target, MssqlQueryStoreCollector queryStore,
                                   QueryPerformanceRepository repository) {
        this.target = target;
        this.queryStore = queryStore;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${dbpulse.query-analytics.interval-ms:30000}", initialDelay = 10000)
    public void collect() {
        if (!target.isEnabled()) {
            state.set(new AnalysisState("NOT_CONFIGURED", null, 0, "MSSQL_NOT_CONFIGURED"));
            return;
        }
        try {
            var result = queryStore.collect();
            if ("QUERY_STORE_DISABLED".equals(result.status())) {
                state.set(new AnalysisState("QUERY_STORE_DISABLED", null, 0, "QUERY_STORE_DISABLED"));
                return;
            }
            repository.save(target.getId(), result.rows());
            Instant sampledAt = result.rows().isEmpty() ? Instant.now() : result.rows().get(0).collectedAt();
            state.set(new AnalysisState(result.status(), sampledAt, result.rows().size(), null));
        } catch (SQLException ex) {
            // Never log passwords or raw SQL here; do not return driver messages to the UI.
            log.warn("Query Store read failed. SQLState={}, vendorCode={}", ex.getSQLState(), ex.getErrorCode());
            state.set(new AnalysisState("OFFLINE", state.get().lastCollectedAt(), 0, "QUERY_STORE_COLLECTION_FAILED"));
        } catch (Exception ex) {
            log.warn("Query Store analysis failed ({})", ex.getClass().getSimpleName());
            state.set(new AnalysisState("OFFLINE", state.get().lastCollectedAt(), 0, "QUERY_STORE_COLLECTION_FAILED"));
        }
    }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void pruneOldSamples() {
        try {
            int deleted = repository.purgeOlderThanSevenDays();
            if (deleted > 0) log.info("Pruned {} old Query Store samples", deleted);
        } catch (Exception ex) {
            log.warn("Failed to prune old Query Store samples ({})", ex.getClass().getSimpleName());
        }
    }

    public AnalysisState state() { return state.get(); }

    public record AnalysisState(String status, Instant lastCollectedAt, int sampledQueries, String errorCode) {}
}
