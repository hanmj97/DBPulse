package com.dbpulse.service;

import com.dbpulse.domain.IncidentRecord;
import com.dbpulse.persistence.IncidentEvidenceRepository.Evidence;
import java.util.ArrayList;
import java.util.List;

/**
 * Evidence-based observations only. A temporal match does not demonstrate
 * root cause, and collection failure does not prove SQL Server downtime.
 */
public final class IncidentEvidenceClassifier {
    private IncidentEvidenceClassifier() {}

    public static List<Finding> assess(IncidentRecord incident, Evidence evidence) {
        List<Finding> findings = new ArrayList<>();
        if ("COLLECTION_FAILURE".equals(incident.type())) {
            findings.add(new Finding("COLLECTION_VISIBILITY_LOST", "LIMITATION",
                "수집 데이터 신뢰도 저하",
                "수집 실패는 DBPulse의 관측 실패이며 SQL Server 자체 장애가 입증된 것은 아닙니다."));
        }
        if (evidence.metrics().samples() == 0) {
            findings.add(new Finding("NO_METRICS_IN_WINDOW", "LIMITATION",
                "사건 시간대의 성능 샘플 없음",
                "관측 공백이나 데이터 보존 기간 때문에 성능 상태를 판단할 수 없습니다."));
        } else {
            if (evidence.metrics().peakBlocked() > 0) {
                findings.add(new Finding("BLOCKED_REQUESTS_OBSERVED", "OBSERVED",
                    "Blocking 수집값 확인",
                    "해당 시간대의 최대 Blocking 요청 수: " + evidence.metrics().peakBlocked() + "건."));
            }
            if (evidence.metrics().peakLongRunning() > 0) {
                findings.add(new Finding("LONG_RUNNING_REQUESTS_OBSERVED", "OBSERVED",
                    "장시간 실행 요청 확인",
                    "해당 시간대의 최대 장시간 실행 요청 수: " +
                        evidence.metrics().peakLongRunning() + "건."));
            }
        }
        if (!evidence.blocking().isEmpty()) {
            var sample = evidence.blocking().getFirst();
            findings.add(new Finding("BLOCKING_CHAIN_OBSERVED", "OBSERVED",
                "차단 세션 관계 포착",
                "관측된 세션 #" + sample.blockingSessionId() +
                    " → 대기 세션 #" + sample.blockedSessionId() +
                    ". Wait Type: " + sample.waitType() +
                    ". 차단의 근본 원인은 별도 확인이 필요합니다."));
        } else if ("BLOCKING".equals(incident.type())) {
            findings.add(new Finding("BLOCKING_EDGE_NOT_CAPTURED", "LIMITATION",
                "세션별 차단 관계 미확인",
                "Blocking 건수는 감지했더라도 개별 차단 세션 관계는 수집 시점 차이로 누락될 수 있습니다."));
        }
        if (!evidence.deadlocks().isEmpty()) {
            findings.add(new Finding("DEADLOCK_RECORDED_NEARBY", "OBSERVED",
                "인접한 Deadlock 이벤트 기록",
                "동일 시간 구간의 Deadlock 이벤트가 기록됐습니다. 해당 Incident의 직접적인 원인이라는 뜻은 아닙니다."));
        }
        if (findings.isEmpty()) {
            findings.add(new Finding("NO_CONCLUSIVE_EVIDENCE", "LIMITATION",
                "확정할 수 있는 근거 없음",
                "현재 저장된 관측 정보만으로는 장애 원인을 특정할 수 없습니다."));
        }
        return List.copyOf(findings);
    }

    public record Finding(String code, String kind, String title, String detail) {}
}
