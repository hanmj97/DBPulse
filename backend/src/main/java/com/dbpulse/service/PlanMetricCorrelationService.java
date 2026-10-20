package com.dbpulse.service;

import com.dbpulse.collector.MssqlPlanOperatorCollector.PlanReport;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Joins a persisted Query Store aggregate with confirmed *structural* operators
 * from the SAME query_id and plan_id, fetched on explicit user request only.
 * Observation != cause: never generates executable SQL or index DDL.
 */
@Service
public class PlanMetricCorrelationService {
    public static final double READ_THRESHOLD_PAGES = 1000;
    public static final double CPU_THRESHOLD_MS = 200;
    public static final double DURATION_THRESHOLD_MS = 500;

    private static final Set<String> SCANS = Set.of(
        "TABLE_SCAN", "INDEX_SCAN", "CLUSTERED_INDEX_SCAN");
    private static final Set<String> LOOKUPS = Set.of("KEY_LOOKUP", "RID_LOOKUP");
    private static final Set<String> CPU_OPERATORS = Set.of("SORT", "HASH_MATCH");
    private static final Set<String> DML = Set.of(
        "TABLE_UPDATE", "INDEX_UPDATE", "CLUSTERED_INDEX_UPDATE",
        "TABLE_INSERT", "INDEX_INSERT", "CLUSTERED_INDEX_INSERT",
        "TABLE_DELETE", "INDEX_DELETE", "CLUSTERED_INDEX_DELETE");

    private static final String LIMITATION =
        "같은 Query/Plan의 Query Store 집계와 추정 실행계획 연산을 연결한 조사 단서입니다. " +
        "SQL 원문, 파라미터, 인덱스·테이블명, 실제 실행계획의 실행 횟수와 Actual Rows는 수집하지 않습니다. " +
        "관측된 연산이 고비용의 직접 원인이라는 뜻이 아니며 자동 SQL·인덱스 변경은 수행하지 않습니다.";

