package com.dbpulse.service;

import com.dbpulse.persistence.IncidentWaitEvidenceRepository.WaitEvidence;
import java.util.ArrayList;
import java.util.List;

/** Conservative wait-related findings. A wait category cannot establish root cause. */
public final class IncidentWaitFindings {
    private IncidentWaitFindings() {}

    public static List<IncidentEvidenceClassifier.Finding> from(WaitEvidence evidence) {
        List<IncidentEvidenceClassifier.Finding> result = new ArrayList<>();
        if (evidence.sampleCount() == 0) {
            result.add(new IncidentEvidenceClassifier.Finding(
                "WAIT_SAMPLES_UNAVAILABLE", "LIMITATION",
                "사건 시간대의 Wait 샘플 없음",
                "해당 구간의 Wait 수집 기록이 없어 대기 여부를 판단할 수 없습니다."));
            return List.copyOf(result);
        }

        if (evidence.truncatedSampleCount() > 0) {
            result.add(new IncidentEvidenceClassifier.Finding(
                "WAIT_COLLECTION_LIMIT_REACHED", "LIMITATION",
                "일부 Wait 수집 제한 도달",
                "최대 100개 요청 제한에 도달한 샘플이 " +
                    evidence.truncatedSampleCount() + "회 있어 일부 관측이 제외됐을 수 있습니다."));
        }

        if (evidence.sampledWaitCount() == 0) {
            result.add(new IncidentEvidenceClassifier.Finding(
                "WAITS_NOT_OBSERVED", "LIMITATION",
                "해당 샘플에서 대기 미관측",
                "수집된 " + evidence.sampleCount() +
                    "개 시점에서는 대기가 관측되지 않았습니다. 대기가 전혀 없었다는 뜻은 아닙니다."));
        } else {
            var top = evidence.categories().getFirst();
            result.add(new IncidentEvidenceClassifier.Finding(
                "WAIT_CATEGORY_OBSERVED", "OBSERVED",
                "사건 당시 Wait 관측",
                "가장 자주 표본에 나타난 대기 유형: " + top.category() +
                    " (" + top.sampledCount() + "개 관측, 최대 " +
                    top.peakWaitMs() + "ms). 동일 요청이 여러 번 수집됐을 수 있으며 근본 원인을 확정하지 않습니다."));
        }
        return List.copyOf(result);
    }
}
