package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.domain.IncidentRecord;
import com.dbpulse.persistence.IncidentRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local-only incident API; authentication and tenant isolation precede public access. */
@RestController
@RequestMapping("/api/v1/targets/{id}/incidents")
public class IncidentController {
    private final MssqlTargetProperties target;
    private final IncidentRepository repository;
    public IncidentController(MssqlTargetProperties target, IncidentRepository repository) {
        this.target = target;
        this.repository = repository;
    }
    @GetMapping
    public List<IncidentRecord> list(@PathVariable String id,
                                      @RequestParam(defaultValue = "30") int limit) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return repository.latest(id, limit);
    }
}