    public Correlation review(QueryPerformanceSnapshot sample, PlanReport plan) {
        if (sample.queryId() != plan.queryId() || sample.planId() != plan.planId()) {
            throw new IllegalArgumentException("QUERY_PLAN_MISMATCH");
        }
        if ("DBPULSE_PATTERN".equals(sample.sourceCategory())) {
            return new Correlation("COLLECTOR_OVERHEAD_ONLY", sample.collectedAt(),
                List.of(new Finding("MONITORING_OVERHEAD", "INFO",
                    "DBPulse 자체 수집 SQL 관측",
                    "수집기 SQL의 평균 논리 읽기 " + fmt(sample.avgLogicalReadsPages()) +
                      " pages, 평균 CPU " + fmt(sample.avgCpuMs()) + " ms.",
                    "DBPulse의 SQL 수집 주기·호출 횟수·실제 CPU와 논리 읽기 부담을 측정하세요. " +
                      "고객 테이블의 인덱스 생성이나 실행계획 변경을 권장하지 않습니다.")),
                LIMITATION + " 자체 수집 SQL은 업무 쿼리 튜닝 후보가 아니며 수집 부하 점검 대상으로 다룹니다.");
        }

        if (!"AVAILABLE".equals(plan.status()) &&
            !"OPERATOR_LIMIT_REACHED".equals(plan.status())) {
            return new Correlation("PLAN_UNAVAILABLE", sample.collectedAt(),
                List.of(), LIMITATION + " 현재 계획 연산을 확인할 수 없어 결론을 내리지 않습니다.");
        }

        Set<String> observed = plan.operators().stream()
            .filter(o -> o.count() > 0)
            .map(o -> o.type())
            .collect(Collectors.toSet());
        var found = new ArrayList<Finding>();
        double reads = sample.avgLogicalReadsPages();
        double cpu = sample.avgCpuMs();
        double duration = sample.avgDurationMs();

        if (reads >= READ_THRESHOLD_PAGES && overlaps(observed, LOOKUPS)) {
            found.add(new Finding("READS_WITH_LOOKUP", "REVIEW",
                "높은 논리 읽기 + Lookup 연산 함께 관측",
                "평균 논리 읽기 " + fmt(reads) + " pages; 실행계획에 Key/RID Lookup 연산이 포함됩니다.",
                "SSMS 실제 실행계획에서 Lookup의 반복 수행·실제 행 수를 확인하고, 기존 인덱스 구조·쓰기 비용을 검토하세요. 커버링 인덱스 생성은 아직 추천하지 않습니다."));
        }
        if (reads >= READ_THRESHOLD_PAGES && overlaps(observed, SCANS)) {
            found.add(new Finding("READS_WITH_SCAN", "REVIEW",
                "높은 논리 읽기 + Scan 연산 함께 관측",
                "평균 논리 읽기 " + fmt(reads) + " pages; 실행계획에 Scan 연산이 포함됩니다.",
                "스캔 대상 크기·선택도·결과 행 수를 함께 확인하세요. Scan 자체가 비효율이라는 뜻은 아닙니다."));
        }
        if (cpu >= CPU_THRESHOLD_MS && overlaps(observed, CPU_OPERATORS)) {
            found.add(new Finding("CPU_WITH_SORT_OR_HASH", "REVIEW",
                "높은 CPU + Sort/Hash 연산 함께 관측",
                "평균 CPU " + fmt(cpu) + " ms; 실행계획에 Sort 또는 Hash Match 연산이 포함됩니다.",
                "SSMS 실제 실행계획에서 Actual Rows, 메모리 부여량, 정렬·해시 Spill 여부를 검토하세요. CPU 사용의 직접 원인은 미확정입니다."));
        }
        if (duration >= DURATION_THRESHOLD_MS) {
            found.add(new Finding("DURATION_REVIEW", "REVIEW",
                "평균 실행 시간 증가 관측",
                "Query Store 평균 실행 시간 " + fmt(duration) + " ms.",
                "같은 시간대 Wait·Blocking과 실행 간 편차를 확인하세요. 실행계획의 연산만으로 지연 원인을 단정할 수 없습니다."));
        }
        if (overlaps(observed, DML)) {
            found.add(new Finding("DML_OPERATOR_OBSERVED", "INFO",
                "데이터 변경 연산이 실행계획에 포함됨",
                "Index/Table Insert·Update·Delete 계열의 구조적 연산이 확인됐습니다.",
                "DML 연산 존재만으로 인덱스 추가나 성능 튜닝이 필요하다고 판단하지 마세요."));
        }
        if (found.isEmpty()) {
            found.add(new Finding("NO_THRESHOLD_CORRELATION", "INFO",
                "현재 결합 규칙에 해당하는 고비용 단서 없음",
                "관측 지표와 계획 연산 중 정의된 임계값 조합이 확인되지 않았습니다.",
                "정상·최적화 상태를 보증하지 않습니다. 필요한 경우 실행별 변동과 실제 실행계획을 더 확인하세요."));
        }

        String status = "OPERATOR_LIMIT_REACHED".equals(plan.status()) ?
            "PARTIAL_EVIDENCE" : "EVIDENCE_AVAILABLE";
        String note = "PARTIAL_EVIDENCE".equals(status)
            ? LIMITATION + " 연산 100개까지만 읽었으므로 확인되지 않은 연산은 없다고 판단할 수 없습니다."
            : LIMITATION;
        return new Correlation(status, sample.collectedAt(), List.copyOf(found), note);
    }

    private static boolean overlaps(Set<String> present, Set<String> target) {
        return target.stream().anyMatch(present::contains);
    }

    private static String fmt(double number) {
        return String.format(java.util.Locale.ROOT, "%.2f", number);
    }

    public record Finding(String code, String kind, String title,
                          String evidence, String nextCheck) {}
    public record Correlation(String status, Instant sampleCollectedAt,
                              List<Finding> findings, String limitation) {}
}
