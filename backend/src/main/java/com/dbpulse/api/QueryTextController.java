package com.dbpulse.api;

import com.dbpulse.collector.MssqlQueryTextCollector;
import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.QueryPerformanceRepository;
import com.dbpulse.service.QueryPerformanceService;
import com.dbpulse.service.QueryTextSnippet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Local opt-in ONLY. No login/tenant authorization exists in this prototype.
 * Never make this route public without explicit per-target authorization.
 */
@RestController
@RequestMapping("/api/v1/targets/{id}/query-performance/query-text")
public class QueryTextController {
    private final MssqlTargetProperties target;
    private final QueryPerformanceService queryStore;
    private final QueryPerformanceRepository repository;
    private final MssqlQueryTextCollector collector;
    private final boolean enabled;

    public QueryTextController(MssqlTargetProperties target,
                               QueryPerformanceService queryStore,
                               QueryPerformanceRepository repository,
                               MssqlQueryTextCollector collector,
                               @Value("${dbpulse.query-text.enabled:false}") boolean enabled) {
        this.target = target;
        this.queryStore = queryStore;
        this.repository = repository;
        this.collector = collector;
        this.enabled = enabled;
    }

    @GetMapping(value="/{queryId}/{planId}", produces="application/json;charset=UTF-8")
    public ResponseEntity<QueryTextSnippet.Result> read(@PathVariable String id,
            @PathVariable long queryId, @PathVariable long planId) {
        return target.withReadLease(() -> {
        if (!target.isEnabled() || !target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "QUERY_TEXT_DISABLED");
        if (queryId <= 0 || planId <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        var state = queryStore.state();
        if (!List.of("ONLINE", "QUERY_STORE_READ_ONLY").contains(state.status())
            || state.lastCollectedAt() == null
            || !queryStore.isFresh(state.lastCollectedAt())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        boolean sampled = repository.latest(id, state.lastCollectedAt(), 50)
            .stream().anyMatch(p -> p.queryId() == queryId && p.planId() == planId);
        if (!sampled) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        QueryTextSnippet.Result response;
        try {
            response = collector.read(queryId, planId);
        } catch (Exception ex) {
            // Deliberately omit SQL Server driver messages / SQL text from logs and response.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Pragma", "no-cache")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .body(response);
    
        });
    }
}
