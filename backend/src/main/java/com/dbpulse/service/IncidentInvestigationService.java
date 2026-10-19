package com.dbpulse.service;

import com.dbpulse.domain.IncidentRecord;
import com.dbpulse.persistence.IncidentEvidenceRepository;
import com.dbpulse.persistence.IncidentEvidenceRepository.BlockingObservation;
import com.dbpulse.persistence.IncidentEvidenceRepository.DeadlockObservation;
import com.dbpulse.persistence.IncidentEvidenceRepository.MetricAggregate;
import com.dbpulse.persistence.IncidentRepository;
import com.dbpulse.persistence.IncidentWaitEvidenceRepository;
import com.dbpulse.persistence.IncidentWaitEvidenceRepository.WaitEvidence;
import java.util.ArrayList;
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
    private final IncidentWaitEvidenceRepository waitEvidence;

    public IncidentInvestigationService(IncidentRepository incidents,
                                        IncidentEvidenceRepository evidence,
                                        IncidentWaitEvidenceRepository waitEvidence) {
        this.incidents = incidents;
        this.evidence = evidence;
        this.waitEvidence = waitEvidence;
    }

    public Investigation investigate(String targetId, long incidentId) {
        IncidentRecord incident = incidents.byId(targetId, incidentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        IncidentEvidenceWindow window = IncidentEvidenceWindow.of(incident, Instant.now());
        var observations = evidence.read(targetId, window.from(), window.to());
        WaitEvidence waits = waitEvidence.read(targetId, window.from(), window.to());
        var findings = new ArrayList<>(IncidentEvidenceClassifier.assess(incident, observations));
        if (waits.sampledWaitCount() > 0) {
            findings.removeIf(f -> "NO_CONCLUSIVE_EVIDENCE".equals(f.code()));
        }
        findings.addAll(IncidentWaitFindings.from(waits));
        return new Investigation(incident, window, observations.metrics(),
            observations.blocking(), observations.deadlocks(), waits,
            List.copyOf(findings));
    }

    public record Investigation(
        IncidentRecord incident,
        IncidentEvidenceWindow window,
        MetricAggregate metrics,
        List<BlockingObservation> blocking,
        List<DeadlockObservation> deadlocks,
        WaitEvidence waitEvidence,
        List<IncidentEvidenceClassifier.Finding> findings
    ) {}
}
