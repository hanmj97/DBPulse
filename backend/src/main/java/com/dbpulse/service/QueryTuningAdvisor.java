package com.dbpulse.service;

import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read-only heuristic triage from Query Store numeric summaries.
 * Never asserts that a particular index, plan operator or SQL predicate exists:
 * the collector deliberately does not retrieve SQL text or execution plan XML.
 */
@Service
public class QueryTuningAdvisor {
    public List<Candidate> advise(List<QueryPerformanceSnapshot> rows, int limit) {
        return advise(rows, limit, "priority");
    }

    /** UI defaults to newest execution; existing two-arg callers keep priority behavior. */
    public List<Candidate> advise(List<QueryPerformanceSnapshot> rows, int limit, String sort) {
        Comparator<Candidate> order = switch (sort) {
            case "recent" -> Comparator
                .comparing(Candidate::lastExecutedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed()
                .thenComparing(Comparator.comparingDouble(
                    (Candidate c) -> c.metrics().avgDurationMs()).reversed());
            case "duration" -> Comparator.comparingDouble(
                (Candidate c) -> c.metrics().avgDurationMs()).reversed();
            case "priority" -> Comparator
                .comparingInt((Candidate c) -> priorityOrder(c.priority()))
                .thenComparing(Comparator.comparingDouble(
                    (Candidate c) -> c.metrics().avgDurationMs()).reversed());
            default -> throw new IllegalArgumentException("INVALID_SORT");
        };
        return rows.stream().map(this::classify).sorted(order
            .thenComparingLong(Candidate::queryId)
            .thenComparingLong(Candidate::planId)).limit(limit).toList();
    }

    private static int priorityOrder(String priority) {
        return switch (priority) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; };
    }

    private Candidate classify(QueryPerformanceSnapshot row) {
        List<Advice> steps = new ArrayList<>();
        double duration = row.avgDurationMs();
        double cpu = row.avgCpuMs();
        double reads = row.avgLogicalReadsPages();
        boolean slow = duration >= 500;
        boolean cpuHeavy = cpu >= 200;
        boolean readHeavy = reads >= 1000;
        String priority = slow && (cpuHeavy || readHeavy) ? "HIGH"
            : slow || cpuHeavy || readHeavy ? "MEDIUM" : "OBSERVE";

        if (readHeavy) steps.add(new Advice("REVIEW_LOGICAL_READS",
            "논리 읽기량 원인 확인",
            "실제 실행계획에서 인덱스 접근 방식과 테이블/인덱스 스캔, Key Lookup 여부를 확인하세요. 해당 연산이 존재하는지는 아직 확인되지 않았습니다."));
        if (cpuHeavy) steps.add(new Advice("REVIEW_CPU_OPERATORS",
            "CPU 사용 연산 확인",
            "실제 실행계획에서 Sort·Hash·Join·병렬 처리 연산과 데이터 처리량을 점검하세요. 특정 연산이 발견됐다는 뜻은 아닙니다."));
        if (slow) steps.add(new Advice("REVIEW_WAIT_AND_BLOCKING",
            "응답 지연의 Wait·Blocking 확인",
            "실행 시간만으로 CPU 또는 I/O 병목을 확정할 수 없습니다. 같은 시간대 Wait, Blocking, 실행계획 정보를 함께 검토하세요."));
        if (steps.isEmpty()) steps.add(new Advice("BASELINE_ONLY",
            "추가 근거 수집",
            "현재 규칙의 임계값을 넘지 않았습니다. 정상 동작을 보증하지 않으며 추세와 실행계획을 추가로 확인할 수 있습니다."));

        return new Candidate(row.queryId(), row.planId(), priority,
            row.diagnosisCode(), row.lastExecutedAt(), row.sourceCategory(),
            new Metrics(row.executionCount(), duration, cpu, reads),
            List.copyOf(steps),
            "Query Store 집계 수치에서 도출한 점검 후보입니다. SQL 원문·실행계획 XML을 수집하지 않아 인덱스 생성이나 실행계획 개선 효과를 확정하지 않습니다.");
    }

    public record Advice(String code, String title, String description) {}
    public record Metrics(long executionCount, double avgDurationMs,
                          double avgCpuMs, double avgLogicalReadsPages) {}
    public record Candidate(long queryId, long planId, String priority,
                            String diagnosisCode, Instant lastExecutedAt,
                            String sourceCategory, Metrics metrics, List<Advice> actions, String limitation) {}
    public record Report(String status, Instant collectedAt, String source,
                         String coverage, String errorCode, List<Candidate> candidates) {}
}
