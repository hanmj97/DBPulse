package com.dbpulse.service;

import com.dbpulse.domain.MetricSnapshot;
import com.dbpulse.persistence.IncidentRepository;
import com.dbpulse.service.IncidentSignalTracker.Action;
import com.dbpulse.service.IncidentSignalTracker.Type;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Reads real collector measurements and creates one OPEN event per signal/target.
 * A collector failure is NOT proof that SQL Server is unavailable.
 */
@Service
public class IncidentService {
    private final IncidentRepository repository;
    private IncidentSignalTracker tracker = new IncidentSignalTracker();

    /** Avoid linking consecutive-sample incident decisions across two databases. */
    public synchronized void resetTransientSignals() {
        tracker = new IncidentSignalTracker();
    }

    public IncidentService(IncidentRepository repository) { this.repository = repository; }

    public synchronized void onSample(String target, MetricSnapshot sample) {
        Instant at = sample.collectedAt();
        observe(target, Type.BLOCKING, sample.blockedRequests() > 0, at,
            sample.blockedRequests(), "Blocked requests persisted across samples.");
        observe(target, Type.LONG_RUNNING, sample.longRunningRequests() > 0, at,
            sample.maxElapsedMs(), "Long-running requests persisted across samples.");
        observe(target, Type.COLLECTION_FAILURE, false, at, 0,
            "Collector failed repeatedly; check connectivity, permissions and logs.");
    }

    public synchronized void onCollectionError(String target, Instant at) {
        // Database-side measurements are unknown during collector failure:
        // do NOT implicitly resolve an existing BLOCKING/LONG_RUNNING incident.
        observe(target, Type.COLLECTION_FAILURE, true, at, 1,
            "Collector failed repeatedly; check connectivity, permissions and logs.");
    }

    private void observe(String target, Type type, boolean bad, Instant at, long value, String text) {
        Action action = tracker.observe(type, bad);
        if (action == Action.OPEN) repository.upsert(target, type.name(), at, value, text);
        if (action == Action.RESOLVE) repository.resolve(target, type.name(), at);
    }
}
