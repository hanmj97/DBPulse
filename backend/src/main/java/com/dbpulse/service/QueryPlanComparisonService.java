package com.dbpulse.service;

import com.dbpulse.domain.QueryPerformanceSnapshot;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * On-demand, bounded comparison between different stored plans for the same
 * Query Store query_id at ONE successful collection timestamp.
 *
 * Query Store aggregates can cover overlapping runtime intervals and different
 * parameter values. A larger average cannot prove a regression or its cause.
 */
@Service
public class QueryPlanComparisonService {
    private static final long MIN_EXECUTIONS = 5;
    private static final double MIN_DURATION_MS = 5.0;
    private static final double LARGE_DIFFERENCE_FACTOR = 1.5;
    private static final String LIMITATION =
        "동일 Query ID의 서로 다른 Plan ID를 한 수집 시점의 Query Store 집계로 비교했습니다. " +
        "실행 횟수는 새로 실행한 횟수나 초당 처리량이 아니며, 각 계획의 실행 조건·매개변수·수집 구간은 같다고 보장할 수 없습니다. " +
        "평균값 차이는 관측 사실이며 시간순 회귀 또는 원인 확정이 아닙니다. SQL 원문·실행계획 XML은 조회하지 않으며 변경 SQL을 자동 실행하지 않습니다.";

    public Report compare(QueryPerformanceSnapshot selected,
                          List<QueryPerformanceSnapshot> recentPlans) {
        var peers = recentPlans.stream().filter(p -> p.queryId() == selected.queryId())
            .filter(p -> p.planId() != selected.planId())
            .sorted(Comparator.comparingLong(QueryPerformanceSnapshot::planId))
            .limit(49).toList();
        var comparisons = peers.stream().map(p -> compareOne(selected, p)).toList();
        String status = comparisons.isEmpty() ? "ONLY_ONE_PLAN_SAMPLED" : "COMPARISON_AVAILABLE";
        return new Report(selected.queryId(), selected.planId(),
            selected.collectedAt(), status, metric(selected), comparisons, LIMITATION);
    }

    private Comparison compareOne(QueryPerformanceSnapshot selected,
                                  QueryPerformanceSnapshot peer) {
        double from = selected.avgDurationMs();
        double to = peer.avgDurationMs();
        Double ratio = from < MIN_DURATION_MS || to < MIN_DURATION_MS ? null : to / from;
        String assessment;
        String nextCheck;
        if (selected.executionCount() < MIN_EXECUTIONS || peer.executionCount() < MIN_EXECUTIONS) {
            assessment = "INSUFFICIENT_SAMPLES";
            nextCheck = "각 계획의 Query Store 집계 실행 횟수가 5회 이상인 표본을 확보하세요. 두 계획을 동일 조건에서 실행했다고 가정하지 마세요.";
        } else if (ratio == null) {
            assessment = "SMALL_DURATION_BASELINE";
            nextCheck = "평균 실행 시간이 매우 짧아 배율 비교가 불안정합니다. 반복 실행과 실제 실행계획을 추가로 검토하세요.";
        } else if (ratio >= LARGE_DIFFERENCE_FACTOR || ratio <= 1 / LARGE_DIFFERENCE_FACTOR) {
            assessment = "MEAN_DURATION_DIFFERENCE";
            nextCheck = "평균 실행 시간 차이가 관측됐습니다. SSMS에서 각 계획, 파라미터·행 수·Wait 및 실제 실행 환경을 확인하세요. 시간순 회귀가 확정된 것은 아닙니다.";
        } else {
            assessment = "NO_LARGE_MEAN_DIFFERENCE";
            nextCheck = "현재 정의된 1.5배 기준 이상의 평균 시간 차이가 없습니다. 동일한 실행 비용이나 안정성을 증명하는 것은 아닙니다.";
        }
        return new Comparison(peer.planId(), metric(peer), ratio,
            ratio == null ? null : String.format(Locale.ROOT, "%.2fx", ratio),
            assessment, nextCheck);
    }

    private static Metrics metric(QueryPerformanceSnapshot row) {
        return new Metrics(row.executionCount(), row.avgDurationMs(),
            row.avgCpuMs(), row.avgLogicalReadsPages(), row.lastExecutedAt(),
            row.sourceCategory());
    }

    public record Metrics(long executionCount, double avgDurationMs,
                          double avgCpuMs, double avgLogicalReadsPages,
                          Instant lastExecutedAt, String sourceCategory) {}
    public record Comparison(long planId, Metrics metrics, Double durationRatioToSelected,
                             String durationRatioLabel, String assessment, String nextCheck) {}
    public record Report(long queryId, long selectedPlanId, Instant sampleCollectedAt,
                         String status, Metrics selected, List<Comparison> comparisons,
                         String limitation) {}
}
