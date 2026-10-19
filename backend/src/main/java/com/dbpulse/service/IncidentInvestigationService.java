package com.dbpulse.service;

import com.dbpulse.domain.IncidentRecord;
import com.dbpulse.persistence.IncidentEvidenceRepository;
import com.dbpulse.persistence.IncidentEvidenceRepository.BlockingObservation;
import com.dbpulse.persistence.IncidentEvidenceRepository.DeadlockObservation;
import com.dbpulse.persistence.IncidentEvidenceRepository.MetricAggregate;
import com.dbpulse.persistence.IncidentRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Correlates only the persisted observations for the incident's time window.
 * This is an evidence navigator, NOT an automated root-cause determination.
 */
@Service
public class IncidentInvestigationService {
    private final IncidentRepository incidents;
    private final IncidentEvidenceRepository evidence;

    public IncidentInvestigationService(IncidentRepository incidents,
                                        IncidentEvidenceRepository evidence) {
        this.incidents = incidents;
        this.evidence = evidence;
    }

    public Investigation investigate(String targetId, long incidentId) {
        IncidentRecord incident = incidents.byId(targetId, incidentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        IncidentEvidenceWindow window = IncidentEvidenceWindow.of(incident, Instant.now());
        var observations = evidence.read(targetId, window.from(), window.to());
        return new Investigation(incident, window, observations.metrics(),
            observations.blocking(), observations.deadlocks(),
            IncidentEvidenceClassifier.assess(incident, observations));
    }

    public record Investigation(
        IncidentRecord incident,
        IncidentEvidenceWindow window,
        MetricAggregate metrics,
        List<BlockingObservation> blocking,
        List<DeadlockObservation> deadlocks,
        List<IncidentEvidenceClassifier.Finding> findings
    ) {}
}
