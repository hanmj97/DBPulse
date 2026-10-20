package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import com.dbpulse.persistence.QueryPerformanceRepository;
import com.dbpulse.service.QueryPerformanceService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local-only endpoint until authentication/tenant isolation is added. */
@RestController
@RequestMapping("/api/v1/targets/{id}/query-performance")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class QueryPerformanceController {
    private final MssqlTargetProperties target;
    private final QueryPerformanceService service;
    private final QueryPerformanceRepository repository;

    public QueryPerformanceController(MssqlTargetProperties target, QueryPerformanceService service,
                                      QueryPerformanceRepository repository) {
        this.target = target;
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/status")
    public QueryPerformanceService.AnalysisState status(@PathVariable String id) {
        requireTarget(id);
        return service.state();
    }

    @GetMapping
    public List<QueryPerformanceSnapshot> list(@PathVariable String id,
                                               @RequestParam(defaultValue = "20") int limit) {
        requireTarget(id);
        if (limit < 1 || limit > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "limit must be between 1 and 50");
        if (!"ONLINE".equals(service.state().status())
            && !"QUERY_STORE_READ_ONLY".equals(service.state().status())) return List.of();
        var analysisState = service.state();
        if (analysisState.sampledQueries() == 0 || analysisState.lastCollectedAt() == null) return List.of();
        return repository.latest(id, analysisState.lastCollectedAt(), limit);
    }

    private void requireTarget(String id) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
