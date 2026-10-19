package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.persistence.DeadlockRepository;
import com.dbpulse.service.DeadlockService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local development API; requires auth and tenant isolation before public access. */
@RestController
@RequestMapping("/api/v1/targets/{id}/deadlocks")
public class DeadlockController {
    private final MssqlTargetProperties target;
    private final DeadlockService service;
    private final DeadlockRepository repository;

    public DeadlockController(MssqlTargetProperties target, DeadlockService service,
                              DeadlockRepository repository) {
        this.target = target;
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/status")
    public DeadlockService.DeadlockState status(@PathVariable String id) {
        requireTarget(id);
        return service.state();
    }

    @GetMapping
    public List<DeadlockRepository.DeadlockEventView> latest(
            @PathVariable String id, @RequestParam(defaultValue = "20") int limit) {
        requireTarget(id);
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..50");
        }
        return repository.history(id, limit);
    }

    private void requireTarget(String id) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
