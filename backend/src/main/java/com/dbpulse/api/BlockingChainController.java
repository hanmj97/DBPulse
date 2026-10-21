package com.dbpulse.api;

import com.dbpulse.config.MssqlTargetProperties;
import com.dbpulse.service.BlockingChainService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Local-only API: session metadata must be tenant-isolated before remote release. */
@RestController
@RequestMapping("/api/v1/targets/{id}/blocking-chains")
public class BlockingChainController {
    private final MssqlTargetProperties target;
    private final BlockingChainService chains;
    private final com.dbpulse.persistence.BlockingChainRepository repository;

    public BlockingChainController(MssqlTargetProperties target, BlockingChainService chains,
                                   com.dbpulse.persistence.BlockingChainRepository repository) {
        this.target = target;
        this.chains = chains;
        this.repository = repository;
    }

    @GetMapping("/history")
    public java.util.List<com.dbpulse.persistence.BlockingChainRepository.BlockingHistoryEntry> history(
            @PathVariable String id, @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int limit) {
        requireTarget(id);
        if (limit < 1 || limit > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be 1..50");
        }
        if (!target.isEnabled()) return java.util.List.of();
        return repository.history(id, limit);
    }

    @GetMapping
    public BlockingChainService.BlockingView latest(@PathVariable String id) {
        requireTarget(id);
        return chains.current();
    }
    private void requireTarget(String id) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
