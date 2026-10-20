package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.MetricSnapshot;
import com.dbpulse.persistence.MetricRepository;
import com.dbpulse.service.MonitorService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "http://localhost:5173") // local-only in phase 1
public class ApiController {
    private final MssqlTargetProperties target;
    private final MonitorService monitor;
    private final MetricRepository metrics;

    public ApiController(MssqlTargetProperties target, MonitorService monitor,
                         MetricRepository metrics) {
        this.target = target;
        this.monitor = monitor;
        this.metrics = metrics;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("service", "DBPulse", "version", "0.11.0-alpha", "status", "UP", "phase", 11);
    }

    @GetMapping("/targets")
    public List<TargetResponse> targets() {
        var state = monitor.state();
        return List.of(new TargetResponse(target.getId(), target.getName(), "MSSQL",
            target.isEnabled(), target.getDatabase(), state.status(),
            state.lastCollectedAt(), state.errorCode()));
    }

    @GetMapping("/targets/{id}/metrics")
    public List<MetricSnapshot> history(@PathVariable String id,
                                        @RequestParam(defaultValue = "60") int limit) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (limit < 1 || limit > 360) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "limit must be between 1 and 360");
        return metrics.history(id, limit);
    }

    public record TargetResponse(String id, String name, String engine, boolean enabled,
        String databaseName, String collectorStatus,
        java.time.Instant lastCollectedAt, String errorCode) {}
}
