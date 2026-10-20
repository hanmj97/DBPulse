package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.QueryPerformanceRepository;
import com.dbpulse.service.QueryPerformanceService;
import com.dbpulse.service.QueryTuningAdvisor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local-only metric-based tuning review; no SQL Server commands are issued. */
@RestController
@RequestMapping("/api/v1/targets/{id}/query-performance/advisor")
public class QueryTuningAdvisorController {
    private static final String SOURCE = "QUERY_STORE_AGGREGATES";
    private static final String COVERAGE =
        "Query Store 최근 실행 정보로 선정된 최대 20개 query/plan의 집계값입니다. " +
        "실행 횟수는 폴링 사이 증분이나 정확히 최근 15분에 실행된 횟수를 뜻하지 않습니다.";

    private final MssqlTargetProperties target;
    private final QueryPerformanceService service;
    private final QueryPerformanceRepository repository;
    private final QueryTuningAdvisor advisor;

    public QueryTuningAdvisorController(MssqlTargetProperties target,
                                        QueryPerformanceService service,
                                        QueryPerformanceRepository repository,
                                        QueryTuningAdvisor advisor) {
        this.target = target;
        this.service = service;
        this.repository = repository;
        this.advisor = advisor;
    }

    @GetMapping(produces = "application/json;charset=UTF-8")
    public QueryTuningAdvisor.Report report(
        @PathVariable String id, @RequestParam(defaultValue = "10") int limit) {
        if (!target.getId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (limit < 1 || limit > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..20");
        }
        var state = service.state();
        boolean ready = "ONLINE".equals(state.status()) ||
            "QUERY_STORE_READ_ONLY".equals(state.status());
        if (!ready || state.lastCollectedAt() == null) {
            return new QueryTuningAdvisor.Report(
                state.status(), state.lastCollectedAt(), SOURCE, COVERAGE,
                state.errorCode(), List.of());
        }
        if (Duration.between(state.lastCollectedAt(), Instant.now())
            .compareTo(Duration.ofMinutes(2)) > 0) {
            return new QueryTuningAdvisor.Report(
                "STALE", state.lastCollectedAt(), SOURCE, COVERAGE,
                "QUERY_STORE_SAMPLE_STALE", List.of());
        }
        var rows = repository.latest(id, state.lastCollectedAt(), 20);
        return new QueryTuningAdvisor.Report(
            state.status(), state.lastCollectedAt(), SOURCE, COVERAGE,
            null, advisor.advise(rows, limit));
    }
}
