package com.dbpulse.api;

import com.dbpulse.collector.MssqlPlanOperatorCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.QueryPerformanceRepository;
import com.dbpulse.service.QueryPerformanceService;
import com.dbpulse.service.PlanMetricCorrelationService;
import com.dbpulse.service.PlanMetricCorrelationService.Correlation;
import com.dbpulse.service.PlanOperatorSummary;
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
    private final PlanMetricCorrelationService correlation;

    public QueryPlanOperatorController(MssqlTargetProperties target,
                                       QueryPerformanceService queryStore,
                                       QueryPerformanceRepository repository,
                                       MssqlPlanOperatorCollector collector,
                                       PlanMetricCorrelationService correlation) {
        this.target = target;
        this.queryStore = queryStore;
        this.repository = repository;
        this.collector = collector;
        this.correlation = correlation;
    }

    @GetMapping(value = "/{queryId}/{planId}", produces = "application/json;charset=UTF-8")
    public PlanEvidenceResponse inspect(@PathVariable String id,
                                                          @PathVariable long queryId,
                                                          @PathVariable long planId) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (queryId <= 0 || planId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var state = queryStore.state();
        if (!List.of("ONLINE", "QUERY_STORE_READ_ONLY").contains(state.status())
            || state.lastCollectedAt() == null
            || !queryStore.isFresh(state.lastCollectedAt())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        // Never allow arbitrary plan lookup outside the most recently sampled bounded sampled set.
        var sample = repository.latest(id, state.lastCollectedAt(), 50).stream()
            .filter(row -> row.queryId() == queryId && row.planId() == planId)
            .findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            var plan = collector.examine(queryId, planId);
            return PlanEvidenceResponse.from(plan, correlation.review(sample, plan));
        } catch (Exception ex) {
            // Do not emit SQL exception text or raw plan details to the client/log.
            log.warn("Read-only plan operator inspection failed ({})", ex.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
    /**
     * Backward-compatible top-level fields; the new "correlation" is additive.
     * No raw SQL text, plan XML, object names or other sensitive plan attributes.
     */
    public record PlanEvidenceResponse(
        long queryId, long planId, String status, Instant collectedAt,
        Boolean parallelPlan, Boolean forcedPlan, long planBytes,
        int sampledOperators, List<PlanOperatorSummary.OperatorCount> operators,
        String interpretation, Correlation correlation
    ) {
        static PlanEvidenceResponse from(
            MssqlPlanOperatorCollector.PlanReport plan, Correlation correlation) {
            return new PlanEvidenceResponse(
                plan.queryId(), plan.planId(), plan.status(), plan.collectedAt(),
                plan.parallelPlan(), plan.forcedPlan(), plan.planBytes(),
                plan.sampledOperators(), plan.operators(),
                plan.interpretation(), correlation);
        }
    }

}
