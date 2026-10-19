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

    public BlockingChainController(MssqlTargetProperties target, BlockingChainService chains) {
        this.target = target;
        this.chains = chains;
    }

    @GetMapping
    public BlockingChainService.BlockingView latest(@PathVariable String id) {
        if (!target.getId().equals(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return chains.current();
    }
}
