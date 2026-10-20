package com.dbpulse.api;

import com.dbpulse.collector.MssqlPlanOperatorCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.QueryPerformanceRepository;
import com.dbpulse.service.QueryPerformanceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demand-only targeted plan operator inspection. No background XML collection.
 * The target is still local-only and requires authentication before public use.
 */
@RestController
@RequestMapping("/api/v1/targets/{id}/query-performance/plan-evidence")
public class QueryPlanOperatorController {
    private static final Logger log = LoggerFactory.getLogger(QueryPlanOperatorController.class);
    private final MssqlTargetProperties target;
    private final QueryPerformanceService queryStore;
    private final QueryPerformanceRepository repository;
    private final MssqlPlanOperatorCollector collector;

    public QueryPlanOperatorController(MssqlTargetProperties target,
                                       QueryPerformanceService queryStore,
                                       QueryPerformanceRepository repository,
                                       MssqlPlanOperatorCollector collector) {
        this.target = target;
        this.queryStore = queryStore;
        this.repository = repository;
        this.collector = collector;
    }

    @GetMapping(value = "/{queryId}/{planId}", produces = "application/json;charset=UTF-8")
    public MssqlPlanOperatorCollector.PlanReport inspect(@PathVariable String id,
                                                          @PathVariable long queryId,
                                                          @PathVariable long planId) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (queryId <= 0 || planId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var state = queryStore.state();
        if (!List.of("ONLINE", "QUERY_STORE_READ_ONLY").contains(state.status())
            || state.lastCollectedAt() == null
            || Duration.between(state.lastCollectedAt(), Instant.now()).compareTo(Duration.ofMinutes(2)) > 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        // Never allow arbitrary plan lookup outside the most recently sampled top-20 set.
        boolean sampled = repository.latest(id, state.lastCollectedAt(), 20).stream()
            .anyMatch(row -> row.queryId() == queryId && row.planId() == planId);
        if (!sampled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            return collector.examine(queryId, planId);
        } catch (Exception ex) {
            // Do not emit SQL exception text or raw plan details to the client/log.
            log.warn("Read-only plan operator inspection failed ({})", ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
