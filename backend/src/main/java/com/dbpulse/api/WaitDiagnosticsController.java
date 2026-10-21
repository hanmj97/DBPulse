package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.WaitRepository;
import com.dbpulse.service.WaitDiagnosticsService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local-only diagnostic API; needs per-user authentication/authorization before SaaS. */
@RestController
@RequestMapping("/api/v1/targets/{id}/waits")
public class WaitDiagnosticsController {
    private final MssqlTargetProperties target;
    private final WaitDiagnosticsService service;
    private final WaitRepository repository;

    public WaitDiagnosticsController(MssqlTargetProperties target,
                                     WaitDiagnosticsService service, WaitRepository repository) {
        this.target = target;
        this.service = service;
        this.repository = repository;
    }

    @GetMapping
    public WaitDiagnosticsService.WaitView latest(@PathVariable String id) {
        requireTarget(id);
        return service.latest();
    }

    @GetMapping("/history")
    public List<WaitRepository.HistoryEntry> history(
            @PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
        requireTarget(id);
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..50");
        }
        if (!target.isEnabled()) return List.of();
        return repository.history(id, limit);
    }

    private void requireTarget(String id) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
