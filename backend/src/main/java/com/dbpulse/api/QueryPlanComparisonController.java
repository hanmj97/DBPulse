package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.QueryPerformanceRepository;
import com.dbpulse.service.QueryPerformanceService;
import com.dbpulse.service.QueryPlanComparisonService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Compare only already stored Query Store aggregates; no extra read of SQL
 * text, actual plan XML, credentials, object/index names or client data.
 */
@RestController
@RequestMapping("/api/v1/targets/{id}/query-performance/plan-comparison")
public class QueryPlanComparisonController {
    private final MssqlTargetProperties target;
    private final QueryPerformanceService queryStore;
    private final QueryPerformanceRepository repository;
    private final QueryPlanComparisonService comparison;

    public QueryPlanComparisonController(MssqlTargetProperties target,
            QueryPerformanceService queryStore,
            QueryPerformanceRepository repository,
            QueryPlanComparisonService comparison) {
        this.target = target;
        this.queryStore = queryStore;
        this.repository = repository;
        this.comparison = comparison;
    }

    @GetMapping(value="/{queryId}/{planId}", produces="application/json;charset=UTF-8")
    public QueryPlanComparisonService.Report report(
            @PathVariable String id, @PathVariable long queryId, @PathVariable long planId) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (queryId <= 0 || planId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var state = queryStore.state();
        if (!List.of("ONLINE", "QUERY_STORE_READ_ONLY").contains(state.status())
            || state.lastCollectedAt() == null
            || !queryStore.isFresh(state.lastCollectedAt())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "QUERY_STORE_NOT_FRESH");
        }
        var rows = repository.latest(id, state.lastCollectedAt(), 50);
        var selected = rows.stream()
            .filter(row -> row.queryId() == queryId && row.planId() == planId)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return comparison.compare(selected, rows);
    }
}
